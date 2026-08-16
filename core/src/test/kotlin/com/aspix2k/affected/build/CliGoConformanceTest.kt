package com.aspix2k.affected.build

import com.aspix2k.affected.ModuleGraph
import com.aspix2k.affected.TaskPlanner
import org.junit.Assume.assumeTrue
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CliGoConformanceTest {

    @Test
    fun `Go changed tests run their whole package without unrelated packages`() = fixture { root ->
        val metadata = execute(root, listOf("go", "list", "-json", "./..."))
        val modules = GoPackages.parse(metadata, root.invariantSeparatorsPath)
        val alpha = modules.single { it.executionId == "example.com/affected-fixture/alpha" }
        val changed = File(root, "alpha/excluded_test.go")
        val graph = ModuleGraph(modules.map { ModuleGraph.Node(it, GoBuildSystem()) })
        val owners = graph.nodesFor(changed)
        assertEquals(listOf(alpha.id), owners.map(ModuleGraph.Node::id))
        val plan = TaskPlanner.plan(owners.map(ModuleGraph.Node::info), emptyList())
        val command = goCommands(plan.groups.single().tasks).single()
        assertEquals(listOf("go", "test", "example.com/affected-fixture/alpha"), command.arguments)

        val markers = File(root, "markers").apply { mkdirs() }
        execute(root, command.arguments, mapOf("AFFECTED_GO_MARKER_DIR" to markers.path))

        assertTrue(File(markers, "alpha-value.marker").isFile)
        assertTrue(File(markers, "alpha-other.marker").isFile)
        assertFalse(File(markers, "beta.marker").exists())
        assertFalse(File(markers, "excluded.marker").exists())

        val failureMarkers = File(root, "failure-markers").apply { mkdirs() }
        val failure = executeResult(
            root,
            command.arguments,
            mapOf(
                "AFFECTED_GO_MARKER_DIR" to failureMarkers.path,
                "AFFECTED_GO_FAIL" to "1",
            ),
        )
        assertTrue(failure.completed, failure.output)
        assertFalse(failure.passed, failure.output)
        assertContains(failure.output, "requested Go fixture failure")
        assertTrue(File(failureMarkers, "alpha-value.marker").isFile)
        assertTrue(File(failureMarkers, "alpha-other.marker").isFile)
        assertFalse(File(failureMarkers, "beta.marker").exists())
    }

    private fun fixture(block: (File) -> Unit) {
        assumeTrue(System.getProperty(CONFORMANCE_PROPERTY) == "true")
        val source = fixtureRoot()
        assertTrue(source.isDirectory, "Missing CLI conformance fixture: $source")
        val target = createTempDirectory("affected-cli-go").toFile().canonicalFile
        try {
            assertTrue(source.copyRecursively(target, overwrite = true), "Could not copy $source")
            block(target)
        } finally {
            target.deleteRecursively()
        }
    }

    private fun fixtureRoot(): File = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .map { File(it, "conformance/cli-fixtures/go") }
        .firstOrNull(File::isDirectory)
        ?: File(System.getProperty("user.dir"), "conformance/cli-fixtures/go")

    private fun execute(
        directory: File,
        arguments: List<String>,
        environment: Map<String, String> = emptyMap(),
    ): String {
        val result = executeResult(directory, arguments, environment)
        assertTrue(result.completed, "Timed out: ${arguments.joinToString(" ")}\n${result.output}")
        assertTrue(result.passed, "Failed: ${arguments.joinToString(" ")}\n${result.output}")
        return result.output
    }

    private fun executeResult(
        directory: File,
        arguments: List<String>,
        environment: Map<String, String> = emptyMap(),
    ): CommandResult {
        val output = File.createTempFile("affected-cli-go-output", ".log")
        try {
            val builder = ProcessBuilder(arguments)
                .directory(directory)
                .redirectErrorStream(true)
                .redirectOutput(output)
            builder.environment().putAll(environment)
            val process = builder.start()
            val completed = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!completed) process.destroyForcibly().waitFor(10, TimeUnit.SECONDS)
            return CommandResult(completed, completed && process.exitValue() == 0, output.readText())
        } finally {
            output.delete()
        }
    }

    private data class CommandResult(val completed: Boolean, val passed: Boolean, val output: String)

    private companion object {
        const val CONFORMANCE_PROPERTY = "affected.cliConformance"
        const val COMMAND_TIMEOUT_SECONDS = 180L
    }
}
