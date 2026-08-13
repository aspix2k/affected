package com.aspix2k.affected.build

import com.intellij.openapi.project.Project
import java.io.File

class SbtBuildSystem : SuspendingBuildSystem, WorkspaceChangesBuildSystem {

    override val id: String = "SBT"

    override val sourceExtensions: Set<String> =
        setOf("scala", "sc", "sbt", "java", "kt", "groovy", "properties")

    override fun isPresent(project: Project): Boolean = manifestOf(project) != null

    override fun modules(project: Project): List<BuildModule> {
        val manifest = manifestOf(project) ?: return emptyList()
        val root = manifest.parentFile
        return failClosedModules(root, SbtTasks.TEST, SbtTasks.COMPILE, sbtModules(root)).modules
    }

    override fun requiresWorkspace(module: BuildModule, changes: BuildChanges): Boolean =
        sbtRequiresWorkspace(module.root, changes)

    override fun run(project: Project, root: String, tasks: List<String>) {
        CommandRunner.runBatch(project, root, sbtCommands(tasks), "Affected sbt")
    }

    override suspend fun runAndWaitSuspending(project: Project, root: String, tasks: List<String>): Boolean =
        CommandRunner.runBatchAndWait(project, root, sbtCommands(tasks), "Affected sbt")

    private fun manifestOf(project: Project): File? =
        project.basePath?.let { File(it, "build.sbt") }?.takeIf(File::isRegularFileNoFollow)
}

internal object SbtTasks {
    const val TEST = "test"
    const val COMPILE = "compile"
}

internal fun sbtRootModule(root: File): BuildModule {
    val rootPath = root.invariantSeparatorsPath
    return BuildModule(
        id = root.name.ifBlank { "project" },
        root = rootPath,
        contentRoots = listOf(rootPath),
        testTask = SbtTasks.TEST,
        compileTask = SbtTasks.COMPILE,
        hasTests = sbtHasTests(root),
        executionId = ".",
    )
}

internal fun sbtHasTests(root: File): Boolean {
    val tests = File(root, "src/test")
    return tests.isDirectory && tests.walkTopDown().any(::sbtSourceFile)
}

internal fun sbtCommands(tasks: List<String>): List<CliCommand> {
    if (tasks.isEmpty()) return emptyList()
    val verbs = tasks.map { it.substringAfterLast(':') }.toSet()
    val verb = if (verbs == setOf(SbtTasks.COMPILE)) SbtTasks.COMPILE else SbtTasks.TEST
    val projects = tasks.map { it.substringBeforeLast(':') }.distinct()
    val scoped = if ("." in projects) {
        listOf(verb)
    } else {
        projects.sorted().map { "$it/$verb" }
    }
    return listOf(CliCommand("sbt $verb", listOf("sbt", "--batch") + scoped))
}

internal fun sbtModules(root: File): List<BuildModule>? {
    val manifest = File(root, "build.sbt")
    if (!manifest.isRegularFileNoFollow()) return null
    val text = runCatching { manifest.readText() }.getOrNull() ?: return null
    if (UNPROVED_SBT_PROJECT.containsMatchIn(text)) return null
    val declared = mutableListOf<Pair<String, String>>()
    for (match in SBT_PROJECT.findAll(text)) {
        val name = match.groupValues[1].removeSurrounding("`")
        val directory = match.groupValues.drop(2).firstOrNull(String::isNotEmpty) ?: name
        if (name.isEmpty() || ".." in directory || directory.startsWith("/")) return null
        val relative = directory.replace('\\', '/').removePrefix("./").ifEmpty { "." }
        declared += name to if (relative == ".") "." else relative
    }
    if (declared.isEmpty()) return listOf(sbtRootModule(root))
    if (declared.map { it.first }.distinct().size != declared.size) return null
    val rootPath = root.invariantSeparatorsPath
    return declared.map { (name, directory) ->
        val content = if (directory == ".") root else File(root, directory)
        BuildModule(
            id = name,
            root = rootPath,
            contentRoots = listOf(content.invariantSeparatorsPath),
            testTask = SbtTasks.TEST,
            compileTask = SbtTasks.COMPILE,
            hasTests = sbtHasTests(content),
            executionRoot = rootPath,
            executionId = name,
        )
    }
}

internal fun sbtRequiresWorkspace(root: String, changes: BuildChanges): Boolean {
    val rootPath = File(root).toPath().toAbsolutePath().normalize()
    return changes.files.any { raw ->
        val file = File(raw).toPath().toAbsolutePath().normalize()
        if (!file.startsWith(rootPath)) return@any true
        val relative = rootPath.relativize(file).toString().replace('\\', '/')
        relative == "build.sbt" || relative.startsWith("project/")
    }
}

private fun sbtSourceFile(file: File): Boolean =
    file.isFile && file.extension in setOf("scala", "java", "kt", "groovy")

private val UNPROVED_SBT_PROJECT = Regex("""\b(?:Project|CrossProject|ProjectRef)\s*\(""")

private val SBT_PROJECT = Regex(
    """lazy\s+val\s+(`[^`]+`|[A-Za-z_][\w]*)\s*=\s*""" +
        """(?:\(\s*project\s+in\s+file\(\s*"([^"]*)"\s*\)\s*\)|""" +
        """project\.in\(\s*file\(\s*"([^"]*)"\s*\)\s*\)|""" +
        """project\s+in\s+file\(\s*"([^"]*)"\s*\)|""" +
        """project\b)""",
)
