package com.aspix2k.affected.build

import com.aspix2k.affected.build.process.CliCommand
import com.aspix2k.affected.build.process.CommandRunner
import com.intellij.openapi.project.Project
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class SwiftBuildSystem(
    private val describe: (String) -> String? = ::describeSwiftPackage,
) : SuspendingBuildSystem, NamedSourceBuildSystem, TransitiveTestConsumersBuildSystem {

    private data class Snapshot(val stamp: String, val modules: List<BuildModule>)

    private val cache = ConcurrentHashMap<String, Snapshot>()

    override val id: String = "SWIFT"

    override val sourceExtensions: Set<String> = setOf("swift", "h", "m", "mm")

    override val sourceFileNames: Set<String> = setOf("Package.swift")

    override fun isPresent(project: Project): Boolean = rootsOf(project).isNotEmpty()

    override fun modules(project: Project): List<BuildModule> {
        val roots = rootsOf(project)
        cache.keys.retainAll(roots.mapTo(HashSet()) { it.invariantSeparatorsPath })
        return roots.flatMap { modules(it) }
    }

    internal fun modules(root: File): List<BuildModule> {
        val rootPath = root.invariantSeparatorsPath
        val stamp = ManifestSearch.fingerprint(root, manifests(root))
        if (stamp != null) cache[rootPath]?.takeIf { it.stamp == stamp }?.let { return it.modules }

        val discovered = describe(rootPath)?.let { SwiftTargets.parse(it, root) } ?: listOf(swiftRootModule(root))
        ManifestSearch.fingerprint(root, manifests(root))?.let { settled ->
            cache.retainBuildSnapshot(rootPath, Snapshot(settled, discovered), discovered.size)
        }
        return discovered
    }

    override fun run(project: Project, root: String, tasks: List<String>) {
        CommandRunner.runBatch(project, root, swiftCommands(tasks), "Affected Swift")
    }

    override suspend fun runAndWaitSuspending(project: Project, root: String, tasks: List<String>): Boolean =
        CommandRunner.runBatchAndWait(project, root, swiftCommands(tasks), "Affected Swift")

    private fun rootsOf(project: Project): List<File> =
        project.basePath?.let(::File)?.let { nestedBuildRoots(it) { swiftManifest(it) != null } }.orEmpty()

    private fun manifests(root: File): List<File> =
        ManifestSearch.find(root, "Package.swift") + ManifestSearch.find(root, "Package.resolved") +
            root.listFiles { file -> VERSIONED_MANIFEST.matches(file.name) }.orEmpty()

    private companion object {
        val VERSIONED_MANIFEST = Regex("Package@swift-.+\\.swift")
    }
}

private fun describeSwiftPackage(root: String): String? =
    CommandRunner.capture(root, DESCRIBE, timeoutSeconds = DESCRIBE_TIMEOUT_SECONDS)

private val DESCRIBE = listOf("swift", "package", "describe", "--type", "json")
private const val DESCRIBE_TIMEOUT_SECONDS = 120L

internal object SwiftTasks {
    const val TEST = "test"
    const val BUILD = "build"
}

internal fun swiftManifest(root: File): File? {
    if (FOREIGN_ROOTS.any { File(root, it).isRegularFileNoFollow() }) return null
    return File(root, "Package.swift").takeIf(File::isRegularFileNoFollow)
}

internal fun swiftRootModule(root: File): BuildModule {
    val rootPath = root.invariantSeparatorsPath
    return BuildModule(
        id = root.name.ifBlank { "project" },
        root = rootPath,
        contentRoots = listOf(rootPath),
        testTask = SwiftTasks.TEST,
        compileTask = SwiftTasks.BUILD,
        hasTests = swiftHasTests(root),
        executionId = PACKAGE,
    )
}

internal fun swiftHasTests(root: File): Boolean {
    val tests = File(root, "Tests")
    return tests.isDirectory && tests.walkTopDown().any(::swiftTestFile)
}

internal fun swiftCommands(tasks: List<String>): List<CliCommand> {
    if (tasks.isEmpty()) return emptyList()
    val targets = tasks.groupBy({ it.substringAfterLast(':') }, { it.substringBeforeLast(':') })
    val unnarrowable = targets.keys.any { it != SwiftTasks.BUILD && it != SwiftTasks.TEST }
    val tests = if (unnarrowable) listOf(PACKAGE) else targets[SwiftTasks.TEST]
    return listOfNotNull(
        targets[SwiftTasks.BUILD]?.let { swiftCommand(SwiftTasks.BUILD, it, "--target") { name -> name } },
        tests?.let { swiftCommand(SwiftTasks.TEST, it, "--filter") { name -> "^$name\\." } },
    )
}

private fun swiftCommand(verb: String, targets: List<String>, option: String, value: (String) -> String): CliCommand {
    val wholePackage = targets.any { it == PACKAGE || !SwiftTargets.SAFE_NAME.matches(it) }
    val selection = if (wholePackage) emptyList() else targets.distinct().flatMap { listOf(option, value(it)) }
    return CliCommand("swift $verb", listOf("swift", verb) + selection)
}

private fun swiftTestFile(file: File): Boolean =
    file.isFile && file.name.endsWith(".swift")

private const val PACKAGE = "."
private val FOREIGN_ROOTS = listOf("settings.gradle.kts", "settings.gradle", "pom.xml")
