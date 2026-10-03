package com.aspix2k.affected

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BaseCheckoutTest {

    private class Fixture(val repository: File, val base: String, val head: String)

    private fun git(directory: File, vararg args: String): String {
        val process = ProcessBuilder(listOf("git") + args).directory(directory).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0) { "git ${args.toList()}: $output" }
        return output.trim()
    }

    private fun fixture(): Fixture {
        val repository = createTempDirectory("base-checkout-repo").toFile()
        git(repository, "init", "-q", "-b", "main")
        git(repository, "config", "user.email", "t@e.com")
        git(repository, "config", "user.name", "t")
        File(repository, "app").mkdirs()
        File(repository, "app/Main.kt").writeText("base\n")
        git(repository, "add", "-A")
        git(repository, "commit", "-qm", "base")
        val base = git(repository, "rev-parse", "HEAD")
        git(repository, "checkout", "-q", "-b", "feature")
        File(repository, "app/Main.kt").writeText("changed\n")
        File(repository, "app/Added.kt").writeText("added\n")
        git(repository, "add", "-A")
        git(repository, "commit", "-qm", "change")
        return Fixture(repository, base, git(repository, "rev-parse", "HEAD"))
    }

    private fun cache(): Path = createTempDirectory("base-checkout-cache")

    private fun userState(repository: File): List<String> = listOf(
        git(repository, "rev-parse", "HEAD"),
        git(repository, "branch", "--show-current"),
        git(repository, "status", "--short"),
        git(repository, "worktree", "list"),
        git(repository, "stash", "list"),
    )

    @Test
    fun `the base commit is checked out apart from the repository`() {
        val fixture = fixture()
        File(fixture.repository, "app/Main.kt").appendText("uncommitted\n")
        File(fixture.repository, "Untracked.kt").writeText("untracked\n")
        val before = userState(fixture.repository)
        val cache = cache()

        val directory = BaseCheckout(fixture.repository, cache).prepare(fixture.base)

        assertEquals(cache.resolve("base"), directory)
        assertEquals("base\n", File(directory.toFile(), "app/Main.kt").readText())
        assertFalse(File(directory.toFile(), "app/Added.kt").exists())
        assertFalse(File(directory.toFile(), "Untracked.kt").exists())
        assertEquals(before, userState(fixture.repository))
        assertEquals("changed\nuncommitted\n", File(fixture.repository, "app/Main.kt").readText())
        assertEquals(fixture.base, git(directory.toFile(), "rev-parse", "HEAD"))
        assertEquals("", git(directory.toFile(), "branch", "--show-current"))
    }

    @Test
    fun `objects are shared with the repository instead of copied`() {
        val fixture = fixture()

        val directory = BaseCheckout(fixture.repository, cache()).prepare(fixture.base)

        val alternates = File(directory.toFile(), ".git/objects/info/alternates").readText().trim()
        assertEquals(File(fixture.repository, ".git/objects").canonicalPath, File(alternates).canonicalPath)
    }

    @Test
    fun `a later run reuses the checkout and moves it to another commit`() {
        val fixture = fixture()
        val cache = cache()
        val first = BaseCheckout(fixture.repository, cache).prepare(fixture.base)
        val marker = File(first.toFile(), ".git/reuse-marker").apply { writeText("kept") }
        File(first.toFile(), "app/Main.kt").writeText("edited inside the checkout\n")

        val second = BaseCheckout(fixture.repository, cache).prepare(fixture.head)

        assertEquals(first, second)
        assertTrue(marker.isFile, "the same clone is reused")
        assertEquals("changed\n", File(second.toFile(), "app/Main.kt").readText())
        assertTrue(File(second.toFile(), "app/Added.kt").isFile)
        assertEquals(fixture.head, git(second.toFile(), "rev-parse", "HEAD"))
    }

    @Test
    fun `a commit missing from the checkout is fetched from the repository again`() {
        val fixture = fixture()
        val cache = cache()
        val directory = BaseCheckout(fixture.repository, cache).prepare(fixture.base)
        val gitDirectory = File(directory.toFile(), ".git")
        File(gitDirectory, "objects/info/alternates").delete()
        File(gitDirectory, "refs/heads").deleteRecursively()
        File(gitDirectory, "packed-refs").delete()
        File(gitDirectory, "refs/heads").mkdirs()

        val again = BaseCheckout(fixture.repository, cache).prepare(fixture.base)

        assertEquals(directory, again)
        assertEquals("base\n", File(again.toFile(), "app/Main.kt").readText())
        assertEquals(fixture.base, git(again.toFile(), "rev-parse", "HEAD"))
    }

    @Test
    fun `a project below the repository root maps to the same folder of the checkout`() {
        val fixture = fixture()

        val directory = BaseCheckout(File(fixture.repository, "app"), cache()).prepare(fixture.base)

        assertEquals("app", directory.fileName.toString())
        assertEquals("base\n", File(directory.toFile(), "Main.kt").readText())
    }

    @Test
    fun `repository variables of the environment never redirect the checkout to the repository`() {
        val fixture = fixture()
        val before = userState(fixture.repository)
        val environment = System.getenv() + mapOf(
            "GIT_DIR" to File(fixture.repository, ".git").path,
            "GIT_WORK_TREE" to fixture.repository.path,
            "GIT_INDEX_FILE" to File(fixture.repository, ".git/index").path,
        )

        val directory = BaseCheckout(fixture.repository, cache(), environment = environment).prepare(fixture.base)

        assertEquals(before, userState(fixture.repository))
        assertEquals("changed\n", File(fixture.repository, "app/Main.kt").readText())
        assertEquals("base\n", File(directory.toFile(), "app/Main.kt").readText())
    }

    @Test
    fun `something that is not a commit id or not in the repository is refused`() {
        val fixture = fixture()
        val checkout = BaseCheckout(fixture.repository, cache())

        assertFailsWith<BaseCheckout.Failure> { checkout.prepare("--upload-pack=touch") }
        assertFailsWith<BaseCheckout.Failure> { checkout.prepare("main") }
        assertFailsWith<BaseCheckout.Failure> { checkout.prepare("0".repeat(40)) }
    }

    @Test
    fun `a directory outside git or a foreign folder in the cache is refused`() {
        val plain = createTempDirectory("base-checkout-plain").toFile()
        assertFailsWith<BaseCheckout.Failure> { BaseCheckout(plain, cache()).prepare("a".repeat(40)) }

        val fixture = fixture()
        val cache = cache()
        Files.createDirectories(cache.resolve("base"))
        assertFailsWith<BaseCheckout.Failure> { BaseCheckout(fixture.repository, cache).prepare(fixture.base) }
        assertEquals(0, cache.resolve("base").toFile().list()?.size)
    }
}
