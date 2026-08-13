package com.aspix2k.affected.build

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MtpFilterClassTest {

    @Test
    fun `an MTP test file change adds filter-class`() {
        val root = createTempDirectory("mtp-filter-class").toFile()
        File(root, "tests/App.Tests.csproj").apply {
            parentFile.mkdirs()
            writeText(
                """
                <Project Sdk="Microsoft.NET.Sdk">
                  <PropertyGroup>
                    <UseMicrosoftTestingPlatformRunner>true</UseMicrosoftTestingPlatformRunner>
                  </PropertyGroup>
                </Project>
                """.trimIndent(),
            )
        }
        val testFile = File(root, "tests/AlphaTests.cs").apply {
            writeText("public sealed class AlphaTests { public void Passes() {} }\n")
        }

        val command = dotnetCommands(
            root.path,
            listOf("tests/App.Tests.csproj:test"),
            BuildChanges(
                files = listOf(testFile.path),
                exactSelectionEligible = setOf(testFile.path),
                comparedToBase = true,
            ),
        ).single()

        assertEquals(
            listOf(
                "dotnet", "test", "--project", "tests/App.Tests.csproj",
                "--", "--filter-class", "AlphaTests",
            ),
            command.arguments,
        )
    }

    @Test
    fun `a production change keeps the full MTP project command`() {
        val root = createTempDirectory("mtp-filter-src").toFile()
        File(root, "tests/App.Tests.csproj").apply {
            parentFile.mkdirs()
            writeText(
                """
                <Project Sdk="Microsoft.NET.Sdk">
                  <PropertyGroup>
                    <UseMicrosoftTestingPlatformRunner>true</UseMicrosoftTestingPlatformRunner>
                  </PropertyGroup>
                </Project>
                """.trimIndent(),
            )
        }
        val source = File(root, "src/Model.cs").apply {
            parentFile.mkdirs()
            writeText("public sealed class Model {}\n")
        }

        val command = dotnetCommands(
            root.path,
            listOf("tests/App.Tests.csproj:test"),
            BuildChanges(
                files = listOf(source.path),
                exactSelectionEligible = setOf(source.path),
                comparedToBase = true,
            ),
        ).single()

        assertEquals(listOf("dotnet", "test", "--project", "tests/App.Tests.csproj"), command.arguments)
        assertNull(selectMtpFilterClasses(root.path, "tests/App.Tests.csproj", commandChanges(source)))
    }

    private fun commandChanges(file: File) = BuildChanges(
        files = listOf(file.path),
        exactSelectionEligible = setOf(file.path),
        comparedToBase = true,
    )
}
