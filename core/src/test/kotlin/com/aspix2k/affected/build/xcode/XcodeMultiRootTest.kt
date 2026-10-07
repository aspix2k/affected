package com.aspix2k.affected.build.xcode

import com.aspix2k.affected.ModuleGraph
import com.aspix2k.affected.TaskPlanner
import com.aspix2k.affected.build.IdeXcodeBuildSystem
import com.aspix2k.affected.build.multiRootProject
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class XcodeMultiRootTest {

    @Test
    fun `two Xcode projects without a root manifest produce modules for both and commands per root`() {
        val base = createTempDirectory("xcode-multi").toFile()
        val roots = listOf("ios" to "Alpha", "apps/mac" to "Beta").map { (path, scheme) -> xcode(base, path, scheme) }
        val system = IdeXcodeBuildSystem()
        val project = multiRootProject(base)

        val modules = system.modules(project)
        val plan = TaskPlanner.plan(modules.map { ModuleGraph.Node(it, system).info() }, emptyList())

        assertTrue(system.isPresent(project))
        assertEquals(roots.map { it.invariantSeparatorsPath }.toSet(), modules.map { it.root }.toSet())
        assertEquals(setOf("."), modules.map { it.executionId }.toSet())
        assertEquals(roots.map { it.invariantSeparatorsPath }.toSet(), plan.groups.map { it.root }.toSet())
        plan.groups.forEach { group ->
            val scheme = if (group.root.endsWith("/ios")) "Alpha" else "Beta"
            assertEquals(
                listOf("xcodebuild", "test", "-scheme", scheme),
                xcodeCommands(File(group.root), group.tasks).single().arguments,
                group.root,
            )
        }
    }

    @Test
    fun `the internal workspace of a project bundle is not a root`() {
        val base = createTempDirectory("xcode-bundle").toFile()
        File(base, "Package.swift").writeText("// swift-tools-version: 5.9\n")
        File(base, "App.xcodeproj/project.xcworkspace").mkdirs()

        assertEquals(emptyList(), IdeXcodeBuildSystem().modules(multiRootProject(base)))
    }

    private fun xcode(base: File, path: String, scheme: String): File = File(base, path).also {
        val schemes = File(it, "App.xcodeproj/xcshareddata/xcschemes")
        schemes.mkdirs()
        File(schemes, "$scheme.xcscheme").writeText(
            "<Scheme><TestAction><Testables><TestableReference skipped=\"NO\"/></Testables></TestAction></Scheme>",
        )
    }
}
