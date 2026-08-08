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
    fun `проекты находятся по всему дереву`() {
        val root = solution()
        project(root, "src/Lib/Lib.csproj", "")
        project(root, "test/Lib.Tests/Lib.Tests.csproj", "")

        val modules = DotnetProjects.parse(root)

        assertEquals(setOf("Lib", "Lib.Tests"), modules.map { it.id }.toSet())
    }

    @Test
    fun `ссылка с обратными слэшами разрешается на любой системе`() {
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
            "MSBuild пишет разделители Windows даже на macOS",
        )
    }

    @Test
    fun `ссылка на несуществующий проект не создаёт ребра`() {
        val root = solution()
        project(
            root,
            "src/App/App.csproj",
            """<ItemGroup><ProjectReference Include="..\Missing\Missing.csproj" /></ItemGroup>""",
        )

        assertEquals(emptySet(), DotnetProjects.parse(root).single().dependencies)
    }

    @Test
    fun `тестовым считается проект со ссылкой на тестовый фреймворк`() {
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
    fun `каталоги сборки не просматриваются`() {
        val root = solution()
        project(root, "src/Lib/Lib.csproj", "")
        project(root, "src/Lib/obj/Debug/Ghost.csproj", "")
        project(root, "src/Lib/bin/Release/Ghost2.csproj", "")

        assertEquals(listOf("Lib"), DotnetProjects.parse(root).map { it.id })
    }

    @Test
    fun `fsproj и vbproj тоже проекты`() {
        val root = solution()
        project(root, "src/Fs/Fs.fsproj", "")
        project(root, "src/Vb/Vb.vbproj", "")

        assertEquals(setOf("Fs", "Vb"), DotnetProjects.parse(root).map { it.id }.toSet())
    }

    @Test
    fun `реальный serilog разбирается с рёбрами графа`() {
        assumeTrue(FixtureRepository.available("dotnet-serilog"))
        val root = File(FixtureRepository.root, "dotnet-serilog")

        val modules = DotnetProjects.parse(root)

        assertTrue(modules.size >= 4, "в serilog несколько проектов, разобрали ${modules.size}")
        assertTrue(modules.any { it.dependencies.isNotEmpty() }, "тестовые проекты ссылаются на Serilog")
        assertTrue(modules.any { it.hasTests }, "в serilog есть тестовые проекты")

        val keys = modules.map { it.key }.toSet()
        val dangling = modules.flatMap { it.dependencies }.filterNot { it in keys }
        assertTrue(dangling.isEmpty(), "ребро не может указывать в пустоту: $dangling")
    }
}
