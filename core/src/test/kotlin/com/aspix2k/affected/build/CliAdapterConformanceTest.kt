package com.aspix2k.affected.build

import org.junit.Assume.assumeTrue
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CliAdapterConformanceTest {

    @Test
    fun `Cargo commands run the selected workspace packages`() = fixture("cargo") { root ->
        val metadata = execute(root, listOf("cargo", "metadata", "--no-deps", "--format-version", "1"))
        val modules = CargoMetadata.parse(metadata, root.invariantSeparatorsPath)
        val output = execute(root, cargoCommands(modules.map { "${it.executionId}:test" }).single().arguments)

        assertContains(output, "affected_alpha")
        assertContains(output, "affected_beta")
    }

    @Test
    fun `Go commands run the selected module packages`() = fixture("go") { root ->
        val metadata = execute(root, listOf("go", "list", "-json", "./..."))
        val modules = GoPackages.parse(metadata, root.invariantSeparatorsPath)
        val output = execute(root, goCommands(modules.map { "${it.executionId}:test" }).single().arguments)

        assertContains(output, "example.com/affected-fixture/alpha")
        assertContains(output, "example.com/affected-fixture/beta")
    }

    @Test
    fun `npm runs exact Jest and Vitest files and preserves full fallback`() = fixture("node") { root ->
        execute(root, listOf("npm", "ci", "--ignore-scripts", "--no-audit", "--no-fund"))
        val modules = NodeWorkspaces.parse(root).filter(BuildModule::hasTests)
        val alphaSelected = File(root, "packages/alpha/alpha-selected.marker")
        val alphaFull = File(root, "packages/alpha/alpha-full.marker")
        val betaSelected = File(root, "packages/beta/beta-selected.marker")
        val betaFull = File(root, "packages/beta/beta-full.marker")
        execute(root, nodeCommands(root.path, modules.map { "${it.executionId}:test" }).single().arguments)

        deleteMarkers(alphaSelected, alphaFull, betaSelected, betaFull)

        val fullJestMillis = measureTimeMillis {
            execute(root, nodeCommands(root.path, listOf("@affected/alpha:test")).single().arguments)
        }
        deleteMarkers(alphaSelected, alphaFull)

        assertJestRelated(root, alphaSelected, alphaFull, fullJestMillis)
        assertVitestRelated(root, betaSelected, betaFull)
        assertNodeDynamicFallback(root, alphaSelected, alphaFull)
    }

    private fun assertJestRelated(root: File, selected: File, full: File, fullMillis: Long) {
        val exactMillis = measureTimeMillis { executeRelated(root, "@affected/alpha", "alpha.js") }
        assertTrue(selected.isFile)
        assertFalse(full.exists())
        assertTrue(exactMillis < fullMillis, "exact=$exactMillis ms, full=$fullMillis ms")

        assertTrue(selected.delete())
        executeRelated(root, "@affected/alpha", "alpha.test.js")
        assertTrue(selected.delete())
        assertFalse(full.exists())

        executeRelated(root, "@affected/alpha", "unused.js")
        assertFalse(selected.exists())
        assertFalse(full.exists())
    }

    private fun assertVitestRelated(root: File, selected: File, full: File) {
        executeRelated(root, "@affected/beta", "beta.js")
        assertTrue(selected.isFile)
        assertFalse(full.exists())

        assertTrue(selected.delete())
        executeRelated(root, "@affected/beta", "beta.test.js")
        assertTrue(selected.delete())
        assertFalse(full.exists())

        executeRelated(root, "@affected/beta", "unused.js")
        assertFalse(selected.exists())
        assertFalse(full.exists())
    }

    private fun executeRelated(root: File, packageName: String, fileName: String) {
        val file = File(root, "packages/${packageName.substringAfterLast('/')}/$fileName")
        val command = nodeCommands(
            root.path,
            listOf("$packageName:test"),
            BuildChanges(listOf(file.path), setOf(file.path), comparedToBase = true),
        ).single()
        execute(root, command.arguments)
    }

    private fun assertNodeDynamicFallback(root: File, selected: File, full: File) {
        File(root, "packages/alpha/dynamic.js").writeText("export const load = name => import(name)\n")
        val alpha = File(root, "packages/alpha/alpha.js")
        val fallback = nodeCommands(
            root.path,
            listOf("@affected/alpha:test"),
            BuildChanges(listOf(alpha.path), setOf(alpha.path), comparedToBase = true),
        ).single()
        execute(root, fallback.arguments)

        assertEquals(listOf("npm", "test", "--workspace", "@affected/alpha"), fallback.arguments)
        assertTrue(selected.isFile)
        assertTrue(full.isFile)
    }

    private fun deleteMarkers(vararg markers: File) {
        markers.forEach { assertTrue(it.delete()) }
    }

    @Test
    fun `dotnet commands run both selected test projects`() = fixture("dotnet") { root ->
        val modules = DotnetProjects.parse(root)
        val output = dotnetCommands(root.path, modules.map { "${it.executionId}:test" })
            .joinToString("\n") { execute(root, it.arguments) }

        assertContains(output, "Alpha.Tests")
        assertContains(output, "Beta.Tests")
    }

    @Test
    fun `pytest command runs both selected packages`() = fixture("python") { root ->
        val modules = PythonProjects.parse(root).filter(BuildModule::hasTests)
        val output = execute(
            root,
            pythonCommands(root.path, modules.map { "${it.executionId}:test" }, modules).single().arguments,
        )

        assertContains(output, "2 passed")
    }

    @Test
    fun `PHPUnit command runs both selected packages`() = fixture("composer") { root ->
        execute(
            root,
            listOf("composer", "install", "--no-interaction", "--no-progress", "--no-plugins", "--no-scripts"),
        )
        val modules = ComposerPackages.parse(root).filter(BuildModule::hasTests)
        val output = execute(
            root,
            composerCommands(root.path, modules.map { "${it.executionId}:test" }, modules).single().arguments,
        )

        assertTrue(output.contains("OK (2 tests") || output.contains("OK, but there were issues!\nTests: 2"), output)
    }

    @Test
    fun `RSpec command runs both selected gems`() = fixture("ruby") { root ->
        execute(root, listOf("bundle", "config", "set", "--local", "path", "vendor/bundle"))
        execute(root, listOf("bundle", "install", "--jobs", "2", "--retry", "2"))
        val modules = RubyGems.parse(root).filter(BuildModule::hasTests)
        val output = execute(
            root,
            rubyCommands(root.path, modules.map { "${it.executionId}:test" }, modules).single().arguments,
        )

        assertContains(output, "2 examples, 0 failures")
    }

    @Test
    fun `CMake commands rebuild and run the complete CTest plan`() = fixture("cmake") { root ->
        execute(root, listOf("cmake", "-S", ".", "-B", "build"))
        val modules = CMakeTargets.parse(root)
        val output = cmakeCommands(root.path, modules.map { "${it.executionId}:test" })
            .joinToString("\n") { execute(root, it.arguments) }

        assertContains(output, "affected_alpha")
        assertContains(output, "affected_beta")
        assertContains(output, "100% tests passed")
    }

    private fun fixture(name: String, block: (File) -> Unit) {
        assumeTrue(System.getProperty(CONFORMANCE_PROPERTY) == "true")
        val source = fixtureRoot().resolve(name)
        assertTrue(source.isDirectory, "Missing CLI conformance fixture: $source")
        val target = createTempDirectory("affected-cli-$name").toFile()
        try {
            assertTrue(source.copyRecursively(target, overwrite = true), "Could not copy $source")
            block(target)
        } finally {
            target.deleteRecursively()
        }
    }

    private fun fixtureRoot(): File = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .map { File(it, "conformance/cli-fixtures") }
        .firstOrNull(File::isDirectory)
        ?: File(System.getProperty("user.dir"), "conformance/cli-fixtures")

    private fun execute(directory: File, arguments: List<String>): String {
        val output = File.createTempFile("affected-cli-output", ".log")
        try {
            val process = ProcessBuilder(arguments)
                .directory(directory)
                .redirectErrorStream(true)
                .redirectOutput(output)
                .start()
            val completed = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!completed) process.destroyForcibly().waitFor(10, TimeUnit.SECONDS)
            val text = output.readText()
            assertTrue(completed, "Timed out: ${arguments.joinToString(" ")}\n$text")
            assertTrue(process.exitValue() == 0, "Failed: ${arguments.joinToString(" ")}\n$text")
            return text
        } finally {
            output.delete()
        }
    }

    private companion object {
        const val CONFORMANCE_PROPERTY = "affected.cliConformance"
        const val COMMAND_TIMEOUT_SECONDS = 180L
    }
}
