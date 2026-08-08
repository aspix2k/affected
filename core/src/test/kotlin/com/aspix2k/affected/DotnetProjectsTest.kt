package com.aspix2k.affected

import com.aspix2k.affected.build.DotnetProjects
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DotnetProjectsTest {

    private fun solution(): File = createTempDirectory("dotnet").toFile()

    private fun project(root: File, path: String, body: String) {
        val file = File(root, path)
        file.parentFile.mkdirs()
        file.writeText("<Project Sdk=\"Microsoft.NET.Sdk\">$body</Project>")
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
            setOf("${root.invariantSeparatorsPath}|Lib"),
            tests.dependencies,
            "MSBuild emits Windows separators even on macOS",
        )
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
        assertFalse(modules.single { it.id == "Lib" }.hasTests)
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
