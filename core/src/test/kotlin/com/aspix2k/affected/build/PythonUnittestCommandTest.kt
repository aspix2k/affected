package com.aspix2k.affected.build

import com.google.gson.JsonParser
import com.intellij.openapi.progress.ProcessCanceledException
import kotlinx.coroutines.CancellationException
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import java.util.Base64
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PythonUnittestCommandTest {

    @Test
    fun `a case-normalized project root keeps exact unittest selection`() {
        val temporary = createTempDirectory("python-unittest-root-alias").toFile()
        File(temporary, "ProjectRoot").mkdirs()
        val root = File(temporary, "projectroot")
        assumeTrue(root.isDirectory)
        File(root, "pyproject.toml").writeText("[project]\nname = \"app\"\n")
        val selected = File(root, "packages/a/test_alpha.py").apply {
            parentFile.mkdirs()
            writeText("import unittest\nclass AlphaTest(unittest.TestCase):\n    pass\n")
        }
        val adapter = File(temporary, "affected_unittest.py").apply { writeText("# adapter\n") }.toPath()

        try {
            val arguments = pythonCommands(
                root.path,
                listOf("pkg-a:test"),
                modules(root, "pkg-a", "packages/a"),
                changes(selected),
                adapter,
            ).single().arguments

            assertExactUnittestSelection(arguments, adapter, "packages/a", "packages/a/test_alpha.py")
        } finally {
            assertTrue(temporary.deleteRecursively(), "Could not delete $temporary")
        }
    }

    @Test
    fun `a real-path selected file keeps exact unittest selection on a lexical root`() {
        val root = createTempDirectory("python-unittest-real-selected").toFile()
        File(root, "pyproject.toml").writeText("[project]\nname = \"app\"\n")
        val selected = File(root, "packages/a/test_alpha.py").apply {
            parentFile.mkdirs()
            writeText("import unittest\nclass AlphaTest(unittest.TestCase):\n    pass\n")
        }
        val adapter = File(root, "affected_unittest.py").apply { writeText("# adapter\n") }.toPath()
        val canonical = selected.toPath().toRealPath().toFile()

        val arguments = pythonCommands(
            root.path,
            listOf("pkg-a:test"),
            modules(root, "pkg-a", "packages/a"),
            changes(canonical),
            adapter,
        ).single().arguments

        assertExactUnittestSelection(arguments, adapter, "packages/a", "packages/a/test_alpha.py")
    }

    @Test
    fun `a test named helper is delegated with its package fallback context`() {
        val root = createTempDirectory("python-unittest-zero-test").toFile()
        File(root, "pyproject.toml").writeText("[project]\nname = \"app\"\n")
        val helper = File(root, "packages/a/test_helpers.py").apply {
            parentFile.mkdirs()
            writeText("VALUE = 1\n")
        }
        File(root, "packages/a/test_consumer.py").writeText(
            "import unittest\nclass ConsumerTest(unittest.TestCase):\n    pass\n",
        )
        val adapter = File(root, "affected_unittest.py").apply { writeText("# adapter\n") }.toPath()

        val arguments = pythonCommands(
            root.path,
            listOf("pkg-a:test"),
            modules(root, "pkg-a", "packages/a"),
            changes(helper),
            adapter,
        ).single().arguments

        assertEquals(listOf("python", adapter.toString()), arguments.take(2))
        assertEquals(3, arguments.size)
        val padding = "=".repeat((4 - arguments[2].length % 4) % 4)
        val context = JsonParser.parseString(
            Base64.getUrlDecoder().decode(arguments[2] + padding).toString(Charsets.UTF_8),
        ).asJsonObject
        assertEquals(1, context.get("schema").asInt)
        assertEquals(listOf("packages/a"), context.getAsJsonArray("packages").map { it.asString })
        assertEquals(listOf("packages/a/test_helpers.py"), context.getAsJsonArray("selected").map { it.asString })
    }

    @Test
    fun `deferred unittest selection keeps exact arguments while the change proof is current`() {
        val root = createTempDirectory("python-unittest-deferred-exact").toFile()
        File(root, "pyproject.toml").writeText("[project]\nname = \"app\"\n")
        val selected = File(root, "packages/a/test_alpha.py").apply {
            parentFile.mkdirs()
            writeText("import unittest\nclass AlphaTest(unittest.TestCase):\n    pass\n")
        }
        val adapter = File(root, "affected_unittest.py").apply { writeText("# adapter\n") }.toPath()
        val planned = changes(selected)
        val modules = modules(root, "pkg-a", "packages/a")

        val command = pythonDeferredCommands(
            root.path,
            listOf("pkg-a:test"),
            modules,
            planned,
            adapter,
        ) { planned }.single().resolve()

        assertEquals(
            pythonCommands(root.path, listOf("pkg-a:test"), modules, planned, adapter).single(),
            command,
        )
    }

    @Test
    fun `deferred unittest selection fails visibly when the test runner changes`() {
        val fixture = deferredFixture("python-unittest-runner-drift")
        val planned = changes(fixture.selected)
        val step = pythonDeferredCommands(
            fixture.root.path,
            listOf("pkg-a:test"),
            modules(fixture.root, "pkg-a", "packages/a"),
            planned,
            fixture.adapter,
        ) { planned }.single()
        File(fixture.root, "pytest.ini").writeText("[pytest]\n")

        val command = step.resolve()!!

        assertEquals(listOf("python", "-c", PYTHON_RUNNER_DRIFT_FAILURE), command.arguments)
    }

    @Test
    fun `deferred unittest selection fails visibly when its adapter disappears`() {
        val fixture = deferredFixture("python-unittest-adapter-drift")
        val planned = changes(fixture.selected)
        val step = pythonDeferredCommands(
            fixture.root.path,
            listOf("pkg-a:test"),
            modules(fixture.root, "pkg-a", "packages/a"),
            planned,
            fixture.adapter,
        ) { planned }.single()
        Files.delete(fixture.adapter)

        val command = step.resolve()!!

        assertEquals(listOf("python", "-c", UNITTEST_ADAPTER_DRIFT_FAILURE), command.arguments)
    }

    @Test
    fun `deferred unittest selection fails visibly when its adapter content changes`() {
        val fixture = deferredFixture("python-unittest-adapter-content-drift")
        val planned = changes(fixture.selected)
        val step = pythonDeferredCommands(
            fixture.root.path,
            listOf("pkg-a:test"),
            modules(fixture.root, "pkg-a", "packages/a"),
            planned,
            fixture.adapter,
        ) { planned }.single()
        fixture.adapter.toFile().writeText("raise SystemExit(0)\n")

        val command = step.resolve()!!

        assertEquals(listOf("python", "-c", UNITTEST_ADAPTER_DRIFT_FAILURE), command.arguments)
    }

    @Test
    fun `deferred unittest selection rechecks adapter drift after refreshing changes`() {
        val fixture = deferredFixture("python-unittest-adapter-refresh-drift")
        val planned = changes(fixture.selected)

        val command = pythonDeferredCommands(
            fixture.root.path,
            listOf("pkg-a:test"),
            modules(fixture.root, "pkg-a", "packages/a"),
            planned,
            fixture.adapter,
        ) {
            fixture.adapter.toFile().writeText("raise SystemExit(0)\n")
            planned
        }.single().resolve()!!

        assertEquals(listOf("python", "-c", UNITTEST_ADAPTER_DRIFT_FAILURE), command.arguments)
    }

    @Test
    fun `deferred unittest selection rechecks runner drift after refreshing changes`() {
        val fixture = deferredFixture("python-unittest-runner-refresh-drift")
        val planned = changes(fixture.selected)

        val command = pythonDeferredCommands(
            fixture.root.path,
            listOf("pkg-a:test"),
            modules(fixture.root, "pkg-a", "packages/a"),
            planned,
            fixture.adapter,
        ) {
            File(fixture.root, "pytest.ini").writeText("[pytest]\n")
            planned
        }.single().resolve()!!

        assertEquals(listOf("python", "-c", PYTHON_RUNNER_DRIFT_FAILURE), command.arguments)
    }

    @Test
    fun `deferred unittest selection preserves type checking in the same plan`() {
        val fixture = deferredFixture("python-unittest-deferred-typecheck")
        val planned = changes(fixture.selected)
        val steps = pythonDeferredCommands(
            fixture.root.path,
            listOf("pkg-a:typecheck", "pkg-a:test"),
            modules(fixture.root, "pkg-a", "packages/a"),
            planned,
            fixture.adapter,
        ) { planned }

        val commands = steps.map { requireNotNull(it.resolve()) }

        assertEquals(2, commands.size)
        assertEquals(listOf("python", "-m", "mypy", "packages/a"), commands.single { it.title == "mypy" }.arguments)
        assertEquals(
            listOf("python", fixture.adapter.toString()),
            commands.single { it.title == "unittest" }.arguments.take(2),
        )
    }

    @Test
    fun `a helper added after planning widens unittest selection in the adapter process`() {
        val root = createTempDirectory("python-unittest-deferred-helper").toFile()
        File(root, "pyproject.toml").writeText("[project]\nname = \"app\"\n")
        val selected = File(root, "packages/a/test_alpha.py").apply {
            parentFile.mkdirs()
            writeText("import unittest\nclass AlphaTest(unittest.TestCase):\n    pass\n")
        }
        val helper = File(root, "packages/a/helper.py").apply { writeText("VALUE = 1\n") }
        val adapter = File(root, "affected_unittest.py").apply { writeText("# adapter\n") }.toPath()
        val planned = changes(selected)

        val arguments = pythonDeferredCommands(
            root.path,
            listOf("pkg-a:test"),
            modules(root, "pkg-a", "packages/a"),
            planned,
            adapter,
        ) { changes(selected, helper) }.single().resolve()!!.arguments

        assertEquals(listOf("python", adapter.toString()), arguments.take(2))
        assertEquals(3, arguments.size)
        val padding = "=".repeat((4 - arguments[2].length % 4) % 4)
        val context = JsonParser.parseString(
            Base64.getUrlDecoder().decode(arguments[2] + padding).toString(Charsets.UTF_8),
        ).asJsonObject
        assertEquals(emptyList(), context.getAsJsonArray("selected").map { it.asString })
    }

    @Test
    fun `a full unittest plan does not refresh changes on the click path`() {
        val root = createTempDirectory("python-unittest-deferred-full").toFile()
        File(root, "pyproject.toml").writeText("[project]\nname = \"app\"\n")
        File(root, "packages/a/test_alpha.py").apply {
            parentFile.mkdirs()
            writeText("import unittest\nclass AlphaTest(unittest.TestCase):\n    pass\n")
        }
        val source = File(root, "packages/a/alpha.py").apply { writeText("VALUE = 1\n") }
        val adapter = File(root, "affected_unittest.py").apply { writeText("# adapter\n") }.toPath()
        val planned = changes(source)
        var refreshed = false

        val command = pythonDeferredCommands(
            root.path,
            listOf("pkg-a:test"),
            modules(root, "pkg-a", "packages/a"),
            planned,
            adapter,
        ) {
            refreshed = true
            planned
        }.single().resolve()

        assertFalse(refreshed)
        assertAdapterFullCommand(requireNotNull(command).arguments, adapter)
    }

    @Test
    fun `a full unittest plan fails visibly when the test runner changes without refreshing changes`() {
        val fixture = deferredFixture("python-unittest-full-runner-drift")
        val source = File(fixture.root, "packages/a/alpha.py").apply { writeText("VALUE = 1\n") }
        val planned = changes(source)
        var refreshed = false
        val step = pythonDeferredCommands(
            fixture.root.path,
            listOf("pkg-a:test"),
            modules(fixture.root, "pkg-a", "packages/a"),
            planned,
            fixture.adapter,
        ) {
            refreshed = true
            planned
        }.single()
        File(fixture.root, "pytest.ini").writeText("[pytest]\n")

        val command = step.resolve()!!

        assertFalse(refreshed)
        assertEquals(listOf("python", "-c", PYTHON_RUNNER_DRIFT_FAILURE), command.arguments)
    }

    @Test
    fun `an ordinary unittest revalidation failure widens in the adapter process`() {
        val fixture = deferredFixture("python-unittest-deferred-error")

        val command = pythonDeferredCommands(
            fixture.root.path,
            listOf("pkg-a:test"),
            modules(fixture.root, "pkg-a", "packages/a"),
            changes(fixture.selected),
            fixture.adapter,
        ) { error("inspection failed") }.single().resolve()!!

        assertAdapterFullCommand(command.arguments, fixture.adapter)
    }

    @Test
    fun `deferred unittest selection propagates coroutine cancellation`() {
        val fixture = deferredFixture("python-unittest-deferred-cancel")
        val cancellation = CancellationException("cancelled")

        assertSame(
            cancellation,
            assertFailsWith<CancellationException> {
                pythonDeferredCommands(
                    fixture.root.path,
                    listOf("pkg-a:test"),
                    modules(fixture.root, "pkg-a", "packages/a"),
                    changes(fixture.selected),
                    fixture.adapter,
                ) { throw cancellation }.single().resolve()
            },
        )
    }

    @Test
    fun `deferred unittest selection propagates IDE process cancellation`() {
        val fixture = deferredFixture("python-unittest-deferred-ide-cancel")
        val cancellation = ProcessCanceledException()

        assertSame(
            cancellation,
            assertFailsWith<ProcessCanceledException> {
                pythonDeferredCommands(
                    fixture.root.path,
                    listOf("pkg-a:test"),
                    modules(fixture.root, "pkg-a", "packages/a"),
                    changes(fixture.selected),
                    fixture.adapter,
                ) { throw cancellation }.single().resolve()
            },
        )
    }

    @Test
    fun `deferred unittest selection propagates interruption and restores its flag`() {
        val fixture = deferredFixture("python-unittest-deferred-interrupt")
        val interruption = InterruptedException("interrupted")

        try {
            assertSame(
                interruption,
                assertFailsWith<InterruptedException> {
                    pythonDeferredCommands(
                        fixture.root.path,
                        listOf("pkg-a:test"),
                        modules(fixture.root, "pkg-a", "packages/a"),
                        changes(fixture.selected),
                        fixture.adapter,
                    ) { throw interruption }.single().resolve()
                },
            )
            assertTrue(Thread.interrupted())
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `a missing unittest adapter keeps package discovery`() {
        val root = createTempDirectory("python-unittest-exact-files").toFile()
        File(root, "pyproject.toml").writeText("[project]\nname = \"app\"\n")
        val changed = File(root, "packages/a/test_alpha.py").apply {
            parentFile.mkdirs()
            writeText("import unittest\nclass AlphaTest(unittest.TestCase):\n    pass\n")
        }
        File(root, "packages/a/test_other.py").writeText(
            "import unittest\nclass OtherTest(unittest.TestCase):\n    pass\n",
        )
        val modules = modules(root, "pkg-a", "packages/a")

        val arguments = pythonCommands(
            root.path,
            listOf("pkg-a:test"),
            modules,
            changes(changed),
        ).single().arguments

        assertEquals(
            listOf("python", "-m", "unittest", "discover", "-s", "packages/a", "-t", "."),
            arguments,
        )
    }

    @Test
    fun `unittest projects resolve the unittest plugin adapter`() {
        val root = createTempDirectory("python-unittest-adapter-root").toFile()
        File(root, "test_alpha.py").writeText(
            "import unittest\nclass AlphaTest(unittest.TestCase):\n    pass\n",
        )
        val plugin = createTempDirectory("python-unittest-adapter-plugin").toFile()
        val pytest = File(plugin, "agent/affected-pytest.py").apply {
            parentFile.mkdirs()
            writeText("# pytest\n")
        }
        val unittest = File(plugin, "agent/affected-unittest.py").apply { writeText("# unittest\n") }
        val classPath = File(plugin, "lib/core.jar").apply {
            parentFile.mkdirs()
            writeText("jar\n")
        }

        assertEquals(unittest.toPath(), findPythonAdapter(root, classPath.toPath()))
        assertFalse(findPythonAdapter(root, classPath.toPath()) == pytest.toPath())
    }

    @Test
    fun `a generated unittest module keeps package discovery`() {
        val root = createTempDirectory("python-unittest-generated").toFile()
        File(root, "pyproject.toml").writeText("[project]\nname = \"app\"\n")
        val generated = File(root, "packages/a/build/test_generated.py").apply {
            parentFile.mkdirs()
            writeText("import unittest\nclass GeneratedTest(unittest.TestCase):\n    pass\n")
        }
        File(root, "packages/a/test_other.py").writeText(
            "import unittest\nclass OtherTest(unittest.TestCase):\n    pass\n",
        )
        val adapter = File(root, "affected_unittest.py").apply { writeText("# adapter\n") }.toPath()

        val command = pythonDeferredCommands(
            root.path,
            listOf("pkg-a:test"),
            modules(root, "pkg-a", "packages/a"),
            changes(generated),
            adapter,
        ) { changes(generated) }.single().resolve()!!

        assertAdapterFullCommand(command.arguments, adapter)
    }

    @Test
    fun `an intermediate unittest symlink fails visibly during runner proof`() {
        val root = createTempDirectory("python-unittest-intermediate-link").toFile()
        File(root, "pyproject.toml").writeText("[project]\nname = \"app\"\n")
        File(root, "test_runner.py").writeText("import unittest\n")
        val real = File(root, "packages/a/real").apply { mkdirs() }
        val linked = File(root, "packages/a/linked")
        Files.createSymbolicLink(linked.toPath(), real.toPath())
        val changed = File(linked, "test_alpha.py").apply {
            writeText("import unittest\nclass AlphaTest(unittest.TestCase):\n    pass\n")
        }
        File(root, "packages/a/test_other.py").writeText(
            "import unittest\nclass OtherTest(unittest.TestCase):\n    pass\n",
        )
        val adapter = File(root, "affected_unittest.py").apply { writeText("# adapter\n") }.toPath()

        val command = pythonDeferredCommands(
            root.path,
            listOf("pkg-a:test"),
            modules(root, "pkg-a", "packages/a"),
            changes(changed),
            adapter,
        ) { changes(changed) }.single().resolve()!!

        assertEquals(listOf("python", "-c", PYTHON_RUNNER_DISCOVERY_FAILURE), command.arguments)
    }

    @Test
    fun `overlapping unittest package owners keep independent discovery`() {
        val root = createTempDirectory("python-unittest-overlapping-owners").toFile()
        File(root, "pyproject.toml").writeText("[project]\nname = \"app\"\n")
        val changed = File(root, "packages/a/test_alpha.py").apply {
            parentFile.mkdirs()
            writeText("import unittest\nclass AlphaTest(unittest.TestCase):\n    pass\n")
        }
        val adapter = File(root, "affected_unittest.py").apply { writeText("# adapter\n") }.toPath()

        val commands = pythonDeferredCommands(
            root.path,
            listOf("root:test", "pkg-a:test"),
            modules(root, "root", ".", "pkg-a", "packages/a"),
            changes(changed),
            adapter,
        ) { changes(changed) }

        assertEquals(1, commands.size)
        assertAdapterFullCommand(commands.single().resolve()!!.arguments, adapter)
    }

    @Test
    fun `an oversized unittest context keeps package discovery`() {
        val root = createTempDirectory("python-unittest-context-limit").toFile()
        File(root, "pyproject.toml").writeText("[project]\nname = \"app\"\n")
        val directory = File(root, "packages/a").apply { mkdirs() }
        val changed = (0 until 256).map { index ->
            File(directory, "test_${index}_${"x".repeat(80)}.py").apply {
                writeText("import unittest\nclass LimitTest(unittest.TestCase):\n    pass\n")
            }
        }
        val adapter = File(root, "affected_unittest.py").apply { writeText("# adapter\n") }.toPath()

        val arguments = pythonCommands(
            root.path,
            listOf("pkg-a:test"),
            modules(root, "pkg-a", "packages/a"),
            changes(*changed.toTypedArray()),
            adapter,
        ).single().arguments

        assertEquals(
            listOf("python", "-m", "unittest", "discover", "-s", "packages/a", "-t", "."),
            arguments,
        )
    }

    @Test
    fun `an oversized unittest package set fails visibly instead of bypassing the adapter`() {
        val root = createTempDirectory("python-unittest-package-context-limit").toFile()
        File(root, "pyproject.toml").writeText("[project]\nname = \"app\"\n")
        val source = File(root, "source.py").apply { writeText("value = 1\n") }
        val adapter = File(root, "affected_unittest.py").apply { writeText("# adapter\n") }.toPath()
        val entries = (0 until 256).flatMap { index ->
            listOf("pkg-$index", "packages/${index}_${"x".repeat(80)}")
        }.toTypedArray()
        val modules = modules(root, *entries)
        val tasks = modules.map { "${it.executionId}:test" }

        val commands = pythonDeferredCommands(
            root.path,
            tasks,
            modules,
            changes(source),
            adapter,
            PythonTestRunner.UNITTEST,
        ) { changes(source) }

        assertEquals(1, commands.size)
        assertEquals(
            listOf("python", "-c", UNITTEST_CONTEXT_FAILURE),
            commands.single().resolve()!!.arguments,
        )
    }

    @Test
    fun `a production unittest change keeps package discovery`() {
        val root = createTempDirectory("python-unittest-src-full").toFile()
        File(root, "pyproject.toml").writeText("[project]\nname = \"app\"\n")
        File(root, "packages/a/test_alpha.py").apply {
            parentFile.mkdirs()
            writeText("import unittest\nclass AlphaTest(unittest.TestCase):\n    pass\n")
        }
        val source = File(root, "packages/a/alpha.py").apply {
            writeText("value = 1\n")
        }
        val modules = modules(root, "pkg-a", "packages/a")

        val arguments = pythonCommands(
            root.path,
            listOf("pkg-a:test"),
            modules,
            changes(source),
        ).single().arguments

        assertEquals(
            listOf("python", "-m", "unittest", "discover", "-s", "packages/a", "-t", "."),
            arguments,
        )
    }

    @Test
    fun `an unproved planned unittest package keeps discovery`() {
        val root = createTempDirectory("python-unittest-partial-plan").toFile()
        File(root, "pyproject.toml").writeText("[project]\nname = \"app\"\n")
        val changed = File(root, "packages/a/test_alpha.py").apply {
            parentFile.mkdirs()
            writeText("import unittest\nclass AlphaTest(unittest.TestCase):\n    pass\n")
        }
        File(root, "packages/b/test_beta.py").apply {
            parentFile.mkdirs()
            writeText("import unittest\nclass BetaTest(unittest.TestCase):\n    pass\n")
        }
        val modules = modules(root, "pkg-a", "packages/a", "pkg-b", "packages/b")

        val commands = pythonCommands(
            root.path,
            listOf("pkg-a:test", "pkg-b:test"),
            modules,
            changes(changed),
        )

        assertEquals(2, commands.size)
        assertFalse(commands.any { it.arguments.contains("packages/a/test_alpha.py") })
        assertEquals(
            listOf("python", "-m", "unittest", "discover", "-s", "packages/a", "-t", "."),
            commands.single { it.title == "unittest packages/a" }.arguments,
        )
    }

    @Test
    fun `changes without a merge base keep unittest discovery`() {
        val root = createTempDirectory("python-unittest-no-base").toFile()
        File(root, "pyproject.toml").writeText("[project]\nname = \"app\"\n")
        val changed = File(root, "packages/a/test_alpha.py").apply {
            parentFile.mkdirs()
            writeText("import unittest\nclass AlphaTest(unittest.TestCase):\n    pass\n")
        }
        val modules = modules(root, "pkg-a", "packages/a")

        val arguments = pythonCommands(
            root.path,
            listOf("pkg-a:test"),
            modules,
            BuildChanges(listOf(changed.path), exactSelectionEligible = emptySet(), comparedToBase = false),
        ).single().arguments

        assertEquals(
            listOf("python", "-m", "unittest", "discover", "-s", "packages/a", "-t", "."),
            arguments,
        )
    }

    private fun changes(vararg files: File) = BuildChanges(
        files = files.map(File::getPath),
        exactSelectionEligible = files.mapTo(LinkedHashSet(), File::getPath),
        comparedToBase = true,
    )

    private fun deferredFixture(prefix: String): DeferredFixture {
        val root = createTempDirectory(prefix).toFile()
        File(root, "pyproject.toml").writeText("[project]\nname = \"app\"\n")
        val selected = File(root, "packages/a/test_alpha.py").apply {
            parentFile.mkdirs()
            writeText("import unittest\nclass AlphaTest(unittest.TestCase):\n    pass\n")
        }
        val adapter = File(root, "affected_unittest.py").apply { writeText("# adapter\n") }.toPath()
        return DeferredFixture(root, selected, adapter)
    }

    private fun assertAdapterFullCommand(arguments: List<String>, adapter: java.nio.file.Path) {
        assertEquals(listOf("python", adapter.toString()), arguments.take(2))
        assertEquals(3, arguments.size)
        val padding = "=".repeat((4 - arguments[2].length % 4) % 4)
        val context = JsonParser.parseString(
            Base64.getUrlDecoder().decode(arguments[2] + padding).toString(Charsets.UTF_8),
        ).asJsonObject
        assertEquals(emptyList(), context.getAsJsonArray("selected").map { it.asString })
    }

    private fun assertExactUnittestSelection(
        arguments: List<String>,
        adapter: java.nio.file.Path,
        packageName: String,
        selected: String,
    ) {
        assertEquals(listOf("python", adapter.toString()), arguments.take(2))
        assertEquals(3, arguments.size)
        val padding = "=".repeat((4 - arguments[2].length % 4) % 4)
        val context = JsonParser.parseString(
            Base64.getUrlDecoder().decode(arguments[2] + padding).toString(Charsets.UTF_8),
        ).asJsonObject
        assertEquals(1, context.get("schema").asInt)
        assertEquals(listOf(packageName), context.getAsJsonArray("packages").map { it.asString })
        assertEquals(listOf(selected), context.getAsJsonArray("selected").map { it.asString })
    }

    private fun modules(root: File, vararg entries: String): List<BuildModule> {
        require(entries.size % 2 == 0)
        return entries.toList().chunked(2).map { (name, path) ->
            BuildModule(
                name,
                root.path,
                listOf(File(root, path).path),
                PythonProjects.TEST,
                PythonProjects.TYPECHECK,
                true,
                executionId = name,
            )
        }
    }

    private data class DeferredFixture(
        val root: File,
        val selected: File,
        val adapter: java.nio.file.Path,
    )

    private companion object {
        const val PYTHON_RUNNER_DISCOVERY_FAILURE =
            "import sys; sys.stderr.write(\"Affected could not safely determine whether this project uses " +
                "pytest or unittest; remove test-tree symlinks or declare pytest.\\n\"); raise SystemExit(2)"
        const val UNITTEST_CONTEXT_FAILURE =
            "import sys; sys.stderr.write(\"Affected could not safely encode the unittest package set; " +
                "reduce the number or depth of Python package roots.\\n\"); raise SystemExit(2)"
        const val PYTHON_RUNNER_DRIFT_FAILURE =
            "import sys; sys.stderr.write(\"Affected detected a Python test-runner change after planning; " +
                "refresh the project model and run again.\\n\"); raise SystemExit(2)"
        const val UNITTEST_ADAPTER_DRIFT_FAILURE =
            "import sys; sys.stderr.write(\"Affected could not revalidate the packaged unittest adapter; " +
                "reinstall or rebuild the plugin and run again.\\n\"); raise SystemExit(2)"
    }
}
