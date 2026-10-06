package com.aspix2k.affected.build.dotnet

import com.aspix2k.affected.build.BuildChanges
import com.aspix2k.affected.build.TestWorkspace
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MtpFilterClassTest {

    @Test
    fun `an official runner-only MTP config follows the active SDK grammar`() {
        val root = createTempDirectory("dotnet-mtp").toFile()
        File(root, "global.json").writeText(
            """{ "test": { "runner": "Microsoft.Testing.Platform" } }""",
        )
        val tasks = listOf("tests/App.Tests.csproj:test")

        assertEquals(
            listOf("dotnet", "test", "--project", "tests/App.Tests.csproj"),
            requireNotNull(dotnetSteps(TestWorkspace(), root.path, tasks) { "10.0.401" }.single().resolve()).arguments,
        )
        assertEquals(
            listOf("dotnet", "test", "tests/App.Tests.csproj"),
            requireNotNull(dotnetSteps(TestWorkspace(), root.path, tasks) { "8.0.100" }.single().resolve()).arguments,
        )
        val error = assertFailsWith<IllegalArgumentException> {
            dotnetSteps(TestWorkspace(), root.path, tasks) { null }.single().resolve()
        }
        assertTrue(error.message.orEmpty().contains("could not determine the active .NET SDK"))

        File(root, "global.json").writeText(
            """
            {
              "sdk": { "version": "8.0.100", "rollForward": "latestMajor" },
              "test": { "runner": "Microsoft.Testing.Platform" }
            }
            """.trimIndent(),
        )
        assertEquals(
            listOf("dotnet", "test", "--project", "tests/App.Tests.csproj"),
            requireNotNull(dotnetSteps(TestWorkspace(), root.path, tasks) { "10.0.401" }.single().resolve()).arguments,
        )
    }

    @Test
    fun `symlinked MTP roots keep the active SDK command grammar`() {
        val directory = createTempDirectory("dotnet-mtp-symlink").toFile()
        val realRoot = File(directory, "real").apply { mkdirs() }
        File(realRoot, "global-target.json").writeText(
            """{ "test": { "runner": "Microsoft.Testing.Platform" } }""",
        )
        val globalLink = File(realRoot, "global.json").toPath()
        assumeTrue(runCatching { Files.createSymbolicLink(globalLink, Path.of("global-target.json")) }.isSuccess)
        val rootLink = File(directory, "linked").toPath()
        assumeTrue(runCatching { Files.createSymbolicLink(rootLink, realRoot.toPath()) }.isSuccess)
        val tasks = listOf("tests/App.Tests.csproj:test")

        listOf(realRoot.path, rootLink.toString()).forEach { root ->
            assertEquals(
                listOf("dotnet", "test", "--project", "tests/App.Tests.csproj"),
                requireNotNull(dotnetSteps(TestWorkspace(), root, tasks) { "10.0.401" }.single().resolve()).arguments,
            )
        }
    }

    @Test
    fun `an exact MTP filter that exceeds the Windows command line budget falls back`() {
        val root = createTempDirectory("mtp-filter-budget").toFile()
        writeNativeXunitProject(root)
        val files = (1..32).map { index ->
            File(root, "tests/Long${index}Tests.cs").apply {
                parentFile.mkdirs()
                val namespace = "N${"a".repeat(600)}$index"
                writeText(
                    "namespace $namespace; public sealed class Long${index}Tests { " +
                        "[global::Xunit.Fact] public void Passes() {} }",
                )
            }
        }

        assertNull(
            dotnetMtpSelectionPlan(
                root.path,
                "tests/App.Tests.csproj",
                commandChanges(*files.toTypedArray()),
            ),
        )
    }

    @Test
    fun `a proven native xUnit MTP test file adds a fully qualified filter class`() {
        val root = createTempDirectory("mtp-filter-class").toFile()
        writeNativeXunitProject(root)
        val testFile = File(root, "tests/AlphaTests.cs").apply {
            writeText(
                """
                using Xunit;

                namespace App.Tests;

                public sealed class AlphaTests
                {
                    [global::Xunit.Fact]
                    public void Passes() => Assert.True(true);
                }
                """.trimIndent(),
            )
        }

        val changes = commandChanges(testFile)
        val selection = dotnetMtpSelectionPlan(root.path, "tests/App.Tests.csproj", changes)
        val arguments = dotnetMtpTestArguments(
            TestWorkspace(),
            root = root.path,
            project = "tests/App.Tests.csproj",
            planned = changes,
            plannedSelection = selection,
            currentChanges = { changes },
            runtimeProof = { _, _, _ -> true },
        )

        assertEquals(
            listOf(
                "dotnet", "test", "--project", "tests/App.Tests.csproj",
                "--no-build",
                "--minimum-expected-tests", "1",
                "--filter-class", "App.Tests.AlphaTests",
            ),
            arguments,
        )
    }

    @Test
    fun `a production change keeps the full native MTP project command`() {
        val root = createTempDirectory("mtp-filter-src").toFile()
        writeNativeXunitProject(root)
        val source = File(root, "src/Model.cs").apply {
            parentFile.mkdirs()
            writeText("public sealed class Model {}\n")
        }

        val command = dotnetCommands(
            root.path,
            listOf("tests/App.Tests.csproj:test"),
        ).single()

        assertEquals(listOf("dotnet", "test", "--project", "tests/App.Tests.csproj"), command.arguments)
        assertNull(selectMtpFilterClasses(root.path, "tests/App.Tests.csproj", commandChanges(source)))
    }

    @Test
    fun `changed files after planning keep the full native MTP project command`() {
        val root = createTempDirectory("mtp-stale-changes").toFile()
        writeNativeXunitProject(root)
        val alpha = nativeTestFile(root, "AlphaTests")
        val beta = nativeTestFile(root, "BetaTests")
        val planned = commandChanges(alpha)
        val selection = dotnetMtpSelectionPlan(root.path, "tests/App.Tests.csproj", planned)

        assertEquals(
            listOf("dotnet", "test", "--project", "tests/App.Tests.csproj", "--no-build"),
            dotnetMtpTestArguments(
                TestWorkspace(),
                root.path,
                "tests/App.Tests.csproj",
                planned,
                selection,
                { commandChanges(beta) },
                { _, _, _ -> true },
            ),
        )
    }

    @Test
    fun `source mutation after planning keeps the full native MTP project command`() {
        val root = createTempDirectory("mtp-stale-source").toFile()
        writeNativeXunitProject(root)
        val alpha = nativeTestFile(root, "AlphaTests")
        val planned = commandChanges(alpha)
        val selection = dotnetMtpSelectionPlan(root.path, "tests/App.Tests.csproj", planned)
        alpha.appendText("\n// changed after planning\n")

        assertEquals(
            listOf("dotnet", "test", "--project", "tests/App.Tests.csproj", "--no-build"),
            dotnetMtpTestArguments(
                TestWorkspace(),
                root.path,
                "tests/App.Tests.csproj",
                planned,
                selection,
                { planned },
                { _, _, _ -> true },
            ),
        )
    }

    @Test
    fun `source mutation inside runtime proof keeps the full native MTP project command`() {
        val root = createTempDirectory("mtp-runtime-race").toFile()
        writeNativeXunitProject(root)
        val alpha = nativeTestFile(root, "AlphaTests")
        val planned = commandChanges(alpha)
        val selection = assertNotNull(dotnetMtpSelectionPlan(root.path, "tests/App.Tests.csproj", planned))

        assertEquals(
            listOf("dotnet", "test", "--project", "tests/App.Tests.csproj", "--no-build"),
            dotnetMtpTestArguments(
                TestWorkspace(),
                root.path,
                "tests/App.Tests.csproj",
                planned,
                selection,
                { planned },
                { _, _, _ ->
                    alpha.appendText("\n// changed during runtime proof\n")
                    true
                },
            ),
        )
    }

    @Test
    fun `source mutation inside the final runtime proof keeps the full native MTP project command`() {
        val root = createTempDirectory("mtp-final-runtime-race").toFile()
        writeNativeXunitProject(root)
        val alpha = nativeTestFile(root, "AlphaTests")
        val planned = commandChanges(alpha)
        val selection = assertNotNull(dotnetMtpSelectionPlan(root.path, "tests/App.Tests.csproj", planned))
        var calls = 0

        assertEquals(
            listOf("dotnet", "test", "--project", "tests/App.Tests.csproj", "--no-build"),
            dotnetMtpTestArguments(
                TestWorkspace(),
                root.path,
                "tests/App.Tests.csproj",
                planned,
                selection,
                { planned },
                { _, _, _ ->
                    if (calls++ == 1) alpha.appendText("\n// changed during final runtime proof\n")
                    true
                },
            ),
        )
        assertEquals(2, calls)
    }

    @Test
    fun `ordinary current change failures keep the full native MTP project command`() {
        val root = createTempDirectory("mtp-change-error").toFile()
        writeNativeXunitProject(root)
        val planned = commandChanges(nativeTestFile(root, "AlphaTests"))
        val selection = dotnetMtpSelectionPlan(root.path, "tests/App.Tests.csproj", planned)

        assertEquals(
            listOf("dotnet", "test", "--project", "tests/App.Tests.csproj", "--no-build"),
            dotnetMtpTestArguments(
                TestWorkspace(),
                root.path,
                "tests/App.Tests.csproj",
                planned,
                selection,
                { error("git failed") },
                { _, _, _ -> true },
            ),
        )
    }

    @Test
    fun `current change cancellation is propagated`() {
        val root = createTempDirectory("mtp-change-cancel").toFile()
        writeNativeXunitProject(root)
        val planned = commandChanges(nativeTestFile(root, "AlphaTests"))
        val selection = dotnetMtpSelectionPlan(root.path, "tests/App.Tests.csproj", planned)

        assertFailsWith<CancellationException> {
            dotnetMtpTestArguments(
                TestWorkspace(),
                root.path,
                "tests/App.Tests.csproj",
                planned,
                selection,
                { throw CancellationException() },
            )
        }
        assertFailsWith<PlatformCancellation> {
            dotnetMtpTestArguments(
                TestWorkspace(),
                root.path,
                "tests/App.Tests.csproj",
                planned,
                selection,
                { throw PlatformCancellation() },
            )
        }
    }

    @Test
    fun `an SDK 8 or 9 compatibility marker keeps positional full project execution`() {
        val root = createTempDirectory("mtp-compatibility").toFile()
        File(root, "tests/App.Tests.csproj").apply {
            parentFile.mkdirs()
            writeText(
                """
                <Project Sdk="Microsoft.NET.Sdk">
                  <PropertyGroup>
                    <TargetFramework>net8.0</TargetFramework>
                    <TestingPlatformDotnetTestSupport>true</TestingPlatformDotnetTestSupport>
                  </PropertyGroup>
                </Project>
                """.trimIndent(),
            )
        }
        val testFile = File(root, "tests/AlphaTests.cs").apply {
            writeText("namespace App.Tests; public sealed class AlphaTests {}\n")
        }

        val command = dotnetCommands(
            root.path,
            listOf("tests/App.Tests.csproj:test"),
        ).single()

        assertEquals(listOf("dotnet", "test", "tests/App.Tests.csproj"), command.arguments)
        assertNull(selectMtpFilterClasses(root.path, "tests/App.Tests.csproj", commandChanges(testFile)))
    }

    @Test
    fun `an SDK 8 global runner marker keeps positional full project execution`() {
        val root = createTempDirectory("mtp-global-compatibility").toFile()
        writeNativeXunitProject(root)
        replace(File(root, "global.json"), "10.0.401", "8.0.100")

        val command = dotnetCommands(
            root.path,
            listOf("tests/App.Tests.csproj:test"),
        ).single()

        assertEquals(listOf("dotnet", "test", "tests/App.Tests.csproj"), command.arguments)
    }

    @Test
    fun `any later SDK 10 patch and any locked xUnit 4 release keep native exact selection`() {
        val lines = listOf("10.0.400" to "4.0.0", "10.0.402" to "4.0.2", "10.0.500" to "4.1.0", "10.0.1000" to "4.12.3")
        lines.forEach { (sdk, xunit) ->
            val root = createTempDirectory("mtp-line").toFile()
            writeNativeXunitProject(root, xunitVersion = xunit)
            replace(File(root, "global.json"), "10.0.401", sdk)
            val testFile = nativeTestFile(root, "AlphaTests")

            val plan = assertNotNull(
                dotnetMtpSelectionPlan(root.path, "tests/App.Tests.csproj", commandChanges(testFile)),
                "$sdk $xunit",
            )
            assertEquals(listOf("App.Tests.AlphaTests"), plan.classes)
        }
    }

    @Test
    fun `an unsupported locked xUnit version keeps the full native project`() {
        val root = createTempDirectory("mtp-version").toFile()
        writeNativeXunitProject(root, xunitVersion = "3.2.2")
        val testFile = File(root, "tests/AlphaTests.cs").apply {
            writeText("namespace App.Tests; public sealed class AlphaTests {}\n")
        }

        val command = dotnetCommands(
            root.path,
            listOf("tests/App.Tests.csproj:test"),
        ).single()

        assertEquals(listOf("dotnet", "test", "--project", "tests/App.Tests.csproj"), command.arguments)
        assertNull(selectMtpFilterClasses(root.path, "tests/App.Tests.csproj", commandChanges(testFile)))
    }

    @Test
    fun `ambiguous C sharp declarations keep the full native project`() {
        val root = createTempDirectory("mtp-ambiguous-source").toFile()
        writeNativeXunitProject(root)
        val testFile = File(root, "tests/AlphaTests.cs").apply {
            writeText(
                """
                using Xunit;
                namespace App.Tests;
                public sealed class AlphaTests { [Fact] public void Passes() {} }
                public sealed class HiddenTests { [Fact] public void Passes() {} }
                """.trimIndent(),
            )
        }

        val command = dotnetCommands(
            root.path,
            listOf("tests/App.Tests.csproj:test"),
        ).single()

        assertEquals(listOf("dotnet", "test", "--project", "tests/App.Tests.csproj"), command.arguments)
    }

    @Test
    fun `multiple proven classes use one filter switch and an exact minimum count`() {
        val root = createTempDirectory("mtp-multiple-classes").toFile()
        writeNativeXunitProject(root)
        val beta = nativeTestFile(root, "BetaTests")
        val alpha = nativeTestFile(root, "AlphaTests")
        val changes = commandChanges(beta, alpha)
        val selection = assertNotNull(dotnetMtpSelectionPlan(root.path, "tests/App.Tests.csproj", changes))

        assertEquals(
            listOf(
                "dotnet", "test", "--project", "tests/App.Tests.csproj", "--no-build",
                "--minimum-expected-tests", "2", "--filter-class",
                "App.Tests.AlphaTests", "App.Tests.BetaTests",
            ),
            dotnetMtpTestArguments(
                TestWorkspace(),
                root.path,
                "tests/App.Tests.csproj",
                changes,
                selection,
                { changes },
                { _, _, _ -> true },
            ),
        )
    }

    @Test
    fun `unsupported native metadata keeps full project selection`() {
        val mutations = listOf<Pair<String, (File) -> Unit>>(
            "older SDK band" to { root -> replace(File(root, "global.json"), "10.0.401", "10.0.399") },
            "future major SDK" to { root -> replace(File(root, "global.json"), "10.0.401", "11.0.400") },
            "prerelease SDK" to { root -> replace(File(root, "global.json"), "10.0.401", "10.0.401-rc.1") },
            "string boolean" to { root -> replace(File(root, "global.json"), "false", "\"false\"") },
            "extra global key" to { root -> replace(File(root, "global.json"), "\"test\"", "\"paths\": [], \"test\"") },
            "custom restore source" to { root ->
                appendProject(
                    root,
                    "<PropertyGroup><RestoreSources>https://example.invalid</RestoreSources></PropertyGroup>",
                )
            },
            "custom compile item" to { root ->
                appendProject(root, "<ItemGroup><Compile Remove=\"AlphaTests.cs\" /></ItemGroup>")
            },
            "extra package" to { root ->
                appendProject(root, "<ItemGroup><PackageReference Include=\"Other\" Version=\"1.0.0\" /></ItemGroup>")
            },
            "lowercase NuGet config" to { root -> File(root, "nuget.config").writeText("<configuration />") },
            "xUnit runner config" to { root -> File(root, "tests/xunit.runner.json").writeText("{}") },
            "MTP test config" to { root -> File(root, "tests/testconfig.json").writeText("{}") },
            "run settings" to { root -> File(root, "tests/custom.runsettings").writeText("<RunSettings />") },
            "launch profile" to { root ->
                File(root, "tests/Properties/launchSettings.json").apply {
                    parentFile.mkdirs()
                    writeText("{}")
                }
            },
            "content extension" to { root ->
                appendProject(root, "<ItemGroup><Content Include=\"xunit.runner.json\" /></ItemGroup>")
            },
            "analyzer extension" to { root ->
                appendProject(root, "<ItemGroup><Analyzer Include=\"custom.dll\" /></ItemGroup>")
            },
            "project extension" to { root ->
                appendProject(root, "<ItemGroup><ProjectReference Include=\"../Custom.csproj\" /></ItemGroup>")
            },
            "transitive xunit lock entry" to { root ->
                replace(File(root, "tests/packages.lock.json"), "\"type\": \"Direct\"", "\"type\": \"Transitive\"")
            },
            "floating xunit lock range" to { root ->
                replace(File(root, "tests/packages.lock.json"), "[4.0.1, 4.0.1]", "[4.0.1, )")
            },
            "mtp v1 lock entry" to { root ->
                val v1 = "\"xunit.v3.core.mtp-v1\": {\"type\": \"Transitive\", " +
                    "\"resolved\": \"4.0.1\", \"contentHash\": \"x\"},"
                replace(File(root, "tests/packages.lock.json"), "\"xunit.v3.mtp-v2\": {", "$v1 \"xunit.v3.mtp-v2\": {")
            },
        )

        mutations.forEach { (name, mutate) ->
            val root = createTempDirectory("mtp-metadata").toFile()
            writeNativeXunitProject(root)
            val testFile = nativeTestFile(root, "AlphaTests")
            mutate(root)
            assertNull(
                dotnetMtpSelectionPlan(root.path, "tests/App.Tests.csproj", commandChanges(testFile)),
                name,
            )
        }

        assertTrue(hasDotnetEnvironmentOverrides(mapOf("TestingPlatformCommandLineArguments" to "--filter-class X")))
        assertTrue(hasDotnetEnvironmentOverrides(mapOf("RestoreSources" to "https://example.invalid")))
        assertTrue(hasDotnetEnvironmentOverrides(mapOf("MSBuildUserExtensionsPath" to "/tmp/custom")))
        assertTrue(hasDotnetEnvironmentOverrides(mapOf("TESTINGPLATFORM_EXITCODE_IGNORE" to "2;8;9")))
    }

    @Test
    fun `unproved SDK 10 MTP metadata keeps the native full project grammar`() {
        val mutations = listOf<(File) -> Unit>(
            { root -> replace(File(root, "global.json"), "10.0.401", "10.0.399") },
            { root -> replace(File(root, "global.json"), "\"test\"", "\"paths\": [], \"test\"") },
        )

        mutations.forEach { mutate ->
            val root = createTempDirectory("mtp-full-grammar").toFile()
            writeNativeXunitProject(root)
            mutate(root)

            assertEquals(
                listOf("dotnet", "test", "--project", "tests/App.Tests.csproj"),
                dotnetCommands(root.path, listOf("tests/App.Tests.csproj:test")).single().arguments,
            )
        }
    }

    @Test
    fun `generated C sharp paths keep full project selection`() {
        val paths = listOf(
            "tests/Generated/AlphaTests.cs",
            "tests/AlphaTests.g.cs",
            "tests/AlphaTests.generated.cs",
            "tests/AlphaTests.Designer.cs",
        )

        paths.forEach { relative ->
            val root = createTempDirectory("mtp-generated-source").toFile()
            writeNativeXunitProject(root)
            val generated = File(root, relative).apply {
                parentFile.mkdirs()
                writeText(nativeTestSource("AlphaTests"))
            }

            assertNull(dotnetMtpSelectionPlan(root.path, "tests/App.Tests.csproj", commandChanges(generated)))
        }
    }
}

