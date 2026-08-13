package com.aspix2k.affected.build

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

class RubyCommandsTest {

    @Test
    fun `Bundler gems share one RSpec command`() {
        val root = createTempDirectory("ruby-rspec").toFile()
        File(root, "gems/a/spec").mkdirs()
        File(root, "gems/b/spec").mkdirs()
        val modules = rubyModules(
            root.path,
            Triple("gem-a", "gems/a", "test-rspec"),
            Triple("gem-b", "gems/b", "test-rspec"),
        )

        val commands = rubyCommands(root.path, listOf("gem-a:test-rspec", "gem-b:test-rspec"), modules)

        assertEquals(
            listOf("bundle", "exec", "rspec", "./gems/a", "./gems/b"),
            commands.single().arguments,
        )
    }

    @Test
    fun `Bundler runner groups stay in one ordered command batch`() {
        val root = createTempDirectory("ruby-runners").toFile()
        File(root, "gems/rspec/spec").mkdirs()
        File(root, "gems/minitest/test").mkdirs()
        File(root, "gems/test-unit/test").mkdirs()
        val modules = rubyModules(
            root.path,
            Triple("rspec", "gems/rspec", "test-rspec"),
            Triple("minitest", "gems/minitest", "test-minitest"),
            Triple("test-unit", "gems/test-unit", "test-test-unit"),
        )

        val commands = rubyCommands(
            root.path,
            listOf("rspec:test-rspec", "minitest:test-minitest", "test-unit:test-test-unit"),
            modules,
        )

        assertEquals(
            listOf(
                listOf("bundle", "exec", "rspec", "./gems/rspec"),
                listOf("bundle", "exec", "minitest", "./gems/minitest/test"),
                listOf("bundle", "exec", "test-unit", "./gems/test-unit/test"),
            ),
            commands.map(CliCommand::arguments),
        )
    }

    @Test
    fun `a gem with multiple supported suites runs every suite`() {
        val root = createTempDirectory("ruby-mixed").toFile()
        File(root, "gems/mixed/spec").mkdirs()
        File(root, "gems/mixed/test").mkdirs()
        val modules = rubyModules(
            root.path,
            Triple("mixed", "gems/mixed", "test-rspec+minitest"),
        )

        val commands = rubyCommands(root.path, listOf("mixed:test-rspec+minitest"), modules)

        assertEquals(
            listOf(
                listOf("bundle", "exec", "rspec", "./gems/mixed"),
                listOf("bundle", "exec", "minitest", "./gems/mixed/test"),
            ),
            commands.map(CliCommand::arguments),
        )
    }

    @Test
    fun `an incomplete Bundler graph falls back to every declared runner`() {
        val root = createTempDirectory("ruby-fallback").toFile()
        File(root, "Gemfile").writeText(
            """
            source "https://rubygems.org"
            gem "minitest", "6.0.6"
            gem "test-unit", "3.7.8"
            """.trimIndent(),
        )
        File(root, "Gemfile.lock").writeText(
            """
            GEM
              remote: https://rubygems.org/
              specs:
                minitest (6.0.6)
                rspec (3.13.2)
                test-unit (3.7.8)

            DEPENDENCIES
              minitest (= 6.0.6)
              rspec (= 3.13.2)
              test-unit (= 3.7.8)
            """.trimIndent(),
        )
        listOf("minitest", "test-unit").forEach { name ->
            val directory = File(root, "gems/$name").apply { mkdirs() }
            File(directory, "$name.gemspec").writeText(
                "Gem::Specification.new { |spec| spec.name = \"$name\"; spec.version = \"1.0.0\" }\n",
            )
            File(directory, "test").mkdirs()
        }
        File(root, "gems/minitest/spec").mkdirs()
        val task = RubyTestSuites.fallbackTask(root)
        val module = RubyGems.fallback(root, task)

        val commands = rubyCommands(root.path, listOf(".:$task"), listOf(module))

        assertEquals("fallback-rspec+minitest+test-unit", task)
        assertEquals(
            listOf(
                listOf("bundle", "exec", "rspec", "./gems/minitest"),
                listOf("bundle", "exec", "minitest", "./gems/minitest/test", "./gems/test-unit/test"),
                listOf("bundle", "exec", "test-unit", "./gems/minitest/test", "./gems/test-unit/test"),
            ),
            commands.map(CliCommand::arguments),
        )
    }

    @Test
    fun `a stale Bundler runner plan invalidates the whole command batch`() {
        val modules = rubyModules("/repo", Triple("gem-a", "gems/a", "test-minitest"))

        assertEquals(emptyList(), rubyCommands("/repo", listOf("gem-a:test-rspec"), modules))
    }

