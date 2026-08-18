package com.aspix2k.affected.build

import com.intellij.openapi.progress.ProcessCanceledException
import kotlinx.coroutines.CancellationException
import java.io.File
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PythonUnittestCommandTest {

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
            unittestModules(root, "pkg-a", "packages/a"),
            unittestChanges(helper),
            adapter,
        ).single().arguments

        assertExactUnittestSelection(arguments, adapter, "packages/a", "packages/a/test_helpers.py")
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
        val planned = unittestChanges(selected)
        val modules = unittestModules(root, "pkg-a", "packages/a")

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
        val fixture = deferredUnittestFixture("python-unittest-runner-drift")
        val planned = unittestChanges(fixture.selected)
        val step = pythonDeferredCommands(
            fixture.root.path,
            listOf("pkg-a:test"),
            unittestModules(fixture.root, "pkg-a", "packages/a"),
            planned,
            fixture.adapter,
        ) { planned }.single()
        File(fixture.root, "pytest.ini").writeText("[pytest]\n")

        val command = step.resolve()!!

        assertEquals(listOf("python", "-c", PYTHON_RUNNER_DRIFT_FAILURE), command.arguments)
    }

    @Test
    fun `deferred unittest selection fails visibly when its adapter disappears`() {
        val fixture = deferredUnittestFixture("python-unittest-adapter-drift")
        val planned = unittestChanges(fixture.selected)
        val step = pythonDeferredCommands(
            fixture.root.path,
            listOf("pkg-a:test"),
            unittestModules(fixture.root, "pkg-a", "packages/a"),
            planned,
            fixture.adapter,
        ) { planned }.single()
        Files.delete(fixture.adapter)

        val command = step.resolve()!!

        assertEquals(listOf("python", "-c", UNITTEST_ADAPTER_DRIFT_FAILURE), command.arguments)
    }

    @Test
    fun `deferred unittest selection fails visibly when its adapter content changes`() {
        val fixture = deferredUnittestFixture("python-unittest-adapter-content-drift")
        val planned = unittestChanges(fixture.selected)
        val step = pythonDeferredCommands(
            fixture.root.path,
            listOf("pkg-a:test"),
            unittestModules(fixture.root, "pkg-a", "packages/a"),
            planned,
            fixture.adapter,
        ) { planned }.single()
        fixture.adapter.toFile().writeText("raise SystemExit(0)\n")

        val command = step.resolve()!!

        assertEquals(listOf("python", "-c", UNITTEST_ADAPTER_DRIFT_FAILURE), command.arguments)
    }

    @Test
    fun `deferred unittest selection rechecks adapter drift after refreshing changes`() {
        val fixture = deferredUnittestFixture("python-unittest-adapter-refresh-drift")
        val planned = unittestChanges(fixture.selected)

        val command = pythonDeferredCommands(
            fixture.root.path,
            listOf("pkg-a:test"),
            unittestModules(fixture.root, "pkg-a", "packages/a"),
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
        val fixture = deferredUnittestFixture("python-unittest-runner-refresh-drift")
        val planned = unittestChanges(fixture.selected)

        val command = pythonDeferredCommands(
            fixture.root.path,
            listOf("pkg-a:test"),
            unittestModules(fixture.root, "pkg-a", "packages/a"),
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
        val fixture = deferredUnittestFixture("python-unittest-deferred-typecheck")
        val planned = unittestChanges(fixture.selected)
        val steps = pythonDeferredCommands(
            fixture.root.path,
            listOf("pkg-a:typecheck", "pkg-a:test"),
            unittestModules(fixture.root, "pkg-a", "packages/a"),
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
        val planned = unittestChanges(selected)

        val arguments = pythonDeferredCommands(
            root.path,
            listOf("pkg-a:test"),
            unittestModules(root, "pkg-a", "packages/a"),
            planned,
            adapter,
        ) { unittestChanges(selected, helper) }.single().resolve()!!.arguments

        assertUnittestAdapterFullCommand(arguments, adapter)
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
        val planned = unittestChanges(source)
        var refreshed = false

        val command = pythonDeferredCommands(
            root.path,
            listOf("pkg-a:test"),
            unittestModules(root, "pkg-a", "packages/a"),
            planned,
            adapter,
        ) {
            refreshed = true
            planned
        }.single().resolve()

        assertFalse(refreshed)
        assertUnittestAdapterFullCommand(requireNotNull(command).arguments, adapter)
    }

    @Test
    fun `a full unittest plan fails visibly when the test runner changes without refreshing changes`() {
        val fixture = deferredUnittestFixture("python-unittest-full-runner-drift")
        val source = File(fixture.root, "packages/a/alpha.py").apply { writeText("VALUE = 1\n") }
        val planned = unittestChanges(source)
        var refreshed = false
        val step = pythonDeferredCommands(
            fixture.root.path,
            listOf("pkg-a:test"),
            unittestModules(fixture.root, "pkg-a", "packages/a"),
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
        val fixture = deferredUnittestFixture("python-unittest-deferred-error")

        val command = pythonDeferredCommands(
            fixture.root.path,
            listOf("pkg-a:test"),
            unittestModules(fixture.root, "pkg-a", "packages/a"),
            unittestChanges(fixture.selected),
            fixture.adapter,
        ) { error("inspection failed") }.single().resolve()!!

        assertUnittestAdapterFullCommand(command.arguments, fixture.adapter)
    }

    @Test
    fun `deferred unittest selection propagates coroutine cancellation`() {
        val fixture = deferredUnittestFixture("python-unittest-deferred-cancel")
        val cancellation = CancellationException("cancelled")

        assertSame(
            cancellation,
            assertFailsWith<CancellationException> {
                pythonDeferredCommands(
                    fixture.root.path,
                    listOf("pkg-a:test"),
                    unittestModules(fixture.root, "pkg-a", "packages/a"),
                    unittestChanges(fixture.selected),
                    fixture.adapter,
                ) { throw cancellation }.single().resolve()
            },
        )
    }

    @Test
    fun `deferred unittest selection propagates IDE process cancellation`() {
        val fixture = deferredUnittestFixture("python-unittest-deferred-ide-cancel")
        val cancellation = ProcessCanceledException()

        assertSame(
            cancellation,
            assertFailsWith<ProcessCanceledException> {
                pythonDeferredCommands(
                    fixture.root.path,
                    listOf("pkg-a:test"),
                    unittestModules(fixture.root, "pkg-a", "packages/a"),
                    unittestChanges(fixture.selected),
                    fixture.adapter,
                ) { throw cancellation }.single().resolve()
            },
        )
    }

    @Test
    fun `deferred unittest selection propagates interruption and restores its flag`() {
        val fixture = deferredUnittestFixture("python-unittest-deferred-interrupt")
        val interruption = InterruptedException("interrupted")

        try {
            assertSame(
                interruption,
                assertFailsWith<InterruptedException> {
                    pythonDeferredCommands(
                        fixture.root.path,
                        listOf("pkg-a:test"),
                        unittestModules(fixture.root, "pkg-a", "packages/a"),
                        unittestChanges(fixture.selected),
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
        val modules = unittestModules(root, "pkg-a", "packages/a")

        val arguments = pythonCommands(
            root.path,
            listOf("pkg-a:test"),
            modules,
            unittestChanges(changed),
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
            unittestModules(root, "pkg-a", "packages/a"),
            unittestChanges(generated),
            adapter,
        ) { unittestChanges(generated) }.single().resolve()!!

        assertUnittestAdapterFullCommand(command.arguments, adapter)
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
            unittestModules(root, "pkg-a", "packages/a"),
            unittestChanges(changed),
            adapter,
        ) { unittestChanges(changed) }.single().resolve()!!

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
            unittestModules(root, "root", ".", "pkg-a", "packages/a"),
            unittestChanges(changed),
            adapter,
        ) { unittestChanges(changed) }

        assertEquals(1, commands.size)
        assertUnittestAdapterFullCommand(commands.single().resolve()!!.arguments, adapter)
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
            unittestModules(root, "pkg-a", "packages/a"),
            unittestChanges(*changed.toTypedArray()),
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
        val modules = unittestModules(root, *entries)
        val tasks = modules.map { "${it.executionId}:test" }

        val commands = pythonDeferredCommands(
            root.path,
            tasks,
            modules,
            unittestChanges(source),
            adapter,
            PythonTestRunner.UNITTEST,
        ) { unittestChanges(source) }

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
        val modules = unittestModules(root, "pkg-a", "packages/a")

        val arguments = pythonCommands(
            root.path,
            listOf("pkg-a:test"),
            modules,
            unittestChanges(source),
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
        val modules = unittestModules(root, "pkg-a", "packages/a", "pkg-b", "packages/b")

        val commands = pythonCommands(
            root.path,
            listOf("pkg-a:test", "pkg-b:test"),
            modules,
            unittestChanges(changed),
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
        val modules = unittestModules(root, "pkg-a", "packages/a")

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
}
