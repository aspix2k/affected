package com.aspix2k.affected

import java.io.File
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertTrue

class BundleCompletenessTest {

    private val directory = File("src/main/resources/messages")

    private fun keysOf(file: File): Set<String> =
        Properties().apply { file.inputStream().use { load(it) } }
            .stringPropertyNames()

    private fun translations(): List<File> =
        directory.listFiles { file -> file.name.startsWith("AffectedBundle_") }.orEmpty().sortedBy { it.name }

    @Test
    fun `every translation contains every English key`() {
        val expected = keysOf(File(directory, "AffectedBundle.properties"))

        val gaps = translations().mapNotNull { file ->
            val missing = expected - keysOf(file)
            if (missing.isEmpty()) null else "${file.name}: ${missing.sorted()}"
        }

        assertTrue(gaps.isEmpty(), "translations are missing keys:\n${gaps.joinToString("\n")}")
    }

    @Test
    fun `translations contain no keys absent from English`() {
        val expected = keysOf(File(directory, "AffectedBundle.properties"))

        val strays = translations().mapNotNull { file ->
            val extra = keysOf(file) - expected
            if (extra.isEmpty()) null else "${file.name}: ${extra.sorted()}"
        }

        assertTrue(strays.isEmpty(), "extra keys remain from removed strings:\n${strays.joinToString("\n")}")
    }

    @Test
    fun `no value remains blank`() {
        val blanks = (translations() + File(directory, "AffectedBundle.properties")).mapNotNull { file ->
            val properties = Properties().apply { file.inputStream().use { load(it) } }
            val empty = properties.stringPropertyNames().filter { properties.getProperty(it).isBlank() }
            if (empty.isEmpty()) null else "${file.name}: ${empty.sorted()}"
        }

        assertTrue(
            blanks.isEmpty(),
            "blank values count as missing translations:\n${blanks.joinToString("\n")}",
        )
    }
}
