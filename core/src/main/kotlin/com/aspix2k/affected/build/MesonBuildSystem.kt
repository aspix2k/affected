package com.aspix2k.affected.build

import com.intellij.openapi.project.Project
import java.io.File

class MesonBuildSystem : SuspendingBuildSystem, NamedSourceBuildSystem {

    override val id: String = "MESON"

    override val sourceExtensions: Set<String> = setOf("c", "cc", "cpp", "cxx", "h", "hpp")

    override val sourceFileNames: Set<String> = setOf("meson.build", "meson.options", "meson_options.txt")

    override fun isPresent(project: Project): Boolean = manifestOf(project) != null

    override fun modules(project: Project): List<BuildModule> {
        val root = manifestOf(project)?.parentFile ?: return emptyList()
        return listOf(mesonRootModule(root))
    }

    override fun run(project: Project, root: String, tasks: List<String>) {
        CommandRunner.runBatch(project, root, mesonCommands(File(root), tasks), "Affected Meson")
    }

    override suspend fun runAndWaitSuspending(project: Project, root: String, tasks: List<String>): Boolean =
        CommandRunner.runBatchAndWait(project, root, mesonCommands(File(root), tasks), "Affected Meson")

    private fun manifestOf(project: Project): File? =
        project.basePath?.let { mesonManifest(File(it)) }
}

internal object MesonTasks {
    const val TEST = "test"
    const val COMPILE = "compile"
}

internal fun mesonManifest(root: File): File? {
    if (FOREIGN_ROOTS.any { File(root, it).isRegularFileNoFollow() }) return null
    return File(root, "meson.build").takeIf(File::isRegularFileNoFollow)
}

internal fun mesonRootModule(root: File): BuildModule {
    val rootPath = root.invariantSeparatorsPath
    return BuildModule(
        id = root.name.ifBlank { "project" },
        root = rootPath,
        contentRoots = listOf(rootPath),
        testTask = MesonTasks.TEST,
        compileTask = MesonTasks.COMPILE,
        hasTests = mesonHasTests(root),
        executionId = ".",
    )
}

internal fun mesonHasTests(root: File): Boolean {
    val manifest = File(root, "meson.build")
    if (!manifest.isRegularFileNoFollow()) return false
    val text = runCatching { manifest.readText() }.getOrNull() ?: return false
    return MESON_TEST.containsMatchIn(text)
}

internal fun mesonBuildDirectory(root: File): String {
    if (mesonConfigured(File(root, "build"))) return "build"
    if (mesonConfigured(File(root, "builddir"))) return "builddir"
    return if (File(root, "build").exists()) "builddir" else "build"
}

internal fun mesonCommands(root: File, tasks: List<String>): List<CliCommand> {
    if (tasks.isEmpty()) return emptyList()
    val verbs = tasks.map { it.substringAfterLast(':') }.toSet()
    val verb = if (verbs == setOf(MesonTasks.COMPILE)) MesonTasks.COMPILE else MesonTasks.TEST
    val directory = mesonBuildDirectory(root)
    val commands = mutableListOf<CliCommand>()
    if (!mesonConfigured(File(root, directory))) {
        commands += CliCommand("meson setup", listOf("meson", "setup", directory))
    }
    commands += CliCommand("meson $verb", listOf("meson", verb, "-C", directory))
    return commands
}

private fun mesonConfigured(directory: File): Boolean =
    File(directory, "meson-info").isDirectory

private val MESON_TEST = Regex("""\btest\s*\(""")
private val FOREIGN_ROOTS = listOf("settings.gradle.kts", "settings.gradle", "pom.xml", "CMakeLists.txt")
