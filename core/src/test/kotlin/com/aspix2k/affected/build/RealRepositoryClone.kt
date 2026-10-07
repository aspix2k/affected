package com.aspix2k.affected.build

import java.io.File
import kotlin.test.assertEquals

internal object RealRepositoryClone {

    fun checkout(repository: RealRepository, directory: File) {
        val environment = mapOf("GIT_TERMINAL_PROMPT" to "0")
        listOf(
            listOf("git", "init", "--quiet"),
            listOf("git", "remote", "add", "origin", repository.url),
        ).forEach { NativeProcessRunner.execute(it, directory, TIMEOUT_SECONDS, environment) }
        fetch(repository, directory, environment)
        NativeProcessRunner.execute(
            listOf("git", "checkout", "--quiet", "--detach", "FETCH_HEAD"),
            directory,
            TIMEOUT_SECONDS,
            environment,
        )
        NativeProcessRunner.execute(listOf("git", "branch", BASE_BRANCH), directory, TIMEOUT_SECONDS, environment)
        val head = NativeProcessRunner.execute(listOf("git", "rev-parse", "HEAD"), directory, TIMEOUT_SECONDS)
        assertEquals(repository.sha, head.trim(), "${repository.id}: fetched commit differs from the pinned SHA")
    }

    private fun fetch(repository: RealRepository, directory: File, environment: Map<String, String>) {
        val command = listOf("git", "fetch", "--quiet", "--depth", "1", "origin", repository.sha)
        repeat(FETCH_ATTEMPTS - 1) { attempt ->
            if (NativeProcessRunner.run(command, directory, TIMEOUT_SECONDS, environment).passed) return
            Thread.sleep(FETCH_BACKOFF_MILLIS shl attempt)
        }
        NativeProcessRunner.execute(command, directory, TIMEOUT_SECONDS, environment)
    }

    fun modified(directory: File): Set<String> =
        NativeProcessRunner.execute(listOf("git", "status", "--porcelain"), directory, TIMEOUT_SECONDS)
            .lineSequence()
            .filter(String::isNotBlank)
            .map { it.drop(STATUS_PREFIX).trim() }
            .toSet()

    fun restore(directory: File, files: List<String>, baseline: Set<String>) {
        NativeProcessRunner.execute(listOf("git", "checkout", "--quiet", "--") + files, directory, TIMEOUT_SECONDS)
        val produced = modified(directory) - baseline
        if (produced.isNotEmpty()) {
            NativeProcessRunner.execute(
                listOf("git", "clean", "--quiet", "-d", "--force", "--") + produced,
                directory,
                TIMEOUT_SECONDS,
            )
        }
    }

    const val BASE_BRANCH = "affected-base"
    private const val TIMEOUT_SECONDS = 300L
    private const val STATUS_PREFIX = 3
    private const val FETCH_ATTEMPTS = 4
    private const val FETCH_BACKOFF_MILLIS = 2_000L
}
