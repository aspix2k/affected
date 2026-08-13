package com.aspix2k.affected

import com.aspix2k.affected.build.ManifestSearch
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
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
    fun `excluded directories are not scanned`() {
        val root = tree(
            breadth = 2,
            depth = 2,
            marker = "composer.json",
            noise = listOf("vendor", "node_modules", "build", "fixtures"),
        )

        val found = ManifestSearch.find(root, "composer.json")

        val fromExcluded = found.filter { file ->
            generateSequence(file.parentFile) { it.parentFile }
                .any { it.name in setOf("vendor", "node_modules", "build", "fixtures") }
        }
        assertTrue(fromExcluded.isEmpty(), "generated and fixture directories must not be scanned: $fromExcluded")
    }

    @Test
    fun `traversal stays within a reasonable depth`() {
        val root = createTempDirectory("deep").toFile()
        var current = root
        repeat(15) { level ->
            current = File(current, "level$level").apply { mkdirs() }
            File(current, "composer.json").writeText("{}")
        }

        val found = ManifestSearch.find(root, "composer.json")

        assertTrue(found.isNotEmpty(), "upper levels must be found")
        assertTrue(found.size < 15, "a deep chain is truncated, found ${found.size}")
    }

    @Test
    fun `hidden directories are skipped`() {
        val root = createTempDirectory("hidden").toFile()
        File(root, ".git/objects").mkdirs()
        File(root, ".git/objects/composer.json").writeText("{}")
        File(root, "app").mkdirs()
        File(root, "app/composer.json").writeText("{}")

        val found = ManifestSearch.find(root, "composer.json")

        assertEquals(listOf("app"), found.map { it.parentFile.name })
    }

    @Test
    fun `reaching the result limit fails closed`() {
        val root = createTempDirectory("limit").toFile()
        repeat(20) { File(root, "$it.gemspec").writeText("") }

        val found = ManifestSearch.findByExtension(root, "gemspec", limit = 5)

        assertEquals(emptyList(), found)
    }

    @Test
    fun `fingerprint follows manifest content instead of timestamps`() {
        val root = createTempDirectory("fingerprint").toFile()
        val manifest = File(root, "package.json").apply { writeText("{\"name\":\"first\"}") }
        val timestamp = manifest.lastModified()
        val first = ManifestSearch.fingerprint(root, listOf(manifest))
        manifest.writeText("{\"name\":\"other\"}")
        assertTrue(manifest.setLastModified(timestamp))

        val second = ManifestSearch.fingerprint(root, listOf(manifest))

        assertNotEquals(first, second)
    }

    @Test
    fun `symlinked manifests are neither scanned nor cached`() {
        val root = createTempDirectory("manifest-root").toFile()
        val outside = createTempDirectory("manifest-outside").resolve("package.json")
        Files.writeString(outside, "{}")
        val link = root.toPath().resolve("package.json")
        assumeTrue(runCatching { Files.createSymbolicLink(link, outside) }.isSuccess)

        assertEquals(emptyList(), ManifestSearch.find(root, "package.json"))
        assertNull(ManifestSearch.fingerprint(root, listOf(link.toFile())))
    }

    @Test
    fun `a symlinked package directory invalidates discovery`() {
        val root = createTempDirectory("manifest-link-root").toFile()
        val outside = createTempDirectory("manifest-link-outside").toFile()
        File(outside, "pyproject.toml").writeText("[project]\nname = 'linked'\n")
        val link = File(root, "linked-package").toPath()
        assumeTrue(runCatching { Files.createSymbolicLink(link, outside.toPath()) }.isSuccess)

        assertEquals(emptyList(), ManifestSearch.find(root, "pyproject.toml"))
        assertNull(ManifestSearch.anyFile(root) { it.name.startsWith("test_") })
    }

    @Test
    fun `layout fingerprints change when a test directory appears`() {
        val root = createTempDirectory("layout-fingerprint").toFile()
        val before = ManifestSearch.layoutFingerprint(root) { it.name == "tests" }

        File(root, "package/tests").mkdirs()
        val after = ManifestSearch.layoutFingerprint(root) { it.name == "tests" }

        assertNotEquals(before, after)
    }

    @Test
    fun `oversized manifests are not parsed`() {
        val root = createTempDirectory("manifest-size").toFile()
        val manifest = File(root, "package.json")
        manifest.writeBytes(ByteArray(8 * 1024 * 1024 + 1))

        assertNull(ManifestSearch.readText(manifest))
    }
}
