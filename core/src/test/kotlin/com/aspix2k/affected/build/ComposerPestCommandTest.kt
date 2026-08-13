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
                "--configuration",
                File(root, ".affected/pest-phpunit.xml").invariantSeparatorsPath,
                "./packages/pest-a/tests",
                "./packages/pest-b/tests",
            ),
            commands.first().arguments,
        )
        val generated = File(root, ".affected/pest-phpunit.xml")
        assertTrue(generated.isFile)
        assertTrue(generated.readText().contains("bootstrap=\"../vendor/autoload.php\""))
        assertEquals(listOf("php", "vendor/bin/phpunit", "./packages/phpunit"), commands.last().arguments)
    }

    @Test
    fun `a project phpunit xml is passed before Pest suite paths`() {
        val root = createTempDirectory("composer-pest-phpunit-xml").toFile()
        File(root, "package/tests").mkdirs()
        File(root, "package/tests/A.php").writeText("<?php\n")
        val xml = File(root, "phpunit.xml").apply { writeText("<phpunit/>") }

        val commands = composerCommands(
            root.path,
            listOf("package:${ComposerPackages.PEST}"),
            listOf(module(root, "package", "package", ComposerPackages.PEST)),
        )

        assertEquals(
            listOf(
                "php",
                "vendor/bin/pest",
                "--cache-directory",
                File(root, ".affected/pest-cache").invariantSeparatorsPath,
                "--do-not-cache-result",
                "--no-output",
                "--configuration",
                xml.invariantSeparatorsPath,
                "./package/tests",
            ),
            commands.single().arguments,
        )
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
    fun `changed Pest test files become native pest paths`() {
        val root = createTempDirectory("composer-pest-exact-files").toFile()
        File(root, "package/tests").mkdirs()
        val changed = File(root, "package/tests/FeatureTest.php").apply { writeText("<?php\n") }
        File(root, "package/tests/OtherTest.php").writeText("<?php\n")

        val commands = composerCommands(
            root.path,
            listOf("package:${ComposerPackages.PEST}"),
            listOf(module(root, "package", "package", ComposerPackages.PEST)),
            changes(changed),
        )

        assertEquals(listOf("./package/tests/FeatureTest.php"), commands.single().arguments.takeLast(1))
    }

    @Test
    fun `a production change keeps the full Pest suite`() {
        val root = createTempDirectory("composer-pest-src-full").toFile()
        File(root, "package/tests").mkdirs()
        File(root, "package/tests/FeatureTest.php").writeText("<?php\n")
        val source = File(root, "package/src/Service.php").apply {
            parentFile.mkdirs()
            writeText("<?php\n")
        }

        val commands = composerCommands(
            root.path,
            listOf("package:${ComposerPackages.PEST}"),
            listOf(module(root, "package", "package", ComposerPackages.PEST)),
            changes(source),
        )

        assertEquals(listOf("./package/tests"), commands.single().arguments.takeLast(1))
    }

    @Test
    fun `a named dataset change selects the Pest files that use it`() {
        val root = createTempDirectory("composer-pest-dataset-exact").toFile()
        File(root, "package/tests/Datasets").mkdirs()
        File(root, "package/tests/UsersTest.php").writeText(
            """
            <?php
            test('users', function (string ${'$'}user): void {
                expect(${'$'}user)->not->toBeEmpty();
            })->with('users');
            """.trimIndent(),
        )
        File(root, "package/tests/OtherTest.php").writeText(
            "<?php\ntest('other', fn () => expect(true)->toBeTrue());\n",
        )
        val dataset = File(root, "package/tests/Datasets/users.php").apply {
            writeText("<?php\ndataset('users', ['ada', 'linus']);\n")
        }

        val commands = composerCommands(
            root.path,
            listOf("package:${ComposerPackages.PEST}"),
            listOf(module(root, "package", "package", ComposerPackages.PEST)),
            changes(dataset),
        )

        assertEquals(
            listOf("./package/tests/Datasets/users.php", "./package/tests/UsersTest.php"),
            commands.single().arguments.takeLast(2),
        )
    }

    @Test
    fun `an unused or boot Pest dataset keeps the full suite`() {
        val root = createTempDirectory("composer-pest-dataset-full").toFile()
        File(root, "package/tests/Datasets").mkdirs()
        File(root, "package/tests/FeatureTest.php").writeText(
            "<?php\ntest('plain', fn () => expect(true)->toBeTrue());\n",
        )
        val unused = File(root, "package/tests/Datasets/users.php").apply {
            writeText("<?php\ndataset('users', ['ada']);\n")
        }
        val boot = File(root, "package/tests/Pest.php").apply { writeText("<?php\n") }

        for (file in listOf(unused, boot)) {
            val commands = composerCommands(
                root.path,
                listOf("package:${ComposerPackages.PEST}"),
                listOf(module(root, "package", "package", ComposerPackages.PEST)),
                changes(file),
            )
            assertEquals(listOf("./package/tests"), commands.single().arguments.takeLast(1), file.name)
        }
    }

    @Test
    fun `changed Pest test files in every planned suite stay exact`() {
        val root = createTempDirectory("composer-pest-multi-exact").toFile()
        File(root, "packages/a/tests").mkdirs()
        File(root, "packages/b/tests").mkdirs()
        val first = File(root, "packages/a/tests/A.php").apply { writeText("<?php\n") }
        val second = File(root, "packages/b/tests/B.php").apply { writeText("<?php\n") }

        val commands = composerCommands(
            root.path,
            listOf("a:${ComposerPackages.PEST}", "b:${ComposerPackages.PEST}"),
            listOf(
                module(root, "a", "packages/a", ComposerPackages.PEST),
                module(root, "b", "packages/b", ComposerPackages.PEST),
            ),
            changes(first, second),
        )

        assertEquals(
            listOf("./packages/a/tests/A.php", "./packages/b/tests/B.php"),
            commands.single().arguments.takeLast(2),
        )
    }

    @Test
    fun `an unproved planned suite keeps every Pest suite`() {
        val root = createTempDirectory("composer-pest-partial-plan").toFile()
        File(root, "packages/a/tests").mkdirs()
        File(root, "packages/b/tests").mkdirs()
        val changed = File(root, "packages/a/tests/A.php").apply { writeText("<?php\n") }
        File(root, "packages/b/tests/B.php").writeText("<?php\n")

        val commands = composerCommands(
            root.path,
            listOf("a:${ComposerPackages.PEST}", "b:${ComposerPackages.PEST}"),
            listOf(
                module(root, "a", "packages/a", ComposerPackages.PEST),
                module(root, "b", "packages/b", ComposerPackages.PEST),
            ),
            changes(changed),
        )

        assertEquals(
            listOf("./packages/a/tests", "./packages/b/tests"),
            commands.single().arguments.takeLast(2),
        )
    }

    @Test
    fun `changes without a merge base keep the full Pest suite`() {
        val root = createTempDirectory("composer-pest-no-base").toFile()
        File(root, "package/tests").mkdirs()
        val changed = File(root, "package/tests/FeatureTest.php").apply { writeText("<?php\n") }

        val commands = composerCommands(
            root.path,
            listOf("package:${ComposerPackages.PEST}"),
            listOf(module(root, "package", "package", ComposerPackages.PEST)),
            BuildChanges(listOf(changed.path), exactSelectionEligible = emptySet(), comparedToBase = false),
        )

        assertEquals(listOf("./package/tests"), commands.single().arguments.takeLast(1))
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

    private fun changes(vararg files: File) = BuildChanges(
        files = files.map(File::getPath),
        exactSelectionEligible = files.mapTo(LinkedHashSet(), File::getPath),
        comparedToBase = true,
    )

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
