package com.aspix2k.affected.build.xcode

import com.aspix2k.affected.build.BuildModule
import com.aspix2k.affected.build.EngineBuildSystem
import com.aspix2k.affected.build.NamedSourceBuildSystem
import com.aspix2k.affected.build.PerformanceBudgets
import com.aspix2k.affected.build.Workspace
import com.aspix2k.affected.build.isRegularFileNoFollow
import com.aspix2k.affected.build.nestedBuildRoots
import com.aspix2k.affected.build.process.CliCommand
import com.aspix2k.affected.build.process.CliStep
import com.aspix2k.affected.build.process.DeferredCliCommand
import com.aspix2k.affected.build.runBatch
import com.aspix2k.affected.build.runBatchAndWait
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

internal class XcodeBuildSystem : EngineBuildSystem, NamedSourceBuildSystem {

    override val id: String = "XCODE"

    override val sourceExtensions: Set<String> = setOf("swift", "h", "m", "mm", "plist", "xcscheme", "xctestplan")

    override val sourceFileNames: Set<String> = setOf("project.pbxproj")

    override fun isPresent(workspace: Workspace): Boolean = rootsOf(workspace).isNotEmpty()

    override fun modules(workspace: Workspace): List<BuildModule> = rootsOf(workspace).map(::xcodeRootModule)

    override fun run(workspace: Workspace, root: String, tasks: List<String>) {
        workspace.runBatch(
            root,
            xcodeExecutionCommands(File(root), tasks),
            "Affected Xcode",
            XCODE_METADATA_DRIFT_MESSAGE,
        )
    }

    override suspend fun runAndWait(workspace: Workspace, root: String, tasks: List<String>): Boolean =
        workspace.runBatchAndWait(
            root,
            xcodeExecutionCommands(File(root), tasks),
            "Affected Xcode",
            XCODE_METADATA_DRIFT_MESSAGE,
        )

    private fun rootsOf(workspace: Workspace): List<File> =
        workspace.root?.let { base ->
            nestedBuildRoots(base, XCODE_BUNDLE::containsMatchIn) { xcodeManifest(it) != null }
        }.orEmpty()
}

internal object XcodeTasks {
    const val VALIDATE = "validate"
    const val TEST = "test"
    const val BUILD = "build"
}

internal fun xcodeManifest(root: File): File? {
    if (XCODE_BUNDLE.containsMatchIn(root.name)) return null
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

private val FOREIGN_ROOTS = listOf("settings.gradle.kts", "settings.gradle", "pom.xml")
private const val XCODE_METADATA_DRIFT_MESSAGE =
    "Affected detected an Xcode scheme change after planning. Refresh the project model and run again."
