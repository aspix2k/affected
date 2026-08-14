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
        CommandRunner.runBatch(project, root, antCommands(tasks), "Affected Ant")
    }

    override suspend fun runAndWaitSuspending(project: Project, root: String, tasks: List<String>): Boolean =
        CommandRunner.runBatchAndWait(project, root, antCommands(tasks), "Affected Ant")

    private fun manifestOf(project: Project): File? =
        project.basePath?.let { antManifest(File(it)) }
}

internal object AntTasks {
    const val TEST = "test"
    const val JUNIT = "junit"
    const val COMPILE = "compile"
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

internal fun antCommands(tasks: List<String>): List<CliCommand> {
    if (tasks.isEmpty()) return emptyList()
    val verbs = tasks.map { it.substringAfterLast(':') }.toSet()
    val verb = when {
        verbs == setOf(AntTasks.COMPILE) -> AntTasks.COMPILE
        AntTasks.JUNIT in verbs && AntTasks.TEST !in verbs -> AntTasks.JUNIT
        else -> AntTasks.TEST
    }
    return listOf(CliCommand("ant $verb", listOf("ant", verb)))
}

internal data class AntDiscovery(val targets: Set<String>, val complete: Boolean)

internal fun antTargets(root: File): Set<String> = antDiscovery(root).targets

internal fun antDiscovery(root: File): AntDiscovery {
    val start = antManifest(root) ?: return AntDiscovery(emptySet(), complete = false)
    val pending = ArrayDeque(listOf(start))
    val seen = HashSet<String>()
    val targets = LinkedHashSet<String>()
    var complete = true
    while (pending.isNotEmpty()) {
        val parsed = parseAntFile(pending.removeFirst(), seen)
        targets += parsed.targets
        complete = complete && parsed.complete
        pending.addAll(parsed.imports)
    }
    return AntDiscovery(targets, complete)
}

private data class AntFileParse(
    val targets: Set<String> = emptySet(),
    val imports: List<File> = emptyList(),
    val complete: Boolean = true,
)

private fun parseAntFile(file: File, seen: MutableSet<String>): AntFileParse {
    if (!file.isRegularFileNoFollow()) return AntFileParse(complete = false)
    val key = file.canonicalFile.invariantSeparatorsPath
    if (!seen.add(key)) return AntFileParse()
    val text = runCatching { file.readText() }.getOrNull() ?: return AntFileParse(complete = false)
    val targets = ANT_TARGET.findAll(text).mapTo(LinkedHashSet()) { it.groupValues[1] }
    val imports = mutableListOf<File>()
    val complete = ANT_IMPORT.findAll(text)
        .map { it.groupValues[1] }
        .fold(true) { ok, attributes -> enqueueAntImport(file, attributes, imports) && ok }
    return AntFileParse(targets, imports, complete)
}

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
private val ANT_IMPORT = Regex("""<(?:import|include)\b([^>]*)/?>""", RegexOption.IGNORE_CASE)
private val ANT_IMPORT_FILE = Regex("""\bfile\s*=\s*"([^"]+)"""")
private val ANT_IMPORT_PATH = Regex("""[A-Za-z0-9._][A-Za-z0-9._/\-]*""")
private val ANT_OPTIONAL = Regex("""\boptional\s*=\s*"true"""")
private val ANT_INCLUDE_AS = Regex("""\bas\s*=\s*["']""")
private val UNPROVED_ANT_IMPORT = Regex("""[$*?]""")
private val FOREIGN_ROOTS = listOf("settings.gradle.kts", "settings.gradle", "pom.xml")
