package com.aspix2k.affected

import com.aspix2k.affected.build.ManifestSearch
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ManifestSearchTest {

    private fun tree(
        breadth: Int,
        depth: Int,
        marker: String? = null,
        noise: List<String> = emptyList(),
    ): File {
        val root = createTempDirectory("search").toFile()

        fun build(directory: File, level: Int) {
            if (level > depth) return
            repeat(breadth) { index ->
                val child = File(directory, "level${level}_$index").apply { mkdirs() }
                marker?.let { File(child, it).writeText("{}") }
                build(child, level + 1)
            }
            noise.forEach { name ->
                val excluded = File(directory, name).apply { mkdirs() }
                File(excluded, marker ?: "composer.json").writeText("{}")
            }
        }

        build(root, 1)
        return root
    }

    @Test
    fun `исключённые каталоги не просматриваются`() {
        val root = tree(
            breadth = 2,
            depth = 2,
            marker = "composer.json",
            noise = listOf("vendor", "node_modules", "build"),
        )

        val found = ManifestSearch.find(root, "composer.json")

        val fromExcluded = found.filter { file ->
            generateSequence(file.parentFile) { it.parentFile }
                .any { it.name in setOf("vendor", "node_modules", "build") }
        }
        assertTrue(fromExcluded.isEmpty(), "внутрь vendor и node_modules заходить нельзя: $fromExcluded")
    }

    @Test
    fun `обход не уходит глубже разумного`() {
        val root = createTempDirectory("deep").toFile()
        var current = root
        repeat(15) { level ->
            current = File(current, "level$level").apply { mkdirs() }
            File(current, "composer.json").writeText("{}")
        }

        val found = ManifestSearch.find(root, "composer.json")

        assertTrue(found.isNotEmpty(), "верхние уровни должны находиться")
        assertTrue(found.size < 15, "глубокая цепочка обрезается, нашли ${found.size}")
    }

    @Test
    fun `скрытые каталоги пропускаются`() {
        val root = createTempDirectory("hidden").toFile()
        File(root, ".git/objects").mkdirs()
        File(root, ".git/objects/composer.json").writeText("{}")
        File(root, "app").mkdirs()
        File(root, "app/composer.json").writeText("{}")

        val found = ManifestSearch.find(root, "composer.json")

        assertEquals(listOf("app"), found.map { it.parentFile.name })
    }

    @Test
    fun `предел количества найденного соблюдается`() {
        val root = createTempDirectory("limit").toFile()
        repeat(20) { File(root, "$it.gemspec").writeText("") }

        val found = ManifestSearch.findByExtension(root, "gemspec", limit = 5)

        assertEquals(5, found.size)
    }
}
