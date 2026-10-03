package com.aspix2k.affected

import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TestRootResolverTest {

    private fun module(block: (File) -> Unit): File {
        val dir = createTempDirectory("test-root").toFile()
        block(dir)
        return dir
    }

    @Test
    fun `descends through packages to the first source directory`() {
        val dir = module {
            File(it, "src/test/kotlin/com/example/app/integration").mkdirs()
            File(it, "src/test/kotlin/com/example/app/integration/SomeTest.kt").writeText("class SomeTest")
        }
        assertEquals(
            File(dir, "src/test/kotlin/com/example/app/integration").path,
            TestRootResolver.resolve(dir.path),
        )
    }

    @Test
    fun `stops where a package branches`() {
        val dir = module {
            File(it, "src/test/kotlin/com/example/first").mkdirs()
            File(it, "src/test/kotlin/com/example/second").mkdirs()
            File(it, "src/test/kotlin/com/example/first/A.kt").writeText("class A")
            File(it, "src/test/kotlin/com/example/second/B.kt").writeText("class B")
        }
        assertEquals(
            File(dir, "src/test/kotlin/com/example").path,
            TestRootResolver.resolve(dir.path),
            "one branch cannot be selected arbitrarily",
        )
    }

    @Test
    fun `stops at a directory containing files and subdirectories`() {
        val dir = module {
            File(it, "src/test/kotlin/ru/nested").mkdirs()
            File(it, "src/test/kotlin/ru/Top.kt").writeText("class Top")
            File(it, "src/test/kotlin/ru/nested/Deep.kt").writeText("class Deep")
        }
        assertEquals(
            File(dir, "src/test/kotlin/ru").path,
            TestRootResolver.resolve(dir.path),
        )
    }

    @Test
    fun `Scala sources are also found`() {
        val dir = module {
            File(it, "src/test/scala/com/example").mkdirs()
            File(it, "src/test/scala/com/example/AlphaSpec.scala").writeText("class AlphaSpec")
        }
        assertEquals(
            File(dir, "src/test/scala/com/example").path,
            TestRootResolver.resolve(dir.path),
        )
    }

    @Test
    fun `Groovy sources are also found`() {
        val dir = module {
            File(it, "src/test/groovy/com/example").mkdirs()
            File(it, "src/test/groovy/com/example/BetaSpec.groovy").writeText("class BetaSpec {}")
        }
        assertEquals(
            File(dir, "src/test/groovy/com/example").path,
            TestRootResolver.resolve(dir.path),
        )
    }

    @Test
    fun `Java sources are also found`() {
        val dir = module {
            File(it, "src/test/java/com/example").mkdirs()
            File(it, "src/test/java/com/example/LegacyTest.java").writeText("class LegacyTest {}")
        }
        assertEquals(
            File(dir, "src/test/java/com/example").path,
            TestRootResolver.resolve(dir.path),
        )
    }

    @Test
    fun `a module without tests returns null`() {
        val dir = module {
            File(it, "src/main/kotlin").mkdirs()
            File(it, "src/main/kotlin/Main.kt").writeText("class Main")
        }
        assertNull(TestRootResolver.resolve(dir.path))
    }

    @Test
    fun `an empty test directory returns itself`() {
        val dir = module { File(it, "src/test/kotlin").mkdirs() }
        assertEquals(File(dir, "src/test/kotlin").path, TestRootResolver.resolve(dir.path))
    }

    @Test
    fun `a KMP layout is found`() {
        val dir = module {
            File(it, "src/commonTest/kotlin/app").mkdirs()
            File(it, "src/commonTest/kotlin/app/CommonTest.kt").writeText("class CommonTest")
        }
        assertEquals(
            File(dir, "src/commonTest/kotlin/app").path,
            TestRootResolver.resolve(dir.path),
        )
    }

    @Test
    fun `a missing module does not crash the resolver`() {
        assertNull(TestRootResolver.resolve("/no/such/module/anywhere"))
    }

    @Test
    fun `a non-source file does not stop descent through a single package`() {
        val dir = module {
            File(it, "src/test/kotlin/com/example").mkdirs()
            File(it, "src/test/kotlin/README.md").writeText("# tests")
            File(it, "src/test/kotlin/com/example/SomeTest.kt").writeText("class SomeTest")
        }
        assertEquals(
            File(dir, "src/test/kotlin/com/example").path,
            TestRootResolver.resolve(dir.path),
            "markdown next to a single package is not a test source",
        )
    }

    @Test
    fun `a directory named like a source file is still descended`() {
        val dir = module {
            File(it, "src/test/kotlin/Suite.kt").mkdirs()
            File(it, "src/test/kotlin/Suite.kt/NestedTest.kt").writeText("class NestedTest")
        }
        assertEquals(
            File(dir, "src/test/kotlin/Suite.kt").path,
            TestRootResolver.resolve(dir.path),
            "a package directory is not a Kotlin file",
        )
    }

    @Test
    fun `a lone non-source file is not treated as a package`() {
        val dir = module {
            File(it, "src/test/kotlin").mkdirs()
            File(it, "src/test/kotlin/notes.txt").writeText("not a test")
        }
        assertEquals(
            File(dir, "src/test/kotlin").path,
            TestRootResolver.resolve(dir.path),
            "an unlistable-looking file must not become the test root",
        )
    }

    @Test
    fun `an unlistable test directory returns itself`() {
        val dir = module { File(it, "src/test/kotlin").mkdirs() }
        val locked = File(dir, "src/test/kotlin")
        assumeTrue(locked.setReadable(false, false) && locked.listFiles() == null)
        try {
            assertEquals(locked.path, TestRootResolver.resolve(dir.path))
        } finally {
            locked.setReadable(true, false)
        }
    }
}