class MtpRuntimeProofTest {

    @Test
    fun `effective assets require the locked official xUnit MTP graph`() {
        val mutations = listOf<(File) -> Unit>(
            { root ->
                replace(
                    File(root, "tests/obj/project.assets.json"),
                    "https://api.nuget.org/v3/index.json",
                    "https://example.invalid/v3/index.json",
                )
            },
            { root -> corruptFirstJsonHash(File(root, "tests/obj/project.assets.json"), "sha512") },
            { root ->
                replace(
                    File(root, "packages/xunit.v3/4.0.1/.nupkg.metadata"),
                    "https://api.nuget.org/v3/index.json",
                    "https://example.invalid/v3/index.json",
                )
            },
            { root -> File(root, "packages/xunit.v3/4.0.1/xunit.v3.4.0.1.nupkg").appendText("tampered") },
            { root -> File(root, "packages/xunit.v3/4.0.1/xunit.v3.4.0.1.nupkg.sha512").writeText("invalid") },
            { root -> File(root, "packages/xunit.v3/4.0.1/build/xunit.v3.props").appendText("tampered") },
            { root ->
                replace(
                    File(root, "tests/obj/project.assets.json"),
                    "lib/net8.0/Microsoft.Testing.Platform.dll",
                    "lib/net8.0/Other.dll",
                )
            },
            { root -> replace(File(root, "tests/obj/project.assets.json"), "[4.0.1, 4.0.1]", "[4.0.1, )") },
        )

        mutations.forEachIndexed { index, mutate ->
            val root = createTempDirectory("mtp-assets").toFile()
            writeNativeXunitProject(root)
            writeNativeAssets(root)
            assertTrue(nativeMtpAssetsProof(root.path, "tests/App.Tests.csproj"))
            mutate(root)
            assertEquals(false, nativeMtpAssetsProof(root.path, "tests/App.Tests.csproj"), "mutation $index")
        }
    }

