package com.aspix2k.affected

import com.aspix2k.affected.build.KotlinToolchainBuildSystem
import com.aspix2k.affected.build.RBuildSystem
import com.aspix2k.affected.build.SbtBuildSystem
import com.aspix2k.affected.build.SwiftBuildSystem
import com.aspix2k.affected.build.cargo.CargoBuildSystem
import com.aspix2k.affected.build.cmake.CMakeBuildSystem
import com.aspix2k.affected.build.dart.DartBuildSystem
import com.aspix2k.affected.build.dart.FlutterBuildSystem
import com.aspix2k.affected.build.deno.DenoBuildSystem
import com.aspix2k.affected.build.dotnet.DotnetBuildSystem
import com.aspix2k.affected.build.node.NodeBuildSystem
import com.aspix2k.affected.build.php.ComposerBuildSystem
import com.aspix2k.affected.build.python.PythonBuildSystem
import com.aspix2k.affected.build.ruby.RubyBuildSystem
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
            marker.writeTo(root)

            assertTrue(system(projectAt(root)), marker.file)
        }
    }

    @Test
    fun `a single first-level nested marker is present`() {
        systems.forEach { (system, marker) ->
            val root = createTempDirectory("nested-detection").toFile()
            marker.writeTo(File(root, "nested"))

            assertTrue(system(projectAt(root)), marker.file)
        }
    }

    @Test
    fun `several first-level nested markers stay off`() {
        systems.filterNot { it.second.multiRoot }.forEach { (system, marker) ->
            val root = createTempDirectory("nested-many").toFile()
            marker.writeTo(File(root, "one"))
            marker.writeTo(File(root, "two"))

            assertFalse(system(projectAt(root)), marker.file)
        }
    }

    @Test
    fun `several first-level nested markers are present for multi-root systems`() {
        systems.filter { it.second.multiRoot }.forEach { (system, marker) ->
            val root = createTempDirectory("nested-many-roots").toFile()
            marker.writeTo(File(root, "one"))
            marker.writeTo(File(root, "two"))

            assertTrue(system(projectAt(root)), marker.file)
        }
    }

    @Test
    fun `a nested marker deeper than three levels stays off`() {
        systems.forEach { (system, marker) ->
            val root = createTempDirectory("nested-deep").toFile()
            marker.writeTo(File(root, "a/b/c/d"))

            assertFalse(system(projectAt(root)), marker.file)
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

    private class Marker(
        val file: String,
        val multiRoot: Boolean = true,
        val content: String = "",
        val extras: Map<String, String> = emptyMap(),
    ) {
        fun writeTo(directory: File) {
            directory.mkdirs()
            File(directory, file).writeText(content)
            extras.forEach { (name, text) -> File(directory, name).writeText(text) }
        }
    }

    private companion object {
        val systems = listOf<Pair<(Project) -> Boolean, Marker>>(
            RubyBuildSystem()::isPresent to Marker("Gemfile"),
            ComposerBuildSystem()::isPresent to Marker("composer.json"),
            PythonBuildSystem()::isPresent to Marker("pyproject.toml"),
            CMakeBuildSystem()::isPresent to Marker("CMakeLists.txt", multiRoot = false),
            NodeBuildSystem()::isPresent to Marker("package.json"),
            DotnetBuildSystem()::isPresent to Marker("app.csproj", multiRoot = false),
            SbtBuildSystem()::isPresent to Marker("build.sbt"),
            CargoBuildSystem()::isPresent to Marker("Cargo.toml"),
            SwiftBuildSystem()::isPresent to Marker("Package.swift"),
            RBuildSystem()::isPresent to Marker("DESCRIPTION", content = "Package: probe\n"),
            KotlinToolchainBuildSystem()::isPresent to Marker("project.yaml", extras = mapOf("kotlin" to "")),
            DenoBuildSystem()::isPresent to Marker("deno.json", content = "{}"),
            DartBuildSystem()::isPresent to Marker("pubspec.yaml", content = "name: probe\n"),
            FlutterBuildSystem()::isPresent to Marker(
                "pubspec.yaml",
                content = "name: probe\ndependencies:\n  flutter:\n    sdk: flutter\n",
            ),
        )
    }
}
