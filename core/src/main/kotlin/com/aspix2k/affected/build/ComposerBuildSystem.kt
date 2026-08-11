package com.aspix2k.affected.build

import com.intellij.openapi.project.Project
import java.io.File
import java.util.concurrent.atomic.AtomicReference

class ComposerBuildSystem : SuspendingBuildSystem {

    private data class Snapshot(val root: String, val stamp: String, val modules: List<BuildModule>)

    private val cache = AtomicReference<Snapshot?>(null)

    override val id: String = "COMPOSER"

    override val sourceExtensions: Set<String> = setOf("php", "json", "neon", "xml", "lock")

    override fun isPresent(project: Project): Boolean = rootOf(project) != null

    override fun modules(project: Project): List<BuildModule> {
        val root = rootOf(project) ?: return emptyList()
        val stamp = combineFingerprints(
            ManifestSearch.fingerprint(
                root,
                listOf(
                    "composer.json", "composer.lock", "phpunit.xml", "phpunit.xml.dist", "phpunit.dist.xml",
                    "phpstan.neon", "phpstan.neon.dist", "phpstan.dist.neon", "psalm.xml", "psalm.xml.dist",
                ).flatMap { ManifestSearch.find(root, it) },
            ),
            ManifestSearch.layoutFingerprint(root) { it.name in COMPOSER_TEST_DIRECTORIES },
        )

        val rootPath = root.invariantSeparatorsPath
        if (stamp != null) cache.get()?.takeIf { it.root == rootPath && it.stamp == stamp }?.let { return it.modules }

        val discovered = runCatching { ComposerPackages.parse(root) }.getOrNull()
        val discovery = failClosedModules(root, ComposerPackages.TEST, null, discovered)
        if (stamp != null && discovery.complete) cache.set(Snapshot(rootPath, stamp, discovery.modules))
        return discovery.modules
    }

    override fun run(project: Project, root: String, tasks: List<String>) {
        CommandRunner.runBatch(project, root, commands(project, root, tasks), "Affected Composer")
    }

    override suspend fun runAndWaitSuspending(project: Project, root: String, tasks: List<String>): Boolean =
        CommandRunner.runBatchAndWait(project, root, commands(project, root, tasks), "Affected Composer")

    private fun commands(project: Project, root: String, tasks: List<String>): List<CliCommand> {
        return composerCommands(root, tasks, modules(project))
    }

    private fun rootOf(project: Project): File? =
        project.basePath?.let(::File)?.takeIf { File(it, "composer.json").isRegularFileNoFollow() }
}

private val COMPOSER_TEST_DIRECTORIES = setOf("test", "tests", "Tests")

internal fun composerCommands(root: String, tasks: List<String>, modules: List<BuildModule>): List<CliCommand> {
    val byName = modules.associateBy { it.executionId }
    val resolved = tasks.map { task ->
        val name = task.substringBeforeLast(':')
        val directory = byName[name]?.contentRoots?.singleOrNull() ?: return emptyList()
        task.substringAfterLast(':') to directory.removePrefix("$root/").ifEmpty { "." }
    }
    return resolved.groupBy({ it.first }, { it.second }).map { (task, paths) ->
        if (task == ComposerPackages.ANALYSE) {
            CliCommand("phpstan", listOf("php", "vendor/bin/phpstan", "analyse") + paths.distinct())
        } else {
            CliCommand("phpunit", listOf("php", "vendor/bin/phpunit") + paths.distinct())
        }
    }
}
