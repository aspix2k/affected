package com.aspix2k.affected.build

import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CliConformanceRepositoryTest {

    @Test
    fun `repository resolution rejects an ambient relative root`() {
        assertFailsWith<IllegalArgumentException> {
            CliConformanceRepository(java.io.File("."))
        }
    }

    @Test
    fun `configured repository ignores mutable user directory for fixtures and adapters`() {
        val unrelated = createTempDirectory("affected-cli-unrelated").toFile()
        val original = System.getProperty("user.dir")
        try {
            System.setProperty("user.dir", unrelated.resolve("missing").path)
            val repository = CliConformanceRepository.configured
            val root = configuredRoot()

            assertEquals(root.resolve("conformance/cli-fixtures/go").canonicalFile, repository.fixture("go"))
            assertEquals(root.resolve("conformance/cli-fixtures/r").canonicalFile, repository.fixture("r"))
            assertEquals(
                root.resolve("core/src/main/python/affected_unittest.py").canonicalFile,
                repository.repositoryFile("core/src/main/python/affected_unittest.py"),
            )
            assertFailsWith<IllegalStateException> { error("injected setup failure") }
            assertEquals(
                root.resolve("core/src/main/python/affected_unittest.py").canonicalFile,
                repository.repositoryFile("core/src/main/python/affected_unittest.py"),
            )
            assertEquals(root.resolve("conformance/cli-fixtures/r").canonicalFile, repository.fixture("r"))
            assertEquals(root.resolve("conformance/cli-fixtures/go").canonicalFile, repository.fixture("go"))
        } finally {
            System.setProperty("user.dir", original)
            assertTrue(unrelated.deleteRecursively(), "Could not delete $unrelated")
        }
    }

    @Test
    fun `native CLI conformance does not discover fixtures through the ambient user directory`() {
        val buildTests = configuredRoot().resolve("core/src/test/kotlin/com/aspix2k/affected/build")
        val offenders = buildTests.walkTopDown()
            .filter { it.isFile && it.name.contains("Cli") && it.name.endsWith("ConformanceTest.kt") }
            .filter { LEGACY_AMBIENT_ROOT.containsMatchIn(it.readText()) }
            .map { it.relativeTo(configuredRoot()).invariantSeparatorsPath }
            .sorted()
            .toList()

        assertEquals(emptyList(), offenders)
    }

    @Test
    fun `fixture resolution rejects traversal outside the repository`() {
        assertFailsWith<IllegalArgumentException> {
            CliConformanceRepository.configured.fixture("../outside")
        }
    }

    @Test
    fun `fixture resolution rejects a missing directory`() {
        val root = createTempDirectory("affected-cli-repository").toFile()
        try {
            assertFailsWith<IllegalStateException> {
                CliConformanceRepository(root).fixture("missing")
            }
        } finally {
            assertTrue(root.deleteRecursively(), "Could not delete $root")
        }
    }

    @Test
    fun `fixture resolution rejects a directory symlink`() {
        val root = createTempDirectory("affected-cli-repository").toFile()
        val outside = createTempDirectory("affected-cli-outside").toFile()
        try {
            val fixtures = root.resolve("conformance/cli-fixtures").apply { mkdirs() }
            val link = fixtures.resolve("linked").toPath()
            assumeTrue(runCatching { Files.createSymbolicLink(link, outside.toPath()) }.isSuccess)

            assertFailsWith<IllegalArgumentException> {
                CliConformanceRepository(root).fixture("linked")
            }
        } finally {
            assertTrue(root.deleteRecursively(), "Could not delete $root")
            assertTrue(outside.deleteRecursively(), "Could not delete $outside")
        }
    }

    @Test
    fun `repository resolution rejects a symlinked root`() {
        val parent = createTempDirectory("affected-cli-root-parent").toFile()
        val outside = createTempDirectory("affected-cli-root-outside").toFile()
        try {
            val linkedRoot = parent.resolve("repository").toPath()
            assumeTrue(runCatching { Files.createSymbolicLink(linkedRoot, outside.toPath()) }.isSuccess)

            assertFailsWith<IllegalArgumentException> {
                CliConformanceRepository(linkedRoot.toFile())
            }
        } finally {
            assertTrue(parent.deleteRecursively(), "Could not delete $parent")
            assertTrue(outside.deleteRecursively(), "Could not delete $outside")
        }
    }

    @Test
    fun `repository file resolution rejects an intermediate symlink`() {
        val root = createTempDirectory("affected-cli-repository").toFile()
        try {
            val real = root.resolve("real").apply { mkdirs() }
            real.resolve("adapter.py").writeText("pass\n")
            val linked = root.resolve("linked").toPath()
            assumeTrue(runCatching { Files.createSymbolicLink(linked, real.toPath()) }.isSuccess)

            assertFailsWith<IllegalArgumentException> {
                CliConformanceRepository(root).repositoryFile("linked/adapter.py")
            }
        } finally {
            assertTrue(root.deleteRecursively(), "Could not delete $root")
        }
    }

    private fun configuredRoot(): File =
        File(checkNotNull(System.getProperty("affected.test.repositoryRoot"))).canonicalFile

    private companion object {
        val LEGACY_AMBIENT_ROOT = Regex("""generateSequence\(File\(System\.getProperty\("user\.dir"\)\)""")
    }
}
