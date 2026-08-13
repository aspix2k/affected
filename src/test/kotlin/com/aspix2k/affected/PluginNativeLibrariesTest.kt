package com.aspix2k.affected

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PluginNativeLibrariesTest {

    @Test
    fun `a native shared library is reported`() {
        val root = createTempDirectory("native-lib").toFile()
        File(root, "natives/libfoo.so").apply {
            parentFile.mkdirs()
            writeBytes(byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()))
        }
        File(root, "Main.kt").writeText("class Main")

        val found = PluginNativeLibraries.find(root).map { it.relativeTo(root).invariantSeparatorsPath }

        assertEquals(listOf("natives/libfoo.so"), found)
    }

    @Test
    fun `the packaged plugin sources do not ship native libraries`() {
        val found = PluginNativeLibraries.PLUGIN_TREES.flatMap { root ->
            PluginNativeLibraries.find(root).map { "${root.path}:${it.relativeTo(root).path}" }
        }
        assertEquals(emptyList(), found)
    }
}

object PluginNativeLibraries {

    val PLUGIN_TREES = listOf(
        File("src/main"),
        File("mcp/src/main"),
        File("core/src/main"),
    )

    private val EXTENSIONS = setOf("so", "dll", "dylib", "jnilib")

    fun find(root: File): List<File> {
        assertTrue(root.isDirectory, "plugin tree is missing: ${root.path}")
        return root.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in EXTENSIONS }
            .sortedBy { it.invariantSeparatorsPath }
            .toList()
    }
}
