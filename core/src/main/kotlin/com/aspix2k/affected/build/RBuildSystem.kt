package com.aspix2k.affected.build

import com.intellij.openapi.project.Project
import java.io.File

class RBuildSystem : SuspendingBuildSystem, NamedSourceBuildSystem {

    override val id: String = "RPROJECT"

    override val sourceExtensions: Set<String> = setOf("r", "R")

    override val sourceFileNames: Set<String> = setOf("DESCRIPTION", "renv.lock")

    override fun isPresent(project: Project): Boolean = manifestOf(project) != null

    override fun modules(project: Project): List<BuildModule> {
        val root = manifestOf(project)?.parentFile ?: return emptyList()
        return listOf(rRootModule(root))
    }

    override fun run(project: Project, root: String, tasks: List<String>) {
        CommandRunner.runBatch(project, root, rCommands(File(root), tasks), "Affected R")
    }

    override suspend fun runAndWaitSuspending(project: Project, root: String, tasks: List<String>): Boolean =
        CommandRunner.runBatchAndWait(project, root, rCommands(File(root), tasks), "Affected R")

    private fun manifestOf(project: Project): File? =
        project.basePath?.let { rManifest(File(it)) }
}

internal object RTasks {
    const val TEST = "test"
    const val CHECK = "check"
}

internal fun rManifest(root: File): File? {
    if (FOREIGN_ROOTS.any { File(root, it).isRegularFileNoFollow() }) return null
    val description = File(root, "DESCRIPTION").takeIf(File::isRegularFileNoFollow)
    val lockfile = File(root, "renv.lock").takeIf(File::isRegularFileNoFollow)
    if (description != null) {
        val text = runCatching { description.readText() }.getOrNull()
        if (text != null && PACKAGE_FIELD.containsMatchIn(text)) return description
    }
    return lockfile
}

internal fun rRootModule(root: File): BuildModule {
    val rootPath = root.invariantSeparatorsPath
    return BuildModule(
        id = root.name.ifBlank { "project" },
        root = rootPath,
        contentRoots = listOf(rootPath),
        testTask = RTasks.TEST,
        compileTask = RTasks.CHECK,
        hasTests = rHasTests(root),
        executionId = ".",
    )
}

internal fun rHasTests(root: File): Boolean {
    val tests = File(root, "tests/testthat")
    if (tests.isDirectory && tests.walkTopDown().any(::rTestFile)) return true
    return rManifest(root)?.name == "renv.lock"
}

internal fun rCommands(tasks: List<String>): List<CliCommand> = rCommands(File("."), tasks)

internal fun rCommands(root: File, tasks: List<String>): List<CliCommand> {
    if (tasks.isEmpty()) return emptyList()
    val verbs = tasks.map { it.substringAfterLast(':') }.toSet()
    val check = verbs == setOf(RTasks.CHECK) && rManifest(root)?.name == "DESCRIPTION"
    val arguments = if (check) {
        listOf("Rscript", "-e", "read.dcf(\"DESCRIPTION\")")
    } else {
        listOf("Rscript", "-e", "testthat::test_dir(\"tests/testthat\")")
    }
    return listOf(CliCommand(arguments.joinToString(" "), arguments))
}

private fun rTestFile(file: File): Boolean =
    file.isFile && TEST_FILE.matches(file.name)

private val PACKAGE_FIELD = Regex("""(?m)^Package\s*:""")
private val TEST_FILE = Regex("""test-.*\.[Rr]""")
private val FOREIGN_ROOTS = listOf("settings.gradle.kts", "settings.gradle", "pom.xml")
