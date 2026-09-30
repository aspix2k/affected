package com.aspix2k.affected

import com.aspix2k.affected.build.AllFileChangesBuildSystem
import com.aspix2k.affected.build.BuildModule
import com.aspix2k.affected.build.BuildSystem
import com.aspix2k.affected.build.BuildSystems
import com.aspix2k.affected.build.RBuildSystem
import com.intellij.openapi.project.Project
import java.io.File
import java.lang.reflect.Proxy
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BuildSystemsVfsCostTest {

    @Test
    fun `generated roots are not discovered for an absent build system`() {
        val root = createTempDirectory("vfs-cost-absent").toFile()
        val r = CountingR()

        assertEquals(emptyList(), BuildSystems.generatedFileChangeRoots(projectAt(root), listOf(r)))
        assertEquals(0, r.moduleCalls)
    }

    @Test
    fun `generated roots are discovered for a present build system`() {
        val root = createTempDirectory("vfs-cost-present").toFile()
        File(root, "renv.lock").writeText("{}")
        val r = CountingR()

        assertEquals(
            listOf(root.invariantSeparatorsPath),
            BuildSystems.generatedFileChangeRoots(projectAt(root), listOf(r)),
        )
        assertEquals(1, r.moduleCalls)
    }

    @Test
    fun `presence is only probed for all-file build systems`() {
        val root = createTempDirectory("vfs-cost-probe").toFile()
        val unrelated = UnrelatedSystem()

        assertFalse(BuildSystems.includesAllFileChanges(projectAt(root), listOf(unrelated)))
        assertEquals(0, unrelated.presenceCalls)
    }

    @Test
    fun `a present all-file build system is reported`() {
        val root = createTempDirectory("vfs-cost-all").toFile()
        File(root, "renv.lock").writeText("{}")

        assertTrue(BuildSystems.includesAllFileChanges(projectAt(root), listOf(UnrelatedSystem(), CountingR())))
    }

    private class CountingR(
        private val delegate: RBuildSystem = RBuildSystem(),
    ) : BuildSystem by delegate, AllFileChangesBuildSystem {
        var moduleCalls = 0

        override val includeGeneratedFiles: Boolean = true

        override fun modules(project: Project): List<BuildModule> {
            moduleCalls++
            return delegate.modules(project)
        }
    }

    private class UnrelatedSystem : BuildSystem {
        var presenceCalls = 0
        override val id: String = "UNRELATED"
        override val sourceExtensions: Set<String> = emptySet()
        override fun isPresent(project: Project): Boolean = true.also { presenceCalls++ }
        override fun modules(project: Project): List<BuildModule> = emptyList()
        override fun run(project: Project, root: String, tasks: List<String>) = Unit
        override fun runAndWait(project: Project, root: String, tasks: List<String>): Boolean = true
    }

    private fun projectAt(root: File): Project =
        Proxy.newProxyInstance(
            Project::class.java.classLoader,
            arrayOf(Project::class.java),
        ) { proxy, method, arguments ->
            when (method.name) {
                "getBasePath" -> root.path
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === arguments?.singleOrNull()
                "toString" -> "Project(${root.path})"
                else -> error("Unexpected Project call: ${method.name}")
            }
        } as Project
}
