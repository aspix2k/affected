package com.aspix2k.affected

import com.aspix2k.affected.build.CommandRunner
import com.aspix2k.affected.build.GoPackages
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class GoFixtureTest {

    private val fixture = "go-gin"

    private fun listing(): Pair<File, String>? {
        if (!FixtureRepository.available(fixture)) return null
        val root = File(FixtureRepository.root, fixture)
        val output = CommandRunner.capture(
            root.invariantSeparatorsPath,
            listOf("go", "list", "-json", "./..."),
            timeoutSeconds = 180,
        ) ?: return null
        return root to output
    }

    @Test
    fun `real go list output is parsed into packages with dependencies`() {
        val listing = listing()
        assumeTrue(listing != null)
        val (root, output) = listing!!

        val modules = GoPackages.parse(output, root.invariantSeparatorsPath)

        assertTrue(modules.size >= 5, "Gin has more than five packages, parsed ${modules.size}")
        assertTrue(
            modules.any { it.dependencies.isNotEmpty() },
            "internal imports must create graph edges",
        )
        assertTrue(
            modules.any { it.hasTests },
            "Gin has packages with tests",
        )
    }

    @Test
    fun `package directories exist on disk`() {
        val listing = listing()
        assumeTrue(listing != null)
        val (root, output) = listing!!

        val missing = GoPackages.parse(output, root.invariantSeparatorsPath)
            .map { File(it.contentRoots.single()) }
            .filterNot { it.isDirectory }

        assertTrue(missing.isEmpty(), "directories from go list must exist: $missing")
    }

    @Test
    fun `dependencies reference existing packages`() {
        val listing = listing()
        assumeTrue(listing != null)
        val (root, output) = listing!!

        val modules = GoPackages.parse(output, root.invariantSeparatorsPath)
        val keys = modules.map { it.key }.toSet()
        val dangling = modules.flatMap { it.dependencies }.filterNot { it in keys }

        assertTrue(dangling.isEmpty(), "an edge cannot point nowhere: $dangling")
    }
}
