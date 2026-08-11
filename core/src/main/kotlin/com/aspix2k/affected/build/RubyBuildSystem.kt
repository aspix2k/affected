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
        val discovery = failClosedModules(root, RubyGems.TEST, null, discovered)
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
    val paths = tasks.map { task ->
        val name = task.substringBeforeLast(':')
        val directory = byName[name]?.contentRoots?.singleOrNull() ?: return emptyList()
        directory.removePrefix("$root/").ifEmpty { "." }
    }
    return listOf(CliCommand("rspec", listOf("bundle", "exec", "rspec") + paths.distinct()))
}
