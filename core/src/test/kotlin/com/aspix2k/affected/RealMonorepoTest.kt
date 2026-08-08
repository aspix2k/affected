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
        assertTrue(modules.size >= minimum, "ожидали не меньше $minimum модулей, разобрали ${modules.size}")

        val missing = modules.map { File(it.contentRoots.single()) }.filterNot { it.isDirectory }
        assertTrue(missing.isEmpty(), "каталоги модулей должны существовать: ${missing.take(3)}")

        val keys = modules.map { it.key }.toSet()
        val dangling = modules.flatMap { it.dependencies }.filterNot { it in keys }
        assertTrue(dangling.isEmpty(), "ребро не может указывать в пустоту: ${dangling.take(3)}")
    }

    @Test
    fun `symfony разбирается как монорепо composer`() {
        val root = fixture("php-symfony")
        assumeTrue(root != null)

        val modules = ComposerPackages.parse(root!!)

        assertGraphIsSound(modules, minimum = 50)
        assertTrue(
            modules.any { it.dependencies.isNotEmpty() },
            "компоненты symfony зависят друг от друга",
        )
        assertTrue(modules.any { it.hasTests }, "у компонентов symfony есть тесты")
    }

    @Test
    fun `у symfony находится проверка потребителей через статический анализ`() {
        val root = fixture("php-symfony")
        assumeTrue(root != null)

        val modules = ComposerPackages.parse(root!!)

        assertTrue(
            modules.any { it.compileTask != null },
            "хотя бы один компонент symfony должен запускать статический анализ",
        )
    }

    @Test
    fun `rails разбирается как монорепо гемов`() {
        val root = fixture("ruby-rails")
        assumeTrue(root != null)

        val modules = RubyGems.parse(root!!)

        assertGraphIsSound(modules, minimum = 8)
        assertTrue(
            modules.any { it.dependencies.isNotEmpty() },
            "actionpack зависит от activesupport и подобного",
        )
        assertTrue(modules.all { it.compileTask == null }, "в ruby потребители не проверяются")
    }

    @Test
    fun `цели fmt разбираются из cmake`() {
        val root = fixture("cmake-fmt")
        assumeTrue(root != null)

        val modules = CMakeTargets.parse(root!!)

        assertGraphIsSound(modules, minimum = 2)
    }

    @Test
    fun `цели spdlog разбираются из cmake`() {
        val root = fixture("cmake-spdlog")
        assumeTrue(root != null)

        val modules = CMakeTargets.parse(root!!)

        assertGraphIsSound(modules, minimum = 2)
    }

    @Test
    fun `ни один разбор не занимает недопустимо долго`() {
        val root = fixture("php-symfony")
        assumeTrue(root != null)

        val started = System.nanoTime()
        ComposerPackages.parse(root!!)
        val took = (System.nanoTime() - started) / 1_000_000

        assertTrue(took < 10_000, "разбор 194 пакетов занял $took мс, это уже заметно пользователю")
    }
}
