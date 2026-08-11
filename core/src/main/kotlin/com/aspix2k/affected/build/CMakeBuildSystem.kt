package com.aspix2k.affected.build

import com.intellij.openapi.project.Project
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.concurrent.atomic.AtomicReference

class CMakeBuildSystem : SuspendingBuildSystem {

    private data class Snapshot(val root: String, val stamp: String, val modules: List<BuildModule>)

    private val cache = AtomicReference<Snapshot?>(null)

    override val id: String = "CMAKE"

    override val sourceExtensions: Set<String> =
        setOf("c", "cc", "cpp", "cxx", "h", "hpp", "hxx", "txt", "cmake", "json")

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
        if (stamp != null && discovery.complete) cache.set(Snapshot(rootPath, stamp, discovery.modules))
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

    private fun rootOf(project: Project): File? =
        project.basePath?.let(::File)?.takeIf { File(it, "CMakeLists.txt").isRegularFileNoFollow() }
}

internal fun cmakeCommands(root: String, tasks: List<String>): List<CliCommand> {
    val buildDirectory = cmakeBuildDirectory(File(root)) ?: return emptyList()
    if (tasks.any { it.substringAfterLast(':') == CMakeTargets.TEST }) {
        return listOf(
            CliCommand("cmake --build", listOf("cmake", "--build", buildDirectory)),
            CliCommand(
                "ctest",
                listOf("ctest", "--test-dir", buildDirectory, "--output-on-failure"),
            ),
        )
    }
    if (tasks.any { it.substringAfterLast(':') != CMakeTargets.BUILD }) return emptyList()
    val selection = tasks.map { it.substringBeforeLast(':') }.filterNot { it == "." }.distinct()
    val arguments = if (selection.isEmpty()) emptyList() else listOf("--target") + selection
    return listOf(CliCommand("cmake --build", listOf("cmake", "--build", buildDirectory) + arguments))
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

private fun containsCMakeCache(files: Array<File>): Boolean = files.any {
    it.name == "CMakeCache.txt" && Files.isRegularFile(it.toPath(), LinkOption.NOFOLLOW_LINKS)
}

private fun cmakeDirectories(files: Array<File>): List<File> = files.filter { child ->
    child.isDirectory && !Files.isSymbolicLink(child.toPath()) && !child.name.startsWith('.')
}

private const val MAX_CMAKE_DIRECTORIES = 512
private const val CMAKE_RESOLUTION_ERROR =
    "Affected could not find exactly one configured CMake build tree. Configure one profile and run again."