    @Test
    fun `runtime proof rejects assembly-specific output configuration`() {
        listOf(
            "testconfig.json",
            "xunit.runner.json",
            "App.Tests.testconfig.json",
            "App.Tests.xunit.runner.json",
        ).forEach { config ->
            val root = createTempDirectory("mtp-output-config").toFile()
            writeNativeXunitProject(root)
            writeNativeAssets(root)
            val selected = nativeTestFile(root, "AlphaTests")
            File(root, "tests/bin/Debug/net10.0/$config").apply {
                parentFile.mkdirs()
                writeText("{}")
            }
            val compile = nativeMsbuildOutput(root, selected)
            var calls = 0

            assertEquals(
                false,
                dotnetMtpRuntimeProof(
                    TestWorkspace(),
                    root.path,
                    "tests/App.Tests.csproj",
                    setOf(selected.toPath().toRealPath().toString()),
                    importProof = { _, _, _, _, _ -> true },
                ) { _, _, _, _ ->
                    if (calls++ == 0) "10.0.401" else compile
                },
                config,
            )
        }
    }

    @Test
    fun `runtime proof requires the active SDK to equal the global json SDK`() {
        val root = createTempDirectory("mtp-runtime-sdk").toFile()
        writeNativeXunitProject(root)
        writeNativeAssets(root)
        val selected = nativeTestFile(root, "AlphaTests")
        val compile = nativeMsbuildOutput(root, selected)
        val files = setOf(selected.toPath().toRealPath().toString())

        listOf("10.0.402" to false, "10.0.399" to false, "10.0.401" to true).forEach { (active, expected) ->
            var calls = 0
            assertEquals(
                expected,
                dotnetMtpRuntimeProof(
                    TestWorkspace(),
                    root.path,
                    "tests/App.Tests.csproj",
                    files,
                    importProof = { _, _, _, _, _ -> true },
                ) { _, _, _, _ ->
                    if (calls++ == 0) active else compile
                },
                active,
            )
        }
    }

