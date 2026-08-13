package com.aspix2k.affected

import com.aspix2k.affected.build.CMakeTargets
import com.aspix2k.affected.build.ComposerPackages
import com.aspix2k.affected.build.RubyGems
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class RealMonorepoTest {

    private fun fixture(name: String): File? =
        File(FixtureRepository.root, name).takeIf { it.isDirectory }

    private fun assertGraphIsSound(modules: List<com.aspix2k.affected.build.BuildModule>, minimum: Int) {
        assertTrue(modules.size >= minimum, "expected at least $minimum modules, parsed ${modules.size}")

        val missing = modules.map { File(it.contentRoots.single()) }.filterNot { it.isDirectory }
        assertTrue(missing.isEmpty(), "module directories must exist: ${missing.take(3)}")

        val keys = modules.map { it.key }.toSet()
        val dangling = modules.flatMap { it.dependencies }.filterNot { it in keys }
        assertTrue(dangling.isEmpty(), "an edge cannot point nowhere: ${dangling.take(3)}")
    }

    @Test
    fun `Symfony is parsed as a Composer monorepo`() {
        val root = fixture("php-symfony")
        assumeTrue(root != null)

        val modules = ComposerPackages.parse(root!!)

        assertGraphIsSound(modules, minimum = 50)
        assertTrue(
            modules.any { it.dependencies.isNotEmpty() },
            "Symfony components depend on each other",
        )
        assertTrue(modules.any { it.hasTests }, "Symfony components have tests")
    }

    @Test
    fun `Symfony consumer checks use static analysis`() {
        val root = fixture("php-symfony")
        assumeTrue(root != null)

        val modules = ComposerPackages.parse(root!!)

        assertTrue(
            modules.any { it.compileTask != null },
            "at least one Symfony component must run static analysis",
        )
    }

    @Test
    fun `Rails is parsed as a gem monorepo`() {
        val root = fixture("ruby-rails")
        assumeTrue(root != null)

        val modules = RubyGems.parse(root!!)

        assertGraphIsSound(modules, minimum = 8)
        assertTrue(
            modules.any { it.dependencies.isNotEmpty() },
            "actionpack depends on activesupport and related gems",
        )
        assertTrue(modules.all { it.compileTask == null }, "Ruby consumers are not checked")
    }

    @Test
    fun `fmt targets are parsed from CMake`() {
        val root = fixture("cmake-fmt")
        assumeTrue(root != null)

        val modules = CMakeTargets.parse(root!!)

        assertGraphIsSound(modules, minimum = 2)
    }

    @Test
    fun `spdlog targets are parsed from CMake`() {
        val root = fixture("cmake-spdlog")
        assumeTrue(root != null)

        val modules = CMakeTargets.parse(root!!)

        assertGraphIsSound(modules, minimum = 2)
    }

    @Test
    fun `no parser takes unacceptably long`() {
        val root = fixture("php-symfony")
        assumeTrue(root != null)

        val started = System.nanoTime()
        ComposerPackages.parse(root!!)
        val took = (System.nanoTime() - started) / 1_000_000

        assertTrue(took < 10_000, "parsing 194 packages took $took ms and is user-visible")
    }
}
