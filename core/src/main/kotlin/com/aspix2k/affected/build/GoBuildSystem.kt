package com.aspix2k.affected.build

import com.intellij.openapi.project.Project
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

class GoBuildSystem : ChangeAwareSuspendingBuildSystem {

    private data class Snapshot(val root: String, val stamp: String, val modules: List<BuildModule>)

    private val cache = AtomicReference<Snapshot?>(null)

    override val id: String = "GO"

    override val sourceExtensions: Set<String> = setOf("go", "mod", "sum", "work")

    override fun isPresent(project: Project): Boolean = manifestOf(project) != null

    override fun modules(project: Project): List<BuildModule> {
        val manifest = manifestOf(project) ?: return emptyList()
        val root = manifest.parentFile.invariantSeparatorsPath
        val sources = ManifestSearch.findByExtension(manifest.parentFile, "go")
        val inputs =
            listOf("go.mod", "go.sum", "go.work", "go.work.sum")
                .flatMap { ManifestSearch.find(manifest.parentFile, it) } +
                sources
        val stamp = sources.takeIf { it.isNotEmpty() }?.let {
            ManifestSearch.fingerprint(manifest.parentFile, inputs)
        }

        if (stamp != null) cache.get()?.takeIf { it.root == root && it.stamp == stamp }?.let { return it.modules }

        val output = CommandRunner.capture(root, LIST, timeoutSeconds = 120)
        val discovery = failClosedModules(
            manifest.parentFile,
            GoPackages.TEST,
            GoPackages.COMPILE,
            output?.let { GoPackages.parse(it, root) },
        )
        val fingerprintedPackages = sources.mapTo(HashSet()) {
            it.parentFile.absoluteFile.normalize().invariantSeparatorsPath
        }
        val completeFingerprint = discovery.modules.all { module ->
            module.contentRoots.singleOrNull() in fingerprintedPackages
        }
        if (stamp != null && discovery.complete && completeFingerprint) {
            cache.retainBuildSnapshot(Snapshot(root, stamp, discovery.modules), discovery.modules.size)
        }
        return discovery.modules
    }

    override fun run(project: Project, root: String, tasks: List<String>) {
        CommandRunner.runBatch(project, root, goCommands(tasks), "Affected Go")
    }

    override suspend fun runAndWaitSuspending(project: Project, root: String, tasks: List<String>): Boolean =
        CommandRunner.runBatchAndWait(project, root, goCommands(tasks), "Affected Go")

    override suspend fun runAndWaitSuspending(
        project: Project,
        root: String,
        tasks: List<String>,
        changes: BuildChanges,
    ): Boolean = CommandRunner.runBatchAndWait(
        project,
        root,
        goCommands(tasks, modules(project), changes),
        "Affected Go",
    )

    private fun manifestOf(project: Project): File? =
        project.basePath?.let(::File)?.let(::goProjectRoot)?.let(::goManifest)

    private companion object {
        val LIST = listOf("go", "list", "-json", "./...")
    }
}

internal fun goProjectRoot(base: File): File? =
    nestedBuildRoot(base) { goManifest(it) != null }

internal fun goManifest(root: File): File? =
    File(root, "go.mod").takeIf(File::isRegularFileNoFollow)

internal fun goCommands(
    tasks: List<String>,
    modules: List<BuildModule> = emptyList(),
    changes: BuildChanges? = null,
): List<CliCommand> {
    val grouped = tasks.groupBy({ it.substringAfterLast(':') }, { it.substringBeforeLast(':') })
    val testPackages = grouped[GoPackages.TEST].orEmpty()
    val exact = changes?.let { selectGoTestRuns(testPackages, modules, it) }
    if (exact != null) {
        return exact.map { (pkg, pattern) ->
            CliCommand("go test $pkg", listOf("go", "test", pkg, "-run", pattern))
        } + grouped.filterKeys { it != GoPackages.TEST }.map { (task, packages) ->
            val verb = if (task == GoPackages.COMPILE) "build" else task
            CliCommand("go $verb", listOf("go", verb) + packages.map { if (it == ".") "./..." else it })
        }
    }
    return grouped.map { (task, packages) ->
        val verb = if (task == GoPackages.COMPILE) "build" else "test"
        CliCommand("go $verb", listOf("go", verb) + packages.map { if (it == ".") "./..." else it })
    }
}

internal fun selectGoTestRuns(
    packages: List<String>,
    modules: List<BuildModule>,
    changes: BuildChanges,
): List<Pair<String, String>>? = runCatching {
    require(changes.comparedToBase)
    require(changes.files.isNotEmpty())
    require(changes.files.toSet() == changes.exactSelectionEligible)
    require(packages.isNotEmpty() && "." !in packages)
    val byId = modules.associateBy(BuildModule::id)
    val selected = ArrayList<Pair<String, String>>()
    for (pkg in packages.distinct()) {
        val directory = byId[pkg]?.contentRoots?.singleOrNull() ?: return@runCatching null
        val names = goTestFunctions(directory, changes) ?: return@runCatching null
        selected += pkg to goTestRunPattern(names)
    }
    selected.takeIf { it.isNotEmpty() }
}.getOrNull()

internal fun goTestFunctions(directory: String, changes: BuildChanges): List<String>? = runCatching {
    val root = Path.of(directory).toAbsolutePath().normalize()
    require(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(root))
    val names = LinkedHashSet<String>()
    for (raw in changes.files) {
        val requested = Path.of(raw).toAbsolutePath().normalize()
        require(Files.isRegularFile(requested, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(requested))
        val real = requested.toRealPath(LinkOption.NOFOLLOW_LINKS)
        require(real.startsWith(root) && real.fileName.toString().endsWith("_test.go"))
        val text = Files.readString(real)
        require(text.toByteArray().size <= MAX_GO_TEST_BYTES)
        val found = GO_TEST_FUNC.findAll(text).map { it.groupValues[1] }.toList()
        require(found.isNotEmpty())
        names += found
    }
    names.sorted().takeIf { it.isNotEmpty() }
}.getOrNull()

internal fun goTestRunPattern(names: List<String>): String {
    require(names.isNotEmpty() && names.all { GO_TEST_NAME.matches(it) })
    return names.joinToString(separator = "|", prefix = "^(", postfix = ")$")
}

private val GO_TEST_FUNC = Regex("""(?m)^func (Test[A-Za-z0-9_]+)\(""")
private val GO_TEST_NAME = Regex("""Test[A-Za-z0-9_]+""")
private const val MAX_GO_TEST_BYTES = 1024 * 1024
