package com.aspix2k.affected.build

import com.intellij.openapi.project.Project
import java.io.File
import java.util.concurrent.atomic.AtomicReference

class RubyBuildSystem : SuspendingBuildSystem, NamedSourceBuildSystem {

    private data class Snapshot(val root: String, val stamp: String, val modules: List<BuildModule>)

    private val cache = AtomicReference<Snapshot?>(null)

    override val id: String = "RUBY"

    override val sourceExtensions: Set<String> = setOf("rb", "gemspec", "rake", "ru", "lock")

    override val sourceFileNames: Set<String> = setOf("Gemfile", "Rakefile")

    override fun isPresent(project: Project): Boolean = rootOf(project) != null

    override fun modules(project: Project): List<BuildModule> {
        val root = rootOf(project) ?: return emptyList()
        val stamp = combineFingerprints(
            ManifestSearch.fingerprint(
                root,
                listOf("Gemfile", "Gemfile.lock").flatMap { ManifestSearch.find(root, it) } +
                    ManifestSearch.findByExtension(root, "gemspec"),
            ),
            ManifestSearch.layoutFingerprint(root) { it.name in RUBY_TEST_DIRECTORIES },
        )

        val rootPath = root.invariantSeparatorsPath
        if (stamp != null) cache.get()?.takeIf { it.root == rootPath && it.stamp == stamp }?.let { return it.modules }

        val discovered = runCatching { RubyGems.parse(root) }.getOrNull()
        val discovery = if (discovered.isNullOrEmpty()) {
            val fallbackTask = RubyTestSuites.fallbackTask(root)
            ModuleDiscovery(listOf(RubyGems.fallback(root, fallbackTask)), complete = false)
        } else {
            ModuleDiscovery(discovered, complete = true)
        }
        if (stamp != null && discovery.complete) cache.set(Snapshot(rootPath, stamp, discovery.modules))
        return discovery.modules
    }

    override fun run(project: Project, root: String, tasks: List<String>) {
        CommandRunner.runBatch(project, root, commands(project, root, tasks), "Affected Bundler")
    }

    override suspend fun runAndWaitSuspending(project: Project, root: String, tasks: List<String>): Boolean =
        CommandRunner.runBatchAndWait(project, root, commands(project, root, tasks), "Affected Bundler")

    private fun commands(project: Project, root: String, tasks: List<String>): List<CliCommand> {
        return rubyCommands(root, tasks, modules(project))
    }

    private fun rootOf(project: Project): File? =
        project.basePath?.let(::File)?.let { nestedBuildRoot(it) { File(it, "Gemfile").isRegularFileNoFollow() } }
}

private val RUBY_TEST_DIRECTORIES = setOf("test", "spec")

private data class RubySelection(
    val runners: List<RubyTestRunner>,
    val directories: List<String>,
    val fallback: Boolean,
)

internal fun rubyCommands(root: String, tasks: List<String>, modules: List<BuildModule>): List<CliCommand> {
    val selected = selectRubySuites(root, tasks, modules) ?: return emptyList()
    val suiteState = cachedSuiteState(root)
    if (!fallbackLayoutHolds(selected, suiteState)) return emptyList()
    return runnerCommands(selected, suiteState)
}

private fun selectRubySuites(
    root: String,
    tasks: List<String>,
    modules: List<BuildModule>,
): List<RubySelection>? {
    val byName = modules.associateBy { it.executionId }
    val selected = mutableListOf<RubySelection>()
    for (task in tasks) {
        val module = byName[task.substringBeforeLast(':')] ?: return null
        val plannedTask = task.substringAfterLast(':')
        if (module.testTask != plannedTask) return null
        val directories = module.contentRoots.map { relativeRubyPath(root, it) ?: return null }
        val runners = RubyTestSuites.runners(plannedTask) ?: return null
        selected += RubySelection(runners, directories, RubyTestSuites.isFallback(plannedTask))
    }
    return selected
}

private fun cachedSuiteState(root: String): (String) -> Boolean? {
    val suiteStates = mutableMapOf<String, Boolean?>()
    return { path ->
        if (suiteStates.containsKey(path)) {
            suiteStates.getValue(path)
        } else {
            RubyTestSuites.suitePresent(File(root, path)).also { suiteStates[path] = it }
        }
    }
}

private fun fallbackLayoutHolds(
    selected: List<RubySelection>,
    suiteState: (String) -> Boolean?,
): Boolean {
    for ((runners, roots, fallback) in selected) {
        if (!fallback) continue
        for (path in roots) {
            val spec = suiteState(if (path == ".") "spec" else "$path/spec") ?: return false
            val test = suiteState(if (path == ".") "test" else "$path/test") ?: return false
            if (spec && RubyTestRunner.RSPEC !in runners) return false
            if (test && runners.none { it != RubyTestRunner.RSPEC }) return false
        }
    }
    return true
}

private fun runnerCommands(
    selected: List<RubySelection>,
    suiteState: (String) -> Boolean?,
): List<CliCommand> {
    val commands = mutableListOf<CliCommand>()
    for (runner in RubyTestRunner.entries) {
        val paths = pathsForRunner(runner, selected, suiteState) ?: return emptyList()
        if (paths.isNotEmpty()) {
            commands += CliCommand(
                runner.command,
                listOf("bundle", "exec", runner.command) + paths.map(::rubyRunnerPath),
            )
        }
    }
    return commands
}

private fun pathsForRunner(
    runner: RubyTestRunner,
    selected: List<RubySelection>,
    suiteState: (String) -> Boolean?,
): List<String>? {
    val paths = mutableListOf<String>()
    for ((runners, roots, fallback) in selected) {
        if (runner !in runners) continue
        for (path in roots) {
            val suite = suitePath(path, runner, fallback, suiteState) ?: return null
            if (suite.isNotEmpty()) paths += suite
        }
    }
    return paths.distinct()
}

private fun suitePath(
    path: String,
    runner: RubyTestRunner,
    fallback: Boolean,
    suiteState: (String) -> Boolean?,
): String? {
    if (runner == RubyTestRunner.RSPEC) {
        if (suiteState(path) != true) return null
        when (suiteState(if (path == ".") "spec" else "$path/spec")) {
            null -> return null
            false -> return if (fallback) "" else null
            true -> Unit
        }
        return path
    }
    val relative = if (path == ".") "test" else "$path/test"
    when (suiteState(relative)) {
        null -> return null
        false -> return if (fallback) "" else null
        true -> Unit
    }
    return relative
}

private fun rubyRunnerPath(path: String): String = if (path == ".") path else "./$path"

private fun relativeRubyPath(root: String, directory: String): String? {
    val rootPath = File(root).toPath().toAbsolutePath().normalize().let { path ->
        runCatching(path::toRealPath).getOrDefault(path)
    }
    val directoryPath = File(directory).toPath().toAbsolutePath().normalize().let { path ->
        runCatching(path::toRealPath).getOrDefault(path)
    }
    if (!directoryPath.startsWith(rootPath)) return null
    return rootPath.relativize(directoryPath).toString().replace('\\', '/').ifEmpty { "." }
}
