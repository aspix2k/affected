package com.aspix2k.affected

import com.aspix2k.affected.build.CommandRunner
import com.aspix2k.affected.build.GoPackages
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

/**
 * Parses what the real toolchain prints for a real project, which synthetic
 * fixtures cannot vouch for. Skips when the fixture or the toolchain is absent.
 */
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
    fun `реальный вывод go list разбирается в пакеты с зависимостями`() {
        val listing = listing()
        assumeTrue(listing != null)
        val (root, output) = listing!!

        val modules = GoPackages.parse(output, root.invariantSeparatorsPath)

        assertTrue(modules.size >= 5, "в gin больше пяти пакетов, разобрали ${modules.size}")
        assertTrue(
            modules.any { it.dependencies.isNotEmpty() },
            "внутренние импорты обязаны давать рёбра графа",
        )
        assertTrue(
            modules.any { it.hasTests },
            "в gin есть пакеты с тестами",
        )
    }

    @Test
    fun `каталоги пакетов существуют на диске`() {
        val listing = listing()
        assumeTrue(listing != null)
        val (root, output) = listing!!

        val missing = GoPackages.parse(output, root.invariantSeparatorsPath)
            .map { File(it.contentRoots.single()) }
            .filterNot { it.isDirectory }

        assertTrue(missing.isEmpty(), "каталоги из go list должны существовать: $missing")
    }

    @Test
    fun `зависимости ссылаются на существующие пакеты`() {
        val listing = listing()
        assumeTrue(listing != null)
        val (root, output) = listing!!

        val modules = GoPackages.parse(output, root.invariantSeparatorsPath)
        val keys = modules.map { it.key }.toSet()
        val dangling = modules.flatMap { it.dependencies }.filterNot { it in keys }

        assertTrue(dangling.isEmpty(), "ребро не может указывать в пустоту: $dangling")
    }
}
