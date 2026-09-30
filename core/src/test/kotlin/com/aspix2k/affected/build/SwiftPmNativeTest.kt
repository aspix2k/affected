package com.aspix2k.affected.build

import com.aspix2k.affected.ModuleGraph
import com.aspix2k.affected.TaskPlanner
import com.aspix2k.affected.build.process.CliCommand
import com.aspix2k.affected.build.process.SequentialProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.openapi.util.Key
import org.junit.Assume.assumeTrue
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SwiftPmNativeTest {

    @Test
    fun `SwiftPM discovers targets and runs only affected test targets`() = fixture { root ->
        val system = SwiftBuildSystem()
        val modules = system.modules(root)
        assertEquals(
            setOf("Alpha", "Beta", "Gamma", "Delta", "AlphaTests", "BetaTests", "GammaTests", "DeltaTests"),
            modules.mapTo(HashSet(), BuildModule::id),
        )
        assertEquals(
            setOf("AlphaTests", "BetaTests", "GammaTests", "DeltaTests"),
            modules.filter(BuildModule::hasTests).mapTo(HashSet(), BuildModule::id),
        )
        val prefix = "${root.invariantSeparatorsPath}|"
        assertEquals(setOf("${prefix}Alpha"), modules.single { it.id == "Beta" }.dependencies)
        assertEquals(setOf("${prefix}Beta"), modules.single { it.id == "BetaTests" }.dependencies)
        val graph = ModuleGraph(modules.map { ModuleGraph.Node(it, system) })

        val alpha = commandsFor(graph, File(root, "Sources/Alpha/Alpha.swift"))
        assertEquals(listOf(listOf("swift", "build"), listOf("swift", "test")), alpha.map { it.arguments.take(2) })
        assertEquals(setOf("Alpha", "Beta"), selected(alpha.first()))
        assertEquals(setOf("^AlphaTests\\.", "^BetaTests\\."), selected(alpha.last()))
        assertEquals(0, execute(root, alpha).exitCode)
        assertEquals(setOf("AlphaTests", "BetaTests"), markers(root))

        File(root, "markers").deleteRecursively()
        val gamma = commandsFor(graph, File(root, "Tests/GammaTests/GammaTests.swift"))
        assertEquals(listOf(listOf("swift", "test", "--filter", "^GammaTests\\.")), gamma.map(CliCommand::arguments))
        assertEquals(0, execute(root, gamma).exitCode)
        assertEquals(setOf("GammaTests"), markers(root))

        File(root, "markers").deleteRecursively()
        val delta = commandsFor(graph, File(root, "Sources/Delta/Delta.swift"))
        assertEquals(setOf("^DeltaTests\\."), selected(delta.last()))
        assertEquals(0, execute(root, delta).exitCode)
        assertEquals(setOf("DeltaTests"), markers(root))

        val manifest = commandsFor(graph, File(root, "Package.swift"))
        assertEquals(setOf("Alpha", "Beta", "Gamma", "Delta"), selected(manifest.first()))
        assertEquals(
            setOf("^AlphaTests\\.", "^BetaTests\\.", "^GammaTests\\.", "^DeltaTests\\."),
            selected(manifest.last()),
        )

        File(root, "markers").deleteRecursively()
        File(root, "markers").mkdirs()
        File(root, "markers/BetaTests.fail").writeText("")
        val failure = execute(root, alpha)
        assertNotEquals(0, failure.exitCode, failure.output)
        assertContains(failure.output, "requested Swift fixture failure")
        assertTrue(File(root, "markers/BetaTests.marker").isFile)
        assertFalse(File(root, "markers/GammaTests.marker").exists())
    }

    @Test
    fun `stop terminates the Swift test process tree`() = fixture { root ->
        File(root, "markers").mkdirs()
        File(root, "markers/AlphaTests.hang").writeText("")
        val pids = File(root, "markers/hang.pids")
        val handler = SequentialProcessHandler(root, swiftCommands(listOf("AlphaTests:test")))
        var tree = emptyList<Long>()
        try {
            handler.startNotify()
            val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(4)
            while (!pids.isFile && !handler.isProcessTerminated && System.nanoTime() < deadline) Thread.sleep(50)
            assertTrue(pids.isFile, "The hanging Swift test did not start")
            tree = pids.readText().lines().filter(String::isNotBlank).map(String::toLong)
            assertEquals(2, tree.size)
            assertTrue(tree.all(::alive))

            assertTrue(handler.stopIfActive())
            assertTrue(handler.waitFor(30_000))
            val terminated = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            while (tree.any(::alive) && System.nanoTime() < terminated) Thread.sleep(50)
            assertFalse(tree.any(::alive), "Swift test processes survived Stop: $tree")
            assertNotEquals(0, handler.exitCode)
        } finally {
            if (!handler.isProcessTerminated) handler.destroyProcess()
            tree.forEach { pid -> ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly) }
        }
    }

    private fun fixture(block: (File) -> Unit) {
        assumeTrue(System.getProperty(CONFORMANCE_PROPERTY) == "true")
        assumeTrue("swift is not available", swiftAvailable())
        val source = CliConformanceRepository.configured.fixture("swiftpm")
        val target = createTempDirectory("affected-swiftpm").toFile()
        try {
            assertTrue(source.copyRecursively(target, overwrite = true), "Could not copy $source")
            block(target)
        } finally {
            target.deleteRecursively()
        }
    }

    private fun commandsFor(graph: ModuleGraph, file: File): List<CliCommand> {
        val changed = graph.nodesFor(file).toSet()
        val consumers = graph.transitiveTestConsumers(changed)
        val plan = TaskPlanner.plan((changed + consumers).map(ModuleGraph.Node::info), emptyList())
        return swiftCommands(plan.groups.single().tasks)
    }

    private fun selected(command: CliCommand): Set<String> =
        command.arguments.drop(2).chunked(2).mapTo(HashSet()) { it.last() }

    private fun markers(root: File): Set<String> =
        File(root, "markers").listFiles().orEmpty()
            .filter { it.name.endsWith(".marker") }
            .mapTo(HashSet()) { it.name.removeSuffix(".marker") }

    private fun execute(root: File, commands: List<CliCommand>): Execution {
        val output = StringBuilder()
        val handler = SequentialProcessHandler(root, commands)
        handler.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                output.append(event.text)
            }
        })
        handler.startNotify()
        if (!handler.waitFor(COMMAND_TIMEOUT_MILLIS)) handler.destroyProcess()
        assertTrue(handler.isProcessTerminated, "Timed out: $output")
        return Execution(checkNotNull(handler.exitCode), output.toString())
    }

    private fun alive(pid: Long): Boolean =
        ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)

    private fun swiftAvailable(): Boolean = runCatching {
        ProcessBuilder("swift", "--version")
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
            .waitFor(30, TimeUnit.SECONDS)
    }.getOrDefault(false)

    private data class Execution(val exitCode: Int, val output: String)

    private companion object {
        const val CONFORMANCE_PROPERTY = "affected.cliConformance"
        const val COMMAND_TIMEOUT_MILLIS = 300_000L
    }
}
