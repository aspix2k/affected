package com.aspix2k.affected.build

import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DotnetMtpCliConformanceTest {

    @Test
    fun `dotnet 10 xUnit 4 runs exact classes and preserves full fallback`() {
        assumeTrue(System.getProperty(CONFORMANCE_PROPERTY) == "true")
        fixture { root ->
            val environment = executionEnvironment(root)
            val version = execute(root, listOf("dotnet", "--version"), environment).output.trim()
            assumeTrue(version == "10.0.400")
            val project = "Mtp.Tests/Mtp.Tests.csproj"
            val alpha = File(root, "Mtp.Tests/AlphaTests.cs")
            val markers = File(environment.getValue("AFFECTED_MTP_MARKERS"))

            val alphaChanges = changes(alpha)
            val alphaPlan = assertNotNull(dotnetMtpSelectionPlan(root.path, project, alphaChanges))
            execute(root, dotnetBuildCommand(project).arguments, environment).requirePassed()
            val archiveIdentity = nativeMtpArchiveIdentity(root.path, project)
            assertTrue(
                nativeMtpAssetsProof(root.path, project),
                "restored MTP assets are not proven: $archiveIdentity",
            )
            val alphaArguments = dotnetMtpTestArguments(root.path, project, alphaChanges, alphaPlan, { alphaChanges })
            assertEquals(
                listOf(
                    "dotnet", "test", "--project", project, "--no-build",
                    "--minimum-expected-tests", "1", "--filter-class", "Mtp.Tests.AlphaTests",
                ),
                alphaArguments,
            )
            execute(root, alphaArguments, environment).requirePassed()
            assertMarkers(markers, expected = setOf("alpha"))

            clearMarkers(markers)
            val helper = File(root, "Mtp.Tests/Helper.cs").apply {
                writeText("namespace Mtp.Tests; public sealed class Helper {}")
            }
            val helperChanges = changes(helper)
            execute(root, dotnetBuildCommand(project).arguments, environment).requirePassed()
            val fullArguments = dotnetMtpTestArguments(root.path, project, helperChanges, null, { helperChanges })
            assertEquals(listOf("dotnet", "test", "--project", project, "--no-build"), fullArguments)
            execute(root, fullArguments, environment).requirePassed()
            assertMarkers(markers, expected = setOf("alpha", "beta"))

            clearMarkers(markers)
            alpha.writeText(alpha.readText().replace("Assert.True(true);", "Assert.True(false);"))
            val failingChanges = changes(alpha)
            val failingPlan = assertNotNull(dotnetMtpSelectionPlan(root.path, project, failingChanges))
            execute(root, dotnetBuildCommand(project).arguments, environment).requirePassed()
            val failingArguments = dotnetMtpTestArguments(
                root.path,
                project,
                failingChanges,
                failingPlan,
                { failingChanges },
            )
            execute(root, failingArguments, environment).requireFailed()
            assertMarkers(markers, expected = setOf("alpha"))

            clearMarkers(markers)
            val missingArguments = alphaArguments.dropLast(1) + "Mtp.Tests.MissingTests"
            execute(root, missingArguments, environment).requireFailed()
            assertMarkers(markers, expected = emptySet())

            alpha.writeText(alpha.readText().replace("Assert.True(false);", "Assert.True(true);"))
            File(root, "Mtp.Tests/XunitShadow.cs").writeText(
                "namespace Xunit; [System.AttributeUsage(System.AttributeTargets.Method)] " +
                    "public sealed class FactAttribute : System.Attribute {}",
            )
            val shadowedPlan = assertNotNull(dotnetMtpSelectionPlan(root.path, project, alphaChanges))
            execute(root, dotnetBuildCommand(project).arguments, environment).requirePassed()
            assertEquals(
                listOf("dotnet", "test", "--project", project, "--no-build"),
                dotnetMtpTestArguments(root.path, project, alphaChanges, shadowedPlan, { alphaChanges }),
            )
        }
    }

    private fun fixture(block: (File) -> Unit) {
        val source = fixtureRoot().resolve("dotnet-mtp-xunit4")
        val root = createTempDirectory("dotnet-mtp-xunit4").toFile()
        try {
            assertTrue(source.copyRecursively(root, overwrite = true), "Could not copy $source")
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun executionEnvironment(root: File): Map<String, String> = mapOf(
        "AFFECTED_MTP_MARKERS" to File(root, "markers").apply { mkdirs() }.path,
        "DOTNET_CLI_TELEMETRY_OPTOUT" to "1",
        "DOTNET_NOLOGO" to "1",
        "TESTINGPLATFORM_TELEMETRY_OPTOUT" to "1",
    )

    private fun execute(directory: File, arguments: List<String>, environment: Map<String, String>): Result {
        val log = Files.createTempFile("dotnet-mtp-cli", ".log")
        return try {
            val process = ProcessBuilder(arguments)
                .directory(directory)
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .apply { environment().putAll(environment) }
                .start()
            if (!process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                error("Timed out: ${arguments.joinToString(" ")}")
            }
            require(Files.size(log) <= MAX_OUTPUT_BYTES)
            Result(process.exitValue() == 0, Files.readString(log))
        } finally {
            Files.deleteIfExists(log)
        }
    }

    private fun assertMarkers(directory: File, expected: Set<String>) {
        assertEquals(expected, directory.listFiles().orEmpty().mapTo(sortedSetOf(), File::getName))
    }

    private fun clearMarkers(directory: File) {
        directory.listFiles().orEmpty().forEach { assertTrue(it.delete(), "Could not delete $it") }
    }

    private fun changes(file: File) = BuildChanges(
        files = listOf(file.path),
        exactSelectionEligible = setOf(file.path),
        comparedToBase = true,
    )

    private fun fixtureRoot(): File = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .map { File(it, "conformance/cli-fixtures") }
        .firstOrNull(File::isDirectory)
        ?: error("conformance fixtures not found")

    private data class Result(val passed: Boolean, val output: String)

    private fun Result.requirePassed() {
        assertTrue(passed, output)
    }

    private fun Result.requireFailed() {
        assertFalse(passed, output)
    }

    private companion object {
        const val PROCESS_TIMEOUT_SECONDS = 300L
        const val MAX_OUTPUT_BYTES = 16L * 1024 * 1024
        const val CONFORMANCE_PROPERTY = "affected.cliConformance"
    }
}
