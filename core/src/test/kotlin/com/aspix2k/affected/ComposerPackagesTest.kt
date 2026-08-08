package com.aspix2k.affected

import com.aspix2k.affected.build.ComposerPackages
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ComposerPackagesTest {

    private fun monorepo(): File = createTempDirectory("composer").toFile()

    private fun packageAt(
        root: File,
        path: String,
        name: String,
        require: Map<String, String> = emptyMap(),
        requireDev: Map<String, String> = emptyMap(),
        tests: Boolean = false,
        phpstan: Boolean = false,
    ) {
        val directory = File(root, path).apply { mkdirs() }
        val requires = require.entries.joinToString(", ") { """"${it.key}": "${it.value}"""" }
        val devs = requireDev.entries.joinToString(", ") { """"${it.key}": "${it.value}"""" }
        File(directory, "composer.json").writeText(
            """{ "name": "$name", "require": { $requires }, "require-dev": { $devs } }""",
        )
        if (tests) File(directory, "tests").mkdirs()
        if (phpstan) File(directory, "phpstan.neon").writeText("parameters:\n  level: 8\n")
    }

    @Test
    fun `пакеты монорепо находятся`() {
        val root = monorepo()
        packageAt(root, ".", "acme/root")
        packageAt(root, "packages/core", "acme/core")
        packageAt(root, "packages/api", "acme/api")

        val modules = ComposerPackages.parse(root)

        assertEquals(setOf("acme/root", "acme/core", "acme/api"), modules.map { it.id }.toSet())
    }

    @Test
    fun `зависимостями считаются только свои пакеты`() {
        val root = monorepo()
        packageAt(root, "packages/core", "acme/core")
        packageAt(
            root,
            "packages/api",
            "acme/api",
            require = mapOf("acme/core" to "*", "symfony/console" to "^7.0"),
        )

        val api = ComposerPackages.parse(root).single { it.id == "acme/api" }

        assertEquals(
            setOf("${root.invariantSeparatorsPath}|acme/core"),
            api.dependencies,
            "symfony ставится с packagist и потребителем быть не может",
        )
    }

    @Test
    fun `require-dev тоже создаёт ребро`() {
        val root = monorepo()
        packageAt(root, "packages/core", "acme/core")
        packageAt(root, "packages/test-utils", "acme/test-utils", requireDev = mapOf("acme/core" to "*"))

        val utils = ComposerPackages.parse(root).single { it.id == "acme/test-utils" }

        assertEquals(setOf("${root.invariantSeparatorsPath}|acme/core"), utils.dependencies)
    }

    @Test
    fun `потребителя проверяем только при статическом анализаторе`() {
        val root = monorepo()
        packageAt(root, "packages/analysed", "acme/analysed", phpstan = true)
        packageAt(root, "packages/plain", "acme/plain")

        val modules = ComposerPackages.parse(root)

        assertEquals("analyse", modules.single { it.id == "acme/analysed" }.compileTask)
        assertNull(
            modules.single { it.id == "acme/plain" }.compileTask,
            "в php компилировать нечего, а без phpstan и проверять нечем",
        )
    }

    @Test
    fun `psalm в зависимостях тоже включает проверку`() {
        val root = monorepo()
        packageAt(root, "packages/a", "acme/a", requireDev = mapOf("vimeo/psalm" to "^5.0"))
        packageAt(root, "packages/b", "acme/b")

        val modules = ComposerPackages.parse(root)

        assertEquals("analyse", modules.single { it.id == "acme/a" }.compileTask)
    }

    @Test
    fun `каталог tests делает пакет тестируемым`() {
        val root = monorepo()
        packageAt(root, "packages/with", "acme/with", tests = true)
        packageAt(root, "packages/without", "acme/without")

        val modules = ComposerPackages.parse(root)

        assertTrue(modules.single { it.id == "acme/with" }.hasTests)
        assertFalse(modules.single { it.id == "acme/without" }.hasTests)
    }

    @Test
    fun `vendor не просматривается`() {
        val root = monorepo()
        packageAt(root, "packages/core", "acme/core")
        packageAt(root, "packages/api", "acme/api")
        packageAt(root, "vendor/someone/library", "someone/library")

        assertEquals(setOf("acme/core", "acme/api"), ComposerPackages.parse(root).map { it.id }.toSet())
    }

    @Test
    fun `одиночный пакет не считается монорепо`() {
        val root = monorepo()
        packageAt(root, ".", "acme/single")

        assertEquals(emptyList(), ComposerPackages.parse(root))
    }
}
