package com.aspix2k.affected.build

import com.intellij.openapi.project.Project
import java.io.File

class AntBuildSystem : SuspendingBuildSystem {

    override val id: String = "ANT"

    override val sourceExtensions: Set<String> = setOf("java", "kt", "xml", "properties")

    override fun isPresent(project: Project): Boolean = manifestOf(project) != null

    override fun modules(project: Project): List<BuildModule> {
        val root = manifestOf(project)?.parentFile ?: return emptyList()
        return listOf(antRootModule(root))
    }

    override fun run(project: Project, root: String, tasks: List<String>) {
        CommandRunner.runBatch(project, root, antCommands(File(root), tasks), "Affected Ant")
    }

    override suspend fun runAndWaitSuspending(project: Project, root: String, tasks: List<String>): Boolean =
        CommandRunner.runBatchAndWait(project, root, antCommands(File(root), tasks), "Affected Ant")

    private fun manifestOf(project: Project): File? =
        project.basePath?.let { antManifest(File(it)) }
}

internal object AntTasks {
    const val TEST = "test"
    const val JUNIT = "junit"
    const val TESTNG = "testng"
    const val COMPILE = "compile"
    const val GENERATE = "generate"
    const val CODEGEN = "codegen"
}

internal fun antManifest(root: File): File? {
    if (FOREIGN_ROOTS.any { File(root, it).isRegularFileNoFollow() }) return null
    return File(root, "build.xml").takeIf(File::isRegularFileNoFollow)
}

internal fun antRootModule(root: File): BuildModule {
    val rootPath = root.invariantSeparatorsPath
    val discovery = antDiscovery(root)
    val testTask = when {
        AntTasks.TEST in discovery.targets -> AntTasks.TEST
        AntTasks.JUNIT in discovery.targets -> AntTasks.JUNIT
        AntTasks.TESTNG in discovery.targets -> AntTasks.TESTNG
        discovery.taskTargets.isNotEmpty() -> discovery.taskTargets.first()
        else -> AntTasks.TEST
    }
    return BuildModule(
        id = root.name.ifBlank { "project" },
        root = rootPath,
        contentRoots = listOf(rootPath),
        testTask = testTask,
        compileTask = AntTasks.COMPILE,
        hasTests = testTask in discovery.targets || !discovery.complete,
        executionId = ".",
    )
}

internal fun antCommands(tasks: List<String>): List<CliCommand> = antCommands(File("."), tasks)

internal fun antCommands(root: File, tasks: List<String>): List<CliCommand> {
    if (tasks.isEmpty()) return emptyList()
    val verbs = tasks.map { it.substringAfterLast(':') }.toSet()
    val testTask = antRootModule(root).testTask
    val verb = when {
        verbs == setOf(AntTasks.COMPILE) -> AntTasks.COMPILE
        AntTasks.TEST in verbs -> AntTasks.TEST
        AntTasks.JUNIT in verbs -> AntTasks.JUNIT
        testTask in verbs -> testTask
        else -> AntTasks.TEST
    }
    val commands = mutableListOf<CliCommand>()
    val generate = antGenerateTask(antDiscovery(root))
    if (verb != AntTasks.COMPILE && generate != null && antNeedsGenerate(root, verb, generate)) {
        commands += CliCommand("ant $generate", listOf("ant", generate))
    }
    commands += CliCommand("ant $verb", listOf("ant", verb))
    return commands
}

internal data class AntDiscovery(
    val targets: Set<String>,
    val taskTargets: Set<String> = emptySet(),
    val depends: Map<String, Set<String>> = emptyMap(),
    val complete: Boolean,
)

internal fun antTargets(root: File): Set<String> = antDiscovery(root).targets

internal fun antDiscovery(root: File): AntDiscovery {
    val start = antManifest(root) ?: return AntDiscovery(emptySet(), complete = false)
    val pending = ArrayDeque(listOf(start))
    val seen = HashSet<String>()
    val targets = LinkedHashSet<String>()
    val taskTargets = LinkedHashSet<String>()
    val depends = LinkedHashMap<String, MutableSet<String>>()
    var complete = true
    while (pending.isNotEmpty()) {
        val parsed = parseAntFile(pending.removeFirst(), seen)
        targets += parsed.targets
        taskTargets += parsed.taskTargets
        parsed.depends.forEach { (name, edges) ->
            depends.getOrPut(name) { LinkedHashSet() }.addAll(edges)
        }
        complete = complete && parsed.complete
        pending.addAll(parsed.imports)
    }
    return AntDiscovery(targets, taskTargets, depends, complete)
}

internal fun antGenerateTask(discovery: AntDiscovery): String? =
    GENERATE_TARGETS.firstOrNull { it in discovery.targets }

internal fun antNeedsGenerate(root: File, verb: String, generate: String): Boolean {
    val discovery = antDiscovery(root)
    if (verb == generate) return false
    return !antDependsOn(discovery, verb, generate) || !discovery.complete
}

private data class AntFileParse(
    val targets: Set<String> = emptySet(),
    val taskTargets: Set<String> = emptySet(),
    val depends: Map<String, Set<String>> = emptyMap(),
    val imports: List<File> = emptyList(),
    val complete: Boolean = true,
)

