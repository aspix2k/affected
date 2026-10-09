package com.aspix2k.affected.build

import com.aspix2k.affected.build.process.CliCommand
import java.io.File

internal class SbtBuildSystem : EngineBuildSystem, WorkspaceChangesBuildSystem {

    override val id: String = "SBT"

    override fun isTestSource(path: String): Boolean = isJvmTestSourceSet(path)

    override val sourceExtensions: Set<String> =
        setOf("scala", "sc", "sbt", "java", "kt", "groovy", "properties")

    override fun isPresent(workspace: Workspace): Boolean = rootsOf(workspace).isNotEmpty()

    override fun modules(workspace: Workspace): List<BuildModule> =
        rootsOf(workspace).flatMap { root ->
            failClosedModules(root, SbtTasks.TEST, SbtTasks.COMPILE, sbtModules(root)).modules
        }

    override fun requiresWorkspace(module: BuildModule, changes: BuildChanges): Boolean =
        sbtRequiresWorkspace(module.root, changes)

    override fun run(workspace: Workspace, root: String, tasks: List<String>) {
        workspace.runBatch(root, sbtCommands(tasks), "Affected sbt")
    }

    override suspend fun runAndWait(workspace: Workspace, root: String, tasks: List<String>): Boolean =
        workspace.runBatchAndWait(root, sbtCommands(tasks), "Affected sbt")

    private fun rootsOf(workspace: Workspace): List<File> =
        workspace.root?.let(::sbtProjectRoots).orEmpty()
}

internal fun sbtProjectRoots(base: File): List<File> {
    val declared = HashMap<File, Set<String>?>()
    return nestedBuildRoots(
        base,
        setOf("build.sbt"),
        { root, nested ->
            val directories = declared.getOrPut(root) { sbtModules(root)?.flatMapTo(HashSet()) { it.contentRoots } }
            directories == null || nested.invariantSeparatorsPath in directories
        },
    ) { File(it, "build.sbt").isRegularFileNoFollow() }
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
    val matches = SBT_PROJECT.findAll(text).toList()
    val declared = matches.map { sbtDeclaration(root, it) ?: return null }
    if (declared.isEmpty()) return listOf(sbtRootModule(root))
    val names = declared.map { it.first }
    if (names.distinct().size != declared.size) return null
    val rootPath = root.invariantSeparatorsPath
    return declared.mapIndexed { index, (name, directory) ->
        val end = matches.getOrNull(index + 1)?.range?.first ?: text.length
        val depended = sbtDependencies(text.substring(matches[index].range.last + 1, end))
        val content = if (directory == ".") root else File(root, directory)
        BuildModule(
            id = name,
            root = rootPath,
            contentRoots = listOf(content.invariantSeparatorsPath),
            testTask = SbtTasks.TEST,
            compileTask = SbtTasks.COMPILE,
            hasTests = sbtHasTests(content),
            dependencies = depended.filter { it in names && it != name }.mapTo(HashSet()) { "$rootPath|$it" },
            executionRoot = rootPath,
            executionId = name,
        )
    }
}

private fun sbtDependencies(declaration: String): Set<String> =
    SBT_DEPENDS_ON.findAll(declaration).flatMapTo(HashSet()) { call ->
        SBT_IDENTIFIER.findAll(call.groupValues[1]).map { it.value.removeSurrounding("`") }
    }

private fun sbtDeclaration(root: File, match: MatchResult): Pair<String, String>? {
    val name = match.groupValues[1].removeSurrounding("`")
    val explicit = match.groupValues.drop(2).firstOrNull(String::isNotEmpty)
    val directory = explicit ?: name
    if (name.isEmpty() || ".." in directory || directory.startsWith("/")) return null
    if (explicit == null && !File(root, directory).isDirectory) return null
    return name to directory.replace('\\', '/').removePrefix("./").ifEmpty { "." }
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

private val SBT_DEPENDS_ON = Regex("""\.\s*dependsOn\s*\(([^)]*)\)""")
private val SBT_IDENTIFIER = Regex("""`[^`]+`|[A-Za-z_]\w*""")
private val UNPROVED_SBT_PROJECT = Regex("""\b(?:Project|CrossProject|ProjectRef)\s*\(""")

private val SBT_PROJECT = Regex(
    """lazy\s+val\s+(`[^`]+`|[A-Za-z_][\w]*)\s*=\s*""" +
        """(?:\(\s*project\s+in\s+file\(\s*"([^"]*)"\s*\)\s*\)|""" +
        """project\s*\.\s*in\(\s*file\(\s*"([^"]*)"\s*\)\s*\)|""" +
        """project\s+in\s+file\(\s*"([^"]*)"\s*\)|""" +
        """project\b)""",
)
