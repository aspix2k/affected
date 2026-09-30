package com.aspix2k.affected.build

import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CliConformanceRepositoryTest {

    @Test
    fun `repository resolution rejects an ambient relative root`() {
        assertFailsWith<IllegalArgumentException> {
            CliConformanceRepository(File("."))
        }
    }

    @Test
    fun `configured repository ignores mutable user directory for fixtures and adapters`() {
        OwnedSandbox.use("affected-cli-unrelated") { sandbox ->
            val unrelated = sandbox.root
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
            }
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
        OwnedSandbox.use("affected-cli-repository") { sandbox ->
            assertFailsWith<IllegalStateException> {
                CliConformanceRepository(sandbox.root).fixture("missing")
            }
        }
    }

    @Test
    fun `fixture resolution rejects a directory symlink`() {
        OwnedSandbox.use("affected-cli-repository") { sandbox ->
            OwnedSandbox.use("affected-cli-outside") { outside ->
                val fixtures = sandbox.root.resolve("conformance/cli-fixtures").apply { mkdirs() }
                val link = fixtures.resolve("linked").toPath()
                assumeTrue(runCatching { Files.createSymbolicLink(link, outside.root.toPath()) }.isSuccess)

                assertFailsWith<IllegalArgumentException> {
                    CliConformanceRepository(sandbox.root).fixture("linked")
                }
            }
        }
    }

    @Test
    fun `repository resolution rejects a symlinked root`() {
        OwnedSandbox.use("affected-cli-root-parent") { parent ->
            OwnedSandbox.use("affected-cli-root-outside") { outside ->
                val linkedRoot = parent.root.resolve("repository").toPath()
                assumeTrue(runCatching { Files.createSymbolicLink(linkedRoot, outside.root.toPath()) }.isSuccess)

                assertFailsWith<IllegalArgumentException> {
                    CliConformanceRepository(linkedRoot.toFile())
                }
            }
        }
    }

    @Test
    fun `repository file resolution rejects an intermediate symlink`() {
        OwnedSandbox.use("affected-cli-repository") { sandbox ->
            val real = sandbox.root.resolve("real").apply { mkdirs() }
            real.resolve("adapter.py").writeText("pass\n")
            val linked = sandbox.root.resolve("linked").toPath()
            assumeTrue(runCatching { Files.createSymbolicLink(linked, real.toPath()) }.isSuccess)

            assertFailsWith<IllegalArgumentException> {
                CliConformanceRepository(sandbox.root).repositoryFile("linked/adapter.py")
            }
        }
    }

    private fun configuredRoot(): File =
        File(checkNotNull(System.getProperty("affected.test.repositoryRoot"))).canonicalFile

    private companion object {
        val LEGACY_AMBIENT_ROOT = Regex("""generateSequence\(File\(System\.getProperty\("user\.dir"\)\)""")
    }
}
