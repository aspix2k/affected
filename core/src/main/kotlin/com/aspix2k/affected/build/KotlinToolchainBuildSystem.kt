package com.aspix2k.affected.build

import com.intellij.openapi.project.Project
import java.io.File

class KotlinToolchainBuildSystem : SuspendingBuildSystem {

    override val id: String = "KOTLIN_TOOLCHAIN"

    override val sourceExtensions: Set<String> = setOf("kt", "kts", "java", "yaml")

    override fun isPresent(project: Project): Boolean = manifestOf(project) != null

    override fun modules(project: Project): List<BuildModule> {
        val root = manifestOf(project)?.parentFile ?: return emptyList()
        return listOf(kotlinToolchainRootModule(root))
    }

    override fun run(project: Project, root: String, tasks: List<String>) {
        CommandRunner.runBatch(project, root, kotlinToolchainCommands(File(root), tasks), "Affected Kotlin Toolchain")
    }

    override suspend fun runAndWaitSuspending(project: Project, root: String, tasks: List<String>): Boolean =
        CommandRunner.runBatchAndWait(
            project,
            root,
            kotlinToolchainCommands(File(root), tasks),
            "Affected Kotlin Toolchain",
        )

    private fun manifestOf(project: Project): File? =
        project.basePath?.let { kotlinToolchainManifest(File(it)) }
}

internal object KotlinToolchainTasks {
    const val TEST = "test"
    const val BUILD = "build"
}

internal fun kotlinToolchainManifest(root: File): File? {
    if (GRADLE_SETTINGS.any { File(root, it).isRegularFileNoFollow() }) return null
    val yaml = TOOLCHAIN_YAML.firstOrNull { File(root, it).isRegularFileNoFollow() } ?: return null
    if (kotlinToolchainWrapper(root) == null) return null
    return File(root, yaml)
}

internal fun kotlinToolchainWrapper(root: File): String? {
    val windows = File.separatorChar == '\\'
    val script = File(root, "kotlin")
    val batch = File(root, "kotlin.bat")
    return when {
        windows && batch.isRegularFileNoFollow() -> "kotlin.bat"
        script.isRegularFileNoFollow() -> "./kotlin"
        batch.isRegularFileNoFollow() -> "kotlin.bat"
        else -> null
    }
}

internal fun kotlinToolchainRootModule(root: File): BuildModule {
    val rootPath = root.invariantSeparatorsPath
    return BuildModule(
        id = root.name.ifBlank { "project" },
        root = rootPath,
        contentRoots = listOf(rootPath),
        testTask = KotlinToolchainTasks.TEST,
        compileTask = KotlinToolchainTasks.BUILD,
        hasTests = kotlinToolchainHasTests(root),
        executionId = ".",
    )
}

internal fun kotlinToolchainHasTests(root: File): Boolean =
    root.listFiles().orEmpty().any { candidate ->
        candidate.isDirectory &&
            (candidate.name == "test" || candidate.name.startsWith("test@")) &&
            candidate.walkTopDown().any(::kotlinToolchainSourceFile)
    }

internal fun kotlinToolchainCommands(root: File, tasks: List<String>): List<CliCommand> {
    if (tasks.isEmpty()) return emptyList()
    val wrapper = kotlinToolchainWrapper(root) ?: return emptyList()
    val verbs = tasks.map { it.substringAfterLast(':') }.toSet()
    val verb = if (verbs == setOf(KotlinToolchainTasks.BUILD)) {
        KotlinToolchainTasks.BUILD
    } else {
        KotlinToolchainTasks.TEST
    }
    return listOf(CliCommand("kotlin $verb", listOf(wrapper, verb)))
}

private fun kotlinToolchainSourceFile(file: File): Boolean =
    file.isFile && file.extension in setOf("kt", "kts", "java")

private val TOOLCHAIN_YAML = listOf("project.yaml", "module.yaml")
private val GRADLE_SETTINGS = listOf("settings.gradle.kts", "settings.gradle")
