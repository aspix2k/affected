package com.aspix2k.affected.build

import com.intellij.openapi.project.Project
import java.io.File
import java.util.concurrent.atomic.AtomicReference

class CMakeBuildSystem : SuspendingBuildSystem {

    private data class Snapshot(val root: String, val stamp: Long, val modules: List<BuildModule>)

    private val cache = AtomicReference<Snapshot?>(null)

    override val id: String = "CMAKE"

    override val sourceExtensions: Set<String> = setOf("c", "cc", "cpp", "cxx", "h", "hpp", "hxx", "txt", "cmake")

    override fun isPresent(project: Project): Boolean = rootOf(project) != null

    override fun modules(project: Project): List<BuildModule> {
        val root = rootOf(project) ?: return emptyList()
        val stamp = File(root, "CMakeLists.txt").lastModified()

        val rootPath = root.invariantSeparatorsPath
        cache.get()?.takeIf { it.root == rootPath && it.stamp == stamp }?.let { return it.modules }

        val modules = CMakeTargets.parse(root)
        cache.set(Snapshot(rootPath, stamp, modules))
        return modules
    }

    override fun run(project: Project, root: String, tasks: List<String>) {
        commands(tasks).forEach { (title, command) -> CommandRunner.run(project, root, command, title) }
    }

    override suspend fun runAndWaitSuspending(project: Project, root: String, tasks: List<String>): Boolean =
        commands(tasks).all { (title, command) -> CommandRunner.runAndWait(project, root, command, title) }

    private fun commands(tasks: List<String>): List<Pair<String, List<String>>> =
        tasks.map { task ->
            val name = task.substringBeforeLast(':')

            if (task.substringAfterLast(':') == CMakeTargets.TEST) {
                "ctest $name" to listOf("ctest", "--test-dir", BUILD_DIRECTORY, "-R", name, "--output-on-failure")
            } else {
                "cmake --build $name" to listOf("cmake", "--build", BUILD_DIRECTORY, "--target", name)
            }
        }

    private fun rootOf(project: Project): File? =
        project.basePath?.let(::File)?.takeIf { File(it, "CMakeLists.txt").isFile }

    private companion object {
        const val BUILD_DIRECTORY = "cmake-build-debug"
    }
}
