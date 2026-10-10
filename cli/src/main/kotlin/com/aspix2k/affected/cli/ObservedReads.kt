package com.aspix2k.affected.cli

import com.aspix2k.affected.Engine
import com.aspix2k.affected.ModuleInfo
import com.aspix2k.affected.TaskKey
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

internal class ObservedReads(
    private val arguments: Arguments,
    private val cache: Path,
    private val tracer: String,
) {

    fun unplanned(tracked: List<File>): Map<File, List<ModuleInfo>>? {
        val byRealPath = tracked.associateBy { it.canonicalFile }
        val trace = Files.createTempFile("affected-trace", ".txt")
        try {
            val prefix = listOf(tracer, "-f", "-qq", "-A", "-e", "trace=?open,openat,?openat2,execve", "-o", "$trace")
            val plan = Engine.plan(arguments.request(cache, prefix) { _, _ -> })
            val readers = LinkedHashMap<File, MutableList<ModuleInfo>>()
            for (module in plan.testModules) {
                Files.deleteIfExists(trace)
                if (!runBlocking { Engine.runTests(plan, module) } || !Files.isRegularFile(trace)) return null
                val key = TaskKey(module.systemId, module.executionRoot, module.test())
                Files.newBufferedReader(trace).useLines { lines ->
                    lines.mapNotNull { straceRead(it, File(module.executionRoot)) }
                        .mapNotNullTo(LinkedHashSet()) { byRealPath[it.canonicalFile] }
                }.filter { key !in Engine.testsCovering(plan, relative(it)) }
                    .forEach { readers.getOrPut(it) { mutableListOf() } += module }
            }
            return readers
        } finally {
            Files.deleteIfExists(trace)
        }
    }

    private fun relative(file: File): String = file.relativeToOrSelf(arguments.directory).invariantSeparatorsPath
}

internal fun tracerOnPath(): String? = System.getenv("PATH").orEmpty().split(File.pathSeparator)
    .map { File(it, "strace") }
    .firstOrNull { it.isFile && it.canExecute() }?.path

internal fun straceRead(line: String, workingDirectory: File): File? {
    if (FAILED in line) return null
    val match = OPENED.find(line) ?: EXECUTED.find(line) ?: return null
    if (match.groupValues.getOrNull(3).orEmpty().contains("O_WRONLY")) return null
    val path = File(unescaped(match.groupValues[2]))
    return when {
        path.isAbsolute -> path
        match.groupValues[1].isEmpty() -> File(workingDirectory, path.path)
        else -> null
    }
}

private fun unescaped(text: String): String {
    val bytes = ByteArrayOutputStream()
    var index = 0
    while (index < text.length) {
        val char = text[index]
        val octal = if (char == '\\') OCTAL.matchAt(text, index + 1) else null
        when {
            octal != null -> {
                bytes.write(octal.value.toInt(OCTAL_RADIX))
                index += octal.value.length + 1
            }
            char == '\\' && index + 1 < text.length -> {
                bytes.write(ESCAPES[text[index + 1]] ?: text[index + 1].code)
                index += 2
            }
            else -> {
                bytes.write(char.toString().toByteArray())
                index++
            }
        }
    }
    return bytes.toString(Charsets.UTF_8)
}

private const val FAILED = ") = -1 "
private const val OCTAL_RADIX = 8
private val OPENED =
    Regex("""\bopen(?:at2?)?\((?:AT_FDCWD, |(\d+)(?:<[^>]*>)?, )?"((?:[^"\\]|\\.)*)", ([A-Z_|0-9x{}a-z=, ]+)""")
private val EXECUTED = Regex("""\bexecve\(()"((?:[^"\\]|\\.)*)"""")
private val OCTAL = Regex("[0-7]{1,3}")
private val ESCAPES = mapOf('n' to '\n'.code, 't' to '\t'.code, 'r' to '\r'.code, 'f' to 0x0c, 'v' to 0x0b)
