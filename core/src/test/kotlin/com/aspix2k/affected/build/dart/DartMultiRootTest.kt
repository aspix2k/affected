package com.aspix2k.affected.build.dart

import com.aspix2k.affected.ModuleGraph
import com.aspix2k.affected.TaskPlanner
import com.aspix2k.affected.build.IdeDartBuildSystem
import com.aspix2k.affected.build.multiRootProject
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DartMultiRootTest {

    @Test
    fun `two Dart packages without a root manifest produce modules for both and commands per root`() {
        val base = createTempDirectory("dart-multi").toFile()
        val generated = dart(base, "packages/models", "dev_dependencies:\n  build_runner: ^2.4.0\n")
        val plain = dart(base, "packages/cli", "")
        val expected = setOf(generated, plain).map { it.invariantSeparatorsPath }.toSet()
        val system = IdeDartBuildSystem()
        val project = multiRootProject(base)

        val modules = system.modules(project)
        val plan = TaskPlanner.plan(modules.map { ModuleGraph.Node(it, system).info() }, emptyList())

        assertTrue(system.isPresent(project))
        assertEquals(expected, modules.map { it.root }.toSet())
        assertEquals(expected, plan.groups.map { it.root }.toSet())
        val commands = plan.groups.associate { group -> group.root to dartCommands(File(group.root), group.tasks) }
        assertEquals(
            listOf(BUILD_RUNNER_COMMAND.arguments, listOf("dart", "test")),
            commands.getValue(generated.invariantSeparatorsPath).map { it.arguments },
        )
        assertEquals(
            listOf(listOf("dart", "test")),
            commands.getValue(plain.invariantSeparatorsPath).map { it.arguments },
        )
    }

    @Test
    fun `a Flutter app keeps its directory out of the Dart roots`() {
        val base = createTempDirectory("dart-multi-flutter").toFile()
        val plain = dart(base, "packages/cli", "")
        File(base, "apps/mobile").also {
            it.mkdirs()
            File(it, "pubspec.yaml").writeText("name: mobile\ndependencies:\n  flutter:\n    sdk: flutter\n")
        }

        assertEquals(listOf(plain.canonicalFile), dartProjectRoots(base).map(File::getCanonicalFile))
    }

    @Test
    fun `a package without workspace resolution is its own root and members are not duplicated`() {
        val base = createTempDirectory("dart-independent").toFile()
        dart(base, ".", "workspace:\n  - packages/a\n")
        dart(base, "packages/a", "resolution: workspace\n")
        dart(base, "tools/standalone", "")

        assertEquals(
            listOf(base, File(base, "tools/standalone")).map(File::getCanonicalFile),
            dartProjectRoots(base).map(File::getCanonicalFile),
        )
    }

    private fun dart(base: File, path: String, extra: String): File = File(base, path).also {
        File(it, "test").mkdirs()
        File(it, "test/a_test.dart").writeText("")
        File(it, "pubspec.yaml").writeText("name: ${it.name}\n$extra")
    }
}
