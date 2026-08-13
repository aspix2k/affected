package com.aspix2k.affected

import com.aspix2k.affected.build.CMakeTargets
import com.aspix2k.affected.build.ComposerPackages
import com.aspix2k.affected.build.PythonProjects
import com.aspix2k.affected.build.RubyGems
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertTrue

class DetectionCostTest {

    @Test
    fun `Ruby parsing does not read a deeply nested manifest`() {
        val root = createTempDirectory("ruby-cost").toFile()
        val buried = buryManifest(root, "buried.gemspec")
        File(root, "Gemfile").writeText("source 'https://rubygems.org'")
        File(root, "root.gemspec").writeText("Gem::Specification.new { |s| s.name = \"root\" }")

        val found = RubyGems.parse(root).flatMap { it.contentRoots }

        assertTrue(found.none { it.startsWith(buried.parent) }, buried.path)
    }

    @Test
    fun `Composer parsing does not read a deeply nested manifest`() {
        val root = createTempDirectory("composer-cost").toFile()
        val buried = buryManifest(root, "composer.json")
        File(root, "composer.json").writeText("""{ "name": "acme/root" }""")

        val found = ComposerPackages.parse(root).flatMap { it.contentRoots }

        assertTrue(found.none { it.startsWith(buried.parent) }, buried.path)
    }

    @Test
    fun `Python parsing does not read a deeply nested manifest`() {
        val root = createTempDirectory("python-cost").toFile()
        val buried = buryManifest(root, "pyproject.toml")
        File(root, "pyproject.toml").writeText("[project]\nname = \"root\"\n")

        val found = PythonProjects.parse(root).flatMap { it.contentRoots }

        assertTrue(found.none { it.startsWith(buried.parent) }, buried.path)
    }

    @Test
    fun `CMake parsing does not read a deeply nested manifest`() {
        val root = createTempDirectory("cmake-cost").toFile()
        val buried = buryManifest(root, "CMakeLists.txt")
        File(root, "CMakeLists.txt").writeText("add_executable(app main.cpp)")

        val found = CMakeTargets.parse(root).flatMap { it.contentRoots }

        assertTrue(found.none { it.startsWith(buried.parent) }, buried.path)
    }

    private fun buryManifest(root: File, name: String): File {
        var directory = root
        repeat(12) { level -> directory = File(directory, "deep$level").apply { mkdirs() } }
        return File(directory, name).apply { writeText(CONTENT.getValue(name)) }
    }

    private companion object {
        val CONTENT = mapOf(
            "buried.gemspec" to "Gem::Specification.new { |s| s.name = \"buried\" }",
            "composer.json" to """{ "name": "acme/buried" }""",
            "pyproject.toml" to "[project]\nname = \"buried\"\n",
            "CMakeLists.txt" to "add_library(buried STATIC buried.cpp)",
        )
    }
}
