package com.aspix2k.affected.build

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

class GoCommandTest {

    @Test
    fun `a changed Go test file selects its Test functions`() {
        val root = createTempDirectory("go-exact-run").toFile()
        val testFile = File(root, "alpha/alpha_test.go").apply {
            parentFile.mkdirs()
            writeText(
                """
                package alpha

                import "testing"

                func TestValue(t *testing.T) {}
                func TestOther(t *testing.T) {}
                """.trimIndent(),
            )
        }
        val module = BuildModule(
            "example.com/alpha",
            root.path,
            listOf(File(root, "alpha").path),
            GoPackages.TEST,
            GoPackages.COMPILE,
            true,
        )

        val command = goCommands(
            listOf("example.com/alpha:test"),
            listOf(module),
            BuildChanges(
                files = listOf(testFile.path),
                exactSelectionEligible = setOf(testFile.path),
                comparedToBase = true,
            ),
        ).single()

        assertEquals(
            listOf("go", "test", "example.com/alpha", "-run", "^(TestOther|TestValue)$"),
            command.arguments,
        )
    }

    @Test
    fun `a Go production change keeps the package test command`() {
        val root = createTempDirectory("go-src-full").toFile()
        val source = File(root, "alpha/alpha.go").apply {
            parentFile.mkdirs()
            writeText("package alpha\nfunc Value() int { return 1 }\n")
        }
        File(root, "alpha/alpha_test.go").writeText(
            "package alpha\nimport \"testing\"\nfunc TestValue(t *testing.T) {}\n",
        )
        val module = BuildModule(
            "example.com/alpha",
            root.path,
            listOf(File(root, "alpha").path),
            GoPackages.TEST,
            GoPackages.COMPILE,
            true,
        )

        val command = goCommands(
            listOf("example.com/alpha:test"),
            listOf(module),
            BuildChanges(
                files = listOf(source.path),
                exactSelectionEligible = setOf(source.path),
                comparedToBase = true,
            ),
        ).single()

        assertEquals(listOf("go", "test", "example.com/alpha"), command.arguments)
    }
}
