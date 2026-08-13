package com.aspix2k.affected

import com.aspix2k.affected.build.ComposerPackages
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ComposerPackagesTest {

    private fun monorepo(): File = createTempDirectory("composer").toFile()

    private fun packageAt(
        root: File,
        path: String,
        name: String,
        require: Map<String, String> = emptyMap(),
        requireDev: Map<String, String> = emptyMap(),
        tests: Boolean = false,
        phpstan: Boolean = false,
    ) {
        val directory = File(root, path).apply { mkdirs() }
        val requires = require.entries.joinToString(", ") { """"${it.key}": "${it.value}"""" }
        val devs = requireDev.entries.joinToString(", ") { """"${it.key}": "${it.value}"""" }
        File(directory, "composer.json").writeText(
            """{ "name": "$name", "require": { $requires }, "require-dev": { $devs } }""",
        )
        if (tests) {
            File(directory, "tests").mkdirs()
            File(directory, "tests/ExampleTest.php").writeText("<?php\n")
        }
        if (phpstan) File(directory, "phpstan.neon").writeText("parameters:\n  level: 8\n")
    }

    private fun pestLock(root: File, pest: String, phpunit: String) {
        File(root, "composer.lock").writeText(
            """
            {
              "packages": [],
              "packages-dev": [
                {
                  "name": "pestphp/pest",
                  "version": "v$pest",
                  "source": {
                    "type": "git",
                    "url": "https://github.com/pestphp/pest.git",
                    "reference": "208f447a10fc416397edf00a5fc6380aa284d393"
                  },
                  "dist": {
                    "type": "zip",
                    "url": "https://api.github.com/repos/pestphp/pest/zipball/208f447a10fc416397edf00a5fc6380aa284d393",
                    "reference": "208f447a10fc416397edf00a5fc6380aa284d393",
                    "shasum": ""
                  },
                  "require": { "phpunit/phpunit": "^$phpunit" },
                  "conflict": { "phpunit/phpunit": ">$phpunit" },
                  "bin": ["bin/pest"]
                },
                {
                  "name": "phpunit/phpunit",
                  "version": "$phpunit",
                  "source": {
                    "type": "git",
                    "url": "https://github.com/sebastianbergmann/phpunit.git",
                    "reference": "346fcba6ce7ab89bb1b0675feac6bc29c0f7711b"
                  },
                  "dist": {
                    "type": "zip",
                    "url": "https://api.github.com/repos/sebastianbergmann/phpunit/zipball/346fcba6ce7ab89bb1b0675feac6bc29c0f7711b",
                    "reference": "346fcba6ce7ab89bb1b0675feac6bc29c0f7711b",
                    "shasum": ""
                  }
                }
              ]
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `monorepo packages are found`() {
        val root = monorepo()
        packageAt(root, ".", "acme/root")
        packageAt(root, "packages/core", "acme/core")
        packageAt(root, "packages/api", "acme/api")

        val modules = ComposerPackages.parse(root)

        assertEquals(setOf("acme/root", "acme/core", "acme/api"), modules.map { it.id }.toSet())
    }

    @Test
    fun `only local packages are dependencies`() {
        val root = monorepo()
        packageAt(root, "packages/core", "acme/core")
        packageAt(
            root,
            "packages/api",
            "acme/api",
            require = mapOf("acme/core" to "*", "symfony/console" to "^7.0"),
        )

        val api = ComposerPackages.parse(root).single { it.id == "acme/api" }

        assertEquals(
            setOf("${root.invariantSeparatorsPath}|acme/core"),
            api.dependencies,
            "symfony comes from Packagist and cannot be a consumer",
        )
    }

    @Test
    fun `require-dev also creates an edge`() {
        val root = monorepo()
        packageAt(root, "packages/core", "acme/core")
        packageAt(root, "packages/test-utils", "acme/test-utils", requireDev = mapOf("acme/core" to "*"))

        val utils = ComposerPackages.parse(root).single { it.id == "acme/test-utils" }

        assertEquals(setOf("${root.invariantSeparatorsPath}|acme/core"), utils.dependencies)
    }

    @Test
    fun `a consumer is checked only with a static analyzer`() {
        val root = monorepo()
        packageAt(root, "packages/analysed", "acme/analysed", phpstan = true)
        packageAt(root, "packages/plain", "acme/plain")

        val modules = ComposerPackages.parse(root)

        assertEquals("analyse", modules.single { it.id == "acme/analysed" }.compileTask)
        assertNull(
            modules.single { it.id == "acme/plain" }.compileTask,
            "PHP has nothing to compile and no check without phpstan",
        )
    }

    @Test
    fun `a psalm dependency also enables checking`() {
        val root = monorepo()
        packageAt(root, "packages/a", "acme/a", requireDev = mapOf("vimeo/psalm" to "^5.0"))
        packageAt(root, "packages/b", "acme/b")

        val modules = ComposerPackages.parse(root)

        assertEquals("analyse", modules.single { it.id == "acme/a" }.compileTask)
        assertEquals("analyse", modules.single { it.id == "acme/a" }.testTask)
        assertTrue(modules.single { it.id == "acme/a" }.hasTests)
    }

    @Test
    fun `a tests directory makes a package testable`() {
        val root = monorepo()
        packageAt(root, "packages/with", "acme/with", tests = true)
        packageAt(root, "packages/without", "acme/without")

        val modules = ComposerPackages.parse(root)

        assertTrue(modules.single { it.id == "acme/with" }.hasTests)
        assertFalse(modules.single { it.id == "acme/without" }.hasTests)
    }

    @Test
    fun `vendor is not scanned`() {
        val root = monorepo()
        packageAt(root, "packages/core", "acme/core")
        packageAt(root, "packages/api", "acme/api")
        packageAt(root, "vendor/someone/library", "someone/library")

        assertEquals(setOf("acme/core", "acme/api"), ComposerPackages.parse(root).map { it.id }.toSet())
    }

    @Test
    fun `a single package remains runnable`() {
        val root = monorepo()
        packageAt(root, ".", "acme/single")

        val module = ComposerPackages.parse(root).single()

        assertEquals("acme/single", module.id)
        assertEquals(".", module.executionId)
    }

    @Test
    fun `a malformed package manifest invalidates the graph`() {
        val root = monorepo()
        packageAt(root, ".", "acme/root")
        File(root, "packages/broken").mkdirs()
        File(root, "packages/broken/composer.json").writeText("{")

        assertEquals(emptyList(), ComposerPackages.parse(root))
    }

    @Test
    fun `supported Pest and PHPUnit pairs select the Pest runner`() {
        val root = monorepo()
        packageAt(root, ".", "acme/pest", requireDev = mapOf("pestphp/pest" to "5.1.1"), tests = true)
        pestLock(root, "5.1.1", "13.3.0")

        assertEquals(ComposerPackages.PEST, ComposerPackages.parse(root).single().testTask)
    }

    @Test
    fun `a PHPT suite is runnable through the Pest runner`() {
        val root = monorepo()
        packageAt(root, ".", "acme/pest", requireDev = mapOf("pestphp/pest" to "5.1.1"))
        File(root, "tests").mkdirs()
        File(root, "tests/Example.phpt").writeText("--TEST--\nAffected\n--FILE--\n<?php\n--EXPECT--\n")
        pestLock(root, "5.1.1", "13.3.0")

        assertEquals(ComposerPackages.PEST, ComposerPackages.parse(root).single().testTask)
        assertTrue(ComposerPackages.parse(root).single().hasTests)
    }

    @Test
    fun `duplicate Pest declarations fail closed`() {
        val root = monorepo()
        packageAt(
            root,
            ".",
            "acme/pest",
            require = mapOf("pestphp/pest" to "^5.0"),
            requireDev = mapOf("pestphp/pest" to "5.1.1"),
            tests = true,
        )
        pestLock(root, "5.1.1", "13.3.0")

        assertEquals(emptyList(), ComposerPackages.parse(root))
        assertEquals(ComposerPackages.INVALID, ComposerPackages.fallbackTask(root))
    }

    @Test
    fun `a root Pest dependency applies to workspace packages`() {
        val root = monorepo()
        packageAt(root, ".", "acme/root", requireDev = mapOf("pestphp/pest" to "5.1.1"))
        packageAt(root, "packages/api", "acme/api", tests = true)
        pestLock(root, "5.1.1", "13.3.0")

        assertEquals(ComposerPackages.PEST, ComposerPackages.parse(root).single { it.id == "acme/api" }.testTask)
    }

    @Test
    fun `a Pest package without tests keeps its analysis task`() {
        val root = monorepo()
        packageAt(root, ".", "acme/root", requireDev = mapOf("pestphp/pest" to "5.1.1"))
        packageAt(root, "packages/api", "acme/api", phpstan = true)
        pestLock(root, "5.1.1", "13.3.0")

        val module = ComposerPackages.parse(root).single { it.id == "acme/api" }

        assertEquals(ComposerPackages.ANALYSE, module.testTask)
        assertEquals(ComposerPackages.ANALYSE, module.compileTask)
        assertTrue(module.hasTests)
    }

    @Test
    fun `an unproved Pest lock stops with an unresolved plan`() {
        val root = monorepo()
        packageAt(root, ".", "acme/pest", requireDev = mapOf("pestphp/pest" to "5.1.1"), tests = true)
        File(root, "composer.lock").writeText("{")

        assertEquals(emptyList(), ComposerPackages.parse(root))
        assertEquals(ComposerPackages.INVALID, ComposerPackages.fallbackTask(root))
    }

    @Test
    fun `an ambiguous Pest declaration fails closed instead of guessing a CLI`() {
        val root = monorepo()
        packageAt(root, ".", "acme/pest", requireDev = mapOf("pestphp/pest" to "^5.1"), tests = true)

        assertEquals(ComposerPackages.INVALID, ComposerPackages.fallbackTask(root))
    }

    @Test
    fun `malformed Pest package arrays fail closed without escaping the parser`() {
        val root = monorepo()
        packageAt(root, ".", "acme/pest", requireDev = mapOf("pestphp/pest" to "5.1.1"), tests = true)
        File(root, "composer.lock").writeText("""{"packages":{},"packages-dev":[]}""")

        assertEquals(emptyList(), ComposerPackages.parse(root))
        assertEquals(ComposerPackages.INVALID, ComposerPackages.fallbackTask(root))
    }

    @Test
    fun `non-object dependency sections fail closed without escaping fallback discovery`() {
        val root = monorepo()
        File(root, "composer.json").writeText("""{"name":"acme/root","require":[]}""")

        assertEquals(emptyList(), ComposerPackages.parse(root))
        assertEquals(ComposerPackages.INVALID, ComposerPackages.fallbackTask(root))
    }

    @Test
    fun `a nested Pest declaration cannot claim a missing root executable`() {
        val root = monorepo()
        packageAt(root, ".", "acme/root")
        packageAt(root, "packages/api", "acme/api", requireDev = mapOf("pestphp/pest" to "5.1.1"), tests = true)
        pestLock(root, "5.1.1", "13.3.0")

        assertEquals(emptyList(), ComposerPackages.parse(root))
        assertEquals(ComposerPackages.INVALID, ComposerPackages.fallbackTask(root))
    }

    @Test
    fun `a noncanonical Pest package source is not trusted`() {
        val root = monorepo()
        packageAt(root, ".", "acme/pest", requireDev = mapOf("pestphp/pest" to "5.1.1"), tests = true)
        pestLock(root, "5.1.1", "13.3.0")
        val lock = File(root, "composer.lock")
        lock.writeText(
            lock.readText().replace(
                "https://github.com/pestphp/pest.git",
                "https://packages.example/pest.git",
            ),
        )

        assertEquals(emptyList(), ComposerPackages.parse(root))
        assertEquals(ComposerPackages.INVALID, ComposerPackages.fallbackTask(root))
    }

    @Test
    fun `a root Pest bootstrap is not treated as a test suite`() {
        val root = monorepo()
        packageAt(root, ".", "acme/root", requireDev = mapOf("pestphp/pest" to "5.1.1"))
        packageAt(root, "packages/api", "acme/api", tests = true)
        File(root, "tests").mkdirs()
        File(root, "tests/Pest.php").writeText("<?php\n")
        pestLock(root, "5.1.1", "13.3.0")

        val modules = ComposerPackages.parse(root)

        assertFalse(modules.single { it.id == "acme/root" }.hasTests)
        assertTrue(modules.single { it.id == "acme/api" }.hasTests)
    }

    @Test
    fun `a custom Pest test contract invalidates the whole graph`() {
        val root = monorepo()
        packageAt(root, ".", "acme/pest", requireDev = mapOf("pestphp/pest" to "5.1.1"))
        File(root, "phpunit.xml").writeText("<phpunit/>")
        pestLock(root, "5.1.1", "13.3.0")

        assertEquals(emptyList(), ComposerPackages.parse(root))
        assertEquals(ComposerPackages.INVALID, ComposerPackages.fallbackTask(root))
    }

    @Test
    fun `malformed Pest binary metadata fails closed without throwing`() {
        val root = monorepo()
        packageAt(root, ".", "acme/pest", requireDev = mapOf("pestphp/pest" to "5.1.1"), tests = true)
        pestLock(root, "5.1.1", "13.3.0")
        val lock = File(root, "composer.lock")
        lock.writeText(lock.readText().replace("\"bin\": [\"bin/pest\"]", "\"bin\": [{}]"))

        assertEquals(emptyList(), ComposerPackages.parse(root))
    }

    @Test
    fun `malformed Composer scripts fail closed without throwing`() {
        val root = monorepo()
        File(root, "composer.json").writeText("""{"name":"acme/root","scripts":[]}""")

        assertEquals(emptyList(), ComposerPackages.parse(root))
        assertEquals(ComposerPackages.INVALID, ComposerPackages.fallbackTask(root))
    }
}
