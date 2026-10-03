package com.aspix2k.affected.build.process

import java.nio.file.Files
import java.nio.file.Path

internal fun testJavaClassPathArgument(directory: Path): String {
    val argumentFile = directory.resolve(".affected-test-java-classpath.args")
    Files.writeString(argumentFile, "-cp\n${javaArgumentFileValue(System.getProperty("java.class.path"))}\n")
    return "@${argumentFile.fileName}"
}

private fun javaArgumentFileValue(value: String): String = buildString {
    append('"')
    value.forEach { character ->
        when (character) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n', '\r' -> error("A Java classpath cannot contain a line break")
            else -> append(character)
        }
    }
    append('"')
}
