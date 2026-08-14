package com.aspix2k.affected.build

import com.intellij.openapi.project.Project
import java.io.File
import java.nio.file.Files

class BazelBuildSystem : SuspendingBuildSystem, WorkspaceChangesBuildSystem, NamedSourceBuildSystem {

    override val id: String = "BAZEL"

    override val sourceExtensions: Set<String> =
        setOf("java", "kt", "kts", "cc", "c", "h", "py", "go", "rs", "bzl", "bazel")

    override val sourceFileNames: Set<String> = BAZEL_NAMED_SOURCES

    override fun isPresent(project: Project): Boolean = manifestOf(project) != null

    override fun modules(project: Project): List<BuildModule> {
        val root = manifestOf(project)?.parentFile ?: return emptyList()
        return failClosedModules(root, BazelTasks.TEST, BazelTasks.BUILD, bazelPackages(root)).modules
    }

    override fun requiresWorkspace(module: BuildModule, changes: BuildChanges): Boolean =
        bazelRequiresWorkspace(module.root, changes)

    override fun run(project: Project, root: String, tasks: List<String>) {
        CommandRunner.runBatch(project, root, bazelCommands(tasks), "Affected Bazel")
    }

    override suspend fun runAndWaitSuspending(project: Project, root: String, tasks: List<String>): Boolean =
        CommandRunner.runBatchAndWait(project, root, bazelCommands(tasks), "Affected Bazel")

    private fun manifestOf(project: Project): File? =
        project.basePath?.let(::File)?.let(::bazelProjectRoot)?.let(::bazelManifest)
}

internal object BazelTasks {
    const val TEST = "test"
    const val BUILD = "build"
}

internal fun bazelProjectRoot(base: File): File? =
    nestedBuildRoot(base) { bazelManifest(it) != null }

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
    root.walkTopDown().maxDepth(6).onEnter(::bazelPackageDirectory).any { file ->
        file.isFile && file.name in BAZEL_BUILD_FILES && BAZEL_TEST_RULE.containsMatchIn(
            runCatching { file.readText() }.getOrDefault(""),
        )
    }

internal fun bazelPackages(root: File): List<BuildModule>? {
    val packages = LinkedHashMap<String, BuildModule>()
    packages["."] = bazelRootModule(root)
    val seen = HashSet<String>()
    root.walkTopDown().maxDepth(6).onEnter(::bazelPackageDirectory).forEach { file ->
        if (!file.isFile || file.name !in BAZEL_BUILD_FILES) return@forEach
        if (!file.isRegularFileNoFollow()) return null
        val directory = file.parentFile ?: return null
        if (!seen.add(directory.invariantSeparatorsPath)) return null
        val relative = root.toPath().relativize(directory.toPath()).toString().replace('\\', '/').ifEmpty { "." }
        if (relative == ".") return@forEach
        if (".." in relative || relative.startsWith("/") || !PACKAGE_PATH.matches(relative)) return null
        val text = runCatching { file.readText() }.getOrNull() ?: return null
        packages[relative] = BuildModule(
            id = relative,
            root = root.invariantSeparatorsPath,
            contentRoots = listOf(directory.invariantSeparatorsPath),
            testTask = BazelTasks.TEST,
            compileTask = BazelTasks.BUILD,
            hasTests = BAZEL_TEST_RULE.containsMatchIn(text),
            executionRoot = root.invariantSeparatorsPath,
            executionId = relative,
        )
    }
    return packages.values.toList()
}

private fun bazelPackageDirectory(directory: File): Boolean =
    !Files.isSymbolicLink(directory.toPath()) &&
        directory.name != ".git" &&
        directory.name !in BAZEL_OUTPUT_DIRECTORIES

internal fun bazelRequiresWorkspace(root: String, changes: BuildChanges): Boolean {
    val rootPath = File(root).toPath().toAbsolutePath().normalize()
    return changes.files.any { raw ->
        val file = File(raw).toPath().toAbsolutePath().normalize()
        if (!file.startsWith(rootPath)) return@any true
        val relative = rootPath.relativize(file).toString().replace('\\', '/')
        val name = relative.substringAfterLast('/')
        relative in BAZEL_WORKSPACE_FILES || name in BAZEL_BUILD_FILES || name.endsWith(".bzl")
    }
}

internal fun bazelCommands(tasks: List<String>): List<CliCommand> {
    if (tasks.isEmpty()) return emptyList()
    val verbs = tasks.map { it.substringAfterLast(':') }.toSet()
    val verb = if (verbs == setOf(BazelTasks.BUILD)) BazelTasks.BUILD else BazelTasks.TEST
    val packages = tasks.map { it.substringBeforeLast(':') }.distinct()
    val scoped = packages.none { it == "." } && packages.all { PACKAGE_PATH.matches(it) }
    val targets = if (scoped) {
        packages.sorted().map { "//$it:all" }
    } else {
        listOf("//...")
    }
    return listOf(CliCommand("bazel $verb", listOf("bazel", verb) + targets))
}

private val BAZEL_MARKERS = listOf("MODULE.bazel", "WORKSPACE.bazel", "WORKSPACE")
private val BAZEL_BUILD_FILES = setOf("BUILD.bazel", "BUILD")
private val BAZEL_NAMED_SOURCES = setOf(
    "BUILD",
    "BUILD.bazel",
    "MODULE.bazel",
    "WORKSPACE",
    "WORKSPACE.bazel",
    ".bazelversion",
    ".bazelrc",
    "USER.bazelrc",
)
private val BAZEL_WORKSPACE_FILES = setOf(
    "MODULE.bazel",
    "WORKSPACE",
    "WORKSPACE.bazel",
    ".bazelversion",
    ".bazelrc",
    "USER.bazelrc",
)
private val BAZEL_OUTPUT_DIRECTORIES = setOf("bazel-bin", "bazel-out", "bazel-testlogs", "bazel-genfiles")
private val BAZEL_TEST_RULE = Regex("""\b\w*_test\s*\(""")
private val PACKAGE_PATH = Regex("""[A-Za-z0-9._\-]+(?:/[A-Za-z0-9._\-]+)*""")
private val FOREIGN_ROOTS = listOf("settings.gradle.kts", "settings.gradle", "pom.xml")
