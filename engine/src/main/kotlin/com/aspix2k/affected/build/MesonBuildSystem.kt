package com.aspix2k.affected.build

import com.aspix2k.affected.build.process.CliCommand
import com.google.gson.JsonParser
import java.io.File
import java.nio.file.Files

internal class MesonBuildSystem : EngineBuildSystem, NamedSourceBuildSystem, AllFileChangesBuildSystem {

    override val id: String = "MESON"

    override val sourceExtensions: Set<String> = setOf("c", "cc", "cpp", "cxx", "h", "hpp")

    override val sourceFileNames: Set<String> = setOf("meson.build", "meson.options", "meson_options.txt")

    override fun isPresent(workspace: Workspace): Boolean = rootsOf(workspace).isNotEmpty()

    override fun modules(workspace: Workspace): List<BuildModule> = rootsOf(workspace).map(::mesonRootModule)

    override fun run(workspace: Workspace, root: String, tasks: List<String>) {
        workspace.runBatch(root, mesonCommands(File(root), tasks), "Affected Meson")
    }

    override suspend fun runAndWait(workspace: Workspace, root: String, tasks: List<String>): Boolean =
        workspace.runBatchAndWait(root, mesonCommands(File(root), tasks), "Affected Meson")

    private fun rootsOf(workspace: Workspace): List<File> =
        workspace.root?.let(::mesonProjectRoots).orEmpty()
}

internal object MesonTasks {
    const val TEST = "test"
    const val COMPILE = "compile"
}

internal fun mesonProjectRoots(base: File): List<File> =
    nestedBuildRoots(base, setOf("meson.build")) { mesonManifest(it) != null }

internal fun mesonManifest(root: File): File? {
    if (FOREIGN_ROOTS.any { File(root, it).isRegularFileNoFollow() }) return null
    return File(root, "meson.build").takeIf(File::isRegularFileNoFollow)
}

internal data class MesonDiscovery(
    val subprojects: List<File>,
    val hasTests: Boolean,
    val complete: Boolean,
)

internal fun mesonRootModule(root: File): BuildModule {
    val discovery = mesonDiscovery(root)
    val rootPath = root.invariantSeparatorsPath
    return BuildModule(
        id = root.name.ifBlank { "project" },
        root = rootPath,
        contentRoots = (listOf(root) + discovery.subprojects).map { it.invariantSeparatorsPath }.distinct(),
        testTask = MesonTasks.TEST,
        compileTask = MesonTasks.COMPILE,
        hasTests = discovery.hasTests || !discovery.complete,
        executionId = ".",
    )
}

internal fun mesonHasTests(root: File): Boolean = mesonRootModule(root).hasTests

internal fun mesonDiscovery(root: File): MesonDiscovery {
    val staticSubprojects = staticMesonSubprojects(root)
    val staticTests = mesonManifestHasTests(File(root, "meson.build")) ||
        staticSubprojects.any { mesonManifestHasTests(File(it, "meson.build")) }
    val info = mesonInfoDirectory(root) ?: return MesonDiscovery(staticSubprojects, staticTests, complete = true)
    val introspected = mesonIntrospection(root, info)
        ?: return MesonDiscovery(staticSubprojects, hasTests = true, complete = false)
    return MesonDiscovery(
        subprojects = introspected.subprojects.ifEmpty { staticSubprojects },
        hasTests = introspected.hasTests || staticTests,
        complete = true,
    )
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

private fun mesonInfoDirectory(root: File): File? =
    listOf("build", "builddir")
        .map { File(File(root, it), "meson-info") }
        .firstOrNull { it.isDirectory }

private fun mesonManifestHasTests(manifest: File): Boolean {
    if (!manifest.isRegularFileNoFollow()) return false
    val text = runCatching { manifest.readText() }.getOrNull() ?: return false
    return MESON_TEST.containsMatchIn(text)
}

private fun staticMesonSubprojects(root: File): List<File> {
    val directory = File(root, DEFAULT_SUBPROJECT_DIR)
    if (!directory.isDirectory || Files.isSymbolicLink(directory.toPath())) return emptyList()
    return directory.listFiles().orEmpty()
        .filter { child ->
            child.isDirectory &&
                !Files.isSymbolicLink(child.toPath()) &&
                child.name !in SKIPPED_SUBPROJECT_DIRS &&
                !child.name.startsWith(".") &&
                File(child, "meson.build").isRegularFileNoFollow()
        }
        .sortedBy { it.name }
}

private fun mesonIntrospection(root: File, info: File): MesonDiscovery? {
    val projectInfo = File(info, "intro-projectinfo.json")
    val tests = File(info, "intro-tests.json")
    if (!projectInfo.isRegularFileNoFollow() || !tests.isRegularFileNoFollow()) return null
    val projectJson = runCatching { JsonParser.parseString(projectInfo.readText()).asJsonObject }
        .getOrNull() ?: return null
    val testsJson = runCatching { JsonParser.parseString(tests.readText()).asJsonArray }
        .getOrNull() ?: return null
    val directoryName = projectJson.get("subproject_dir")
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
        ?.asString
        ?: DEFAULT_SUBPROJECT_DIR
    if (!SUBPROJECT_DIR.matches(directoryName)) return null
    val names = projectJson.getAsJsonArray("subprojects") ?: return null
    val subprojects = names.map { element ->
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isString) return null
        val name = element.asString
        if (!SUBPROJECT_NAME.matches(name)) return null
        File(File(root, directoryName), name).takeIf { File(it, "meson.build").isRegularFileNoFollow() }
    }.filterNotNull()
    return MesonDiscovery(subprojects, hasTests = testsJson.size() > 0, complete = true)
}

private val MESON_TEST = Regex("""\btest\s*\(""")
private val SUBPROJECT_DIR = Regex("""[A-Za-z0-9._][A-Za-z0-9._/\-]*""")
private val SUBPROJECT_NAME = Regex("""[A-Za-z0-9._][A-Za-z0-9._\-]*""")
private const val DEFAULT_SUBPROJECT_DIR = "subprojects"
private val SKIPPED_SUBPROJECT_DIRS = setOf("packagecache", "packagefiles")
private val FOREIGN_ROOTS = listOf("settings.gradle.kts", "settings.gradle", "pom.xml", "CMakeLists.txt")
