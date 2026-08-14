package com.aspix2k.affected.build

import com.intellij.openapi.project.Project
import java.io.File

class DartBuildSystem : SuspendingBuildSystem, WorkspaceChangesBuildSystem {

    override val id: String = "DART"

    override val sourceExtensions: Set<String> = setOf("dart", "yaml")

    override fun isPresent(project: Project): Boolean = manifestOf(project) != null

    override fun modules(project: Project): List<BuildModule> {
        val root = manifestOf(project)?.parentFile ?: return emptyList()
        return failClosedModules(root, DartTasks.TEST, DartTasks.ANALYZE, dartModules(root)).modules
    }

    override fun requiresWorkspace(module: BuildModule, changes: BuildChanges): Boolean =
        dartRequiresWorkspace(module.root, changes)

    override fun run(project: Project, root: String, tasks: List<String>) {
        CommandRunner.runBatch(project, root, dartCommands(File(root), tasks), "Affected Dart")
    }

    override suspend fun runAndWaitSuspending(project: Project, root: String, tasks: List<String>): Boolean =
        CommandRunner.runBatchAndWait(project, root, dartCommands(File(root), tasks), "Affected Dart")

    private fun manifestOf(project: Project): File? =
        project.basePath?.let { dartManifest(File(it)) }
}

internal object DartTasks {
    const val TEST = "test"
    const val ANALYZE = "analyze"
}

internal fun dartManifest(root: File): File? {
    if (FOREIGN_ROOTS.any { File(root, it).isRegularFileNoFollow() }) return null
    val manifest = File(root, "pubspec.yaml").takeIf(File::isRegularFileNoFollow) ?: return null
    val text = runCatching { manifest.readText() }.getOrNull() ?: return null
    if (FLUTTER_SDK.containsMatchIn(text)) return null
    return manifest
}

internal fun dartRootModule(root: File): BuildModule {
    val rootPath = root.invariantSeparatorsPath
    return BuildModule(
        id = root.name.ifBlank { "project" },
        root = rootPath,
        contentRoots = listOf(rootPath),
        testTask = DartTasks.TEST,
        compileTask = DartTasks.ANALYZE,
        hasTests = dartHasTests(root),
        executionId = ".",
    )
}

internal fun dartHasTests(root: File): Boolean {
    val tests = File(root, "test")
    return tests.isDirectory && tests.walkTopDown().any(::dartTestFile)
}

internal fun dartModules(root: File): List<BuildModule>? {
    val manifest = dartManifest(root) ?: return emptyList()
    val text = runCatching { manifest.readText() }.getOrNull() ?: return null
    val section = WORKSPACE_SECTION.find(text)?.groupValues[1] ?: return listOf(dartRootModule(root))
    return dartWorkspaceModules(root, section)
}

private fun dartWorkspaceModules(root: File, section: String): List<BuildModule>? {
    if (UNPROVED_WORKSPACE.containsMatchIn(section)) {
        return listOf(dartRootModule(root).copy(hasTests = true))
    }
    val declared = WORKSPACE_PATH.findAll(section).map { it.groupValues[1] }.toList()
    if (declared.isEmpty()) return listOf(dartRootModule(root))
    val modules = LinkedHashMap<String, BuildModule>()
    if (dartHasTests(root)) {
        modules["."] = dartRootModule(root)
    }
    if (declared.any { directory -> !addDartWorkspaceMember(root, directory, modules) }) {
        return null
    }
    return modules.values.toList().takeIf { it.isNotEmpty() }
        ?: listOf(dartRootModule(root).copy(hasTests = true))
}

private fun addDartWorkspaceMember(
    root: File,
    directory: String,
    modules: MutableMap<String, BuildModule>,
): Boolean {
    val relative = dartWorkspacePath(directory) ?: return false
    val content = File(root, relative)
    val member = File(content, "pubspec.yaml")
    if (!member.isRegularFileNoFollow()) return false
    val memberText = runCatching { member.readText() }.getOrNull() ?: return false
    if (FLUTTER_SDK.containsMatchIn(memberText)) return true
    if (relative in modules) return false
    modules[relative] = BuildModule(
        id = relative,
        root = root.invariantSeparatorsPath,
        contentRoots = listOf(content.invariantSeparatorsPath),
        testTask = DartTasks.TEST,
        compileTask = DartTasks.ANALYZE,
        hasTests = dartHasTests(content),
        executionRoot = root.invariantSeparatorsPath,
        executionId = relative,
    )
    return true
}

