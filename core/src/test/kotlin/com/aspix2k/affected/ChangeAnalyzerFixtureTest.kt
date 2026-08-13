package com.aspix2k.affected

import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChangeAnalyzerFixtureTest {

    private val allExtensions =
        setOf("kt", "kts", "java", "xml", "json", "pro", "rs", "toml", "go", "ts", "js", "py", "cs")

    private fun analyzer(repository: File, extensions: Set<String> = ChangeAnalyzer.DEFAULT_EXTENSIONS) =
        ChangeAnalyzer(repository, "", extensions)

    private fun onBranch(repository: File): File {
        FixtureRepository.git(repository, "checkout", "--quiet", "-b", "affected-test")
        return repository
    }

    @Test
    fun `an untouched clone of a real project has no changes`() {
        assumeTrue(FixtureRepository.available("gradle-okhttp"))
        val repository = FixtureRepository.checkout("gradle-okhttp")

        val changed = analyzer(repository).collectPaths()

        assertTrue(changed.isEmpty(), "a clean tree cannot have changes, got ${changed.size}")
    }

    @Test
    fun `a source edit in a real project is found`() {
        assumeTrue(FixtureRepository.available("gradle-okhttp"))
        val repository = onBranch(FixtureRepository.checkout("gradle-okhttp"))
        val source = FixtureRepository.sourcesIn(repository, "kt").first()

        source.appendText("\n// touched by the fixture test\n")

        val changed = analyzer(repository).collectPaths()

        assertTrue(source in changed, "changed file ${source.name} must be listed")
    }

    @Test
    fun `a new untracked file is found`() {
        assumeTrue(FixtureRepository.available("cargo-ripgrep"))
        val repository = onBranch(FixtureRepository.checkout("cargo-ripgrep"))
        val created = File(repository, "crates/core/flag/brand_new.rs").apply {
            parentFile.mkdirs()
            writeText("pub fn hello() {}\n")
        }

        val changed = analyzer(repository, setOf("rs", "toml")).collectPaths()

        assertTrue(created in changed, "an untracked file must count as a change")
    }

    @Test
    fun `a change inside a function body is not an API change`() {
        assumeTrue(FixtureRepository.available("gradle-detekt"))
        val repository = onBranch(FixtureRepository.checkout("gradle-detekt"))

        val source = FixtureRepository.sourcesIn(repository, "kt", limit = 400)
            .firstOrNull { file ->
                file.useLines { lines -> lines.any { it.trimStart().startsWith("private ") } }
            } ?: return

        val text = source.readText()
        source.writeText(text.replace("\n}", "\n    // body-only edit\n}"))

        val changes = analyzer(repository).collect()

        assertTrue(source in changes.files, "the file must count as changed")
        assertFalse(source in changes.apiTouched, "a comment in the body does not change the public API")
    }

    @Test
    fun `adding a public function is an API change`() {
        assumeTrue(FixtureRepository.available("gradle-detekt"))
        val repository = onBranch(FixtureRepository.checkout("gradle-detekt"))
        val source = FixtureRepository.sourcesIn(repository, "kt", limit = 200)
            .first { !it.path.contains("/test") }

        source.appendText("\nfun addedPublicApi(): Int = 42\n")

        val changes = analyzer(repository).collect()

        assertTrue(source in changes.apiTouched, "a new public function must count as an API change")
    }

    @Test
    fun `analysis completes for every available project`() {
        val names = FixtureRepository.names()
        assumeTrue(names.isNotEmpty())

        val failures = names.mapNotNull { name ->
            runCatching {
                val repository = File(FixtureRepository.root, name)
                ChangeAnalyzer(repository, "", allExtensions).collectPaths()
            }.exceptionOrNull()?.let { "$name: ${it.message}" }
        }

        assertTrue(failures.isEmpty(), "analyzer failed on: $failures")
    }
}