    @Test
    fun `runtime proof rejects unproved effective imports`() {
        val root = createTempDirectory("mtp-runtime-imports").toFile()
        writeNativeXunitProject(root)
        writeNativeAssets(root)
        val selected = nativeTestFile(root, "AlphaTests")
        val compile = nativeMsbuildOutput(root, selected)
        var calls = 0

        assertEquals(
            false,
            dotnetMtpRuntimeProof(
                TestWorkspace(),
                root.path,
                "tests/App.Tests.csproj",
                setOf(selected.toPath().toRealPath().toString()),
                importProof = { _, _, _, _, _ -> false },
            ) { _, _, _, _ ->
                if (calls++ == 0) "10.0.401" else compile
            },
        )
    }

    @Test
    fun `runtime evidence mutation after the first proof keeps full project selection`() {
        val root = createTempDirectory("mtp-runtime-evidence-race").toFile()
        writeNativeXunitProject(root)
        val changes = commandChanges(nativeTestFile(root, "AlphaTests"))
        val selection = assertNotNull(dotnetMtpSelectionPlan(root.path, "tests/App.Tests.csproj", changes))
        var calls = 0

        assertEquals(
            listOf("dotnet", "test", "--project", "tests/App.Tests.csproj", "--no-build"),
            dotnetMtpTestArguments(
                TestWorkspace(),
                root.path,
                "tests/App.Tests.csproj",
                changes,
                selection,
                { changes },
                { _, _, _ -> calls++ == 0 },
            ),
        )
        assertEquals(2, calls)
    }

