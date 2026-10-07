package com.aspix2k.affected

import com.aspix2k.affected.build.IdeCMakeBuildSystem
import com.aspix2k.affected.build.IdeCargoBuildSystem
import com.aspix2k.affected.build.IdeComposerBuildSystem
import com.aspix2k.affected.build.IdeDartBuildSystem
import com.aspix2k.affected.build.IdeDenoBuildSystem
import com.aspix2k.affected.build.IdeDotnetBuildSystem
import com.aspix2k.affected.build.IdeFlutterBuildSystem
import com.aspix2k.affected.build.IdeKotlinToolchainBuildSystem
import com.aspix2k.affected.build.IdeNodeBuildSystem
import com.aspix2k.affected.build.IdePythonBuildSystem
import com.aspix2k.affected.build.IdeRBuildSystem
import com.aspix2k.affected.build.IdeRubyBuildSystem
import com.aspix2k.affected.build.IdeSbtBuildSystem
import com.aspix2k.affected.build.IdeSwiftBuildSystem
import com.aspix2k.affected.build.IdeXcodeBuildSystem
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
        val bundle: Boolean = false,
        val content: String = "",
        val extras: Map<String, String> = emptyMap(),
    ) {
        fun writeTo(directory: File) {
            directory.mkdirs()
            File(directory, file).also { if (bundle) it.mkdirs() else it.writeText(content) }
            extras.forEach { (name, text) -> File(directory, name).writeText(text) }
        }
    }

    private companion object {
        val systems = listOf<Pair<(Project) -> Boolean, Marker>>(
            IdeRubyBuildSystem()::isPresent to Marker("Gemfile"),
            IdeComposerBuildSystem()::isPresent to Marker("composer.json"),
            IdePythonBuildSystem()::isPresent to Marker("pyproject.toml"),
            IdeCMakeBuildSystem()::isPresent to Marker("CMakeLists.txt"),
            IdeNodeBuildSystem()::isPresent to Marker("package.json"),
            IdeDotnetBuildSystem()::isPresent to Marker("app.csproj", multiRoot = false),
            IdeDotnetBuildSystem()::isPresent to Marker("app.sln"),
            IdeXcodeBuildSystem()::isPresent to Marker("App.xcodeproj", bundle = true),
            IdeSbtBuildSystem()::isPresent to Marker("build.sbt"),
            IdeCargoBuildSystem()::isPresent to Marker("Cargo.toml"),
            IdeSwiftBuildSystem()::isPresent to Marker("Package.swift"),
            IdeRBuildSystem()::isPresent to Marker("DESCRIPTION", content = "Package: probe\n"),
            IdeKotlinToolchainBuildSystem()::isPresent to Marker("project.yaml", extras = mapOf("kotlin" to "")),
            IdeDenoBuildSystem()::isPresent to Marker("deno.json", content = "{}"),
            IdeDartBuildSystem()::isPresent to Marker("pubspec.yaml", content = "name: probe\n"),
            IdeFlutterBuildSystem()::isPresent to Marker(
                "pubspec.yaml",
                content = "name: probe\ndependencies:\n  flutter:\n    sdk: flutter\n",
            ),
        )
    }
}