private fun parseAntFile(file: File, seen: MutableSet<String>): AntFileParse {
    if (!file.isRegularFileNoFollow()) return AntFileParse(complete = false)
    val key = file.canonicalFile.invariantSeparatorsPath
    if (!seen.add(key)) return AntFileParse()
    val text = runCatching { file.readText() }.getOrNull() ?: return AntFileParse(complete = false)
    val targets = ANT_TARGET.findAll(text).mapTo(LinkedHashSet()) { it.groupValues[1] }
    val depends = LinkedHashMap<String, Set<String>>()
    var complete = true
    for (attrs in ANT_TARGET_OPEN.findAll(text).map { it.groupValues[1] }) {
        val parsed = parseAntDepends(attrs) ?: run {
            complete = false
            continue
        }
        if (parsed.name != null) depends[parsed.name] = parsed.depends
    }
    val imports = mutableListOf<File>()
    complete = ANT_IMPORT.findAll(text)
        .map { it.groupValues[1] }
        .fold(complete) { ok, attributes -> enqueueAntImport(file, attributes, imports) && ok }
    return AntFileParse(targets, antTaskTargets(text), depends, imports, complete)
}

private data class AntDependsParse(val name: String?, val depends: Set<String>)

private fun parseAntDepends(attributes: String): AntDependsParse? {
    val name = ANT_TARGET_NAME.find(attributes)?.groupValues?.get(1)
    val raw = ANT_DEPENDS.find(attributes)?.groupValues?.get(1)?.trim().orEmpty()
    if (raw.isEmpty()) return AntDependsParse(name, emptySet())
    if (UNPROVED_ANT_IMPORT.containsMatchIn(raw)) return null
    val edges = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    if (edges.any { !ANT_TARGET_ID.matches(it) }) return null
    return AntDependsParse(name, edges.toSet())
}

private fun antDependsOn(discovery: AntDiscovery, start: String, goal: String): Boolean {
    val pending = ArrayDeque(listOf(start))
    val seen = HashSet<String>()
    while (pending.isNotEmpty()) {
        val current = pending.removeFirst()
        if (!seen.add(current)) continue
        if (current == goal) return true
        pending.addAll(discovery.depends[current].orEmpty())
    }
    return false
}

private fun antTaskTargets(text: String): Set<String> =
    ANT_TARGET_BLOCK.findAll(text).mapNotNull { match ->
        val name = ANT_TARGET_NAME.find(match.groupValues[1])?.groupValues?.get(1) ?: return@mapNotNull null
        name.takeIf { ANT_TEST_TASK.containsMatchIn(match.groupValues[2]) }
    }.toCollection(LinkedHashSet())

private fun enqueueAntImport(file: File, attributes: String, pending: MutableList<File>): Boolean {
    if (UNPROVED_ANT_IMPORT.containsMatchIn(attributes)) return false
    if (ANT_INCLUDE_AS.containsMatchIn(attributes)) return false
    val path = ANT_IMPORT_FILE.find(attributes)?.groupValues?.get(1) ?: return false
    if (!ANT_IMPORT_PATH.matches(path)) return false
    val imported = File(file.parentFile, path.replace('/', File.separatorChar))
    if (imported.isRegularFileNoFollow()) {
        pending.add(imported)
        return true
    }
    return ANT_OPTIONAL.containsMatchIn(attributes)
}

private val ANT_TARGET = Regex("""<target\b[^>]*\bname\s*=\s*"([^"]+)"""")
private val ANT_TARGET_BLOCK = Regex(
    """<target\b([^>]*)>(.*?)</target>""",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)
private val ANT_TARGET_OPEN = Regex("""<target\b([^>]*)/?>""", RegexOption.IGNORE_CASE)
private val ANT_TARGET_NAME = Regex("""\bname\s*=\s*"([^"]+)"""")
private val ANT_TEST_TASK = Regex("""<(?:junit|testng)\b""", RegexOption.IGNORE_CASE)
private val ANT_DEPENDS = Regex("""\bdepends\s*=\s*"([^"]+)"""")
private val ANT_TARGET_ID = Regex("""[A-Za-z0-9._][A-Za-z0-9._-]*""")
private val GENERATE_TARGETS = listOf(AntTasks.GENERATE, AntTasks.CODEGEN)
private val ANT_IMPORT = Regex("""<(?:import|include)\b([^>]*)/?>""", RegexOption.IGNORE_CASE)
private val ANT_IMPORT_FILE = Regex("""\bfile\s*=\s*"([^"]+)"""")
private val ANT_IMPORT_PATH = Regex("""[A-Za-z0-9._][A-Za-z0-9._/\-]*""")
private val ANT_OPTIONAL = Regex("""\boptional\s*=\s*"true"""")
private val ANT_INCLUDE_AS = Regex("""\bas\s*=\s*["']""")
private val UNPROVED_ANT_IMPORT = Regex("""[$*?]""")
private val FOREIGN_ROOTS = listOf("settings.gradle.kts", "settings.gradle", "pom.xml")
