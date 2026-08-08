package com.aspix2k.affected

import com.aspix2k.affected.build.GoPackages
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GoPackagesTest {

    private val stream = """
        {
            "Dir": "/ws/app",
            "ImportPath": "example.com/app",
            "Imports": [
                "example.com/app/internal/store",
                "fmt",
                "github.com/spf13/cobra"
            ],
            "TestGoFiles": ["app_test.go"]
        }
        {
            "Dir": "/ws/app/internal/store",
            "ImportPath": "example.com/app/internal/store",
            "Imports": ["database/sql"],
            "TestGoFiles": []
        }
        {
            "Dir": "/ws/app/cmd",
            "ImportPath": "example.com/app/cmd",
            "Imports": ["example.com/app"],
            "XTestGoFiles": ["cmd_external_test.go"]
        }
    """.trimIndent()

    @Test
    fun `every package becomes a module`() {
        val modules = GoPackages.parse(stream, "/ws/app")

        assertEquals(
            setOf("example.com/app", "example.com/app/internal/store", "example.com/app/cmd"),
            modules.map { it.id }.toSet(),
        )
    }

    @Test
    fun `only packages from this module are dependencies`() {
        val app = GoPackages.parse(stream, "/ws/app").single { it.id == "example.com/app" }

        assertEquals(
            setOf("/ws/app|example.com/app/internal/store"),
            app.dependencies,
            "fmt from the standard library and remote cobra cannot be consumers",
        )
    }

    @Test
    fun `external tests also make a package testable`() {
        val modules = GoPackages.parse(stream, "/ws/app")

        assertTrue(modules.single { it.id == "example.com/app" }.hasTests)
        assertTrue(modules.single { it.id == "example.com/app/cmd" }.hasTests, "XTestGoFiles are tests too")
        assertFalse(modules.single { it.id.endsWith("/store") }.hasTests)
    }

    @Test
    fun `a stream of objects without commas is fully parsed`() {
        assertEquals(
            3,
            GoPackages.parse(stream, "/ws/app").size,
            "go list emits consecutive objects rather than an array",
        )
    }

    @Test
    fun `truncated output does not crash parsing`() {
        val truncated = stream.substring(0, stream.length / 2)

        val modules = GoPackages.parse(truncated, "/ws/app")

        assertTrue(modules.size <= 3, "a truncated stream returns parsed data without throwing")
    }

    @Test
    fun `Windows paths use forward slashes`() {
        val windows = """{ "Dir": "C:\\ws\\app\\cmd", "ImportPath": "example.com/app/cmd", "Imports": [] }"""

        val module = GoPackages.parse(windows, "C:/ws/app").single()

        assertFalse('\\' in module.contentRoots.single())
        assertTrue(module.contentRoots.single().endsWith("app/cmd"))
    }
}
