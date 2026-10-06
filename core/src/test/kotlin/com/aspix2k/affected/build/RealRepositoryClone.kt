package com.aspix2k.affected.build

import java.io.File
import kotlin.test.assertEquals

internal object RealRepositoryClone {

    fun checkout(repository: RealRepository, directory: File) {
        val environment = mapOf("GIT_TERMINAL_PROMPT" to "0")
        listOf(
            listOf("git", "init", "--quiet"),
            listOf("git", "remote", "add", "origin", repository.url),
            listOf("git", "fetch", "--quiet", "--depth", "1", "origin", repository.sha),
            listOf("git", "checkout", "--quiet", "--detach", "FETCH_HEAD"),
        ).forEach { NativeProcessRunner.execute(it, directory, TIMEOUT_SECONDS, environment) }
        val head = NativeProcessRunner.execute(listOf("git", "rev-parse", "HEAD"), directory, TIMEOUT_SECONDS)
        assertEquals(repository.sha, head.trim(), "${repository.id}: fetched commit differs from the pinned SHA")
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

    private const val TIMEOUT_SECONDS = 300L
    private const val STATUS_PREFIX = 3
}
