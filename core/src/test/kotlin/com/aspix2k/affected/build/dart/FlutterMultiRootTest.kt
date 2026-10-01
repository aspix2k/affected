package com.aspix2k.affected.build.dart

import com.aspix2k.affected.ModuleGraph
import com.aspix2k.affected.TaskPlanner
import com.aspix2k.affected.build.multiRootProject
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FlutterMultiRootTest {

    @Test
    fun `two Flutter apps without a root manifest produce modules for both and commands per root`() {
        val base = createTempDirectory("flutter-multi").toFile()
        val generated = flutter(base, "apps/mobile", "dev_dependencies:\n  build_runner: ^2.4.0\n")
        val plain = flutter(base, "apps/admin", "")
        val expected = setOf(generated, plain).map { it.invariantSeparatorsPath }.toSet()
        val system = FlutterBuildSystem()
        val project = multiRootProject(base)

        val modules = system.modules(project)
        val plan = TaskPlanner.plan(modules.map { ModuleGraph.Node(it, system).info() }, emptyList())

        assertTrue(system.isPresent(project))
        assertEquals(expected, modules.map { it.root }.toSet())
        assertEquals(expected, plan.groups.map { it.root }.toSet())
        val commands = plan.groups.associate { group -> group.root to flutterCommands(File(group.root), group.tasks) }
        assertEquals(
            listOf(BUILD_RUNNER_COMMAND.arguments, listOf("flutter", "test")),
            commands.getValue(generated.invariantSeparatorsPath).map { it.arguments },
        )
        assertEquals(
            listOf(listOf("flutter", "test")),
            commands.getValue(plain.invariantSeparatorsPath).map { it.arguments },
        )
    }

    @Test
    fun `a pure Dart package keeps its directory out of the Flutter roots`() {
        val base = createTempDirectory("flutter-multi-dart").toFile()
        val app = flutter(base, "apps/mobile", "")
        File(base, "packages/cli").also {
            it.mkdirs()
            File(it, "pubspec.yaml").writeText("name: cli\n")
        }

        assertEquals(listOf(app.canonicalFile), flutterProjectRoots(base).map(File::getCanonicalFile))
    }

    private fun flutter(base: File, path: String, extra: String): File = File(base, path).also {
        File(it, "test").mkdirs()
        File(it, "test/a_test.dart").writeText("")
        File(it, "pubspec.yaml").writeText("name: ${it.name}\ndependencies:\n  flutter:\n    sdk: flutter\n$extra")
    }
}
