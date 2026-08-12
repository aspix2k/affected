package com.aspix2k.affected.build

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempFile
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DotnetTestMetadataTest {

    @Test
    fun `complete TRX exposes stable fully qualified test identities`() {
        val report = createTempFile("dotnet", ".trx")
        report.writeText(trx("Completed", passed = 2, failed = 0, secondOutcome = "Passed"))

        val parsed = assertNotNull(readDotnetTestReport(report))

        assertEquals(
            mapOf(
                "Example.Tests.AlphaTest.Passes" to "Example.Tests.AlphaTest",
                "Example.Tests.BetaTest.Passes" to "Example.Tests.BetaTest",
            ),
            parsed.tests,
        )
    }

    @Test
    fun `failed skipped and malformed TRX cannot prove a baseline`() {
        val failed = createTempFile("dotnet", ".trx").apply {
            writeText(trx("Failed", passed = 1, failed = 1, secondOutcome = "Failed"))
        }
        val malformed = createTempFile("dotnet", ".trx").apply {
            writeText("<!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><TestRun>&e;</TestRun>")
        }

        assertNull(readDotnetTestReport(failed))
        assertNull(readDotnetTestReport(malformed))
    }

    @Test
    fun `dotnet analyzer source is discoverable from a plugin layout`() {
        val root = kotlin.io.path.createTempDirectory("dotnet-analyzer-layout")
        val source = root.resolve("affected/agent/dotnet/Affected.DotnetAnalyzer")
        java.nio.file.Files.createDirectories(source)
        source.resolve("Affected.DotnetAnalyzer.csproj").writeText("<Project />")
        source.resolve("Program.cs").writeText("return 0;")

        assertEquals(source, findDotnetAnalyzer(root.resolve("affected/lib/core.jar")))
    }

    @Test
    fun `parent MSBuild configuration disables exact metadata`() {
        val parent = kotlin.io.path.createTempDirectory("dotnet-parent-config")
        val root = parent.resolve("src").also(java.nio.file.Files::createDirectories)
        parent.resolve("Directory.Build.props").writeText("<Project />")

        assertTrue(hasExternalDotnetConfiguration(root))
    }

    @Test
    fun `custom MSBuild targets disable exact without matching TargetFramework`() {
        val root = kotlin.io.path.createTempDirectory("dotnet-custom-target")
        val project = root.resolve("Example.csproj")
        project.writeText(
            "<Project Sdk=\"Microsoft.NET.Sdk\"><PropertyGroup>" +
                "<TargetFramework>net8.0</TargetFramework></PropertyGroup></Project>",
        )

        assertFalse(unsupportedDotnetConfiguration(root.toFile()))

        project.writeText("<Project><Target Name=\"CustomVSTestInput\" BeforeTargets=\"VSTest\" /></Project>")
        assertTrue(unsupportedDotnetConfiguration(root.toFile()))

        project.writeText("<Project Sdk=\"Example.Custom.Sdk/1.0.0\" />")
        assertTrue(unsupportedDotnetConfiguration(root.toFile()))

        project.writeText(
            "<Project Sdk=\"Example.Custom.Sdk/1.0.0\">" +
                "<!-- <Project Sdk=\"Microsoft.NET.Sdk\"> --></Project>",
        )
        assertTrue(unsupportedDotnetConfiguration(root.toFile()))

        project.writeText(
            "<Project Sdk=\"Microsoft.NET.Sdk\"><PropertyGroup>" +
                "<CustomBeforeMicrosoftCommonTargets>external.targets" +
                "</CustomBeforeMicrosoftCommonTargets></PropertyGroup></Project>",
        )
        assertTrue(unsupportedDotnetConfiguration(root.toFile()))

        assertTrue(
            hasDotnetEnvironmentOverrides(
                mapOf("CustomAfterMicrosoftCommonTargets" to "/external/evil.targets"),
            ),
        )
        assertTrue(hasDotnetEnvironmentOverrides(mapOf("EnableMSTestRunner" to "true")))
        assertFalse(hasDotnetEnvironmentOverrides(emptyMap()))
    }

    @Test
    fun `evaluated imports are restricted to project sdk and restored package files`() {
        val root = kotlin.io.path.createTempDirectory("dotnet-import-root").toRealPath()
        val projectImport = root.resolve("obj/Example.csproj.nuget.g.props").also {
            Files.createDirectories(it.parent)
            it.writeText("<Project><Import Project=\"package.props\" /></Project>")
        }
        val dotnet = kotlin.io.path.createTempDirectory("dotnet-import-sdk").toRealPath()
        val sdkDirectory = dotnet.resolve("sdk/8.0.100/Sdks").also(Files::createDirectories)
        val sdkImport = dotnet.resolve("sdk/8.0.100/Sdk.targets").apply { writeText("sdk") }
        val siblingSdkImport = dotnet.resolve("sdk/9.0.100/Sdk.targets").also {
            Files.createDirectories(it.parent)
            it.writeText("other sdk")
        }
        val manifestImport = dotnet.resolve(
            "sdk-manifests/8.0.100/example.workload/1.0.0/WorkloadManifest.targets",
        ).also {
            Files.createDirectories(it.parent)
            it.writeText("current workload")
        }
        val siblingManifestImport = dotnet.resolve(
            "sdk-manifests/9.0.100/example.workload/1.0.0/WorkloadManifest.targets",
        ).also {
            Files.createDirectories(it.parent)
            it.writeText("other workload")
        }
        val packages = kotlin.io.path.createTempDirectory("dotnet-import-packages").toRealPath()
        val packageImport = packages.resolve("example.build/1.0.0/build/Example.targets").also {
            Files.createDirectories(it.parent)
            it.writeText("package")
        }
        val assets = root.resolve("obj/project.assets.json").apply {
            writeText(packageAssets(packages, "example.build/1.0.0", "build/Example.targets"))
        }
        val supported = preprocessedImports(projectImport, sdkImport, manifestImport, packageImport)

        val fingerprint = assertNotNull(
            dotnetImportFingerprint(root, assets, "8.0.100", sdkDirectory.toString(), supported),
        )
        val prerelease = "8.0.100-preview.7.12345.1"
        assertNull(dotnetImportFingerprint(root, assets, prerelease, sdkDirectory.toString(), supported))

        packageImport.writeText("changed package")
        assertTrue(
            fingerprint != dotnetImportFingerprint(root, assets, "8.0.100", sdkDirectory.toString(), supported),
        )
        val external = kotlin.io.path.createTempFile("dotnet-external-import", ".targets").toRealPath().apply {
            writeText("external")
        }
        assertNull(
            dotnetImportFingerprint(
                root,
                assets,
                "8.0.100",
                sdkDirectory.toString(),
                preprocessedImports(projectImport, sdkImport, packageImport, external),
            ),
        )
        assertNull(
            dotnetImportFingerprint(
                root,
                assets,
                "8.0.100",
                sdkDirectory.toString(),
                preprocessedImports(projectImport, sdkImport, packageImport, siblingSdkImport),
            ),
        )
        assertNull(
            dotnetImportFingerprint(
                root,
                assets,
                "8.0.100",
                sdkDirectory.toString(),
                preprocessedImports(projectImport, sdkImport, packageImport, siblingManifestImport),
            ),
        )
        projectImport.writeText("<Project><Target Name=\"Injected\" /></Project>")
        assertNull(dotnetImportFingerprint(root, assets, "8.0.100", sdkDirectory.toString(), supported))
    }

    @Test
    fun `only false boolean MSBuild settings are compatible with exact selection`() {
        val properties = com.google.gson.JsonObject()

        properties.addProperty("EnableNUnitRunner", "false")
        assertTrue(supportedDotnetEvaluatedSettings(properties))

        properties.addProperty("RunSettingsFilePath", "false")
        assertFalse(supportedDotnetEvaluatedSettings(properties))
        properties.remove("RunSettingsFilePath")
        properties.addProperty("EnableNUnitRunner", "true")
        assertFalse(supportedDotnetEvaluatedSettings(properties))
    }

    @Test
    fun `only an explicitly paired dotnet adapter and framework are supported`() {
        assertNotNull(
            supportedDotnetAdapter(
                assets(library("xunit.runner.visualstudio/3.1.5"), library("xunit/2.9.3")),
            ),
        )
        assertNotNull(
            supportedDotnetAdapter(
                assets(library("nunit3testadapter/6.2.0"), library("nunit/4.6.1")),
            ),
        )
        assertNotNull(
            supportedDotnetAdapter(
                assets(library("mstest.testadapter/4.3.3"), library("mstest.testframework/4.3.3")),
            ),
        )
        assertNull(supportedDotnetAdapter(assets(library("nunit3testadapter/6.2.0"), library("xunit/2.9.3"))))
        assertNull(supportedDotnetAdapter(assets(library("nunit3testadapter/6.2.0"), library("nunit/3.14.0"))))
        assertNull(
            supportedDotnetAdapter(
                assets(
                    library("nunit3testadapter/6.2.0"),
                    library("nunit/4.6.1"),
                    library("xunit/2.9.3"),
                ),
            ),
        )
        assertNull(
            supportedDotnetAdapter(
                assets(
                    library("xunit.runner.visualstudio/3.1.5"),
                    library("xunit/2.9.3"),
                    library("Example.Build/1.0.0", "build/Example.targets"),
                ),
            ),
        )
        assertNull(
            supportedDotnetAdapter(
                assets(
                    library("xunit.runner.visualstudio/3.1.5"),
                    library("xunit/2.9.3"),
                    library("Example.Adapter/1.0.0", "build/Example.TestAdapter.dll"),
                ),
            ),
        )
        val contract = assertNotNull(
            supportedDotnetAdapter(assets(library("nunit3testadapter/6.2.0"), library("nunit/4.6.1"))),
        )
        assertEquals(
            false,
            DotnetTestExtensions.supported(contract, listOf("NUnit3.TestAdapter.dll", "Example.TestAdapter.dll")),
        )
    }

    @Test
    fun `only a complete unchanged full run promotes the dotnet baseline`() {
        val directory = kotlin.io.path.createTempDirectory("dotnet-promotion")
        val store = DotnetTestBaselineStore(directory.resolve("maps"))
        val report = directory.resolve("results.trx").apply {
            writeText(trx("Completed", passed = 2, failed = 0, secondOutcome = "Passed"))
        }
        val before = analyzed(classes = emptyMap())
        val after = analyzed(
            classes = mapOf(
                "Example.Tests.AlphaTest" to setOf("Alpha"),
                "Example.Tests.BetaTest" to setOf("Beta"),
            ),
        )

        assertEquals(false, promoteDotnetBaseline(store, before, after, report, full = false, passed = true))
        assertEquals(null, store.read())
        assertEquals(false, promoteDotnetBaseline(store, before, after, report, full = true, passed = false))
        assertEquals(null, store.read())
        assertEquals(true, promoteDotnetBaseline(store, before, after, report, full = true, passed = true))
        assertEquals(
            setOf("Example.Tests.AlphaTest.Passes", "Example.Tests.BetaTest.Passes"),
            assertNotNull(store.read()).tests.keys,
        )
        assertEquals(
            false,
            promoteDotnetBaseline(
                store,
                before,
                after.copy(testAssemblySha256 = "3".repeat(64)),
                report,
                full = true,
                passed = true,
            ),
        )
        assertEquals("2".repeat(64), assertNotNull(store.read()).testAssemblySha256)
    }

    @Test
    fun `selected and empty runs reject artifacts changed after resolution`() {
        val before = analyzed(
            classes = mapOf("Example.Tests.AlphaTest" to setOf("Alpha")),
        )
        val changed = before.copy(
            artifacts = before.artifacts +
                ("Alpha" to DotnetImpactArtifact("c".repeat(64), emptySet())),
        )
        val test = "Example.Tests.AlphaTest.Passes"

        assertEquals(
            false,
            dotnetSelectionCompleted(DotnetTestSelection.Exact(listOf(test)), before, changed, setOf(test)),
        )
        assertEquals(
            false,
            dotnetSelectionCompleted(DotnetTestSelection.Empty, before, changed, emptySet()),
        )
        assertEquals(
            true,
            dotnetSelectionCompleted(DotnetTestSelection.Exact(listOf(test)), before, before, setOf(test)),
        )
    }

    private fun analyzed(classes: Map<String, Set<String>>): DotnetAnalyzedState = DotnetAnalyzedState(
        identity = "1".repeat(64),
        testAssemblySha256 = "2".repeat(64),
        artifacts = mapOf(
            "Alpha" to DotnetImpactArtifact("a".repeat(64), emptySet()),
            "Beta" to DotnetImpactArtifact("b".repeat(64), emptySet()),
        ),
        classes = classes,
    )

    private fun assets(vararg libraries: Library) = createTempFile("dotnet-assets", ".json").apply {
        writeText(
            buildString {
                append("{\"libraries\":{\"Microsoft.NET.Test.Sdk/18.8.1\":{}")
                libraries.forEach { library ->
                    append(",\"").append(library.name).append("\":{\"files\":[")
                    library.files.forEachIndexed { index, file ->
                        if (index > 0) append(',')
                        append("\"").append(file).append("\"")
                    }
                    append("]}")
                }
                append("}}")
            },
        )
    }

    private fun library(name: String, vararg files: String) = Library(name, files.toList())

    private fun packageAssets(root: Path, packagePath: String, file: String): String =
        """{"packageFolders":{"${root.toJsonPath()}/":{}},"libraries":{"Example.Build/1.0.0":{""" +
            """"type":"package","path":"$packagePath","files":["$file"]}}}"""

    private fun preprocessedImports(vararg paths: Path): String = paths.joinToString("\n") { path ->
        val opening = """
        <!--
        ============================================================================================================================================
          <Import
            Project="input">

        $path
        ============================================================================================================================================
        -->
        """.trimIndent()
        val closing = """
        <!--
        ============================================================================================================================================
          </Import>
        ============================================================================================================================================
        -->
        """.trimIndent()
        "$opening\n$closing"
    }

    private fun Path.toJsonPath(): String = toString().replace("\\", "\\\\")

    private data class Library(val name: String, val files: List<String>)

    private fun trx(summary: String, passed: Int, failed: Int, secondOutcome: String): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <TestRun xmlns="http://microsoft.com/schemas/VisualStudio/TeamTest/2010">
          <Results>
            <UnitTestResult testId="11111111-1111-1111-1111-111111111111" outcome="Passed" />
            <UnitTestResult testId="22222222-2222-2222-2222-222222222222" outcome="$secondOutcome" />
          </Results>
          <TestDefinitions>
            <UnitTest id="11111111-1111-1111-1111-111111111111">
              <TestMethod className="Example.Tests.AlphaTest" name="Passes" />
            </UnitTest>
            <UnitTest id="22222222-2222-2222-2222-222222222222">
              <TestMethod className="Example.Tests.BetaTest" name="Passes" />
            </UnitTest>
          </TestDefinitions>
          <ResultSummary outcome="$summary">
            <Counters total="2" passed="$passed" failed="$failed" error="0" timeout="0" aborted="0"
              inconclusive="0" notExecuted="0" disconnected="0" warning="0" />
          </ResultSummary>
        </TestRun>
    """.trimIndent()
}
