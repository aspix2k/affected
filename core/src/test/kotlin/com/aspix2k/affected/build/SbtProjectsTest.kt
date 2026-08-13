package com.aspix2k.affected.build

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SbtProjectsTest {

    @Test
    fun `a single-file build stays one root module`() {
        val root = module {
            File(it, "build.sbt").writeText("""ThisBuild / scalaVersion := "3.3.6"""")
            File(it, "src/test/scala/A.scala").apply {
                parentFile.mkdirs()
                writeText("class A")
            }
        }

        val modules = sbtModules(root)
        assertEquals(listOf("."), modules?.map(BuildModule::executionId))
        assertTrue(modules!!.single().hasTests)
    }

    @Test
    fun `simple lazy val projects become one module each`() {
        val root = module {
            File(it, "build.sbt").writeText(
                """
                lazy val root = (project in file(".")).aggregate(alpha, beta)
                lazy val alpha = project
                lazy val beta = (project in file("beta"))
                """.trimIndent(),
            )
            File(it, "alpha/src/test/scala/A.scala").apply {
                parentFile.mkdirs()
                writeText("class A")
            }
            File(it, "beta/src/main/scala/B.scala").apply {
                parentFile.mkdirs()
                writeText("class B")
            }
        }

        val modules = sbtModules(root)!!.associateBy(BuildModule::executionId)
        assertEquals(setOf("root", "alpha", "beta"), modules.keys)
        assertEquals(File(root, "alpha").invariantSeparatorsPath, modules.getValue("alpha").contentRoots.single())
        assertEquals(File(root, "beta").invariantSeparatorsPath, modules.getValue("beta").contentRoots.single())
        assertTrue(modules.getValue("alpha").hasTests)
        assertEquals(false, modules.getValue("beta").hasTests)
        assertEquals(root.invariantSeparatorsPath, modules.getValue("alpha").executionRoot)
    }

    @Test
    fun `build definition changes require the whole sbt workspace`() {
        val root = module { File(it, "build.sbt").writeText("lazy val alpha = project") }
        val build = File(root, "build.sbt")
        val source = File(root, "alpha/A.scala").apply {
            parentFile.mkdirs()
            writeText("class A")
        }
        assertTrue(
            sbtRequiresWorkspace(
                root.path,
                BuildChanges(listOf(build.path), setOf(build.path), comparedToBase = true),
            ),
        )
        assertEquals(
            false,
            sbtRequiresWorkspace(
                root.path,
                BuildChanges(listOf(source.path), setOf(source.path), comparedToBase = true),
            ),
        )
    }

    @Test
    fun `an unparseable Project constructor keeps the root fallback`() {
        val root = module {
            File(it, "build.sbt").writeText(
                """
                lazy val alpha = Project(id = "alpha", base = file("alpha"))
                """.trimIndent(),
            )
        }

        assertNull(sbtModules(root))
    }

    private fun module(block: (File) -> Unit): File {
        val dir = createTempDirectory("sbt-projects").toFile()
        block(dir)
        return dir
    }
}
