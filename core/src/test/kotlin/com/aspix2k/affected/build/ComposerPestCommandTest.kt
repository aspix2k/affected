package com.aspix2k.affected.build

import java.io.File
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ComposerPestCommandTest {

    @Test
    fun `Pest and PHPUnit packages share one ordered Composer batch`() {
        val root = createTempDirectory("composer-pest-commands").toFile()
        File(root, "packages/pest-a/tests").mkdirs()
        File(root, "packages/pest-a/tests/A.php").writeText("<?php\n")
        File(root, "packages/pest-b/tests").mkdirs()
        File(root, "packages/pest-b/tests/B.php").writeText("<?php\n")
        val modules = listOf(
            module(root, "pest-a", "packages/pest-a", ComposerPackages.PEST),
            module(root, "pest-b", "packages/pest-b", ComposerPackages.PEST),
            module(root, "phpunit", "packages/phpunit", ComposerPackages.TEST),
        )

        val commands = composerCommands(
            root.path,
            listOf(
                "pest-b:${ComposerPackages.PEST}",
                "pest-a:${ComposerPackages.PEST}",
                "phpunit:${ComposerPackages.TEST}",
            ),
            modules,
        )

        assertEquals(
            listOf(
                "php",
                "vendor/bin/pest",
                "--cache-directory",
                File(root, ".affected/pest-cache").invariantSeparatorsPath,
                "--do-not-cache-result",
                "--no-output",
                "./packages/pest-a/tests",
                "./packages/pest-b/tests",
            ),
            commands.first().arguments,
        )
        assertEquals(listOf("php", "vendor/bin/phpunit", "./packages/phpunit"), commands.last().arguments)
    }

    @Test
    fun `an unproved Pest graph does not run a partial native command`() {
        val module = module(File("/repo"), "root", ".", ComposerPackages.INVALID)

        assertEquals(emptyList(), composerCommands("/repo", listOf(".:${ComposerPackages.INVALID}"), listOf(module)))
    }

    @Test
    fun `a symlink inside a selected Pest suite invalidates the batch`() {
        val root = createTempDirectory("composer-pest-symlink").toFile()
        val tests = File(root, "package/tests").apply { mkdirs() }
        val external = File(root, "external.php").apply { writeText("<?php\n") }
        runCatching { Files.createSymbolicLink(File(tests, "ExternalTest.php").toPath(), external.toPath()) }
            .getOrElse { return }

        assertEquals(
            emptyList(),
            composerCommands(
                root.path,
                listOf("package:${ComposerPackages.PEST}"),
                listOf(module(root, "package", "package", ComposerPackages.PEST)),
            ),
        )
    }

    @Test
    fun `a symlinked Pest package root invalidates the batch`() {
        val root = createTempDirectory("composer-pest-package-link").toFile()
        val external = createTempDirectory("composer-pest-external-package").toFile()
        File(external, "tests").mkdirs()
        File(external, "tests/ExternalTest.php").writeText("<?php\n")
        val packagePath = File(root, "package").toPath()
        runCatching { Files.createSymbolicLink(packagePath, external.toPath()) }.getOrElse { return }

        assertEquals(
            emptyList(),
            composerCommands(
                root.path,
                listOf("package:${ComposerPackages.PEST}"),
                listOf(module(root, "package", "package", ComposerPackages.PEST)),
            ),
        )
    }

    @Test
    fun `a Pest batch bypasses PHPUnit selective state`() {
        assertFalse(composerUsesPhpunitSelection(listOf("phpunit:test", "pest:${ComposerPackages.PEST}")))
        assertTrue(composerUsesPhpunitSelection(listOf("phpunit:test")))
    }

    @Test
    fun `only Pest global boot files require the workspace`() {
        val root = createTempDirectory("composer-pest-global-files").toFile()
        val global = listOf(
            "tests/Pest.php",
            "tests/Expectations.php",
            "tests/Helpers/Custom.php",
            "tests/Datasets.php",
            "tests/Feature/Datasets.php",
            "tests/Feature/Datasets/users.php",
        )

        assertTrue(global.all { pestWorkspaceChange(root.path, File(root, it).path) })
        assertFalse(pestWorkspaceChange(root.path, File(root, "tests/FeatureTest.php").path))
        assertFalse(pestWorkspaceChange(root.path, File(root, "packages/alpha/tests/Pest.php").path))
    }

    private fun module(root: File, name: String, path: String, task: String) = BuildModule(
        name,
        root.path,
        listOf(File(root, path).path),
        task,
        null,
        true,
        executionId = if (path == ".") "." else name,
    )
}