    @Test
    fun `unsupported source declarations keep full project selection`() {
        val declarations = listOf(
            "namespace App.Tests { public sealed class AlphaTests { [Fact] public void Passes() {} } }",
            "namespace App.Tests; public sealed partial class AlphaTests { [Fact] public void Passes() {} }",
            "namespace App.Tests; public sealed class AlphaTests<T> { [Fact] public void Passes() {} }",
            "namespace App.Tests; public sealed class AlphaTests { public void Passes() {} }",
            "namespace App.Tests; public sealed class AlphaTests { [MyFact] public void Passes() {} }",
        )

        declarations.forEachIndexed { index, declaration ->
            val root = createTempDirectory("mtp-source-$index").toFile()
            writeNativeXunitProject(root)
            val testFile = File(root, "tests/AlphaTests.cs").apply {
                writeText("using Xunit;\n$declaration")
            }
            assertNull(dotnetMtpSelectionPlan(root.path, "tests/App.Tests.csproj", commandChanges(testFile)))
        }
    }

    @Test
    fun `non C sharp and incomplete change sets keep full project selection`() {
        val root = createTempDirectory("mtp-change-boundary").toFile()
        writeNativeXunitProject(root)
        val csharp = nativeTestFile(root, "AlphaTests")
        val fsharp = File(root, "tests/BetaTests.fs").apply { writeText("module App.Tests.BetaTests") }

        assertNull(dotnetMtpSelectionPlan(root.path, "tests/App.Tests.csproj", commandChanges(fsharp)))
        assertNull(
            dotnetMtpSelectionPlan(
                root.path,
                "tests/App.Tests.csproj",
                BuildChanges(listOf(csharp.path), emptySet(), comparedToBase = true),
            ),
        )
        assertNull(
            dotnetMtpSelectionPlan(
                root.path,
                "tests/App.Tests.csproj",
                BuildChanges(listOf(csharp.path), setOf(csharp.path), comparedToBase = false),
            ),
        )
    }

