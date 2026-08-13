package com.aspix2k.affected

import com.aspix2k.affected.build.isPestPackage
import com.aspix2k.affected.build.pestDeclared
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PestRuntimeTest {

    @Test
    fun `pestphp pest in require-dev is declared`() {
        val root = project()
        manifest(root, ".", """{ "name": "acme/app", "require-dev": { "pestphp/pest": "^3.8" } }""")

        assertTrue(pestDeclared(root))
    }

    @Test
    fun `a pest plugin in a nested package is declared`() {
        val root = project()
        manifest(root, ".", """{ "name": "acme/root" }""")
        manifest(
            root,
            "packages/api",
            """{ "name": "acme/api", "require": { "pestphp/pest-plugin-laravel": "^3.2" } }""",
        )

        assertTrue(pestDeclared(root))
    }

    @Test
    fun `a lockfile packages-dev entry is declared`() {
        val root = project()
        manifest(root, ".", """{ "name": "acme/app", "require-dev": { "phpunit/phpunit": "^11.5" } }""")
        File(root, "composer.lock").writeText(
            """{ "packages": [], "packages-dev": [ { "name": "pestphp/pest", "version": "3.8.2" } ] }""",
        )

        assertTrue(pestDeclared(root))
    }

    @Test
    fun `phpunit-only metadata is not pest`() {
        val root = project()
        manifest(root, ".", """{ "name": "acme/app", "require-dev": { "phpunit/phpunit": "^13.3" } }""")
        File(root, "composer.lock").writeText(
            """{ "packages": [], "packages-dev": [ { "name": "phpunit/phpunit", "version": "13.3.0" } ] }""",
        )

        assertFalse(pestDeclared(root))
    }

    @Test
    fun `unrelated pestphp packages are not the runner`() {
        val root = project()
        manifest(root, ".", """{ "name": "acme/app", "require-dev": { "pestphp/collision": "^3.0" } }""")

        assertFalse(pestDeclared(root))
        assertFalse(isPestPackage("pestphp/collision"))
        assertTrue(isPestPackage("pestphp/pest"))
        assertTrue(isPestPackage("pestphp/pest-plugin"))
    }

    @Test
    fun `vendor manifests do not count`() {
        val root = project()
        manifest(root, ".", """{ "name": "acme/app" }""")
        manifest(
            root,
            "vendor/pestphp/pest",
            """{ "name": "pestphp/pest", "require-dev": { "pestphp/pest": "^3.8" } }""",
        )

        assertFalse(pestDeclared(root))
    }

    @Test
    fun `malformed lock and missing files stay undeclared`() {
        val root = project()
        File(root, "composer.lock").writeText("{")

        assertFalse(pestDeclared(root))
        assertFalse(pestDeclared(File(root, "missing")))
    }

    private fun project(): File = createTempDirectory("pest-runtime").toFile()

    private fun manifest(root: File, path: String, body: String) {
        val directory = if (path == ".") root else File(root, path).apply { mkdirs() }
        File(directory, "composer.json").writeText(body)
    }
}
