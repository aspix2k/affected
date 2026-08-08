package com.aspix2k.affected

import com.aspix2k.affected.build.RubyGems
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RubyGemsTest {

    private fun monorepo(): File = createTempDirectory("ruby").toFile()

    private fun gem(
        root: File,
        path: String,
        name: String,
        dependencies: List<String> = emptyList(),
        specs: Boolean = false,
    ) {
        val directory = File(root, path).apply { mkdirs() }
        File(directory, "$name.gemspec").writeText(
            buildString {
                appendLine("Gem::Specification.new do |spec|")
                appendLine("""  spec.name = "$name"""")
                appendLine("""  spec.version = "1.0.0"""")
                dependencies.forEach { appendLine("""  spec.add_dependency "$it", "~> 1.0"""") }
                appendLine("end")
            },
        )
        if (specs) File(directory, "spec").mkdirs()
    }

    @Test
    fun `monorepo gems are found`() {
        val root = monorepo()
        gem(root, "gems/core", "acme-core")
        gem(root, "gems/api", "acme-api")

        assertEquals(setOf("acme-core", "acme-api"), RubyGems.parse(root).map { it.id }.toSet())
    }

    @Test
    fun `only local gems are dependencies`() {
        val root = monorepo()
        gem(root, "gems/core", "acme-core")
        gem(root, "gems/api", "acme-api", dependencies = listOf("acme-core", "rails"))

        val api = RubyGems.parse(root).single { it.id == "acme-api" }

        assertEquals(
            setOf("${root.invariantSeparatorsPath}|acme-core"),
            api.dependencies,
            "Rails comes from RubyGems and cannot be a consumer",
        )
    }

    @Test
    fun `development dependencies also create an edge`() {
        val root = monorepo()
        gem(root, "gems/core", "acme-core")
        val directory = File(root, "gems/tools").apply { mkdirs() }
        File(directory, "acme-tools.gemspec").writeText(
            """
            Gem::Specification.new do |spec|
              spec.name = "acme-tools"
              spec.add_development_dependency "acme-core", "~> 1.0"
            end
            """.trimIndent(),
        )

        val tools = RubyGems.parse(root).single { it.id == "acme-tools" }

        assertEquals(setOf("${root.invariantSeparatorsPath}|acme-core"), tools.dependencies)
    }

    @Test
    fun `Ruby consumers are not checked`() {
        val root = monorepo()
        gem(root, "gems/core", "acme-core")
        gem(root, "gems/api", "acme-api")

        assertTrue(
            RubyGems.parse(root).all { it.compileTask == null },
            "Ruby has nothing to compile, so consumers are not checked",
        )
        assertNull(RubyGems.parse(root).first().compileTask)
    }

    @Test
    fun `a spec directory makes a gem testable`() {
        val root = monorepo()
        gem(root, "gems/with", "acme-with", specs = true)
        gem(root, "gems/without", "acme-without")

        val modules = RubyGems.parse(root)

        assertTrue(modules.single { it.id == "acme-with" }.hasTests)
        assertFalse(modules.single { it.id == "acme-without" }.hasTests)
    }

    @Test
    fun `a single gem is not a monorepo`() {
        val root = monorepo()
        gem(root, ".", "acme-single")

        assertEquals(emptyList(), RubyGems.parse(root))
    }

    @Test
    fun `a gemspec without a name does not crash parsing`() {
        val root = monorepo()
        gem(root, "gems/core", "acme-core")
        File(root, "gems/broken").mkdirs()
        File(root, "gems/broken/broken.gemspec").writeText("this is not a gemspec at all")

        val modules = RubyGems.parse(root)

        assertTrue(modules.any { it.id == "acme-core" })
        assertTrue(modules.any { it.id == "broken" }, "the filename supplies a missing name")
    }
}
