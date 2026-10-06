package com.aspix2k.affected.build

import com.aspix2k.affected.ModuleGraph
import com.aspix2k.affected.TaskPlanner
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SwiftMultiRootTest {

    @Test
    fun `two Swift packages without a root manifest produce modules for both and commands per root`() {
        val base = createTempDirectory("swift-multi").toFile()
        val roots = listOf("api", "worker").map { swiftPackage(base, "packages/$it") }
        val swift = SwiftBuildSystem { _, _ -> DESCRIBE }
        val system = IdeBuildSystem(swift)
        val project = multiRootProject(base)

        val modules = system.modules(project)
        val plan = TaskPlanner.plan(modules.map { ModuleGraph.Node(it, system).info() }, emptyList())

        assertTrue(system.isPresent(project))
        assertEquals(roots.map { it.invariantSeparatorsPath }.toSet(), modules.map { it.root }.toSet())
        assertEquals(setOf("Alpha", "AlphaTests"), modules.map { it.id }.toSet())
        modules.forEach { module -> assertTrue(module.contentRoots.single().startsWith("${module.root}/")) }
        assertEquals(roots.map { it.invariantSeparatorsPath }.toSet(), plan.groups.map { it.root }.toSet())
        plan.groups.forEach { group ->
            assertEquals(listOf("swift", "swift"), swiftCommands(group.tasks).map { it.arguments.first() }, group.root)
        }
    }

    @Test
    fun `a disappeared root leaves the keyed cache`() {
        val base = createTempDirectory("swift-multi-cache").toFile()
        val roots = listOf("api", "worker").map { swiftPackage(base, "packages/$it") }
        val swift = SwiftBuildSystem { _, _ -> DESCRIBE }
        val system = IdeBuildSystem(swift)
        val project = multiRootProject(base)

        system.modules(project)
        assertEquals(2, cachedRoots(swift).size)
        roots.last().deleteRecursively()
        system.modules(project)

        assertEquals(setOf(roots.first().invariantSeparatorsPath), cachedRoots(swift))
    }

    @Test
    fun `a package nested below a package is its own root and checkouts and fixtures are not`() {
        val base = createTempDirectory("swift-independent").toFile()
        swiftPackage(base, ".")
        swiftPackage(base, "Examples/Demo")
        swiftPackage(base, ".build/checkouts/dep")
        swiftPackage(base, "Tests/Fixtures/sample")

        assertEquals(
            listOf(base, File(base, "Examples/Demo")).map(File::getCanonicalFile),
            IdeSwiftBuildSystem().modules(multiRootProject(base)).map { File(it.root).canonicalFile }.distinct(),
        )
    }

    private fun cachedRoots(system: SwiftBuildSystem): Set<Any?> {
        val field = system.javaClass.getDeclaredField("cache")
        field.isAccessible = true
        return (field.get(system) as Map<*, *>).keys.toSet()
    }

    private fun swiftPackage(base: File, path: String): File = File(base, path).also {
        it.mkdirs()
        File(it, "Package.swift").writeText("// swift-tools-version: 5.9\n")
    }

    private companion object {
        val DESCRIBE = """
            {"targets":[
              {"c99name":"Alpha","name":"Alpha","path":"Sources/Alpha","type":"library"},
              {"c99name":"AlphaTests","name":"AlphaTests","path":"Tests/AlphaTests","type":"test",
               "target_dependencies":["Alpha"]}
            ]}
        """.trimIndent()
    }
}
