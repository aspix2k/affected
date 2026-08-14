package com.aspix2k.affected.build

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MixedPolyglotCommandTest {

    @Test
    fun `a cmake plan in a mixed repository does not invoke dotnet`() {
        val root = fixture()
        File(root, "build").mkdirs()
        File(root, "build/CMakeCache.txt").writeText("CMAKE_COMMAND:INTERNAL=/usr/bin/cmake\n")
        val cmake = CMakeTargets.parse(root)
        assertEquals(setOf("mixed_alpha"), cmake.map { it.id }.toSet())

        val commands = cmakeCommands(root.path, cmake.map { "${it.executionId}:${it.testTask}" })

        assertTrue(commands.any { it.arguments.first() == "cmake" })
        assertTrue(commands.any { it.arguments.first() == "ctest" })
        assertTrue(commands.none { it.arguments.contains("dotnet") })
    }

    @Test
    fun `a dotnet plan in a mixed repository does not invoke cmake`() {
        val root = fixture()
        val tested = DotnetProjects.parse(root).filter { it.testTask == DotnetProjects.TEST }
        assertEquals(setOf("Lib.Tests/Lib.Tests.csproj"), tested.map { it.executionId }.toSet())

        val commands = dotnetCommands(root.path, tested.map { "${it.executionId}:${it.testTask}" })

        assertEquals(listOf("dotnet", "test", "Lib.Tests/Lib.Tests.csproj"), commands.single().arguments)
        assertTrue(commands.none { command -> command.arguments.any { it == "cmake" || it == "ctest" } })
    }

    private fun fixture(): File {
        val source = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .map { File(it, "conformance/cli-fixtures/mixed-cmake-dotnet") }
            .first(File::isDirectory)
        val root = createTempDirectory("mixed-commands").toFile()
        assertTrue(source.copyRecursively(root, overwrite = true))
        return root
    }
}
