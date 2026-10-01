package com.aspix2k.affected.build

import com.aspix2k.affected.build.php.ComposerPackages
import com.aspix2k.affected.build.php.composerCommands
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CliPestConformanceTest {

    @Test
    fun `Pest 5 runs only the changed test files`() = fixture("pest") { root ->
        val lock = root.resolve("composer.lock")
        val locked = lock.readBytes()
        execute(root, listOf("composer", "install", "--no-interaction", "--no-progress", "--no-scripts"))
        val pestTemp = File(root, "vendor/pestphp/pest/.temp")
        assertTrue(pestTemp.mkdirs() || pestTemp.isDirectory)
        assertTrue(locked.contentEquals(lock.readBytes()))
        val alpha = ComposerPackages.parse(root).single { it.id == "affected/fixture-pest-alpha" }
        val changed = File(root, "packages/alpha/tests/AlphaTest.php")
        val command = composerCommands(
            root.path,
            listOf("${alpha.executionId}:${alpha.testTask}"),
            listOf(alpha),
            BuildChanges(
                files = listOf(changed.path),
                exactSelectionEligible = setOf(changed.path),
                comparedToBase = true,
            ),
        ).single()

        assertEquals(listOf("./packages/alpha/tests/AlphaTest.php"), command.arguments.takeLast(1))
        execute(root, command.arguments)

        assertEquals("alpha", File(root, "packages/alpha/alpha.marker").readText())
        assertEquals(listOf("first", "second"), File(root, "packages/alpha/dataset.marker").readLines())
        assertFalse(File(root, "packages/alpha/phpunit.marker").exists())
        assertFalse(File(root, "packages/beta/beta.marker").exists())
    }

    @Test
    fun `Pest 5 runs tests that consume a changed dataset`() = fixture("pest") { root ->
        val lock = root.resolve("composer.lock")
        val locked = lock.readBytes()
        execute(root, listOf("composer", "install", "--no-interaction", "--no-progress", "--no-scripts"))
        val pestTemp = File(root, "vendor/pestphp/pest/.temp")
        assertTrue(pestTemp.mkdirs() || pestTemp.isDirectory)
        assertTrue(locked.contentEquals(lock.readBytes()))
        File(root, "packages/alpha/tests/Datasets").mkdirs()
        val dataset = File(root, "packages/alpha/tests/Datasets/extra.php").apply {
            writeText("<?php\ndataset('extra colors', ['red']);\n")
        }
        File(root, "packages/alpha/tests/ExtraTest.php").writeText(
            """
            <?php
            require_once __DIR__ . '/Datasets/extra.php';
            test('extra dataset', function (string ${'$'}color): void {
                expect(${'$'}color)->not->toBeEmpty();
                file_put_contents(__DIR__ . '/../extra.marker', ${'$'}color);
            })->with('extra colors');
            """.trimIndent(),
        )
        val alpha = ComposerPackages.parse(root).single { it.id == "affected/fixture-pest-alpha" }
        val command = composerCommands(
            root.path,
            listOf("${alpha.executionId}:${alpha.testTask}"),
            listOf(alpha),
            BuildChanges(
                files = listOf(dataset.path),
                exactSelectionEligible = setOf(dataset.path),
                comparedToBase = true,
            ),
        ).single()

        assertEquals(
            listOf(
                "./packages/alpha/tests/Datasets/extra.php",
                "./packages/alpha/tests/ExtraTest.php",
            ),
            command.arguments.takeLast(2),
        )
        execute(root, command.arguments)

        assertEquals("red", File(root, "packages/alpha/extra.marker").readText())
        assertFalse(File(root, "packages/alpha/alpha.marker").exists())
        assertFalse(File(root, "packages/alpha/phpunit.marker").exists())
    }

    @Test
    fun `Pest 5 runs tests that import a changed PSR-4 class`() = fixture("pest") { root ->
        val lock = root.resolve("composer.lock")
        val locked = lock.readBytes()
        execute(root, listOf("composer", "install", "--no-interaction", "--no-progress", "--no-scripts"))
        val pestTemp = File(root, "vendor/pestphp/pest/.temp")
        assertTrue(pestTemp.mkdirs() || pestTemp.isDirectory)
        assertTrue(locked.contentEquals(lock.readBytes()))
        val alpha = ComposerPackages.parse(root).single { it.id == "affected/fixture-pest-alpha" }
        val changed = File(root, "packages/alpha/src/Alpha.php")
        val command = composerCommands(
            root.path,
            listOf("${alpha.executionId}:${alpha.testTask}"),
            listOf(alpha),
            BuildChanges(
                files = listOf(changed.path),
                exactSelectionEligible = setOf(changed.path),
                comparedToBase = true,
            ),
        ).single()

        assertEquals(listOf("./packages/alpha/tests/AlphaTest.php"), command.arguments.takeLast(1))
        assertEquals("alpha package", command.arguments[command.arguments.indexOf("--filter") + 1])
        execute(root, command.arguments)

        assertEquals("alpha", File(root, "packages/alpha/alpha.marker").readText())
        assertFalse(File(root, "packages/alpha/dataset.marker").exists())
        assertFalse(File(root, "packages/alpha/phpunit.marker").exists())
        assertFalse(File(root, "packages/beta/beta.marker").exists())
    }

    private fun fixture(name: String, block: (File) -> Unit) {
        assumeTrue(System.getProperty("affected.cliConformance") == "true")
        val source = File(fixtureRoot(), name)
        assertTrue(source.isDirectory, "Missing CLI conformance fixture: $source")
        OwnedSandbox.use("affected-cli-$name") { sandbox ->
            assertTrue(source.copyRecursively(sandbox.root, overwrite = true), "Could not copy $source")
            block(sandbox.root)
        }
    }

    private fun fixtureRoot(): File = CliConformanceRepository.configured.fixturesRoot()

    private fun execute(directory: File, arguments: List<String>) {
        NativeProcessRunner.execute(arguments, directory, COMMAND_TIMEOUT_SECONDS)
    }

    private companion object {
        const val COMMAND_TIMEOUT_SECONDS = 180L
    }
}
