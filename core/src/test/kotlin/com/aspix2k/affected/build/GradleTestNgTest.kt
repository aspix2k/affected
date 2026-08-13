package com.aspix2k.affected.build

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GradleTestNgTest {

    @Test
    fun `a TestNG test class becomes a Gradle --tests filter`() {
        val root = createTempDirectory("testng-alpha").toFile()
        val test = File(root, "lib/src/test/java/probe/AlphaTest.java").apply {
            parentFile.mkdirs()
            writeText(
                """
                package probe;

                import org.testng.annotations.Test;

                public class AlphaTest {
                    @Test
                    public void value() {}
                }
                """.trimIndent(),
            )
        }

        assertEquals(
            listOf(":lib:test", "--tests", "probe.AlphaTest"),
            gradleTaskNames(
                listOf(":lib:test"),
                BuildChanges(listOf(test.path), setOf(test.path), comparedToBase = true),
            ),
        )
    }

    @Test
    fun `a JUnit test file keeps the unfiltered Gradle task`() {
        val root = createTempDirectory("testng-junit").toFile()
        val test = File(root, "lib/src/test/java/probe/AlphaTest.java").apply {
            parentFile.mkdirs()
            writeText(
                """
                package probe;

                import org.junit.jupiter.api.Test;

                public class AlphaTest {
                    @Test
                    void value() {}
                }
                """.trimIndent(),
            )
        }

        assertEquals(
            listOf(":lib:test"),
            gradleTaskNames(
                listOf(":lib:test"),
                BuildChanges(listOf(test.path), setOf(test.path), comparedToBase = true),
            ),
        )
    }

    @Test
    fun `a production change keeps the unfiltered Gradle task`() {
        val root = createTempDirectory("testng-src").toFile()
        val source = File(root, "lib/src/main/java/probe/Alpha.java").apply {
            parentFile.mkdirs()
            writeText(
                """
                package probe;

                public class Alpha {
                    public int value() { return 1; }
                }
                """.trimIndent(),
            )
        }

        assertNull(
            selectTestNgClasses(
                BuildChanges(listOf(source.path), setOf(source.path), comparedToBase = true),
            ),
        )
        assertEquals(
            listOf(":lib:test"),
            gradleTaskNames(
                listOf(":lib:test"),
                BuildChanges(listOf(source.path), setOf(source.path), comparedToBase = true),
            ),
        )
    }
}
