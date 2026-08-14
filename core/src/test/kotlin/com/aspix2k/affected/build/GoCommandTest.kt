package com.aspix2k.affected.build

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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

    @Test
    fun `a single first-level nested Go module is the root`() {
        val base = createTempDirectory("go-nested").toFile()
        val nested = File(base, "backend")
        goMod().copyRecursively(nested)

        assertEquals(nested.canonicalFile, goProjectRoot(base)?.canonicalFile)
    }

    @Test
    fun `several first-level nested Go modules stay off`() {
        val base = createTempDirectory("go-many").toFile()
        goMod().copyRecursively(File(base, "backend"))
        goMod().copyRecursively(File(base, "tools"))

        assertNull(goProjectRoot(base))
    }

    @Test
    fun `a deeper nested Go module stays off`() {
        val base = createTempDirectory("go-deep").toFile()
        goMod().copyRecursively(File(base, "src/backend"))

        assertNull(goProjectRoot(base))
    }

    private fun goMod(): File {
        val root = createTempDirectory("go-mod").toFile()
        File(root, "go.mod").writeText("module example.com/probe\n\ngo 1.26\n")
        return root
    }
}
