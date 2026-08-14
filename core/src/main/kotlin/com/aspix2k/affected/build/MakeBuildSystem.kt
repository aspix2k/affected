package com.aspix2k.affected.build

import com.intellij.openapi.project.Project
import java.io.File

class MakeBuildSystem : SuspendingBuildSystem, NamedSourceBuildSystem {

    override val id: String = "MAKE"

    override val sourceExtensions: Set<String> = setOf("c", "cc", "cpp", "cxx", "h", "hpp", "mk")

    override val sourceFileNames: Set<String> = MAKEFILES.toSet()

    override fun isPresent(project: Project): Boolean = manifestOf(project) != null

    override fun modules(project: Project): List<BuildModule> {
        val root = manifestOf(project)?.parentFile ?: return emptyList()
        return listOf(makeRootModule(root))
    }

    override fun run(project: Project, root: String, tasks: List<String>) {
        CommandRunner.runBatch(project, root, makeCommands(File(root), tasks), "Affected Make")
    }

    override suspend fun runAndWaitSuspending(project: Project, root: String, tasks: List<String>): Boolean =
        CommandRunner.runBatchAndWait(project, root, makeCommands(File(root), tasks), "Affected Make")

    private fun manifestOf(project: Project): File? =
        project.basePath?.let(::File)?.let(::makeProjectRoot)?.let(::makeManifest)
}

internal object MakeTasks {
    const val TEST = "test"
    const val CHECK = "check"
    const val ALL = "all"
}

internal fun makeProjectRoot(base: File): File? =
    nestedBuildRoot(base) { makeManifest(it) != null }

internal fun makeManifest(root: File): File? {
    if (FOREIGN_ROOTS.any { File(root, it).isRegularFileNoFollow() }) return null
    return MAKEFILES.firstNotNullOfOrNull { File(root, it).takeIf(File::isRegularFileNoFollow) }
}

internal fun makeRootModule(root: File): BuildModule {
    val rootPath = root.invariantSeparatorsPath
    val discovery = makeDiscovery(root)
    val testTask = when {
        MakeTasks.TEST in discovery.targets -> MakeTasks.TEST
        MakeTasks.CHECK in discovery.targets -> MakeTasks.CHECK
        else -> MakeTasks.TEST
    }
    return BuildModule(
        id = root.name.ifBlank { "project" },
        root = rootPath,
        contentRoots = listOf(rootPath),
        testTask = testTask,
        compileTask = MakeTasks.ALL,
        hasTests = testTask in discovery.targets || !discovery.complete,
        executionId = ".",
    )
}

internal fun makeCommands(root: File, tasks: List<String>): List<CliCommand> {
    if (tasks.isEmpty()) return emptyList()
    val verbs = tasks.map { it.substringAfterLast(':') }.toSet()
    val module = makeRootModule(root)
    val arguments = if (verbs == setOf(module.compileTask)) {
        listOf("make")
    } else {
        listOf("make", module.testTask)
    }
    return listOf(CliCommand(arguments.joinToString(" "), arguments))
}

internal data class MakeDiscovery(val targets: Set<String>, val complete: Boolean)

internal fun makeDiscovery(root: File): MakeDiscovery {
    val start = makeManifest(root) ?: return MakeDiscovery(emptySet(), complete = false)
    val pending = ArrayDeque(listOf(start))
    val seen = HashSet<String>()
    val targets = LinkedHashSet<String>()
    var complete = true
    while (pending.isNotEmpty()) {
        val parsed = parseMakeFile(pending.removeFirst(), seen)
        targets += parsed.targets
        complete = complete && parsed.complete
        pending.addAll(parsed.includes)
    }
    return MakeDiscovery(targets, complete)
}

private data class MakeFileParse(
    val targets: Set<String> = emptySet(),
    val includes: List<File> = emptyList(),
    val complete: Boolean = true,
)

private fun parseMakeFile(file: File, seen: MutableSet<String>): MakeFileParse {
    if (!file.isRegularFileNoFollow()) return MakeFileParse(complete = false)
    val key = file.canonicalFile.invariantSeparatorsPath
    if (!seen.add(key)) return MakeFileParse()
    val text = runCatching { file.readText() }.getOrNull() ?: return MakeFileParse(complete = false)
    val targets = MAKE_TARGET.findAll(text).mapNotNull { match ->
        match.groupValues[1].takeUnless { it.startsWith(".") }
    }.toSet()
    val includes = mutableListOf<File>()
    val complete = MAKE_INCLUDE.findAll(text)
        .map { it.groupValues[1].trim() }
        .fold(true) { ok, line -> enqueueMakeIncludes(file, line, includes) && ok }
    return MakeFileParse(targets, includes, complete)
}

private fun enqueueMakeIncludes(file: File, includeLine: String, pending: MutableList<File>): Boolean {
    if (UNPROVED_MAKE_INCLUDE.containsMatchIn(includeLine)) return false
    var complete = true
    for (include in includeLine.split(Regex("""\s+"""))) {
        if (MAKE_INCLUDE_PATH.matches(include)) {
            pending.add(File(file.parentFile, include.replace('/', File.separatorChar)))
        } else {
            complete = false
        }
    }
    return complete
}

internal fun makeTargets(root: File): Set<String> = makeDiscovery(root).targets

private val MAKEFILES = listOf("GNUmakefile", "makefile", "Makefile")
private val MAKE_TARGET = Regex("""(?m)^([A-Za-z0-9][A-Za-z0-9._-]*)\s*:""")
private val MAKE_INCLUDE = Regex("""(?m)^[ \t]*(?:-?include|sinclude)[ \t]+(.+?)\s*$""")
private val MAKE_INCLUDE_PATH = Regex("""[A-Za-z0-9._][A-Za-z0-9._/\-]*""")
private val UNPROVED_MAKE_INCLUDE = Regex("""[$?*]""")
private val FOREIGN_ROOTS = listOf(
    "settings.gradle.kts",
    "settings.gradle",
    "pom.xml",
    "CMakeLists.txt",
    "meson.build",
)
