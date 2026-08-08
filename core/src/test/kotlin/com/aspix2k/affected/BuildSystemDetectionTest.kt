package com.aspix2k.affected

import com.aspix2k.affected.build.CMakeBuildSystem
import com.aspix2k.affected.build.ComposerBuildSystem
import com.aspix2k.affected.build.DotnetBuildSystem
import com.aspix2k.affected.build.NodeBuildSystem
import com.aspix2k.affected.build.PythonBuildSystem
import com.aspix2k.affected.build.RubyBuildSystem
import com.intellij.openapi.project.Project
import java.io.File
import java.lang.reflect.Proxy
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BuildSystemDetectionTest {

    @Test
    fun `a root marker does not require a populated module graph`() {
        systems.forEach { (system, marker) ->
            val root = createTempDirectory("detection").toFile()
            File(root, marker).writeText("")

            assertTrue(system(projectAt(root)), marker)
        }
    }

    @Test
    fun `a nested marker does not replace the project root`() {
        systems.forEach { (system, marker) ->
            val root = createTempDirectory("nested-detection").toFile()
            File(root, "nested/$marker").apply {
                parentFile.mkdirs()
                writeText("")
            }

            assertFalse(system(projectAt(root)), marker)
        }
    }

    private fun projectAt(root: File): Project =
        Proxy.newProxyInstance(
            Project::class.java.classLoader,
            arrayOf(Project::class.java),
        ) { proxy, method, arguments ->
            when (method.name) {
                "getBasePath" -> root.path
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === arguments?.singleOrNull()
                "toString" -> "Project(${root.path})"
                else -> error("Unexpected Project call: ${method.name}")
            }
        } as Project

    private companion object {
        val systems = listOf<(Project) -> Boolean>(
            RubyBuildSystem()::isPresent,
            ComposerBuildSystem()::isPresent,
            PythonBuildSystem()::isPresent,
            CMakeBuildSystem()::isPresent,
            NodeBuildSystem()::isPresent,
            DotnetBuildSystem()::isPresent,
        ).zip(
            listOf("Gemfile", "composer.json", "pyproject.toml", "CMakeLists.txt", "package.json", "app.csproj"),
        )
    }
}
