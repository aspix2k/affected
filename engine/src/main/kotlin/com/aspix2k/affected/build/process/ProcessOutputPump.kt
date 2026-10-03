package com.aspix2k.affected.build.process

import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

internal class ProcessOutputPump(
    private val process: Process,
    private val executor: Executor,
    private val onText: (String, OutputKind) -> Unit,
    private val onTerminated: (Int) -> Unit,
) {

    fun start() {
        val readers = listOf(
            CompletableFuture.runAsync({ read(process.inputStream, OutputKind.STDOUT) }, executor),
            CompletableFuture.runAsync({ read(process.errorStream, OutputKind.STDERR) }, executor),
        )
        executor.execute {
            val exitCode = awaitExit()
            awaitReaders(readers)
            onTerminated(exitCode)
        }
    }

    private fun awaitExit(): Int {
        while (true) {
            try {
                return process.waitFor()
            } catch (_: InterruptedException) {
                continue
            }
        }
    }

    private fun awaitReaders(readers: List<CompletableFuture<Void>>) {
        val timedOut = readers.any { reader ->
            try {
                reader.get(READER_DRAIN_MILLIS, TimeUnit.MILLISECONDS)
                false
            } catch (_: TimeoutException) {
                true
            } catch (_: Exception) {
                false
            }
        }
        if (!timedOut) return
        runCatching { process.inputStream.close() }
        runCatching { process.errorStream.close() }
        readers.forEach { reader -> runCatching { reader.get(READER_CLOSE_MILLIS, TimeUnit.MILLISECONDS) } }
    }

    private fun read(stream: InputStream, kind: OutputKind) {
        val reader = InputStreamReader(stream, StandardCharsets.UTF_8)
        val chunk = CharArray(READ_BUFFER_CHARS)
        val lines = LineSplitter { onText(it, kind) }
        try {
            while (true) {
                val count = reader.read(chunk)
                if (count < 0) break
                lines.accept(chunk, count)
                if (!reader.ready()) lines.flush()
            }
        } catch (_: IOException) {
        } finally {
            lines.flush()
        }
    }
}

private class LineSplitter(private val emit: (String) -> Unit) {

    private val line = StringBuilder()
    private var carriageReturn = false

    fun accept(chunk: CharArray, count: Int) {
        for (index in 0 until count) accept(chunk[index])
    }

    fun flush() {
        if (line.isEmpty()) return
        val text = line.toString()
        line.setLength(0)
        carriageReturn = false
        emit(text)
    }

    private fun accept(char: Char) {
        if (carriageReturn && char != '\n') flush()
        line.append(char)
        when (char) {
            '\n' -> flush()
            '\r' -> carriageReturn = true
        }
    }
}

private const val READ_BUFFER_CHARS = 8 * 1024
private const val READER_DRAIN_MILLIS = 10_000L
private const val READER_CLOSE_MILLIS = 2_000L
