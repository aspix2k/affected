package com.aspix2k.affected

import com.aspix2k.affected.build.DotnetProjects
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DotnetProjectsTest {

    private fun solution(): File = createTempDirectory("dotnet").toFile()

    private fun project(root: File, path: String, body: String, sdk: String = "Microsoft.NET.Sdk") {
        val file = File(root, path)
        file.parentFile.mkdirs()
        file.writeText("<Project Sdk=\"$sdk\">$body</Project>")
    }

    @Test
    fun `projects are found across the tree`() {
        val root = solution()
        project(root, "src/Lib/Lib.csproj", "")
        project(root, "test/Lib.Tests/Lib.Tests.csproj", "")

        val modules = DotnetProjects.parse(root)

        assertEquals(setOf("Lib", "Lib.Tests"), modules.map { it.id }.toSet())
    }

    @Test
    fun `a backslash reference resolves on every system`() {
        val root = solution()
        project(root, "src/Lib/Lib.csproj", "")
        project(
            root,
            "test/Lib.Tests/Lib.Tests.csproj",
            """<ItemGroup><ProjectReference Include="..\..\src\Lib\Lib.csproj" /></ItemGroup>""",
        )

        val tests = DotnetProjects.parse(root).single { it.id == "Lib.Tests" }

        assertEquals(
            setOf("DOTNET|${root.invariantSeparatorsPath}|Lib"),
            tests.dependencies,
            "MSBuild emits Windows separators even on macOS",
        )
    }

    @Test
    fun `a project reference is parsed regardless of attribute order`() {
        val root = solution()
        project(root, "src/Lib/Lib.csproj", "")
        project(
            root,
            "test/Lib.Tests/Lib.Tests.csproj",
            """
                <ItemGroup>
                    <ProjectReference Condition="'$(Configuration)' == 'Debug'" Include="../../src/Lib/Lib.csproj" />
                </ItemGroup>
            """.trimIndent(),
        )

        val tests = DotnetProjects.parse(root).single { it.id == "Lib.Tests" }

        assertEquals(setOf("DOTNET|${root.invariantSeparatorsPath}|Lib"), tests.dependencies)
    }

    @Test
    fun `a reference to a missing project creates no edge`() {
        val root = solution()
        project(
            root,
            "src/App/App.csproj",
            """<ItemGroup><ProjectReference Include="..\Missing\Missing.csproj" /></ItemGroup>""",
        )

        assertEquals(emptySet(), DotnetProjects.parse(root).single().dependencies)
    }

    @Test
    fun `a project referencing a test framework is testable`() {
        val root = solution()
        project(root, "src/Lib/Lib.csproj", "")
        project(
            root,
            "test/Lib.Tests/Lib.Tests.csproj",
            """<ItemGroup><PackageReference Include="xunit" Version="2.9.0" /></ItemGroup>""",
        )

        val modules = DotnetProjects.parse(root)

        assertTrue(modules.single { it.id == "Lib.Tests" }.hasTests)
        assertEquals("test", modules.single { it.id == "Lib.Tests" }.testTask)
        assertEquals("build", modules.single { it.id == "Lib" }.testTask)
    }

    @Test
    fun `an MSTest SDK project remains runnable at project level`() {
        val root = solution()
        project(root, "test/Lib.Tests/Lib.Tests.csproj", "", sdk = "MSTest.Sdk/4.3.3")

        val module = DotnetProjects.parse(root).single()

        assertTrue(module.hasTests)
        assertEquals("test", module.testTask)
    }

    @Test
    fun `build directories are not scanned`() {
        val root = solution()
        project(root, "src/Lib/Lib.csproj", "")
        project(root, "src/Lib/obj/Debug/Ghost.csproj", "")
        project(root, "src/Lib/bin/Release/Ghost2.csproj", "")

        assertEquals(listOf("Lib"), DotnetProjects.parse(root).map { it.id })
    }

    @Test
    fun `fsproj and vbproj files are also projects`() {
        val root = solution()
        project(root, "src/Fs/Fs.fsproj", "")
        project(root, "src/Vb/Vb.vbproj", "")

        assertEquals(setOf("Fs", "Vb"), DotnetProjects.parse(root).map { it.id }.toSet())
    }

    @Test
    fun `execution identity is the project file path`() {
        val root = solution()
        project(root, "src/Library With Spaces/Library.csproj", "")

        val module = DotnetProjects.parse(root).single()

        assertEquals("src/Library With Spaces/Library.csproj", module.executionId)
    }

    @Test
    fun `duplicate project names keep distinct graph identities`() {
        val root = solution()
        project(root, "src/First/Shared.csproj", "")
        project(root, "src/Second/Shared.csproj", "")

        val modules = DotnetProjects.parse(root)

        assertEquals(setOf("src/First/Shared", "src/Second/Shared"), modules.map { it.id }.toSet())
        assertEquals(2, modules.map { it.key }.toSet().size)
    }

    @Test
    fun `real Serilog data is parsed with graph edges`() {
        assumeTrue(FixtureRepository.available("dotnet-serilog"))
        val root = File(FixtureRepository.root, "dotnet-serilog")

        val modules = DotnetProjects.parse(root)

        assertTrue(modules.size >= 4, "Serilog has several projects, parsed ${modules.size}")
        assertTrue(modules.any { it.dependencies.isNotEmpty() }, "test projects reference Serilog")
        assertTrue(modules.any { it.hasTests }, "Serilog has test projects")

        val keys = modules.map { it.key }.toSet()
        val dangling = modules.flatMap { it.dependencies }.filterNot { it in keys }
        assertTrue(dangling.isEmpty(), "an edge cannot point nowhere: $dangling")
    }
}
