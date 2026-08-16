package com.aspix2k.affected.build

import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CliUnittestConformanceTest {

    @Test
    fun `unittest zero test helper falls back to package consumers in one process`() = fixture("unittest") { root ->
        val alpha = PythonProjects.parse(root).single { it.id == "affected-unittest-alpha" }
        val helper = File(root, "packages/alpha/test_helpers.py")
        val adapter = unittestAdapter().toPath()
        val command = pythonCommands(
            root.path,
            listOf("${alpha.executionId}:test"),
            listOf(alpha),
            BuildChanges(
                files = listOf(helper.path),
                exactSelectionEligible = setOf(helper.path),
                comparedToBase = true,
            ),
            adapter,
        ).single()

        assertEquals(listOf("python", adapter.toString()), command.arguments.take(2))
        val execution = execute(root, command.arguments)

        assertTrue(execution.passed, execution.output)
        val consumerPid = File(root, "packages/alpha/consumer.marker").readText()
        val otherPid = File(root, "packages/alpha/other.marker").readText()
        assertEquals(consumerPid, otherPid)
        assertFalse(File(root, "packages/beta/beta.marker").exists())
    }

    @Test
    fun `unittest runs only the changed test modules`() = fixture("unittest") { root ->
        val alpha = PythonProjects.parse(root).single { it.id == "affected-unittest-alpha" }
        val changed = File(root, "packages/alpha/test_alpha.py")
        val adapter = unittestAdapter().toPath()
        val command = pythonCommands(
            root.path,
            listOf("${alpha.executionId}:test"),
            listOf(alpha),
            BuildChanges(
                files = listOf(changed.path),
                exactSelectionEligible = setOf(changed.path),
                comparedToBase = true,
            ),
            adapter,
        ).single()

        assertEquals(listOf("python", adapter.toString()), command.arguments.take(2))
        val execution = execute(root, command.arguments)

        assertTrue(execution.passed, execution.output)
        assertTrue(execution.output.contains("Affected unittest: exact (1 test file, 1 tests)"), execution.output)
        assertEquals("alpha", File(root, "packages/alpha/alpha.marker").readText())
        assertFalse(File(root, "packages/alpha/other.marker").exists())
        assertFalse(File(root, "packages/alpha/consumer.marker").exists())
        assertFalse(File(root, "packages/beta/beta.marker").exists())
    }

    @Test
    fun `unittest runs selected modules from multiple packages in one command`() = fixture("unittest") { root ->
        val command = unittestCommand(
            root,
            File(root, "packages/alpha/test_alpha.py"),
            File(root, "packages/beta/test_beta.py"),
        )

        val execution = execute(root, command.arguments)

        assertTrue(execution.passed, execution.output)
        assertTrue(execution.output.contains("Affected unittest: exact (2 test files, 2 tests)"), execution.output)
        assertTrue(File(root, "packages/alpha/alpha.marker").exists())
        assertTrue(File(root, "packages/beta/beta.marker").exists())
        assertFalse(File(root, "packages/alpha/other.marker").exists())
        assertFalse(File(root, "packages/alpha/consumer.marker").exists())
    }

    @Test
    fun `unittest mixed real and zero test modules widen before execution`() = fixture("unittest") { root ->
        val execution = execute(
            root,
            unittestCommand(
                root,
                File(root, "packages/alpha/test_alpha.py"),
                File(root, "packages/alpha/test_helpers.py"),
            ).arguments,
        )

        assertTrue(execution.passed, execution.output)
        assertTrue(execution.output.contains("Affected unittest: full fallback"), execution.output)
        assertTrue(File(root, "packages/alpha/alpha.marker").exists())
        assertTrue(File(root, "packages/alpha/consumer.marker").exists())
        assertTrue(File(root, "packages/alpha/other.marker").exists())
        assertFalse(File(root, "packages/beta/beta.marker").exists())
    }

    @Test
    fun `unittest import errors widen and remain failures after other tests run`() = fixture("unittest") { root ->
        val broken = File(root, "packages/alpha/test_broken.py").apply {
            writeText("raise RuntimeError('broken import')\n")
        }

        val execution = execute(root, unittestCommand(root, broken).arguments)

        assertTrue(execution.completed, execution.output)
        assertFalse(execution.passed, execution.output)
        assertTrue(execution.output.contains("Affected unittest: full fallback (import)"), execution.output)
        assertTrue(File(root, "packages/alpha/consumer.marker").exists())
        assertTrue(File(root, "packages/alpha/other.marker").exists())
        assertFalse(File(root, "packages/beta/beta.marker").exists())
    }

    @Test
    fun `unittest skipped selected tests stay exact`() = fixture("unittest") { root ->
        val skipped = File(root, "packages/alpha/test_skipped.py").apply {
            writeText(
                """
                import unittest

                @unittest.skip("fixture")
                class SkippedTest(unittest.TestCase):
                    def test_skipped(self):
                        raise AssertionError("must stay skipped")
                """.trimIndent() + "\n",
            )
        }

        val execution = execute(root, unittestCommand(root, skipped).arguments)

        assertTrue(execution.passed, execution.output)
        assertTrue(execution.output.contains("Affected unittest: exact (1 test file, 1 tests)"), execution.output)
        assertFalse(File(root, "packages/alpha/alpha.marker").exists())
        assertFalse(File(root, "packages/alpha/other.marker").exists())
    }

    @Test
    fun `unittest module load hooks widen to package discovery`() = fixture("unittest") { root ->
        val hooked = File(root, "packages/alpha/test_hooked.py").apply {
            writeText(
                """
                import unittest

                class HookedTest(unittest.TestCase):
                    def test_hooked(self):
                        pass

                def load_tests(loader, tests, pattern):
                    return tests
                """.trimIndent() + "\n",
            )
        }

        val execution = execute(root, unittestCommand(root, hooked).arguments)

        assertTrue(execution.passed, execution.output)
        assertTrue(execution.output.contains("Affected unittest: full fallback (module)"), execution.output)
        assertTrue(File(root, "packages/alpha/consumer.marker").exists())
        assertTrue(File(root, "packages/alpha/other.marker").exists())
    }

    @Test
    fun `unittest package load hooks widen to their full custom suite`() = fixture("unittest") { root ->
        File(root, "packages/alpha/__init__.py").writeText(
            """
            def load_tests(loader, tests, pattern):
                return loader.loadTestsFromNames([
                    "packages.alpha.test_consumer.ConsumerTest",
                    "packages.alpha.test_other.OtherTest",
                ])
            """.trimIndent() + "\n",
        )

        val execution = execute(
            root,
            unittestCommand(root, File(root, "packages/alpha/test_alpha.py")).arguments,
        )

        assertTrue(execution.passed, execution.output)
        assertTrue(execution.output.contains("Affected unittest: full fallback (package-load-tests)"), execution.output)
        assertTrue(File(root, "packages/alpha/consumer.marker").exists())
        assertTrue(File(root, "packages/alpha/other.marker").exists())
        assertFalse(File(root, "packages/alpha/alpha.marker").exists())
    }

    @Test
    fun `unittest SystemExit zero from a package initializer remains a failure`() = fixture("unittest") { root ->
        File(root, "packages/alpha/__init__.py").writeText("raise SystemExit(0)\n")

        val execution = execute(
            root,
            unittestCommand(root, File(root, "packages/alpha/test_alpha.py")).arguments,
        )

        assertTrue(execution.completed, execution.output)
        assertFalse(execution.passed, execution.output)
        assertTrue(execution.output.contains("Affected unittest: full fallback (package-import)"), execution.output)
        assertFalse(File(root, "packages/alpha/alpha.marker").exists())
        assertFalse(File(root, "packages/alpha/other.marker").exists())
    }

    @Test
    fun `unittest content drift from a test to a helper widens at process start`() = fixture("unittest") { root ->
        val selected = File(root, "packages/alpha/test_alpha.py")
        val command = unittestCommand(root, selected)
        selected.writeText("VALUE = 'drifted'\n")

        val execution = execute(root, command.arguments)

        assertTrue(execution.passed, execution.output)
        assertTrue(execution.output.contains("Affected unittest: full fallback"), execution.output)
        assertTrue(File(root, "packages/alpha/consumer.marker").exists())
        assertTrue(File(root, "packages/alpha/other.marker").exists())
    }

    @Test
    fun `unittest never executes stale timestamp bytecode`() = fixture("unittest") { root ->
        val selected = File(root, "packages/alpha/test_cache.py")
        val stale = File(root, "packages/alpha/stale.marker")
        val fresh = File(root, "packages/alpha/fresh.marker")
        val oldSource = cachedTestSource("stale.marker", "old")
        val newSource = cachedTestSource("fresh.marker", "new")
        assertEquals(oldSource.length, newSource.length)
        selected.writeText(oldSource)
        val modified = Files.getLastModifiedTime(selected.toPath())

        val first = execute(root, listOf("python", "-m", "unittest", "packages.alpha.test_cache"))

        assertTrue(first.passed, first.output)
        assertTrue(stale.exists(), first.output)
        assertTrue(File(root, "packages/alpha/__pycache__").isDirectory)
        stale.delete()
        selected.writeText(newSource)
        Files.setLastModifiedTime(selected.toPath(), modified)
        val command = unittestCommand(root, selected)

        val second = execute(root, command.arguments)

        assertTrue(second.passed, second.output)
        assertTrue(fresh.exists(), second.output)
        assertFalse(stale.exists(), second.output)
    }

    @Test
    fun `unittest symlink drift fails closed without executing the linked test`() = fixture("unittest") { root ->
        val selected = File(root, "packages/alpha/test_alpha.py")
        val command = unittestCommand(root, selected)
        val outside = createTempDirectory("affected-unittest-outside").toFile()
        val sentinel = File(outside, "outside.marker")
        val linked = File(outside, "test_linked.py").apply {
            writeText(
                """
                import unittest
                from pathlib import Path

                class LinkedTest(unittest.TestCase):
                    def test_linked(self):
                        Path('${sentinel.invariantSeparatorsPath}').write_text('unsafe', encoding='utf-8')
                """.trimIndent() + "\n",
            )
        }
        selected.delete()
        Files.createSymbolicLink(selected.toPath(), linked.toPath())

        try {
            val execution = execute(root, command.arguments)

            assertTrue(execution.completed, execution.output)
            assertFalse(execution.passed, execution.output)
            assertFalse(sentinel.exists(), execution.output)
        } finally {
            outside.deleteRecursively()
        }
    }

    @Test
    fun `unittest rejects a linked package initializer before import`() = fixture("unittest") { root ->
        val outside = createTempDirectory("affected-unittest-init-link").toFile()
        val sentinel = File(outside, "outside.marker")
        val linked = File(outside, "init.py").apply {
            writeText(
                "from pathlib import Path\nPath('${sentinel.invariantSeparatorsPath}').write_text('unsafe')\n",
            )
        }
        val packageInit = File(root, "packages/__init__.py")
        packageInit.delete()
        Files.createSymbolicLink(packageInit.toPath(), linked.toPath())

        try {
            val execution = execute(
                root,
                unittestCommand(root, File(root, "packages/alpha/test_alpha.py")).arguments,
            )

            assertTrue(execution.completed, execution.output)
            assertFalse(execution.passed, execution.output)
            assertFalse(sentinel.exists(), execution.output)
        } finally {
            outside.deleteRecursively()
        }
    }

    @Test
    fun `unittest rejects a dangling package initializer`() = fixture("unittest") { root ->
        val packageInit = File(root, "packages/__init__.py")
        packageInit.delete()
        Files.createSymbolicLink(packageInit.toPath(), packageInit.toPath().resolveSibling("missing-init.py"))

        val execution = execute(
            root,
            unittestCommand(root, File(root, "packages/alpha/test_alpha.py")).arguments,
        )

        assertTrue(execution.completed, execution.output)
        assertFalse(execution.passed, execution.output)
        assertFalse(File(root, "packages/alpha/alpha.marker").exists())
        assertFalse(File(root, "packages/alpha/other.marker").exists())
    }

    @Test
    fun `unittest rejects namespace ancestors before an external package can shadow them`() =
        fixture("unittest") { root ->
            val outside = createTempDirectory("affected-unittest-shadow").toFile()
            val sentinel = File(outside, "outside.marker")
            File(root, "packages/__init__.py").delete()
            File(outside, "packages/__init__.py").apply {
                parentFile.mkdirs()
                writeText(
                    "from pathlib import Path\nPath('${sentinel.invariantSeparatorsPath}').write_text('unsafe')\n",
                )
            }

            try {
                val execution = execute(
                    root,
                    unittestCommand(root, File(root, "packages/alpha/test_alpha.py")).arguments,
                    mapOf("PYTHONPATH" to outside.path),
                )

                assertTrue(execution.completed, execution.output)
                assertFalse(execution.passed, execution.output)
                assertFalse(sentinel.exists(), execution.output)
            } finally {
                outside.deleteRecursively()
            }
        }

    @Test
    fun `unittest helper only packages fail after full discovery collects zero tests`() = fixture("unittest") { root ->
        val packageRoot = File(root, "packages/zero").apply { mkdirs() }
        File(packageRoot, "__init__.py").writeText("")
        File(packageRoot, "pyproject.toml").writeText(
            "[project]\nname = \"affected-unittest-zero\"\nversion = \"0.1.0\"\n",
        )
        val helper = File(packageRoot, "test_helpers.py").apply { writeText("VALUE = 1\n") }

        val execution = execute(root, unittestCommand(root, helper).arguments)

        assertTrue(execution.completed, execution.output)
        assertFalse(execution.passed, execution.output)
        assertTrue(execution.output.contains("Affected unittest: no tests collected"), execution.output)
    }

    @Test
    fun `unittest rejects overlapping package roots instead of executing tests twice`() = fixture("unittest") { root ->
        val alpha = PythonProjects.parse(root).single { it.id == "affected-unittest-alpha" }
        val rootModule = BuildModule(
            id = "affected-unittest-root",
            root = root.path,
            contentRoots = listOf(root.path),
            testTask = PythonProjects.TEST,
            compileTask = null,
            hasTests = true,
            executionId = "root",
        )
        val selected = File(root, "packages/alpha/test_alpha.py")
        val planned = BuildChanges(
            files = listOf(selected.path),
            exactSelectionEligible = setOf(selected.path),
            comparedToBase = true,
        )
        val command = pythonDeferredCommands(
            root.path,
            listOf("${rootModule.executionId}:test", "${alpha.executionId}:test"),
            listOf(rootModule, alpha),
            planned,
            unittestAdapter().toPath(),
            PythonTestRunner.UNITTEST,
        ) { planned }.single().resolve()!!

        val execution = execute(root, command.arguments)

        assertTrue(execution.completed, execution.output)
        assertFalse(execution.passed, execution.output)
        assertTrue(
            execution.output.contains("Affected unittest: invalid context (overlapping-packages)"),
            execution.output,
        )
        assertFalse(File(root, "packages/alpha/alpha.marker").exists())
        assertFalse(File(root, "packages/alpha/other.marker").exists())
    }

    @Test
    fun `unittest exact failures stay exact and do not run unrelated modules`() = fixture("unittest") { root ->
        val failing = File(root, "packages/alpha/test_failing.py").apply {
            writeText(
                """
                import unittest
                from pathlib import Path

                class FailingTest(unittest.TestCase):
                    def test_failing(self):
                        Path(__file__).with_name("failing.marker").write_text("failed", encoding="utf-8")
                        self.fail("requested unittest failure")
                """.trimIndent() + "\n",
            )
        }

        val execution = execute(root, unittestCommand(root, failing).arguments)

        assertTrue(execution.completed, execution.output)
        assertFalse(execution.passed, execution.output)
        assertTrue(execution.output.contains("Affected unittest: exact"), execution.output)
        assertTrue(File(root, "packages/alpha/failing.marker").exists())
        assertFalse(File(root, "packages/alpha/alpha.marker").exists())
        assertFalse(File(root, "packages/alpha/other.marker").exists())
    }

    @Test
    fun `unittest imported test cases do not prove selected file ownership`() = fixture("unittest") { root ->
        val imported = File(root, "packages/alpha/test_imported.py").apply {
            writeText("from .test_consumer import ConsumerTest\n")
        }

        val execution = execute(root, unittestCommand(root, imported).arguments)

        assertTrue(execution.passed, execution.output)
        assertTrue(
            execution.output.contains("Affected unittest: full fallback (zero-or-imported-tests)"),
            execution.output,
        )
        assertTrue(File(root, "packages/alpha/consumer.marker").exists())
        assertTrue(File(root, "packages/alpha/other.marker").exists())
    }

    private fun fixture(name: String, block: (File) -> Unit) {
        assumeTrue(System.getProperty("affected.cliConformance") == "true")
        val source = File(fixtureRoot(), name)
        assertTrue(source.isDirectory, "Missing CLI conformance fixture: $source")
        val target = createTempDirectory("affected-cli-$name").toFile()
        try {
            assertTrue(source.copyRecursively(target, overwrite = true), "Could not copy $source")
            block(target)
        } finally {
            target.deleteRecursively()
        }
    }

    private fun fixtureRoot(): File = CliConformanceRepository.configured.fixturesRoot()

    private fun unittestAdapter(): File =
        CliConformanceRepository.configured.repositoryFile("core/src/main/python/affected_unittest.py")

    private fun unittestCommand(root: File, vararg changed: File): CliCommand {
        val modules = PythonProjects.parse(root)
        val owners = changed.map { file ->
            modules.filter { module ->
                file.toPath().toAbsolutePath().normalize().startsWith(File(module.contentRoots.single()).toPath())
            }.maxBy { it.contentRoots.single().length }
        }.distinct()
        return pythonCommands(
            root.path,
            owners.map { "${it.executionId}:test" },
            owners,
            BuildChanges(
                files = changed.map(File::getPath),
                exactSelectionEligible = changed.mapTo(LinkedHashSet(), File::getPath),
                comparedToBase = true,
            ),
            unittestAdapter().toPath(),
        ).single()
    }

    private fun cachedTestSource(marker: String, value: String): String =
        """
        import unittest
        from pathlib import Path

        class CacheTest(unittest.TestCase):
            def test_cache(self):
                Path(__file__).with_name("$marker").write_text("$value", encoding="utf-8")
        """.trimIndent() + "\n"

    private fun execute(
        directory: File,
        arguments: List<String>,
        environment: Map<String, String> = emptyMap(),
    ): Execution {
        val output = File.createTempFile("affected-cli-output", ".log")
        try {
            val builder = ProcessBuilder(arguments)
                .directory(directory)
                .redirectErrorStream(true)
                .redirectOutput(output)
            builder.environment().putAll(environment)
            val process = builder.start()
            val completed = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!completed) process.destroyForcibly().waitFor(10, TimeUnit.SECONDS)
            val text = output.readText()
            assertTrue(completed, "Timed out: ${arguments.joinToString(" ")}\n$text")
            return Execution(completed, process.exitValue(), text)
        } finally {
            output.delete()
        }
    }

    private companion object {
        const val COMMAND_TIMEOUT_SECONDS = 180L
    }

    private data class Execution(val completed: Boolean, val exitCode: Int, val output: String) {
        val passed: Boolean get() = completed && exitCode == 0
    }
}