internal fun dartRequiresWorkspace(root: String, changes: BuildChanges): Boolean {
    val rootPath = File(root).toPath().toAbsolutePath().normalize()
    return changes.files.any { raw ->
        val file = File(raw).toPath().toAbsolutePath().normalize()
        if (!file.startsWith(rootPath)) return@any true
        val name = rootPath.relativize(file).toString().replace('\\', '/').substringAfterLast('/')
        name == "pubspec.yaml" || name == "pubspec.lock"
    }
}

internal fun dartCommands(tasks: List<String>): List<CliCommand> = dartCommands(File("."), tasks)

internal fun dartCommands(root: File, tasks: List<String>): List<CliCommand> {
    if (tasks.isEmpty()) return emptyList()
    val verbs = tasks.map { it.substringAfterLast(':') }.toSet()
    val verb = if (verbs == setOf(DartTasks.ANALYZE)) DartTasks.ANALYZE else DartTasks.TEST
    val packages = tasks.map { it.substringBeforeLast(':') }.distinct()
    val scoped = packages.none { it == "." } && packages.all { PACKAGE_PATH.matches(it) }
    val arguments = if (!scoped) {
        listOf("dart", verb)
    } else {
        val paths = packages.sorted().map { packagePath(it, verb) }
        listOf("dart", verb) + paths
    }
    val commands = mutableListOf<CliCommand>()
    if (pubNeedsCodegen(root)) {
        commands += BUILD_RUNNER_COMMAND
    }
    commands += CliCommand("dart $verb", arguments)
    return commands
}

internal fun pubNeedsCodegen(root: File): Boolean {
    if (File(root, "build.yaml").isRegularFileNoFollow()) return true
    val manifest = File(root, "pubspec.yaml").takeIf(File::isRegularFileNoFollow) ?: return false
    val text = runCatching { manifest.readText() }.getOrNull() ?: return true
    return BUILD_RUNNER_DEPENDENCY.containsMatchIn(text)
}

private fun dartWorkspacePath(directory: String): String? {
    val relative = directory.replace('\\', '/').removePrefix("./").ifEmpty { "." }
    if (relative == "." || ".." in relative || relative.startsWith("/")) return null
    return relative.takeIf(PACKAGE_PATH::matches)
}

private fun packagePath(relative: String, verb: String): String =
    if (verb == DartTasks.TEST) "$relative/test" else relative

private fun dartTestFile(file: File): Boolean =
    file.isFile && file.name.endsWith("_test.dart")

private val FLUTTER_SDK = Regex("""(?m)^[ \t]*sdk:[ \t]*flutter[ \t]*$""")
private val WORKSPACE_SECTION = Regex("""(?m)^workspace:\s*\n((?:[ \t]+.*\n?)*)""")
private val WORKSPACE_PATH = Regex("""(?m)^[ \t]*-[ \t]+(?:\./)?([A-Za-z0-9][A-Za-z0-9_./-]*)[ \t]*$""")
private val UNPROVED_WORKSPACE = Regex("""[*?\[]|\*\*""")
private val PACKAGE_PATH = Regex("""[A-Za-z0-9._\-]+(?:/[A-Za-z0-9._\-]+)*""")
private val BUILD_RUNNER_DEPENDENCY = Regex("""(?m)^[ \t]*build_runner\s*:""")
internal val BUILD_RUNNER_COMMAND = CliCommand(
    "dart run build_runner build",
    listOf("dart", "run", "build_runner", "build", "--delete-conflicting-outputs"),
)
private val FOREIGN_ROOTS = listOf("settings.gradle.kts", "settings.gradle", "pom.xml")
