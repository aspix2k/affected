package com.aspix2k.affected.build

import java.io.File
import kotlin.test.assertTrue

internal object BazelNativeRunner {

    fun execute(directory: File, arguments: List<String>, timeoutSeconds: Long): String =
        OwnedSandbox.use("affected-bazel-out") { home ->
            val environment = mapOf("TEST_TMPDIR" to home.root.absolutePath)
            val run = runCatching { NativeProcessRunner.execute(arguments, directory, timeoutSeconds, environment) }
            val shutdown = runCatching { shutdown(directory, environment) }
            val primary = run.exceptionOrNull()
            val secondary = shutdown.exceptionOrNull()
            if (primary != null) throw primary.also { failure -> secondary?.let(failure::addSuppressed) }
            secondary?.let { failure -> System.err.println("bazel shutdown failed: ${failure.message}") }
            run.getOrThrow()
        }

    private fun shutdown(directory: File, environment: Map<String, String>) {
        val result = NativeProcessRunner.run(
            listOf("bazel", "shutdown"),
            directory,
            SHUTDOWN_TIMEOUT_SECONDS,
            environment,
        )
        assertTrue(result.completed, "Timed out: bazel shutdown\n${result.output}")
        assertTrue(result.passed, "Failed: bazel shutdown\n${result.output}")
    }

    private const val SHUTDOWN_TIMEOUT_SECONDS = 60L
}
