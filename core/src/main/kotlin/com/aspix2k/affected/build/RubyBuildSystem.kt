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
        project.basePath?.let(::File)?.takeIf { File(it, "Gemfile").isRegularFileNoFollow() }
}

private val RUBY_TEST_DIRECTORIES = setOf("test", "spec")

internal fun rubyCommands(root: String, tasks: List<String>, modules: List<BuildModule>): List<CliCommand> {
    val byName = modules.associateBy { it.executionId }
    val suiteStates = mutableMapOf<String, Boolean?>()
    fun suiteState(path: String): Boolean? {
        if (suiteStates.containsKey(path)) return suiteStates[path]
        return RubyTestSuites.suitePresent(File(root, path)).also { suiteStates[path] = it }
    }
    val selected = tasks.map { task ->
        val name = task.substringBeforeLast(':')
        val plannedTask = task.substringAfterLast(':')
        val module = byName[name] ?: return emptyList()
        if (module.testTask != plannedTask) return emptyList()
        val directories = module.contentRoots.map { relativeRubyPath(root, it) ?: return emptyList() }
        RubyTestSuites.runners(plannedTask)?.let { Triple(it, directories, RubyTestSuites.isFallback(plannedTask)) }
            ?: return emptyList()
    }
    selected.filter { it.third }.forEach { (runners, roots) ->
        roots.forEach { path ->
            val spec = suiteState(if (path == ".") "spec" else "$path/spec") ?: return emptyList()
            val test = suiteState(if (path == ".") "test" else "$path/test") ?: return emptyList()
            if (spec && RubyTestRunner.RSPEC !in runners) return emptyList()
            if (test && runners.none { it != RubyTestRunner.RSPEC }) return emptyList()
        }
    }
    return RubyTestRunner.entries.mapNotNull { runner ->
        val paths = mutableListOf<String>()
        selected.filter { runner in it.first }.forEach { (_, roots, fallback) ->
            roots.forEach { path ->
                when (val suite = suitePath(path, runner, fallback, ::suiteState)) {
                    null -> return emptyList()
                    "" -> Unit
                    else -> paths += suite
                }
            }
        }
        val distinctPaths = paths.distinct()
        distinctPaths.takeIf(List<String>::isNotEmpty)?.let {
            CliCommand(runner.command, listOf("bundle", "exec", runner.command) + it.map(::rubyRunnerPath))
        }
    }
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
