package com.aspix2k.affected.build

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.project.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

class CMakeBuildSystem : ChangeAwareSuspendingBuildSystem, AllFileChangesBuildSystem {

    private data class Snapshot(val root: String, val stamp: String, val modules: List<BuildModule>)

    private val cache = AtomicReference<Snapshot?>(null)

    override val id: String = "CMAKE"

    override val sourceExtensions: Set<String> =
        CMAKE_SOURCE_EXTENSIONS + setOf("txt", "cmake", "json")

    override fun isPresent(project: Project): Boolean = rootOf(project) != null

    override fun modules(project: Project): List<BuildModule> {
        val root = rootOf(project) ?: return emptyList()
        val stamp = ManifestSearch.fingerprint(
            root,
            ManifestSearch.find(root, "CMakeLists.txt") + ManifestSearch.findByExtension(root, "cmake"),
        )

        val rootPath = root.invariantSeparatorsPath
        if (stamp != null) cache.get()?.takeIf { it.root == rootPath && it.stamp == stamp }?.let { return it.modules }

        val discovered = runCatching { CMakeTargets.parse(root) }.getOrNull()
        val discovery = failClosedModules(root, CMakeTargets.TEST, CMakeTargets.BUILD, discovered)
        if (stamp != null && discovery.complete) {
            cache.retainBuildSnapshot(Snapshot(rootPath, stamp, discovery.modules), discovery.modules.size)
        }
        return discovery.modules
    }

    override fun run(project: Project, root: String, tasks: List<String>) {
        CommandRunner.runBatch(project, root, cmakeCommands(root, tasks), "Affected CMake", CMAKE_RESOLUTION_ERROR)
    }

    override suspend fun runAndWaitSuspending(project: Project, root: String, tasks: List<String>): Boolean =
        CommandRunner.runBatchAndWait(
            project,
            root,
            cmakeCommands(root, tasks),
            "Affected CMake",
            CMAKE_RESOLUTION_ERROR,
        )

    override suspend fun runAndWaitSuspending(
        project: Project,
        root: String,
        tasks: List<String>,
        changes: BuildChanges,
    ): Boolean {
        val selective = withContext(Dispatchers.IO) { CMakeSelectiveRun.create(project, root, tasks, changes) }
        if (selective == null) {
            return CommandRunner.runBatchAndWait(
                project,
                root,
                cmakeCommands(root, tasks),
                "Affected CMake",
                CMAKE_RESOLUTION_ERROR,
            )
        }
        return try {
            val passed = CommandRunner.runBatchAndWait(
                project,
                root,
                selective.commands,
                "Affected CMake",
                CMAKE_RESOLUTION_ERROR,
            )
            selective.complete(passed)
            passed
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { selective.close() }
        }
    }

    private fun rootOf(project: Project): File? =
        project.basePath?.let(::File)?.let { base ->
            nestedBuildRoot(base) { File(it, "CMakeLists.txt").isRegularFileNoFollow() }
        }
}