    @Test
    fun `more than thirty two changed classes keep full project selection`() {
        val root = createTempDirectory("mtp-class-limit").toFile()
        writeNativeXunitProject(root)
        val files = (1..33).map { index -> nativeTestFile(root, "Class${index}Tests") }

        assertNull(
            dotnetMtpSelectionPlan(
                root.path,
                "tests/App.Tests.csproj",
                BuildChanges(
                    files.map(File::getPath),
                    files.mapTo(LinkedHashSet(), File::getPath),
                    comparedToBase = true,
                ),
            ),
        )
    }

    @Test
    fun `a source below an intermediate symlink keeps full project selection`() {
        val root = createTempDirectory("mtp-source-link").toFile()
        writeNativeXunitProject(root)
        val external = createTempDirectory("mtp-source-external").toFile()
        val testFile = nativeTestFile(external, "AlphaTests")
        val link = File(root, "tests/linked").toPath()
        assumeTrue(runCatching { Files.createSymbolicLink(link, external.toPath()) }.isSuccess)
        val linked = link.resolve(testFile.name).toFile()

        assertNull(dotnetMtpSelectionPlan(root.path, "tests/App.Tests.csproj", commandChanges(linked)))
    }

    @Test
    fun `runtime ownership rejection keeps full project selection`() {
        val root = createTempDirectory("mtp-runtime-reject").toFile()
        writeNativeXunitProject(root)
        val testFile = nativeTestFile(root, "AlphaTests")
        val changes = commandChanges(testFile)
        val selection = assertNotNull(dotnetMtpSelectionPlan(root.path, "tests/App.Tests.csproj", changes))

        assertEquals(
            listOf("dotnet", "test", "--project", "tests/App.Tests.csproj", "--no-build"),
            dotnetMtpTestArguments(
                TestWorkspace(),
                root.path,
                "tests/App.Tests.csproj",
                changes,
                selection,
                { changes },
                { _, _, _ -> false },
            ),
        )
    }

    @Test
    fun `runtime proof rejects a local Xunit attribute shadow`() {
        listOf("Xunit", "@Xunit", "X\\u0075nit").forEach { namespace ->
            val root = createTempDirectory("mtp-runtime-shadow").toFile()
            writeNativeXunitProject(root)
            writeNativeAssets(root)
            val selected = nativeTestFile(root, "AlphaTests")
            val shadow = File(root, "tests/XunitShadow.cs").apply {
                writeText("namespace $namespace; public sealed class FactAttribute : System.Attribute {}")
            }
            val compile = nativeMsbuildOutput(root, selected, shadow)
            var calls = 0

            assertEquals(
                false,
                dotnetMtpRuntimeProof(
                    TestWorkspace(),
                    root.path,
                    "tests/App.Tests.csproj",
                    setOf(selected.toPath().toRealPath().toString()),
                    importProof = { _, _, _, _, _ -> true },
                ) { _, _, _, _ ->
                    if (calls++ == 0) "10.0.401" else compile
                },
                namespace,
            )
        }
    }

