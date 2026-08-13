package com.aspix2k.affected

import com.aspix2k.affected.build.RubyGems
import com.aspix2k.affected.build.RubyTestSuites
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RubyGemsTest {

    private fun monorepo(): File = createTempDirectory("ruby").toFile().also {
        lock(it, "rspec" to "3.13.2")
    }

    private fun lock(root: File, vararg dependencies: Pair<String, String>) {
        File(root, "Gemfile.lock").writeText(
            buildString {
                appendLine("GEM")
                appendLine("  specs:")
                dependencies.forEach { (name, version) -> appendLine("    $name ($version)") }
                appendLine()
                appendLine("DEPENDENCIES")
                dependencies.forEach { (name, version) -> appendLine("  $name (= $version)") }
            },
        )
    }

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
    fun `Minitest and Test Unit suites keep whole gem selection`() {
        val root = monorepo()
        lock(root, "minitest" to "6.0.6", "test-unit" to "3.7.8")
        gem(root, "gems/minitest", "acme-minitest")
        gem(root, "gems/test-unit", "acme-test-unit")
        File(root, "gems/minitest/test").mkdirs()
        File(root, "gems/minitest/test/widget_test.rb").writeText(
            """
            require "minitest/autorun"
            class WidgetTest < Minitest::Test
            end
            """.trimIndent(),
        )
        File(root, "gems/test-unit/test").mkdirs()
        File(root, "gems/test-unit/test/widget_test.rb").writeText(
            """
            require "test/unit"
            class WidgetTest < Test::Unit::TestCase
            end
            """.trimIndent(),
        )

        val modules = RubyGems.parse(root).associateBy { it.id }

        assertEquals("test-minitest+test-unit", modules.getValue("acme-minitest").testTask)
        assertEquals("test-minitest+test-unit", modules.getValue("acme-test-unit").testTask)
        assertEquals(listOf(File(root, "gems/minitest").invariantSeparatorsPath), modules.getValue("acme-minitest").contentRoots)
        assertEquals(listOf(File(root, "gems/test-unit").invariantSeparatorsPath), modules.getValue("acme-test-unit").contentRoots)
    }

    @Test
    fun `a library only gem does not invalidate a Minitest graph`() {
        val root = monorepo()
        lock(root, "minitest" to "6.0.6")
        gem(root, "gems/library", "acme-library")
        gem(root, "gems/tested", "acme-tested")
        File(root, "gems/tested/test").mkdirs()
        File(root, "gems/tested/test/widget_test.rb").writeText(
            "require \"minitest/autorun\"\nclass WidgetTest < Minitest::Test\nend\n",
        )

        val modules = RubyGems.parse(root).associateBy { it.id }

        assertFalse(modules.getValue("acme-library").hasTests)
        assertEquals("test-minitest", modules.getValue("acme-tested").testTask)
    }

    @Test
    fun `RSpec and Minitest suites in one gem are both retained`() {
        val root = monorepo()
        lock(root, "rspec" to "3.13.2", "minitest" to "6.0.6")
        gem(root, "gems/mixed", "acme-mixed", specs = true)
        File(root, "gems/mixed/test").mkdirs()
        File(root, "gems/mixed/test/widget_test.rb").writeText(
            "require \"minitest/autorun\"\nclass WidgetTest < Minitest::Test\nend\n",
        )

        assertEquals("test-rspec+minitest", RubyGems.parse(root).single().testTask)
    }

    @Test
    fun `an unknown test suite invalidates the complete graph`() {
        val root = monorepo()
        lock(root, "rspec" to "3.13.2")
        gem(root, "gems/known", "acme-known", specs = true)
        gem(root, "gems/unknown", "acme-unknown")
        File(root, "gems/unknown/test").mkdirs()
        File(root, "gems/unknown/test/widget_test.rb").writeText("puts 'custom runner'\n")

        assertEquals(emptyList(), RubyGems.parse(root))
    }

    @Test
    fun `Minitest and Test Unit markers never become an RSpec-only plan`() {
        val root = monorepo()
        lock(root, "minitest" to "6.0.6", "test-unit" to "3.7.8")
        gem(root, "gems/mixed", "acme-mixed")
        File(root, "gems/mixed/test").mkdirs()
        File(root, "gems/mixed/test/minitest_test.rb").writeText(
            "require \"minitest/autorun\"\nclass MiniTest < Minitest::Test\nend\n",
        )
        File(root, "gems/mixed/test/test_unit_test.rb").writeText(
            "require \"test/unit\"\nclass UnitTest < Test::Unit::TestCase\nend\n",
        )

        assertEquals("test-minitest+test-unit", RubyGems.parse(root).single().testTask)
    }

    @Test
    fun `locked runner versions gate native Ruby executables`() {
        val root = monorepo()
        File(root, "Gemfile.lock").writeText(
            """
            GEM
              specs:
                minitest (6.0.6)
                test-unit (3.7.8)

            DEPENDENCIES
              minitest (= 6.0.6)
              rails (= 9.0.0)
              test-unit (= 3.7.8)
            """.trimIndent(),
        )

        assertEquals(
            setOf("MINITEST", "TEST_UNIT"),
            RubyTestSuites.lockedRunners(root).orEmpty().map { it.name }.toSet(),
        )

        File(root, "Gemfile.lock").writeText(
            """
            GEM
              specs:
                minitest (5.25.4)

            DEPENDENCIES
              minitest (= 5.25.4)
            """.trimIndent(),
        )
        assertNull(RubyTestSuites.lockedRunners(root))
    }

    @Test
    fun `transitive Ruby runners are not treated as project commands`() {
        val root = monorepo()
        File(root, "Gemfile.lock").writeText(
            """
            GEM
              specs:
                minitest (6.0.6)
                rails (9.0.0)
                  minitest

            DEPENDENCIES
              rails (= 9.0.0)
            """.trimIndent(),
        )

        assertEquals(emptySet(), RubyTestSuites.lockedRunners(root))
    }

    @Test
    fun `a single gem remains runnable`() {
        val root = monorepo()
        gem(root, ".", "acme-single")

        val module = RubyGems.parse(root).single()

        assertEquals("acme-single", module.id)
        assertEquals(".", module.executionId)
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
