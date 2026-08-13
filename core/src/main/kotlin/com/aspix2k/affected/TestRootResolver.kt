package com.aspix2k.affected

import java.io.File

object TestRootResolver {

    private val TEST_SOURCE_DIRS = listOf(
        "src/test/kotlin",
        "src/test/java",
        "src/test/scala",
        "src/test/groovy",
        "src/testDebug/kotlin",
        "src/commonTest/kotlin",
        "src/jvmTest/kotlin",
        "src/test",
    )

    private val SOURCE_EXTENSIONS = setOf("kt", "java", "scala", "groovy")

    fun resolve(moduleDirectory: String): String? {
        val root = TEST_SOURCE_DIRS
            .map { File(moduleDirectory, it) }
            .firstOrNull { it.isDirectory }
            ?: return null

        return descend(root).path
    }

    private fun descend(directory: File): File {
        var current = directory
        while (true) {
            val children = current.listFiles() ?: return current
            if (children.any { it.isFile && it.extension in SOURCE_EXTENSIONS }) return current

            val subdirectories = children.filter { it.isDirectory }
            if (subdirectories.size != 1) return current
            current = subdirectories.single()
        }
    }
}