    @Test
    fun `runtime proof rejects source-defined assembly extensions`() {
        listOf(
            "[assembly: global::Xunit.TestFramework(typeof(App.Tests.CustomFramework))]",
            "using Xunit; [assembly: TestFramework(typeof(App.Tests.CustomFramework))]",
        ).forEach { attribute ->
            val root = createTempDirectory("mtp-runtime-extension").toFile()
            writeNativeXunitProject(root)
            writeNativeAssets(root)
            val selected = nativeTestFile(root, "AlphaTests")
            val extension = File(root, "tests/AssemblyInfo.cs").apply {
                writeText("$attribute\nnamespace App.Tests; public sealed class CustomFramework {}")
            }
            val compile = nativeMsbuildOutput(root, selected, extension)
            var calls = 0

            assertEquals(
                false,
                dotnetMtpRuntimeProof(
                    TestWorkspace(),
                    root.path,
                    "tests/App.Tests.csproj",
                    setOf(selected.toPath().toRealPath().toString()),
                    importProof = { _, _, _, _, _ -> true },
                ) { _, _, _, _ ->
                    if (calls++ == 0) "10.0.401" else compile
                },
                attribute,
            )
        }
    }

    @Test
    fun `runtime metadata cancellation is propagated`() {
        val root = createTempDirectory("mtp-runtime-cancel").toFile()
        writeNativeXunitProject(root)
        val selected = nativeTestFile(root, "AlphaTests")

        assertFailsWith<CancellationException> {
            dotnetMtpRuntimeProof(
                TestWorkspace(),
                root.path,
                "tests/App.Tests.csproj",
                setOf(selected.path),
            ) { _, _, _, _ ->
                throw CancellationException()
            }
        }
        assertFailsWith<PlatformCancellation> {
            dotnetMtpRuntimeProof(
                TestWorkspace(),
                root.path,
                "tests/App.Tests.csproj",
                setOf(selected.path),
            ) { _, _, _, _ ->
                throw PlatformCancellation()
            }
        }
    }

