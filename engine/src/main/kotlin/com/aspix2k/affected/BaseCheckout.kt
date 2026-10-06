package com.aspix2k.affected

import com.aspix2k.affected.build.resolveExecutable
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class BaseCheckout(
    private val projectDir: File,
    private val cache: Path,
    private val gitExecutable: String = "git",
    environment: Map<String, String> = System.getenv(),
    private val checkCanceled: () -> Unit = {},
) {

    class Failure(message: String, cause: Throwable? = null) : Exception(message, cause)

    private val environment = environment.filterKeys { it !in REPOSITORY_VARIABLES }

    private val clone: Path = cache.resolve(CLONE_DIRECTORY)

    fun prepare(commit: String): Path {
        if (!COMMIT.matches(commit)) throw Failure("$commit is not a commit id")
        val (top, prefix) = repositoryLocation()
        if (!Files.exists(clone)) {
            Files.createDirectories(cache)
            git(cache, TRANSFER_TIMEOUT_MILLIS, "clone", "--shared", "--no-checkout", "--quiet", top, clone.toString())
        }
        verifyOwnClone()
        if (!hasCommit(commit)) {
            git(clone, TRANSFER_TIMEOUT_MILLIS, "fetch", "--no-tags", "--quiet", top, commit)
            if (!hasCommit(commit)) throw Failure("$commit is not in the repository")
        }
        git(clone, TRANSFER_TIMEOUT_MILLIS, "checkout", "--detach", "--force", "--quiet", commit)
        return clone.resolve(prefix)
    }

    private fun repositoryLocation(): Pair<String, String> {
        val lines = git(projectDir.toPath(), QUERY_TIMEOUT_MILLIS, "rev-parse", "--show-toplevel", "--show-prefix")
            .lines()
        val top = lines.firstOrNull().orEmpty().trim()
        if (top.isEmpty()) throw Failure("${projectDir.path} is not inside a git repository")
        return top to lines.getOrNull(1).orEmpty().trim()
    }

    private fun verifyOwnClone() {
        val gitDirectory = git(clone, QUERY_TIMEOUT_MILLIS, "rev-parse", "--absolute-git-dir").trim()
        val expected = runCatching { clone.resolve(".git").toRealPath() }.getOrNull()
        val actual = runCatching { Path.of(gitDirectory).toRealPath() }.getOrNull()
        if (expected == null || expected != actual) throw Failure("$clone is not a git checkout of its own")
    }

    private fun hasCommit(commit: String): Boolean = try {
        git(clone, QUERY_TIMEOUT_MILLIS, "cat-file", "-e", "$commit^{commit}")
        true
    } catch (failure: Failure) {
        false
    }

    private fun git(directory: Path, timeoutMillis: Long, vararg args: String): String {
        val executable = resolveExecutable(
            gitExecutable,
            environment["PATH"] ?: environment["Path"],
            environment["PATHEXT"],
        )
        val output = try {
            ChangeAnalyzer.capture(
                listOf(executable, "-c", "core.quotePath=false") + args,
                directory.toFile(),
                environment + (TERMINAL_PROMPT to "0"),
                timeoutMillis,
                checkCanceled,
            )
        } catch (error: TimeoutException) {
            fail("git ${args.first()} did not finish")
        } catch (error: IOException) {
            fail("git ${args.first()} could not run: ${error.message}", error)
        }
        if (output.exitCode != 0) fail("git ${args.first()} exited with ${output.exitCode}")
        return output.stdout
    }

    private fun fail(message: String, cause: Throwable? = null): Nothing = throw Failure(message, cause)

    private companion object {
        const val CLONE_DIRECTORY = "base"
        const val TERMINAL_PROMPT = "GIT_TERMINAL_PROMPT"
        val COMMIT = Regex("[0-9a-f]{40}|[0-9a-f]{64}")
        val QUERY_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(60)
        val TRANSFER_TIMEOUT_MILLIS = TimeUnit.MINUTES.toMillis(15)
        val REPOSITORY_VARIABLES = setOf(
            "GIT_DIR",
            "GIT_WORK_TREE",
            "GIT_INDEX_FILE",
            "GIT_COMMON_DIR",
            "GIT_OBJECT_DIRECTORY",
            "GIT_ALTERNATE_OBJECT_DIRECTORIES",
            "GIT_NAMESPACE",
            "GIT_PREFIX",
        )
    }
}
