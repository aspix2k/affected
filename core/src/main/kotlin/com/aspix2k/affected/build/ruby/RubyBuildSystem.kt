package com.aspix2k.affected.build.ruby

import com.aspix2k.affected.build.BuildModule
import com.aspix2k.affected.build.ManifestSearch
import com.aspix2k.affected.build.ModuleDiscovery
import com.aspix2k.affected.build.NamedSourceBuildSystem
import com.aspix2k.affected.build.SuspendingBuildSystem
import com.aspix2k.affected.build.combineFingerprints
import com.aspix2k.affected.build.isRegularFileNoFollow
import com.aspix2k.affected.build.nestedBuildRoots
import com.aspix2k.affected.build.pathSegments
import com.aspix2k.affected.build.process.CliCommand
import com.aspix2k.affected.build.process.CommandRunner
import com.aspix2k.affected.build.retainBuildSnapshot
import com.intellij.openapi.project.Project
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class RubyBuildSystem : SuspendingBuildSystem, NamedSourceBuildSystem {

    private data class Snapshot(val stamp: String, val modules: List<BuildModule>)

    private val cache = ConcurrentHashMap<String, Snapshot>()

    override val id: String = "RUBY"

    override fun isTestSource(path: String): Boolean {
        val segments = pathSegments(path)
        val name = segments.last().lowercase()
        return segments.any { it == "test" || it == "spec" } || name.endsWith("_spec.rb") || name.endsWith("_test.rb")
    }

    override val sourceExtensions: Set<String> = setOf("rb", "gemspec", "rake", "ru", "lock")

    override val sourceFileNames: Set<String> = setOf("Gemfile", "Rakefile")

    override fun isPresent(project: Project): Boolean = rootsOf(project).isNotEmpty()

    override fun modules(project: Project): List<BuildModule> {
        val roots = rootsOf(project)
        cache.keys.retainAll(roots.mapTo(HashSet()) { it.invariantSeparatorsPath })
        return roots.flatMap(::modulesOf)
    }

    private fun modulesOf(root: File): List<BuildModule> {
        val stamp = combineFingerprints(
            ManifestSearch.fingerprint(
                root,
                listOf("Gemfile", "Gemfile.lock").flatMap { ManifestSearch.find(root, it) } +
                    ManifestSearch.findByExtension(root, "gemspec"),
            ),
            ManifestSearch.layoutFingerprint(root) { it.name in RUBY_TEST_DIRECTORIES },
        )

        val rootPath = root.invariantSeparatorsPath
        if (stamp != null) cache[rootPath]?.takeIf { it.stamp == stamp }?.let { return it.modules }

        val discovered = runCatching { RubyGems.parse(root) }.getOrNull()
        val discovery = if (discovered.isNullOrEmpty()) {
            val fallbackTask = RubyTestSuites.fallbackTask(root)
            ModuleDiscovery(listOf(RubyGems.fallback(root, fallbackTask)), complete = false)
        } else {
            ModuleDiscovery(discovered, complete = true)
        }
        if (stamp != null && discovery.complete) {
            cache.retainBuildSnapshot(rootPath, Snapshot(stamp, discovery.modules), discovery.modules.size)
        }
        return discovery.modules
    }

    override fun run(project: Project, root: String, tasks: List<String>) {
        CommandRunner.runBatch(project, root, commands(root, tasks), "Affected Bundler")
    }

    override suspend fun runAndWaitSuspending(project: Project, root: String, tasks: List<String>): Boolean =
        CommandRunner.runBatchAndWait(project, root, commands(root, tasks), "Affected Bundler")

    private fun commands(root: String, tasks: List<String>): List<CliCommand> =
        rubyCommands(root, tasks, modulesOf(File(root)))

    private fun rootsOf(project: Project): List<File> =
        project.basePath?.let(::File)?.let { nestedBuildRoots(it) { File(it, "Gemfile").isRegularFileNoFollow() } }
            .orEmpty()
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
