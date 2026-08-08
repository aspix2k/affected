package com.aspix2k.affected.build

import com.intellij.openapi.project.Project
import java.io.File
import java.util.concurrent.atomic.AtomicReference

class CMakeBuildSystem : BuildSystem {

    private data class Snapshot(val stamp: Long, val modules: List<BuildModule>)

    private val cache = AtomicReference<Snapshot?>(null)

    override val id: String = "CMAKE"

    override val sourceExtensions: Set<String> = setOf("c", "cc", "cpp", "cxx", "h", "hpp", "hxx", "txt", "cmake")

    override fun isPresent(project: Project): Boolean = rootOf(project) != null

    override fun modules(project: Project): List<BuildModule> {
        val root = rootOf(project) ?: return emptyList()
        val stamp = File(root, "CMakeLists.txt").lastModified()

        cache.get()?.takeIf { it.stamp == stamp }?.let { return it.modules }

        val modules = CMakeTargets.parse(root)
        cache.set(Snapshot(stamp, modules))
        return modules
    }

    override fun run(project: Project, root: String, tasks: List<String>) {
        commands(tasks).forEach { (title, command) -> CommandRunner.run(project, root, command, title) }
    }

    override fun runAndWait(project: Project, root: String, tasks: List<String>): Boolean =
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

    private fun rootOf(project: Project): File? {
        val base = project.basePath?.let(::File) ?: return null
        if (!File(base, "CMakeLists.txt").isFile) return null
        return base.takeIf { CMakeTargets.parse(it).isNotEmpty() }
    }

    private companion object {
        // What CLion creates by default; a project configured elsewhere still
        // builds, cmake just resolves the directory itself.
        const val BUILD_DIRECTORY = "cmake-build-debug"
    }
}
