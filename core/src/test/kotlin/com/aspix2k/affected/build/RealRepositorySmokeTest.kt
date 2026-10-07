package com.aspix2k.affected.build

import com.aspix2k.affected.ChangeSet
import com.aspix2k.affected.ModuleGraph
import com.aspix2k.affected.TaskGroup
import com.aspix2k.affected.Verification
import com.aspix2k.affected.build.cargo.cargoCommands
import com.aspix2k.affected.build.cmake.cmakeCommands
import com.aspix2k.affected.build.dart.dartCommands
import com.aspix2k.affected.build.dart.flutterCommands
import com.aspix2k.affected.build.deno.denoCommands
import com.aspix2k.affected.build.dotnet.dotnetSteps
import com.aspix2k.affected.build.go.goCommands
import com.aspix2k.affected.build.node.nodeCommands
import com.aspix2k.affected.build.php.composerCommands
import com.aspix2k.affected.build.process.CliCommand
import com.aspix2k.affected.build.process.CliStep
import com.aspix2k.affected.build.python.PythonTestRunner
import com.aspix2k.affected.build.python.pythonCommands
import com.aspix2k.affected.build.python.pythonDeferredCommands
import com.aspix2k.affected.build.python.pythonInterpreter
import com.aspix2k.affected.build.python.pythonToolchain
import com.aspix2k.affected.build.python.withPythonInterpreter
import com.aspix2k.affected.build.ruby.rubyCommands
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.junit.runners.Parameterized.Parameters
import org.w3c.dom.Element
import java.io.File
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(Parameterized::class)
class RealRepositorySmokeTest(private val repository: RealRepository) {

    @Test
    fun `adapter discovers, plans and runs a change in the pinned repository`() {
        assumeTrue(System.getProperty(REAL_REPOSITORIES_PROPERTY) == "true")
        val defect = repository.knownDefect
        if (defect == null) probe() else assertKnownDefect(defect, ::probe)
    }

    private fun probe() {
        val missing = repository.tools.filter {
            resolveExecutable(it, System.getenv("PATH"), System.getenv("PATHEXT")) == it
        }
        if (System.getProperty(SKIP_MISSING_TOOLS_PROPERTY) == "true") {
            assumeTrue("${missing.joinToString()} not on PATH", missing.isEmpty())
        }
        assertTrue(missing.isEmpty(), "${missing.joinToString()} not on PATH")
        OwnedSandbox.use("affected-real-${repository.id}", canonical = true) { sandbox ->
            val root = sandbox.directory("repository")
            RealRepositoryClone.checkout(repository, root)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(repository.budgetSeconds)
            repository.setup.forEach { setup ->
                val result = NativeProcessRunner.run(setup, root, remainingSeconds(deadline), environment())
                assertTrue(result.passed, "Setup failed: ${setup.joinToString(" ")}\n${result.output}")
            }
            val baseline = RealRepositoryClone.modified(root)
            val project = multiRootProject(root)
            val discovered = buildSystems().filter { it.isPresent(project) }.associateWith { it.modules(project) }
            val own = discovered.entries.single { it.key.id == repository.ecosystem }.value
            val graph = ModuleGraph(
                discovered.flatMap { (system, modules) -> modules.map { ModuleGraph.Node(it, system) } },
            )
            assertDiscovery(root, own)
            repository.scenarios.forEach { scenario ->
                scenario.files.forEach {
                    assertTrue(File(root, it).isFile, "${scenario.name}: $it does not exist at ${repository.sha}")
                    File(root, it).appendText("\n")
                }
                assertEquals(baseline + scenario.files, RealRepositoryClone.modified(root))
                runScenario(scenario, root, graph, own, deadline)
                RealRepositoryClone.restore(root, scenario.files, baseline)
            }
        }
    }

    private fun assertKnownDefect(defect: KnownDefect, run: () -> Unit) {
        val failure = try {
            run()
            null
        } catch (failure: AssertionError) {
            failure
        }
        assertNotNull(failure, "${repository.id}: ${defect.id} no longer reproduces, remove its knownDefect")
        assertTrue(
            Regex(defect.failure).containsMatchIn(failure.message.orEmpty()),
            "${repository.id}: failed differently from ${defect.id} (${defect.failure})\n${failure.message}",
        )
    }

    private fun assertDiscovery(root: File, modules: List<BuildModule>) {
        val roots = modules.map { relative(root, it.root) }.toSet()
        assertTrue(roots.containsAll(repository.roots), "${repository.roots} are not among $roots")
        repository.modules.forEach { expected ->
            val found = modules.any { expected in setOf(it.id, it.executionId) }
            assertTrue(found, "$expected is not among ${modules.map(BuildModule::id)}")
        }
    }