private class CMakeSelectiveRun private constructor(
    val commands: List<CliCommand>,
    private val selection: CMakeTestSelection,
    private val root: Path,
    private val build: Path,
    private val store: CMakeTestBaselineStore,
    private val before: CMakeTestSnapshot?,
    private val report: Path?,
    private val temporaryFiles: List<Path>,
) {

    suspend fun complete(passed: Boolean) {
        if (selection != CMakeTestSelection.Full || !passed || report == null) return
        currentCoroutineContext().ensureActive()
        val snapshot = runInterruptible(Dispatchers.IO) { readCMakeTestSnapshot(root, build, ::capture) }
        currentCoroutineContext().ensureActive()
        promoteCMakeBaseline(store, before, snapshot, report, full = true, passed = true)
    }

    fun close() {
        temporaryFiles.forEach { file -> runCatching { Files.deleteIfExists(file) } }
    }

    private fun capture(arguments: List<String>): String? =
        CommandRunner.capture(
            root.toString(),
            arguments,
            CTEST_METADATA_TIMEOUT_SECONDS,
            CTEST_METADATA_MAX_BYTES,
        )

    companion object {
        fun create(project: Project, root: String, tasks: List<String>, changes: BuildChanges): CMakeSelectiveRun? =
            runCatching {
                if (tasks.none { it.substringAfterLast(':') == CMakeTargets.TEST }) return null
                val rootPath = Path.of(root).toAbsolutePath().normalize()
                require(Files.isDirectory(rootPath, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(rootPath))
                val realRoot = rootPath.toRealPath(LinkOption.NOFOLLOW_LINKS)
                val buildName = cmakeBuildDirectory(realRoot.toFile()) ?: return null
                val build = realRoot.resolve(buildName).normalize()
                require(build.startsWith(realRoot))
                require(requestCMakeCodemodel(build))
                val cache = secureCMakeDirectory(
                    PathManager.getSystemDir().resolve(CACHE_DIRECTORY).resolve(project.locationHash).resolve("cmake"),
                )
                val store = CMakeTestBaselineStore(cache.resolve("maps"))
                val capture = { arguments: List<String> ->
                    CommandRunner.capture(
                        realRoot.toString(),
                        arguments,
                        CTEST_METADATA_TIMEOUT_SECONDS,
                        CTEST_METADATA_MAX_BYTES,
                    )
                }
                val current = readCMakeTestSnapshot(realRoot, build, capture)
                if (current == null) return null
                val selection = selectCMakeTests(realRoot, current, store.read(), changes)
                val temporary = secureCMakeDirectory(cache.resolve("runs"))
                val files = ArrayList<Path>()
                val commands = when (selection) {
                    CMakeTestSelection.Full -> {
                        val report = Files.createTempFile(temporary, "ctest-", ".xml").also(files::add)
                        cmakeSelectiveCommands(buildName, selection, report = report)
                    }
                    CMakeTestSelection.Empty -> cmakeSelectiveCommands(buildName, selection)
                    is CMakeTestSelection.Exact -> {
                        val selected = Files.createTempFile(temporary, "ctest-", ".txt").also(files::add)
                        Files.writeString(
                            selected,
                            selection.tests.joinToString("\n", postfix = "\n"),
                            StandardCharsets.UTF_8,
                        )
                        cmakeSelectiveCommands(buildName, selection, selected = selected)
                    }
                }
                val report = files.singleOrNull { it.fileName.toString().endsWith(".xml") }
                CMakeSelectiveRun(commands, selection, realRoot, build, store, current, report, files)
            }.getOrNull()
    }
}

internal fun cmakeCommands(root: String, tasks: List<String>): List<CliCommand> {
    val buildDirectory = cmakeBuildDirectory(File(root)) ?: return emptyList()
    if (tasks.any { it.substringAfterLast(':') == CMakeTargets.TEST }) {
        return cmakeTestCommands(buildDirectory)
    }
    if (tasks.any { it.substringAfterLast(':') != CMakeTargets.BUILD }) return emptyList()
    val selection = tasks.map { it.substringBeforeLast(':') }.filterNot { it == "." }.distinct()
    val arguments = if (selection.isEmpty()) emptyList() else listOf("--target") + selection
    return listOf(CliCommand("cmake --build", listOf("cmake", "--build", buildDirectory) + arguments))
}

private fun cmakeBuildCommands(buildDirectory: String): List<CliCommand> =
    listOf(CliCommand("cmake --build", listOf("cmake", "--build", buildDirectory)))

private fun cmakeTestCommands(buildDirectory: String, extra: List<String> = emptyList()): List<CliCommand> =
    cmakeBuildCommands(buildDirectory) + CliCommand(
        "ctest",
        listOf("ctest", "--test-dir", buildDirectory, "--output-on-failure") + extra,
    )

internal fun cmakeSelectiveCommands(
    buildDirectory: String,
    selection: CMakeTestSelection,
    selected: Path? = null,
    report: Path? = null,
): List<CliCommand> {
    return when (selection) {
        CMakeTestSelection.Full -> {
            require(report != null && selected == null)
            cmakeTestCommands(buildDirectory, listOf("--output-junit", report.toString()))
        }
        CMakeTestSelection.Empty -> {
            require(report == null && selected == null)
            cmakeBuildCommands(buildDirectory)
        }
        is CMakeTestSelection.Exact -> {
            require(selected != null && report == null)
            cmakeTestCommands(
                buildDirectory,
                listOf("--tests-from-file", selected.toString(), "--no-tests=error"),
            )
        }
    }
}

private fun cmakeBuildDirectory(root: File): String? {
    val found = ArrayList<File>()
    val queue = ArrayDeque<Pair<File, Int>>()
    queue += root to 0
    var visited = 0
    while (queue.isNotEmpty() && found.size < 2) {
        if (visited++ >= MAX_CMAKE_DIRECTORIES) return null
        val (directory, depth) = queue.removeFirst()
        val children = directory.listFiles() ?: return null
        if (children.size > MAX_CMAKE_DIRECTORIES) return null
        if (containsCMakeCache(children)) {
            found += directory
        }
        if (depth < 3) {
            val directories = cmakeDirectories(children)
            if (queue.size + visited + directories.size > MAX_CMAKE_DIRECTORIES) return null
            directories.forEach { queue += it to depth + 1 }
        }
    }
    val directory = found.singleOrNull() ?: return null
    return directory.invariantSeparatorsPath.removePrefix("${root.invariantSeparatorsPath}/").ifEmpty { "." }
}

private fun secureCMakeDirectory(path: Path): Path {
    val absolute = path.toAbsolutePath().normalize()
    Files.createDirectories(absolute)
    require(!Files.isSymbolicLink(absolute))
    val real = absolute.toRealPath(LinkOption.NOFOLLOW_LINKS)
    require(Files.isDirectory(real, LinkOption.NOFOLLOW_LINKS) && Files.isReadable(real) && Files.isWritable(real))
    return real
}

private fun containsCMakeCache(files: Array<File>): Boolean = files.any {
    it.name == "CMakeCache.txt" && Files.isRegularFile(it.toPath(), LinkOption.NOFOLLOW_LINKS)
}

private fun cmakeDirectories(files: Array<File>): List<File> = files.filter { child ->
    child.isDirectory && !Files.isSymbolicLink(child.toPath()) && !child.name.startsWith('.')
}

private const val MAX_CMAKE_DIRECTORIES = 512
private const val CACHE_DIRECTORY = "affected"
private const val CTEST_METADATA_TIMEOUT_SECONDS = 30L
private const val CTEST_METADATA_MAX_BYTES = 16 * 1024 * 1024
private const val CMAKE_RESOLUTION_ERROR =
    "Affected could not find exactly one configured CMake build tree. Configure one profile and run again."

internal val CMAKE_SOURCE_EXTENSIONS = setOf(
    "c", "cc", "cp", "cpp", "cxx", "c++",
    "h", "hh", "hp", "hpp", "hxx", "h++",
    "inl", "ipp", "tpp", "ixx", "cppm", "ccm", "cxxm",
)
