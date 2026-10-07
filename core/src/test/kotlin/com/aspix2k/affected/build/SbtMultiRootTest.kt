package com.aspix2k.affected.build

import com.aspix2k.affected.ModuleGraph
import com.aspix2k.affected.TaskPlanner
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SbtMultiRootTest {

    @Test
    fun `two sbt builds without a root manifest produce modules for both and commands per root`() {
        val base = createTempDirectory("sbt-multi").toFile()
        val multi = sbt(base, "services/api", "lazy val core = project.in(file(\"core\"))\n").also {
            File(it, "core/src/test/scala").mkdirs()
            File(it, "core/src/test/scala/CoreSpec.scala").writeText("")
        }
        val single = sbt(base, "services/worker", "name := \"worker\"\n").also {
            File(it, "src/test/scala").mkdirs()
            File(it, "src/test/scala/WorkerSpec.scala").writeText("")
        }
        val expected = setOf(multi, single).map { it.invariantSeparatorsPath }.toSet()
        val system = IdeSbtBuildSystem()
        val project = multiRootProject(base)

        val modules = system.modules(project)
        val plan = TaskPlanner.plan(modules.map { ModuleGraph.Node(it, system).info() }, emptyList())

        assertTrue(system.isPresent(project))
        assertEquals(expected, modules.map { it.root }.toSet())
        assertEquals(expected, plan.groups.map { it.root }.toSet())
        val commands = plan.groups.associate { group -> group.root to sbtCommands(group.tasks).single().arguments }
        assertEquals(listOf("sbt", "--batch", "core/test"), commands.getValue(multi.invariantSeparatorsPath))
        assertEquals(listOf("sbt", "--batch", "test"), commands.getValue(single.invariantSeparatorsPath))
    }

    @Test
    fun `a build outside the declared subprojects is its own root and subprojects are not duplicated`() {
        val base = createTempDirectory("sbt-independent").toFile()
        sbt(
            base,
            ".",
            "lazy val core = (project in file(\"core\"))\nlazy val api = project.in(file(\"modules/api\"))\n",
        )
        sbt(base, "core", "name := \"core\"\n")
        sbt(base, "modules/api", "name := \"api\"\n")
        sbt(base, "tools/other", "name := \"other\"\n")

        assertEquals(
            listOf(base, File(base, "tools/other")).map(File::getCanonicalFile),
            sbtProjectRoots(base).map(File::getCanonicalFile),
        )
    }

    @Test
    fun `nested builds stay members when the parent declares projects in a way that is not understood`() {
        val base = createTempDirectory("sbt-unproved").toFile()
        sbt(base, ".", "lazy val core = Project(\"core\", file(\"core\"))\n")
        sbt(base, "core", "name := \"core\"\n")

        assertEquals(listOf(base.canonicalFile), sbtProjectRoots(base).map(File::getCanonicalFile))
    }

    private fun sbt(base: File, path: String, build: String): File = File(base, path).also {
        it.mkdirs()
        File(it, "build.sbt").writeText(build)
    }
}