    private fun runScenario(
        scenario: RealScenario,
        root: File,
        graph: ModuleGraph,
        own: List<BuildModule>,
        deadline: Long,
    ) {
        val files = scenario.files.map { File(root, it) }
        val changes = ChangeSet(files, emptySet(), files.toSet(), comparedToBase = true)
        val prepared = Verification.prepare(graph, changes, testDependents = scenario.testDependents).testsOnly
        val plan = prepared.plan
        val group = assertNotNull(
            plan.groups.singleOrNull { it.systemId == repository.ecosystem },
            "${scenario.name}: empty plan $plan",
        )
        assertTrue(
            scenario.tasks.all { expected -> group.tasks.any { it.startsWith(expected) } },
            "${scenario.name}: ${group.tasks} lacks ${scenario.tasks}",
        )
        val commands = commands(group, own, prepared.changes).map {
            assertNotNull(it.resolve(), "${scenario.name}: unresolved $it")
        }
        val expected = scenario.command
        assertTrue(
            commands.any { command -> command.arguments.windowed(expected.size).any { it.matches(expected) } },
            "${scenario.name}: no command with $expected in ${commands.map(CliCommand::arguments)}",
        )
        val results = commands.map { command ->
            command to NativeProcessRunner.run(
                command.arguments,
                File(group.root),
                remainingSeconds(deadline),
                command.environment + environment(),
            )
        }
        val output = results.joinToString("\n") { it.second.output }.replace(ANSI_STYLE, "")
        val report = results.joinToString("\n") { (command, result) ->
            "${command.arguments.joinToString(" ")} -> exit ${result.exitCode}"
        }
        assertTrue(results.all { it.second.completed }, "${scenario.name}: timed out\n$report\n$output")
        val selected = Regex(scenario.output).containsMatchIn(output)
        assertTrue(selected, "${scenario.name}: tests were not selected\n$report\n$output")
        val passed = !scenario.pass || results.all { it.second.passed }
        assertTrue(passed, "${scenario.name}: tests failed\n$report\n$output")
    }

    private fun commands(group: TaskGroup, modules: List<BuildModule>, changes: BuildChanges): List<CliStep> =
        when (group.systemId) {
            "GO" -> goCommands(group.tasks)
            "NODE" -> nodeCommands(group.root, group.tasks, changes)
            "CARGO" -> cargoCommands(
                group.root,
                group.tasks,
                changes,
                stopAfterFirstFailure = false,
                snapshotRoot = testSnapshotRoot,
            )
            "CMAKE" -> cmakeCommands(group.root, group.tasks)
            "SWIFT" -> swiftCommands(group.tasks)
            "DOTNET" -> dotnetSteps(testWorkspace(File(group.root)), group.root, group.tasks)
            "PYTHON" -> pythonSteps(group, modules, changes)
                .map { it.withPythonInterpreter(pythonInterpreter(File(group.root))) }
            else -> otherCommands(group, modules, changes)
        }

    private fun otherCommands(group: TaskGroup, modules: List<BuildModule>, changes: BuildChanges): List<CliStep> =
        when (group.systemId) {
            "COMPOSER" -> composerCommands(group.root, group.tasks, modules, changes)
            "RUBY" -> rubyCommands(group.root, group.tasks, modules)
            "DART" -> dartCommands(File(group.root), group.tasks)
            "FLUTTER" -> flutterCommands(File(group.root), group.tasks)
            "DENO" -> denoCommands(File(group.root), group.tasks)
            "SBT" -> sbtCommands(group.tasks)
            "MAKE" -> makeCommands(File(group.root), group.tasks)
            "MESON" -> mesonCommands(File(group.root), group.tasks)
            "NINJA" -> ninjaCommands(File(group.root), group.tasks)
            "BAZEL" -> bazelCommands(group.tasks)
            "RPROJECT" -> rCommands(File(group.root), group.tasks, changes)
            else -> error("No headless command entry point for ${group.systemId}")
        }

    private fun pythonSteps(group: TaskGroup, modules: List<BuildModule>, changes: BuildChanges): List<CliStep> {
        val runner = pythonToolchain(File(group.root)).runner
        val property = when (runner) {
            PythonTestRunner.PYTEST -> "affected.test.pytestAdapter"
            PythonTestRunner.UNITTEST -> "affected.test.unittestAdapter"
            PythonTestRunner.UNKNOWN -> error("Python test runner is not detected in ${group.root}")
        }
        val adapter = Path.of(checkNotNull(System.getProperty(property)))
        return if (runner == PythonTestRunner.UNITTEST) {
            pythonDeferredCommands(group.root, group.tasks, modules, changes, adapter, runner) { changes }
        } else {
            pythonCommands(group.root, group.tasks, modules, changes, adapter)
        }
    }

    private fun List<String>.matches(expected: List<String>): Boolean =
        zip(expected).all { (actual, wanted) -> actual == wanted || actual.endsWith("/$wanted") }

    private fun environment(): Map<String, String> = repository.environment.orEmpty()

    private fun remainingSeconds(deadline: Long): Long =
        TimeUnit.NANOSECONDS.toSeconds(deadline - System.nanoTime()).coerceAtLeast(1)

    private fun relative(root: File, path: String): String =
        File(path).relativeTo(root).invariantSeparatorsPath.ifEmpty { "." }

    private fun buildSystems(): List<BuildSystem> {
        val descriptor = CliConformanceRepository.configured.repositoryFile("src/main/resources/META-INF/plugin.xml")
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        val elements = factory.newDocumentBuilder().parse(descriptor).getElementsByTagName("buildSystem")
        return (0 until elements.length).map { index ->
            val implementation = (elements.item(index) as Element).getAttribute("implementation")
            Class.forName(implementation).getDeclaredConstructor().newInstance() as BuildSystem
        }
    }

    companion object {
        private const val REAL_REPOSITORIES_PROPERTY = "affected.realRepositories"
        private val ANSI_STYLE = Regex("\u001B\\[[0-9;]*m")
        private const val SKIP_MISSING_TOOLS_PROPERTY = "affected.realRepositories.skipMissingTools"

        @JvmStatic
        @Parameters(name = "{0}")
        fun repositories(): List<RealRepository> = RealRepositories.all
    }
}
