package com.aspix2k.affected.build

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModuleDiscoveryTest {

    @Test
    fun `missing tool output widens to a runnable root module`() {
        val root = createTempDirectory("missing-cli").toFile()

        val discovery = failClosedModules(root, "test", "build", null)

        assertFalse(discovery.complete)
        assertEquals(1, discovery.modules.size)
        assertEquals(".", discovery.modules.single().executionId)
        assertTrue(discovery.modules.single().hasTests)
        assertEquals("build", discovery.modules.single().compileTask)
    }

    @Test
    fun `malformed metadata cannot produce a silent empty graph`() {
        val root = createTempDirectory("malformed-cli").toFile()

        val cargo = failClosedModules(
            root,
            CargoMetadata.TEST,
            CargoMetadata.COMPILE,
            CargoMetadata.parse("{", root.path),
        )
        val go = failClosedModules(root, GoPackages.TEST, GoPackages.COMPILE, GoPackages.parse("{", root.path))

        assertFalse(cargo.complete)
        assertFalse(go.complete)
        assertEquals(".", cargo.modules.single().executionId)
        assertEquals(".", go.modules.single().executionId)
    }

    @Test
    fun `a proven module graph stays selective`() {
        val root = createTempDirectory("complete-cli").toFile()
        val module = rootFallbackModule(root, "test", null).copy(id = "known", executionId = "known")

        val discovery = failClosedModules(root, "test", null, listOf(module))

        assertTrue(discovery.complete)
        assertEquals(listOf(module), discovery.modules)
    }
}
