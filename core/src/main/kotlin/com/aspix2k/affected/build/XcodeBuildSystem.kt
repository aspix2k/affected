package com.aspix2k.affected.build

import com.intellij.openapi.project.Project
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

class XcodeBuildSystem : SuspendingBuildSystem, NamedSourceBuildSystem {

    override val id: String = "XCODE"

    override val sourceExtensions: Set<String> = setOf("swift", "h", "m", "mm", "plist", "xcscheme", "xctestplan")

    override val sourceFileNames: Set<String> = setOf("project.pbxproj")

    override fun isPresent(project: Project): Boolean = manifestOf(project) != null

    override fun modules(project: Project): List<BuildModule> {
        val root = manifestOf(project)?.parentFile ?: return emptyList()
        return listOf(xcodeRootModule(root))
    }

    override fun run(project: Project, root: String, tasks: List<String>) {
        CommandRunner.runBatch(
            project,
            root,
            xcodeExecutionCommands(File(root), tasks),
            "Affected Xcode",
            XCODE_METADATA_DRIFT_MESSAGE,
        )
    }

    override suspend fun runAndWaitSuspending(project: Project, root: String, tasks: List<String>): Boolean =
        CommandRunner.runBatchAndWait(
            project,
            root,
            xcodeExecutionCommands(File(root), tasks),
            "Affected Xcode",
            XCODE_METADATA_DRIFT_MESSAGE,
        )

    private fun manifestOf(project: Project): File? =
        project.basePath?.let(::File)?.let { nestedBuildRoot(it) { xcodeManifest(it) != null } }
            ?.let(::xcodeManifest)
}

internal object XcodeTasks {
    const val VALIDATE = "validate"
    const val TEST = "test"
    const val BUILD = "build"
}

internal fun xcodeManifest(root: File): File? {
    if (FOREIGN_ROOTS.any { File(root, it).isRegularFileNoFollow() }) return null
    if (File(root, "Package.swift").isRegularFileNoFollow()) return null
    return xcodeProject(root)
}

internal fun xcodeRootModule(root: File): BuildModule {
    val rootPath = root.invariantSeparatorsPath
    return BuildModule(
        id = root.name.ifBlank { "project" },
        root = rootPath,
        contentRoots = listOf(rootPath),
        testTask = XcodeTasks.VALIDATE,
        compileTask = XcodeTasks.BUILD,
        hasTests = true,
        executionId = ".",
    )
}

internal fun xcodeCommands(root: File, tasks: List<String>): List<CliCommand> {
    return listOfNotNull(xcodeCommand(root, tasks))
}

internal fun xcodeExecutionCommands(root: File, tasks: List<String>): List<CliStep> {
    if (tasks.isEmpty()) return emptyList()
    return listOf(DeferredCliCommand.command {
        xcodeCommand(root, tasks) ?: error(XCODE_METADATA_DRIFT_MESSAGE)
    })
}

private fun xcodeCommand(root: File, tasks: List<String>): CliCommand? {
    if (tasks.isEmpty()) return null
    val verbs = tasks.map { it.substringAfterLast(':') }.toSet()
    val discovery = xcodeSchemeDiscovery(root)
    val verb = when {
        verbs == setOf(XcodeTasks.BUILD) -> XcodeTasks.BUILD
        discovery.complete && discovery.schemes.isNotEmpty() && discovery.schemes.none(XcodeScheme::testable) ->
            XcodeTasks.BUILD
        else -> XcodeTasks.TEST
    }
    val schemes = if (discovery.complete) {
        when (verb) {
            XcodeTasks.TEST -> discovery.schemes.filter(XcodeScheme::testable).map(XcodeScheme::name)
            else -> discovery.schemes.map(XcodeScheme::name)
        }.distinct()
    } else {
        emptyList()
    }
    val arguments = if (schemes.size == 1) {
        listOf("xcodebuild", verb, "-scheme", schemes.single())
    } else {
        listOf("xcodebuild", verb)
    } + if (verb == XcodeTasks.BUILD) listOf("CODE_SIGNING_ALLOWED=NO") else emptyList()
    return CliCommand(arguments.joinToString(" "), arguments)
}

private fun xcodeProject(root: File): File? {
    val directory = root.toPath().toAbsolutePath().normalize()
    if (!directory.isSecureXcodeDirectory()) return null
    val started = System.nanoTime()
    return runCatching {
        Files.newDirectoryStream(directory).use { entries ->
            var count = 0
            for (entry in entries) {
                if (++count > PerformanceBudgets.MAX_DIRECTORIES ||
                    Thread.currentThread().isInterrupted ||
                    System.nanoTime() - started > PerformanceBudgets.SCAN_TIME_NS
                ) {
                    return null
                }
                if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS) &&
                    !Files.isSymbolicLink(entry) &&
                    XCODE_BUNDLE.containsMatchIn(entry.fileName.toString())
                ) {
                    return entry.toFile()
                }
            }
        }
        null
    }.getOrNull()
}

internal val XCODE_BUNDLE = Regex("""\.(?:xcodeproj|xcworkspace)$""")
private val FOREIGN_ROOTS = listOf("settings.gradle.kts", "settings.gradle", "pom.xml")
private const val XCODE_METADATA_DRIFT_MESSAGE =
    "Affected detected an Xcode scheme change after planning. Refresh the project model and run again."