    @Test
    fun `current change interruption is propagated and restores the flag`() {
        val root = createTempDirectory("mtp-change-interrupted").toFile()
        writeNativeXunitProject(root)
        val changes = commandChanges(nativeTestFile(root, "AlphaTests"))
        val selection = dotnetMtpSelectionPlan(root.path, "tests/App.Tests.csproj", changes)
        Thread.interrupted()
        try {
            assertFailsWith<InterruptedException> {
                dotnetMtpTestArguments(
                    TestWorkspace(),
                    root.path,
                    "tests/App.Tests.csproj",
                    changes,
                    selection,
                    { throw InterruptedException("interrupted") },
                )
            }
            assertEquals(true, Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `runtime file hashing propagates interruption`() {
        val file = Files.createTempFile("dotnet-mtp-hash", ".bin").toFile().apply { writeBytes(ByteArray(1_024)) }
        Thread.currentThread().interrupt()
        try {
            assertFailsWith<InterruptedException> { dotnetFileSha256(file.toPath()) }
        } finally {
            assertTrue(Thread.interrupted())
        }
    }
}

private fun writeNativeXunitProject(root: File, xunitVersion: String = "4.0.1") {
    val fixture = fixtureRoot().resolve("dotnet-mtp-xunit4")
    File(root, "global.json").writeText(File(fixture, "global.json").readText())
    File(root, "tests/App.Tests.csproj").apply {
        parentFile.mkdirs()
        writeText(
            File(fixture, "Mtp.Tests/Mtp.Tests.csproj").readText()
                .replace("[4.0.1]", "[$xunitVersion]"),
        )
    }
    File(root, "tests/packages.lock.json").writeText(
        File(fixture, "Mtp.Tests/packages.lock.json").readText()
            .replace("4.0.1", xunitVersion),
    )
}

private val NATIVE_ASSEMBLIES = mapOf(
    "xunit.v3.core.mtp-v2" to "xunit.v3.mtp-v2.dll",
    "Microsoft.Testing.Platform" to "Microsoft.Testing.Platform.dll",
)

private fun nativeTestFile(root: File, className: String): File = File(root, "tests/$className.cs").apply {
    parentFile.mkdirs()
    writeText(nativeTestSource(className))
}

private fun nativeTestSource(className: String): String =
    """
    using Xunit;
    namespace App.Tests;
    public sealed class $className { [global::Xunit.Fact] public void Passes() {} }
    """.trimIndent()

private fun writeNativeAssets(root: File) {
    val lock = JsonParser.parseString(File(root, "tests/packages.lock.json").readText()).asJsonObject
    val dependencies = lock.getAsJsonObject("dependencies").getAsJsonObject("net10.0")
    val libraries = JsonObject()
    val target = JsonObject()
    val packages = File(root, "packages").apply { mkdirs() }
    dependencies.entrySet().forEach { (name, value) ->
        writeNativePackageAssets(name, value.asJsonObject, packages, libraries, target)
    }
    File(root, "tests/packages.lock.json").writeText(lock.toString())
    val rootJson = JsonObject().apply {
        addProperty("version", 4)
        add("targets", JsonObject().apply { add("net10.0", target) })
        add("libraries", libraries)
        add(
            "packageFolders",
            JsonObject().apply { add(packages.toPath().toRealPath().toString() + File.separator, JsonObject()) },
        )
        add(
            "project",
            JsonParser.parseString(
                """{
                "restore":{"sources":{"https://api.nuget.org/v3/index.json":{}}},
                "frameworks":{"net10.0":{"dependencies":{"xunit.v3":{"target":"Package","version":"[4.0.1, 4.0.1]"}}}}
                }""".trimIndent(),
            ),
        )
    }
    File(root, "tests/obj/project.assets.json").apply {
        parentFile.mkdirs()
        writeText(rootJson.toString())
    }
}

private fun writeNativePackageAssets(
    name: String,
    dependency: JsonObject,
    packages: File,
    libraries: JsonObject,
    target: JsonObject,
) {
    val version = dependency.get("resolved").asString
    val key = "$name/$version"
    val packageDirectory = File(packages, "${name.lowercase()}/$version").apply { mkdirs() }
    val archiveName = "${name.lowercase()}.$version.nupkg"
    val archive = File(packageDirectory, archiveName)
    val nuspec = "$name.nuspec"
    val extractedNuspec = "${name.lowercase()}.nuspec"
    val entries = linkedMapOf(
        nuspec to "<package><metadata><id>$name</id><version>$version</version></metadata></package>",
        "build/${name.lowercase()}.props" to "<Project />",
    )
    NATIVE_ASSEMBLIES[name]?.let { assembly -> entries["lib/net8.0/$assembly"] = "assembly" }
    ZipOutputStream(archive.outputStream()).use { zip ->
        entries.forEach { (path, content) ->
            zip.putNextEntry(ZipEntry(path))
            zip.write(content.toByteArray())
            zip.closeEntry()
            val extractedPath = if (path == nuspec) extractedNuspec else path
            File(packageDirectory, extractedPath).apply {
                parentFile.mkdirs()
                writeText(content)
            }
        }
    }
    val hash = Base64.getEncoder().encodeToString(
        MessageDigest.getInstance("SHA-512").digest(archive.readBytes()),
    )
    dependency.addProperty("contentHash", hash)
    libraries.add(
        key,
        JsonObject().apply {
            addProperty("type", "package")
            addProperty("sha512", hash)
            addProperty("path", "${name.lowercase()}/$version")
            add(
                "files",
                JsonParser.parseString(
                    entries.keys.map { path -> if (path == nuspec) extractedNuspec else path }
                        .plus(listOf(".nupkg.metadata", "$archiveName.sha512"))
                        .joinToString(",", "[", "]") { "\"$it\"" },
                ),
            )
        },
    )
    target.add(key, JsonObject())
    File(packageDirectory, ".nupkg.metadata").writeText(
        """{"version":2,"contentHash":"$hash","source":"https://api.nuget.org/v3/index.json"}""",
    )
    File(packageDirectory, "$archiveName.sha512").writeText(hash)
}

private fun corruptFirstJsonHash(file: File, name: String) {
    val text = file.readText()
    val pattern = Regex("(\\\"${Regex.escape(name)}\\\"\\s*:\\s*\\\")([^\\\"]+)")
    val match = requireNotNull(pattern.find(text))
    file.writeText(text.replaceRange(match.range, match.groupValues[1] + "invalid"))
}

private fun nativeMsbuildOutput(root: File, vararg sources: File): String {
    val output = File(root, "tests/bin/Debug/net10.0/App.Tests.dll").apply {
        parentFile.mkdirs()
        writeText("assembly")
    }
    val items = sources.joinToString(",") { source ->
        """{"FullPath":${JsonParser.parseString("\"${source.toPath().toRealPath()}\"")}}"""
    }
    return """
        {"Properties":{
          "TargetPath":${JsonParser.parseString("\"${output.toPath().toRealPath()}\"")},
          "TargetDir":${JsonParser.parseString("\"${output.parentFile.toPath().toRealPath()}${File.separator}\"")},
          "AssemblyName":"App.Tests",
          "Configuration":"Debug",
          "OutputPath":"bin/Debug/net10.0/",
          "ProjectAssetsFile":${JsonParser.parseString("\"${File(root, "tests/obj/project.assets.json").toPath().toRealPath()}\"")},
          "MSBuildSDKsPath":${JsonParser.parseString("\"${File(root, "sdk/10.0.401/Sdks").absolutePath}\"")},
          "TestingPlatformCommandLineArguments":"",
          "RunSettingsFilePath":"",
          "VSTestTestCaseFilter":"",
          "RestoreSources":""
        },"Items":{"Compile":[$items]}}
    """.trimIndent()
}

private fun appendProject(root: File, xml: String) {
    val project = File(root, "tests/App.Tests.csproj")
    replace(project, "</Project>", "$xml</Project>")
}

private fun replace(file: File, from: String, to: String) {
    file.writeText(file.readText().replace(from, to))
}

private fun fixtureRoot(): File = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
    .map { File(it, "conformance/cli-fixtures") }
    .firstOrNull(File::isDirectory)
    ?: error("conformance fixtures not found")

private fun commandChanges(file: File) = BuildChanges(
    files = listOf(file.path),
    exactSelectionEligible = setOf(file.path),
    comparedToBase = true,
)

private fun commandChanges(vararg files: File) = BuildChanges(
    files = files.map(File::getPath),
    exactSelectionEligible = files.mapTo(LinkedHashSet(), File::getPath),
    comparedToBase = true,
)

private class PlatformCancellation : CancellationException("platform cancellation")
