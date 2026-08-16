package com.aspix2k.affected.build

import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DotnetCliConformanceTest {

    @Test
    fun `dotnet commands run both selected test projects`() = fixture { root ->
        prepareFixture(root)
        val modules = DotnetProjects.parse(root).filter { it.testTask == DotnetProjects.TEST }
        val output = dotnetCommands(root.path, modules.map { "${it.executionId}:test" })
            .joinToString("\n") { execute(root, it.arguments) }

        assertContains(output, "Alpha.Tests")
        assertContains(output, "Beta.Tests")
    }

    @Test
    fun `dotnet runs exact VSTest identities and preserves full fallback`() = fixture { root ->
        prepareFixture(root)
        val context = context(root)
        execute(root, dotnetBuildCommand(context.project).arguments)
        val (baseline, fullMillis) = collectBaseline(context)

        val alpha = File(root, "Alpha/AlphaValue.cs")
        alpha.writeText(alpha.readText().replace("Get() => 1;", "Get() => int.Parse(\"1\");"))
        execute(root, dotnetBuildCommand(context.project).arguments)
        val exact = exactSelection(context, baseline, alpha)
        val exactReport = newDotnetReport(context.cache.resolve("reports"))
        val exactMillis = measureTimeMillis {
            execute(root, dotnetTestArguments(context.project, exact, exactReport))
        }
        assertEquals(exact.tests.toSet(), assertNotNull(readDotnetTestReport(exactReport)).tests.keys)
        assertTrue(exactMillis < fullMillis, "exact=$exactMillis ms, full=$fullMillis ms")

        assertGeneratedAndDynamicFallback(context, baseline, alpha)
        assertMetadataAndHelperFallback(context, baseline)
        assertTestAssemblyFallback(context, baseline, alpha)
        assertFrameworkFilter(context, "NUnit.Tests/NUnit.Tests.csproj", "NUnit.Tests.AlphaTest.Passes")
        assertFrameworkFilter(context, "MSTest.Tests/MSTest.Tests.csproj", "MSTest.Tests.AlphaTest.Passes")
        assertNUnitDiscoveryFallback(context)
        assertGlobalLifecycleFallback(context)
        assertConfigurationFallback(context)
    }

    private fun collectBaseline(context: Context): Pair<DotnetTestSnapshot, Long> {
        val metadata = assertNotNull(
            readDotnetProjectMetadata(context.rootPath, context.project, context.productionProjects),
        )
        val before = assertNotNull(analyzeDotnetProject(metadata, emptySet(), context.cache))
        val report = newDotnetReport(context.cache.resolve("reports"))
        val duration = measureTimeMillis {
            execute(context.root, dotnetTestArguments(context.project, DotnetTestSelection.Full, report))
        }
        val full = assertNotNull(readDotnetTestReport(report))
        val after = assertNotNull(analyzeDotnetProject(metadata, full.tests.values.toSet(), context.cache))
        assertTrue("Beta" in after.classes.getValue("Alpha.Tests.AlphaTest"))
        assertEquals(before.identity, after.identity)
        assertEquals(before.testAssemblySha256, after.testAssemblySha256)
        assertEquals(before.artifacts, after.artifacts)
        val store = DotnetTestBaselineStore(context.cache.resolve("maps"))
        store.write(after.snapshot(full.tests))
        return assertNotNull(store.read()) to duration
    }

    private fun exactSelection(
        context: Context,
        baseline: DotnetTestSnapshot,
        alpha: File,
    ): DotnetTestSelection.Exact {
        val metadata = assertNotNull(
            readDotnetProjectMetadata(context.rootPath, context.project, context.productionProjects),
        )
        val current = assertNotNull(analyzeDotnetProject(metadata, baseline.classes.keys, context.cache))
            .snapshot(baseline.tests)
        assertEquals(baseline.identity, current.identity)
        assertEquals(baseline.testAssemblySha256, current.testAssemblySha256)
        assertEquals(baseline.classes, current.classes)
        assertEquals(baseline.tests, current.tests)
        val changes = changes(alpha)
        assertTrue(eligibleDotnetChanges(context.rootPath, context.productionRoots, changes))
        val selection = assertIs<DotnetTestSelection.Exact>(
            selectDotnetTests(context.rootPath, context.productionRoots, current, baseline, changes),
        )
        assertEquals(
            listOf(
                "Alpha.Tests.AlphaTest.Passes",
                "Alpha.Tests.GenericArgumentTest.Passes",
            ),
            selection.tests,
        )
        return selection
    }

    private fun assertGeneratedAndDynamicFallback(
        context: Context,
        baseline: DotnetTestSnapshot,
        alpha: File,
    ) {
        val project = File(context.root, "Alpha/Alpha.csproj")
        val original = project.readText()
        project.writeText(
            original.replace(
                "</Project>",
                "<ItemGroup><Compile Update=\"AlphaValue.cs\" AutoGen=\"true\" /></ItemGroup></Project>",
            ),
        )
        assertFalse(dotnetChangedSourcesAreOwned(context.rootPath, context.productionProjects, changes(alpha)))
        project.writeText(original)
        assertTrue(dotnetChangedSourcesAreOwned(context.rootPath, context.productionProjects, changes(alpha)))

        val dynamic = File(context.root, "Alpha/DynamicValue.cs").apply {
            writeText(
                "namespace Alpha; public static class DynamicValue { " +
                    "public static object Load(string name) => System.Reflection.Assembly.Load(name); }\n",
            )
        }
        execute(context.root, dotnetBuildCommand(context.project).arguments)
        assertNull(
            analyzeDotnetProject(
                assertNotNull(readDotnetProjectMetadata(context.rootPath, context.project)),
                baseline.classes.keys,
                context.cache,
            ),
        )
        assertTrue(dynamic.delete())
        execute(context.root, dotnetBuildCommand(context.project).arguments)
    }

    private fun assertTestAssemblyFallback(
        context: Context,
        baseline: DotnetTestSnapshot,
        alpha: File,
    ) {
        File(context.root, "Alpha.Tests/AlphaTest.cs").appendText(
            "\npublic static class TestAssemblyChange { public static int Value() => 1; }\n",
        )
        execute(context.root, dotnetBuildCommand(context.project).arguments)
        val changed = assertNotNull(
            analyzeDotnetProject(
                assertNotNull(readDotnetProjectMetadata(context.rootPath, context.project)),
                baseline.classes.keys,
                context.cache,
            ),
        ).snapshot(baseline.tests)
        assertEquals(
            DotnetTestSelection.Full,
            selectDotnetTests(
                context.rootPath,
                context.productionRoots,
                changed,
                baseline,
                changes(alpha),
            ),
        )
        val assemblyConfiguration = File(context.root, "Alpha.Tests/AssemblyConfiguration.cs").apply {
            writeText("[assembly: Xunit.TestCollectionOrderer(\"Custom.Orderer\", \"Custom\")]\n")
        }
        execute(context.root, dotnetBuildCommand(context.project).arguments)
        assertNull(analyzeCurrent(context, baseline))
        assertTrue(assemblyConfiguration.delete())
        File(context.root, "Alpha.Tests/ParameterizedTest.cs").writeText(
            "using Xunit; namespace Alpha.Tests; public sealed class ParameterizedTest { " +
                "[Theory] [InlineData(1)] public void Passes(int value) => Assert.Equal(1, value); }\n",
        )
        execute(context.root, dotnetBuildCommand(context.project).arguments)
        assertNull(
            analyzeDotnetProject(
                assertNotNull(readDotnetProjectMetadata(context.rootPath, context.project)),
                baseline.classes.keys,
                context.cache,
            ),
        )
    }

    private fun assertMetadataAndHelperFallback(context: Context, baseline: DotnetTestSnapshot) {
        val alpha = File(context.root, "Alpha/AlphaValue.cs")
        val originalAlpha = alpha.readText()
        alpha.writeText(
            originalAlpha.replace(
                "public static class AlphaValue",
                "[System.ComponentModel.TypeConverter(\"Beta.Converter, Beta\")]\npublic static class AlphaValue",
            ),
        )
        execute(context.root, dotnetBuildCommand(context.project).arguments)
        assertNull(analyzeCurrent(context, baseline))
        alpha.writeText(originalAlpha)

        val sdk = execute(context.root, listOf("dotnet", "--version")).trim().substringBefore('.')
        val helper = File(context.root, "FixtureHelper").apply { mkdirs() }
        File(helper, "FixtureHelper.csproj").writeText(
            "<Project Sdk=\"Microsoft.NET.Sdk\"><PropertyGroup>" +
                "<TargetFramework>net$sdk.0</TargetFramework></PropertyGroup></Project>",
        )
        File(helper, "FixtureBase.cs").writeText(
            "namespace FixtureHelper; public class FixtureBase { " +
                "public static void Load() => System.Reflection.Assembly.Load(\"Beta\"); }\n",
        )
        execute(context.root, dotnetBuildCommand("FixtureHelper/FixtureHelper.csproj").arguments)
        val project = File(context.root, context.project)
        val originalProject = project.readText()
        val helperAssembly = File(helper, "bin/Debug/net$sdk.0/FixtureHelper.dll").invariantSeparatorsPath
        project.writeText(
            originalProject.replace(
                "</Project>",
                "<ItemGroup><Reference Include=\"FixtureHelper\"><HintPath>$helperAssembly" +
                    "</HintPath></Reference></ItemGroup></Project>",
            ),
        )
        val test = File(context.root, "Alpha.Tests/AlphaTest.cs")
        val originalTest = test.readText()
        test.writeText(
            originalTest.replace(
                "public class AlphaTest",
                "public class AlphaTest : FixtureHelper.FixtureBase",
            ),
        )
        execute(context.root, dotnetBuildCommand(context.project).arguments)
        assertNull(analyzeCurrent(context, baseline))
        project.writeText(originalProject)
        test.writeText(originalTest)
        execute(context.root, dotnetBuildCommand(context.project).arguments)
    }

    private fun analyzeCurrent(context: Context, baseline: DotnetTestSnapshot): DotnetAnalyzedState? =
        analyzeDotnetProject(
            assertNotNull(readDotnetProjectMetadata(context.rootPath, context.project, context.productionProjects)),
            baseline.classes.keys,
            context.cache,
        )

    private fun assertFrameworkFilter(context: Context, project: String, expected: String) {
        execute(context.root, dotnetBuildCommand(project).arguments)
        val metadata = assertNotNull(readDotnetProjectMetadata(context.rootPath, project))
        val fullReport = newDotnetReport(context.cache.resolve("reports"))
        execute(context.root, dotnetTestArguments(project, DotnetTestSelection.Full, fullReport))
        val full = assertNotNull(readDotnetTestReport(fullReport))
        assertEquals(2, full.tests.size)
        val analyzed = assertNotNull(analyzeDotnetProject(metadata, full.tests.values.toSet(), context.cache), project)
        assertTrue("Alpha" in analyzed.classes.getValue(expected.substringBeforeLast('.')))
        val exactReport = newDotnetReport(context.cache.resolve("reports"))
        execute(context.root, dotnetTestArguments(project, DotnetTestSelection.Exact(listOf(expected)), exactReport))
        assertEquals(setOf(expected), assertNotNull(readDotnetTestReport(exactReport)).tests.keys)
    }

    private fun assertGlobalLifecycleFallback(context: Context) {
        val project = "MSTest.Tests/MSTest.Tests.csproj"
        val source = File(context.root, "MSTest.Tests/AlphaTest.cs")
        val original = source.readText()
        source.writeText(
            original.replace(
                "public class AlphaTest\n{",
                "public class AlphaTest\n{\n" +
                    "    [Microsoft.VisualStudio.TestTools.UnitTesting.AssemblyInitialize]\n" +
                    "    public static void Initialize(" +
                    "Microsoft.VisualStudio.TestTools.UnitTesting.TestContext context) => " +
                    "System.GC.KeepAlive(Beta.BetaValue.Get());",
            ),
        )
        execute(context.root, dotnetBuildCommand(project).arguments)
        val metadata = assertNotNull(readDotnetProjectMetadata(context.rootPath, project))
        assertNull(
            analyzeDotnetProject(
                metadata,
                setOf("MSTest.Tests.AlphaTest", "MSTest.Tests.BetaTest"),
                context.cache,
            ),
        )
        source.writeText(original)
    }

    private fun assertNUnitDiscoveryFallback(context: Context) {
        val project = "NUnit.Tests/NUnit.Tests.csproj"
        val source = File(context.root, "NUnit.Tests/AlphaTest.cs")
        val original = source.readText()
        source.writeText(
            original.replace(
                "public void Passes()",
                "public void Passes([NUnit.Framework.Values(1)] int value)",
            ),
        )
        execute(context.root, dotnetBuildCommand(project).arguments)
        val metadata = assertNotNull(readDotnetProjectMetadata(context.rootPath, project))
        assertNull(
            analyzeDotnetProject(
                metadata,
                setOf("NUnit.Tests.AlphaTest", "NUnit.Tests.BetaTest"),
                context.cache,
            ),
        )
        source.writeText(original)
        execute(context.root, dotnetBuildCommand(project).arguments)
    }

    private fun assertConfigurationFallback(context: Context) {
        val settings = File(context.root, "custom.runsettings").apply { writeText("<RunSettings />\n") }
        assertNull(readDotnetProjectMetadata(context.rootPath, context.project))
        assertTrue(settings.delete())
        val project = File(context.root, context.project)
        val original = project.readText()
        project.writeText(
            original.replace(
                "</Project>",
                "<Target Name=\"CustomVSTestInput\" BeforeTargets=\"VSTest\" /></Project>",
            ),
        )
        assertNull(readDotnetProjectMetadata(context.rootPath, context.project))
        project.writeText(original)
        project.writeText(
            original.replace(
                "</Project>",
                "<PropertyGroup><UseMicrosoftTestingPlatformRunner>true" +
                    "</UseMicrosoftTestingPlatformRunner></PropertyGroup></Project>",
            ),
        )
        assertNull(readDotnetProjectMetadata(context.rootPath, context.project))
    }

    private fun context(root: File): Context {
        val modules = DotnetProjects.parse(root)
        val test = modules.single { it.executionId == "Alpha.Tests/Alpha.Tests.csproj" }
        val dependencies = test.dependencies.map { key -> modules.single { it.key == key } }
        val cache = File(root, ".affected-cache").toPath().also(Files::createDirectories)
        return Context(
            root,
            root.canonicalFile.toPath(),
            test.executionId,
            dependencies.mapTo(LinkedHashSet()) { Path.of(it.root).resolve(it.executionId) },
            dependencies.mapTo(LinkedHashSet()) { Path.of(it.contentRoots.single()) },
            cache,
        )
    }

    private fun prepareFixture(root: File) {
        val major = execute(root, listOf("dotnet", "--version")).trim().substringBefore('.').toInt()
        assertTrue(major in 8..10)
        if (major == 10) return
        ManifestSearch.findByExtension(root, "csproj").forEach { project ->
            project.writeText(project.readText().replace("net10.0", "net$major.0"))
        }
    }

    private fun changes(file: File): BuildChanges =
        BuildChanges(listOf(file.path), setOf(file.path), comparedToBase = true)

    private fun fixture(block: (File) -> Unit) {
        assumeTrue(System.getProperty(CONFORMANCE_PROPERTY) == "true")
        val source = fixtureRoot().resolve("dotnet")
        assertTrue(source.isDirectory, "Missing CLI conformance fixture: $source")
        val target = createTempDirectory("affected-cli-dotnet").toFile()
        try {
            assertTrue(source.copyRecursively(target, overwrite = true), "Could not copy $source")
            block(target)
        } finally {
            target.deleteRecursively()
        }
    }

    private fun fixtureRoot(): File = CliConformanceRepository.configured.fixturesRoot()

    private fun execute(directory: File, arguments: List<String>): String {
        val output = File.createTempFile("affected-dotnet-output", ".log")
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
            assertEquals(0, process.exitValue(), "Failed: ${arguments.joinToString(" ")}\n$text")
            return text
        } finally {
            output.delete()
        }
    }

    private data class Context(
        val root: File,
        val rootPath: Path,
        val project: String,
        val productionProjects: Set<Path>,
        val productionRoots: Set<Path>,
        val cache: Path,
    )

    private companion object {
        const val CONFORMANCE_PROPERTY = "affected.cliConformance"
        const val COMMAND_TIMEOUT_SECONDS = 180L
    }
}
