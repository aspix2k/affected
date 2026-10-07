package com.aspix2k.affected.build.ruby

import com.aspix2k.affected.ModuleGraph
import com.aspix2k.affected.TaskPlanner
import com.aspix2k.affected.build.IdeRubyBuildSystem
import com.aspix2k.affected.build.multiRootProject
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RubyMultiRootTest {

    @Test
    fun `two Ruby gems without a root manifest produce modules for both and commands per root`() {
        val base = createTempDirectory("ruby-multi").toFile()
        val roots = listOf("api", "worker").map { gem(base, "services/$it", it) }
        val system = IdeRubyBuildSystem()
        val project = multiRootProject(base)

        val modules = system.modules(project)
        val plan = TaskPlanner.plan(modules.map { ModuleGraph.Node(it, system).info() }, emptyList())

        assertTrue(system.isPresent(project))
        assertEquals(roots.map { it.invariantSeparatorsPath }.toSet(), modules.map { it.root }.toSet())
        assertEquals(setOf("api", "worker"), modules.map { it.id }.toSet())
        assertEquals(roots.map { it.invariantSeparatorsPath }.toSet(), plan.groups.map { it.root }.toSet())
        plan.groups.forEach { group ->
            val owned = modules.filter { it.executionRoot == group.root }
            val command = rubyCommands(group.root, group.tasks, owned).single()
            assertEquals(listOf("bundle", "exec", "rspec", "."), command.arguments, group.root)
        }
    }

    @Test
    fun `a Gemfile project without a gemspec is its own root and gemspec directories stay members`() {
        val base = createTempDirectory("ruby-independent").toFile()
        gem(base, ".", "root")
        gem(base, "gems/shared", "shared")
        File(base, "apps/admin/spec").mkdirs()
        File(base, "apps/admin/Gemfile").writeText("source 'https://rubygems.org'\n")

        assertEquals(
            listOf(base, File(base, "apps/admin")).map(File::getCanonicalFile),
            rubyProjectRoots(base).map(File::getCanonicalFile),
        )
    }

    private fun gem(base: File, path: String, name: String): File = File(base, path).also {
        File(it, "spec").mkdirs()
        File(it, "spec/${name}_spec.rb").writeText("")
        File(it, "Gemfile").writeText("source 'https://rubygems.org'\ngemspec\n")
        File(it, "$name.gemspec").writeText("Gem::Specification.new { |s| s.name = '$name' }\n")
    }
}
