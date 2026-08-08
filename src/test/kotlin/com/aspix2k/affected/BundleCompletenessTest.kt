package com.aspix2k.affected

import java.io.File
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A key added to the English bundle and forgotten elsewhere shows up as a raw
 * key in someone's IDE. This is cheaper than noticing that in a screenshot.
 */
class BundleCompletenessTest {

    private val directory = File("src/main/resources/messages")

    private fun keysOf(file: File): Set<String> =
        Properties().apply { file.inputStream().use { load(it) } }
            .stringPropertyNames()

    private fun translations(): List<File> =
        directory.listFiles { file -> file.name.startsWith("AffectedBundle_") }.orEmpty().sortedBy { it.name }

    @Test
    fun `каждый перевод содержит все ключи английского`() {
        val expected = keysOf(File(directory, "AffectedBundle.properties"))

        val gaps = translations().mapNotNull { file ->
            val missing = expected - keysOf(file)
            if (missing.isEmpty()) null else "${file.name}: ${missing.sorted()}"
        }

        assertTrue(gaps.isEmpty(), "в переводах не хватает ключей:\n${gaps.joinToString("\n")}")
    }

    @Test
    fun `перевод не содержит ключей, которых нет в английском`() {
        val expected = keysOf(File(directory, "AffectedBundle.properties"))

        val strays = translations().mapNotNull { file ->
            val extra = keysOf(file) - expected
            if (extra.isEmpty()) null else "${file.name}: ${extra.sorted()}"
        }

        assertTrue(strays.isEmpty(), "лишние ключи остались от удалённых строк:\n${strays.joinToString("\n")}")
    }

    @Test
    fun `ни одно значение не осталось пустым`() {
        val blanks = (translations() + File(directory, "AffectedBundle.properties")).mapNotNull { file ->
            val properties = Properties().apply { file.inputStream().use { load(it) } }
            val empty = properties.stringPropertyNames().filter { properties.getProperty(it).isBlank() }
            if (empty.isEmpty()) null else "${file.name}: ${empty.sorted()}"
        }

        assertTrue(blanks.isEmpty(), "пустые значения читаются как отсутствующий перевод:\n${blanks.joinToString("\n")}")
    }
}
