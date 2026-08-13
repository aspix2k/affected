package com.aspix2k.affected.build

import com.intellij.openapi.project.Project
import java.io.File

class BazelBuildSystem : SuspendingBuildSystem {

    override val id: String = "BAZEL"

    override val sourceExtensions: Set<String> =
        setOf("java", "kt", "kts", "cc", "c", "h", "py", "go", "rs", "bzl", "bazel")

    override fun isPresent(project: Project): Boolean = manifestOf(project) != null

    override fun modules(project: Project): List<BuildModule> {
        val root = manifestOf(project)?.parentFile ?: return emptyList()
        return listOf(bazelRootModule(root))
    }

    override fun run(project: Project, root: String, tasks: List<String>) {
        CommandRunner.runBatch(project, root, bazelCommands(tasks), "Affected Bazel")
    }

    override suspend fun runAndWaitSuspending(project: Project, root: String, tasks: List<String>): Boolean =
        CommandRunner.runBatchAndWait(project, root, bazelCommands(tasks), "Affected Bazel")

    private fun manifestOf(project: Project): File? =
        project.basePath?.let { bazelManifest(File(it)) }
}

internal object BazelTasks {
    const val TEST = "test"
    const val BUILD = "build"
}

internal fun bazelManifest(root: File): File? {
    if (FOREIGN_ROOTS.any { File(root, it).isRegularFileNoFollow() }) return null
    return BAZEL_MARKERS.firstNotNullOfOrNull { File(root, it).takeIf(File::isRegularFileNoFollow) }
}

internal fun bazelRootModule(root: File): BuildModule {
    val rootPath = root.invariantSeparatorsPath
    return BuildModule(
        id = root.name.ifBlank { "project" },
        root = rootPath,
        contentRoots = listOf(rootPath),
        testTask = BazelTasks.TEST,
        compileTask = BazelTasks.BUILD,
        hasTests = bazelHasTests(root),
        executionId = ".",
    )
}

internal fun bazelHasTests(root: File): Boolean =
    root.walkTopDown().maxDepth(6).any { file ->
        file.isFile && file.name in BAZEL_BUILD_FILES && BAZEL_TEST_RULE.containsMatchIn(
            runCatching { file.readText() }.getOrDefault(""),
        )
    }

internal fun bazelCommands(tasks: List<String>): List<CliCommand> {
    if (tasks.isEmpty()) return emptyList()
    val verbs = tasks.map { it.substringAfterLast(':') }.toSet()
    val verb = if (verbs == setOf(BazelTasks.BUILD)) BazelTasks.BUILD else BazelTasks.TEST
    return listOf(CliCommand("bazel $verb", listOf("bazel", verb, "//...")))
}

private val BAZEL_MARKERS = listOf("MODULE.bazel", "WORKSPACE.bazel", "WORKSPACE")
private val BAZEL_BUILD_FILES = setOf("BUILD.bazel", "BUILD")
private val BAZEL_TEST_RULE = Regex("""\b\w*_test\s*\(""")
private val FOREIGN_ROOTS = listOf("settings.gradle.kts", "settings.gradle", "pom.xml")
