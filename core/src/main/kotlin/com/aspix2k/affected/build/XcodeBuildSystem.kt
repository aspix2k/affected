package com.aspix2k.affected.build

import com.intellij.openapi.project.Project
import java.io.File

class XcodeBuildSystem : SuspendingBuildSystem, NamedSourceBuildSystem {

    override val id: String = "XCODE"

    override val sourceExtensions: Set<String> = setOf("swift", "h", "m", "mm", "plist")

    override val sourceFileNames: Set<String> = setOf("project.pbxproj")

    override fun isPresent(project: Project): Boolean = manifestOf(project) != null

    override fun modules(project: Project): List<BuildModule> {
        val root = manifestOf(project)?.parentFile ?: return emptyList()
        return listOf(xcodeRootModule(root))
    }

    override fun run(project: Project, root: String, tasks: List<String>) {
        CommandRunner.runBatch(project, root, xcodeCommands(File(root), tasks), "Affected Xcode")
    }

    override suspend fun runAndWaitSuspending(project: Project, root: String, tasks: List<String>): Boolean =
        CommandRunner.runBatchAndWait(project, root, xcodeCommands(File(root), tasks), "Affected Xcode")

    private fun manifestOf(project: Project): File? =
        project.basePath?.let(::File)?.let { nestedBuildRoot(it) { xcodeManifest(it) != null } }
            ?.let(::xcodeManifest)
}

internal object XcodeTasks {
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
        testTask = XcodeTasks.TEST,
        compileTask = XcodeTasks.BUILD,
        hasTests = true,
        executionId = ".",
    )
}

internal fun xcodeCommands(root: File, tasks: List<String>): List<CliCommand> {
    if (tasks.isEmpty()) return emptyList()
    val verbs = tasks.map { it.substringAfterLast(':') }.toSet()
    val verb = if (verbs == setOf(XcodeTasks.BUILD)) XcodeTasks.BUILD else XcodeTasks.TEST
    val schemes = xcodeSchemes(root)
    val arguments = if (schemes.size == 1) {
        listOf("xcodebuild", verb, "-scheme", schemes.single())
    } else {
        listOf("xcodebuild", verb)
    }
    return listOf(CliCommand(arguments.joinToString(" "), arguments))
}

internal fun xcodeSchemes(root: File): List<String> {
    val names = LinkedHashSet<String>()
    root.listFiles().orEmpty()
        .filter { it.isDirectory && XCODE_BUNDLE.containsMatchIn(it.name) }
        .forEach { project ->
            collectXcodeSchemes(File(project, "xcshareddata/xcschemes"), names)
            File(project, "xcuserdata").listFiles().orEmpty()
                .filter { it.isDirectory }
                .forEach { user -> collectXcodeSchemes(File(user, "xcschemes"), names) }
        }
    return names.toList()
}

private fun collectXcodeSchemes(directory: File, names: MutableSet<String>) {
    directory.listFiles().orEmpty()
        .filter { it.isFile && it.name.endsWith(".xcscheme") }
        .mapTo(names) { it.name.removeSuffix(".xcscheme") }
}

private fun xcodeProject(root: File): File? =
    root.listFiles().orEmpty().firstOrNull { it.isDirectory && XCODE_BUNDLE.containsMatchIn(it.name) }

private val XCODE_BUNDLE = Regex("""\.(?:xcodeproj|xcworkspace)$""")
private val FOREIGN_ROOTS = listOf("settings.gradle.kts", "settings.gradle", "pom.xml")
