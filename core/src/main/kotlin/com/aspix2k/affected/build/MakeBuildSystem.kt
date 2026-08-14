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
        project.basePath?.let { makeManifest(File(it)) }
}

internal object MakeTasks {
    const val TEST = "test"
    const val CHECK = "check"
    const val ALL = "all"
}

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
        val file = pending.removeFirst()
        if (!file.isRegularFileNoFollow()) {
            complete = false
            continue
        }
        val key = file.canonicalFile.invariantSeparatorsPath
        if (!seen.add(key)) continue
        val text = runCatching { file.readText() }.getOrNull()
        if (text == null) {
            complete = false
            continue
        }
        MAKE_TARGET.findAll(text).mapNotNullTo(targets) { match ->
            match.groupValues[1].takeUnless { it.startsWith(".") }
        }
        for (includeLine in MAKE_INCLUDE.findAll(text).map { it.groupValues[1].trim() }) {
            if (UNPROVED_MAKE_INCLUDE.containsMatchIn(includeLine)) {
                complete = false
                continue
            }
            for (include in includeLine.split(Regex("""\s+"""))) {
                if (!MAKE_INCLUDE_PATH.matches(include)) {
                    complete = false
                    continue
                }
                pending.add(File(file.parentFile, include.replace('/', File.separatorChar)))
            }
        }
    }
    return MakeDiscovery(targets, complete)
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
