package com.aspix2k.affected

import com.aspix2k.affected.build.BuildModule
import com.aspix2k.affected.build.BuildSystem
import com.aspix2k.affected.build.CMakeTargets
import com.aspix2k.affected.build.DotnetProjects
import com.aspix2k.affected.build.TransitiveTestConsumersBuildSystem
import com.intellij.openapi.project.Project
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MixedPolyglotTest {

    @Test
    fun `a cmake change does not plan the sibling dotnet project`() {
        val root = mixedRepo()
        val graph = graph(root)
        val changed = File(root, "src/core/core.cpp")

        val plan = verificationPlan(
            graph,
            ProjectChanges.Result(listOf(changed), emptySet(), setOf(changed), comparedToBase = true),
            checkConsumers = false,
        )

        assertEquals(listOf("CMAKE"), plan.groups.map { it.systemId }.distinct())
        assertTrue(plan.groups.single().tasks.any { it.contains("core") })
    }

    @Test
    fun `a dotnet change does not plan the sibling cmake targets`() {
        val root = mixedRepo()
        val graph = graph(root)
        val changed = File(root, "Lib/Value.cs")

        val plan = verificationPlan(
            graph,
            ProjectChanges.Result(listOf(changed), emptySet(), setOf(changed), comparedToBase = true),
            checkConsumers = false,
        )

        assertEquals(listOf("DOTNET"), plan.groups.map { it.systemId }.distinct())
        assertTrue(plan.groups.single().tasks.any { it.contains("Lib.Tests") && it.endsWith(":test") })
        assertTrue(plan.groups.single().tasks.none { "core" in it || it.startsWith("app:") })
    }

    @Test
    fun `both adapters discover modules in one repository`() {
        val root = mixedRepo()

        assertEquals(setOf("core", "app"), CMakeTargets.parse(root).map { it.id }.toSet())
        assertEquals(setOf("Lib", "Lib.Tests"), DotnetProjects.parse(root).map { it.id }.toSet())
    }

    private fun graph(root: File): ModuleGraph {
        val cmake = CMakeBuildSystemStub()
        val dotnet = DotnetBuildSystemStub()
        val nodes = CMakeTargets.parse(root).map { ModuleGraph.Node(it, cmake) } +
            DotnetProjects.parse(root).map { ModuleGraph.Node(it, dotnet) }
        return ModuleGraph(nodes)
    }

    private fun mixedRepo(): File {
        val root = createTempDirectory("mixed-polyglot").toFile()
        File(root, "src/core").mkdirs()
        File(root, "src/core/CMakeLists.txt").writeText("add_library(core STATIC core.cpp)\n")
        File(root, "src/core/core.cpp").writeText("int core() { return 1; }\n")
        File(root, "src/app").mkdirs()
        File(root, "src/app/CMakeLists.txt").writeText(
            """
            add_executable(app main.cpp)
            target_link_libraries(app PRIVATE core)
            """.trimIndent(),
        )
        File(root, "src/app/main.cpp").writeText("int main() { return 0; }\n")
        File(root, "Lib").mkdirs()
        File(root, "Lib/Lib.csproj").writeText("<Project Sdk=\"Microsoft.NET.Sdk\"></Project>\n")
        File(root, "Lib/Value.cs").writeText("namespace Lib;\n")
        File(root, "Lib.Tests").mkdirs()
        File(root, "Lib.Tests/Lib.Tests.csproj").writeText(
            """
            <Project Sdk="Microsoft.NET.Sdk">
              <ItemGroup>
                <PackageReference Include="Microsoft.NET.Test.Sdk" Version="18.9.0" />
                <ProjectReference Include="../Lib/Lib.csproj" />
              </ItemGroup>
            </Project>
            """.trimIndent(),
        )
        File(root, "Lib.Tests/ValueTest.cs").writeText("namespace Lib.Tests;\n")
        return root
    }

    private class CMakeBuildSystemStub : BuildSystem by stub("CMAKE")
    private class DotnetBuildSystemStub : BuildSystem by stub("DOTNET"), TransitiveTestConsumersBuildSystem

    private companion object {
        fun stub(systemId: String): BuildSystem = object : BuildSystem {
            override val id: String = systemId
            override val sourceExtensions: Set<String> = emptySet()
            override fun isPresent(project: Project): Boolean = false
            override fun modules(project: Project): List<BuildModule> = emptyList()
            override fun run(project: Project, root: String, tasks: List<String>) = Unit
            override fun runAndWait(project: Project, root: String, tasks: List<String>): Boolean = false
        }
    }
}