    @Test
    fun `a symlink in a Bundler fallback suite invalidates the whole batch`() {
        val root = createTempDirectory("ruby-symlink").toFile()
        val gem = File(root, "gem").apply { mkdirs() }
        File(gem, "affected.gemspec").writeText(
            "Gem::Specification.new { |spec| spec.name = \"affected\"; spec.version = \"1.0.0\" }\n",
        )
        val test = File(gem, "test").apply { mkdirs() }
        val target = File(root, "outside.rb").apply { writeText("puts 'outside'\n") }
        runCatching { java.nio.file.Files.createSymbolicLink(File(test, "evil_test.rb").toPath(), target.toPath()) }
            .getOrElse { return }
        val task = "fallback-minitest"

        assertEquals(
            emptyList(),
            rubyCommands(root.path, listOf(".:$task"), listOf(RubyGems.fallback(root, task))),
        )
    }

    @Test
    fun `a root Minitest project runs only its locked runner`() {
        val root = createTempDirectory("ruby-root-minitest").toFile()
        File(root, "Gemfile.lock").writeText(
            """
            GEM
              remote: https://rubygems.org/
              specs:
                minitest (6.0.6)

            DEPENDENCIES
              minitest (= 6.0.6)
            """.trimIndent(),
        )
        File(root, "test").mkdirs()
        val task = RubyTestSuites.fallbackTask(root)

        assertEquals("fallback-minitest", task)
        assertEquals(
            listOf(listOf("bundle", "exec", "minitest", "./test")),
            rubyCommands(root.path, listOf(".:$task"), listOf(RubyGems.fallback(root, task)))
                .map(CliCommand::arguments),
        )
    }

    @Test
    fun `an RSpec fallback cannot omit an existing test suite`() {
        val root = createTempDirectory("ruby-partial-rspec").toFile()
        File(root, "Gemfile.lock").writeText(
            """
            GEM
              remote: https://rubygems.org/
              specs:
                rspec (3.13.2)

            DEPENDENCIES
              rspec (= 3.13.2)
            """.trimIndent(),
        )
        listOf("spec", "custom").forEach { name ->
            val gem = File(root, "gems/$name").apply { mkdirs() }
            File(gem, "$name.gemspec").writeText(
                "Gem::Specification.new { |spec| spec.name = \"$name\"; spec.version = \"1.0.0\" }\n",
            )
        }
        File(root, "gems/spec/spec").mkdirs()
        File(root, "gems/custom/test").mkdirs()
        val task = RubyTestSuites.fallbackTask(root)

        assertEquals("fallback-rspec", task)
        assertEquals(emptyList(), rubyCommands(root.path, listOf(".:$task"), listOf(RubyGems.fallback(root, task))))
    }

    @Test
    fun `a Minitest fallback cannot omit an existing spec suite`() {
        val root = createTempDirectory("ruby-partial-minitest").toFile()
        File(root, "Gemfile.lock").writeText(
            """
            GEM
              remote: https://rubygems.org/
              specs:
                minitest (6.0.6)

            DEPENDENCIES
              minitest (= 6.0.6)
            """.trimIndent(),
        )
        val gem = File(root, "gem").apply { mkdirs() }
        File(gem, "affected.gemspec").writeText(
            "Gem::Specification.new { |spec| spec.name = \"affected\"; spec.version = \"1.0.0\" }\n",
        )
        File(gem, "spec").mkdirs()
        File(gem, "test").mkdirs()
        val task = RubyTestSuites.fallbackTask(root)

        assertEquals("fallback-minitest", task)
        assertEquals(emptyList(), rubyCommands(root.path, listOf(".:$task"), listOf(RubyGems.fallback(root, task))))
    }

    @Test
    fun `Bundler suite paths cannot become runner options`() {
        val root = createTempDirectory("ruby-option-path").toFile()
        File(root, "--profile/test").mkdirs()
        val modules = rubyModules(root.path, Triple("option", "--profile", "test-minitest"))

        assertEquals(
            listOf("bundle", "exec", "minitest", "./--profile/test"),
            rubyCommands(root.path, listOf("option:test-minitest"), modules).single().arguments,
        )
    }

    private fun rubyModules(root: String, vararg entries: Triple<String, String, String>): List<BuildModule> =
        entries.map { (id, relative, task) ->
            BuildModule(
                id = id,
                root = root,
                contentRoots = listOf("$root/$relative"),
                testTask = task,
                compileTask = null,
                hasTests = true,
                executionId = id,
            )
        }
}
