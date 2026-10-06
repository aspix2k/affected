package com.aspix2k.affected.build.dotnet

import com.aspix2k.affected.ModuleGraph
import com.aspix2k.affected.ProjectChanges
import com.aspix2k.affected.TaskPlanner
import com.aspix2k.affected.Verification
import com.aspix2k.affected.build.IdeDotnetBuildSystem
import com.aspix2k.affected.build.multiRootProject
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DotnetMultiRootTest {

    @Test
    fun `two solutions without a root manifest produce modules for both and commands per root`() {
        val base = createTempDirectory("dotnet-multi").toFile()
        val roots = listOf("api", "worker").map { solution(base, "services/$it") }
        val system = IdeDotnetBuildSystem()
        val project = multiRootProject(base)

        val modules = system.modules(project)
        val plan = TaskPlanner.plan(modules.map { ModuleGraph.Node(it, system).info() }, emptyList())

        assertTrue(system.isPresent(project))
        assertEquals(roots.map { it.invariantSeparatorsPath }.toSet(), modules.map { it.root }.toSet())
        assertEquals(setOf("Lib", "Lib.Tests"), modules.map { it.id }.toSet())
        assertEquals(roots.map { it.invariantSeparatorsPath }.toSet(), plan.groups.map { it.root }.toSet())
        plan.groups.forEach { group ->
            assertEquals(
                listOf("dotnet", "test", "Lib.Tests/Lib.Tests.csproj"),
                dotnetCommands(group.root, listOf("Lib.Tests/Lib.Tests.csproj:test")).single().arguments,
                group.root,
            )
        }
    }

    @Test
    fun `project references stay inside each solution root`() {
        val base = createTempDirectory("dotnet-multi-edges").toFile()
        val roots = listOf("api", "worker").map { solution(base, it) }

        val modules = IdeDotnetBuildSystem().modules(multiRootProject(base))

        roots.forEach { root ->
            val rootPath = root.invariantSeparatorsPath
            val tests = modules.single { it.root == rootPath && it.id == "Lib.Tests" }
            assertEquals(setOf("DOTNET|$rootPath|Lib"), tests.dependencies)
        }
    }

    @Test
    fun `a project referenced from two solutions is one module with edges from both`() {
        val base = createTempDirectory("dotnet-multi-shared").toFile()
        File(base, "core").mkdirs()
        File(base, "core/Core.sln").writeText("")
        csproj(base, "core/Shared/Shared.csproj", "")
        listOf("api", "worker").forEach { name ->
            File(base, name).mkdirs()
            File(base, "$name/$name.sln").writeText("")
            csproj(
                base,
                "$name/App/App.csproj",
                """<ItemGroup><ProjectReference Include="..\..\core\Shared\Shared.csproj" /></ItemGroup>""",
            )
        }

        val modules = IdeDotnetBuildSystem().modules(multiRootProject(base))
        val core = base.resolve("core").invariantSeparatorsPath

        assertEquals(1, modules.count { it.id == "Shared" })
        assertEquals(2, modules.count { it.id == "App" })
        modules.filter { it.id == "App" }.forEach { assertEquals(setOf("DOTNET|$core|Shared"), it.dependencies) }
    }

    @Test
    fun `a change in a project outside every solution root is unresolved, not ignored`() {
        val base = createTempDirectory("dotnet-multi-outside").toFile()
        csproj(base, "shared/Shared/Shared.csproj", "")
        File(base, "api").mkdirs()
        File(base, "api/api.sln").writeText("")
        csproj(
            base,
            "api/App/App.csproj",
            """<ItemGroup><ProjectReference Include="..\..\shared\Shared\Shared.csproj" /></ItemGroup>""",
        )
        val system = IdeDotnetBuildSystem()
        val graph = ModuleGraph(system.modules(multiRootProject(base)).map { ModuleGraph.Node(it, system) })
        val owned = File(base, "api/App/Program.cs").apply { writeText("class Program {}") }
        val outside = File(base, "shared/Shared/Shared.cs").apply { writeText("class Shared {}") }
        val changes = ProjectChanges.Result(
            listOf(owned, outside),
            emptySet(),
            setOf(owned, outside),
            comparedToBase = true,
        )

        val prepared = Verification.prepare(graph, changes).select(checkConsumers = false)

        assertEquals(listOf(base.resolve("api").invariantSeparatorsPath), graph.nodesFor(owned).map { it.buildRoot })
        assertEquals(1, prepared.unresolvedFiles)
    }

    @Test
    fun `projects without a solution keep the single-root behavior`() {
        val base = createTempDirectory("dotnet-no-solution").toFile()
        csproj(base, "one/One.csproj", "")
        csproj(base, "two/Two.csproj", "")

        assertFalse(IdeDotnetBuildSystem().isPresent(multiRootProject(base)))

        File(base, "two").deleteRecursively()
        assertEquals(
            listOf(File(base, "one").invariantSeparatorsPath),
            IdeDotnetBuildSystem().modules(multiRootProject(base)).map { it.root }.distinct(),
        )
    }

    @Test
    fun `baselines are keyed by root and project`() {
        val one = dotnetBaselineKey("/repo/api", "Lib.Tests/Lib.Tests.csproj")

        assertNotEquals(one, dotnetBaselineKey("/repo/worker", "Lib.Tests/Lib.Tests.csproj"))
        assertNotEquals(one, dotnetBaselineKey("/repo/api", "Other/Other.csproj"))
    }

    private fun solution(base: File, path: String): File = File(base, path).also {
        it.mkdirs()
        File(it, "App.sln").writeText("")
        csproj(it, "Lib/Lib.csproj", "")
        csproj(
            it,
            "Lib.Tests/Lib.Tests.csproj",
            """
                <ItemGroup>
                    <PackageReference Include="xunit" />
                    <ProjectReference Include="..\Lib\Lib.csproj" />
                </ItemGroup>
            """.trimIndent(),
        )
    }

    private fun csproj(base: File, path: String, body: String) {
        val file = File(base, path)
        file.parentFile.mkdirs()
        file.writeText("<Project Sdk=\"Microsoft.NET.Sdk\">$body</Project>")
    }
}
