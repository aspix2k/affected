package com.aspix2k.affected

import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChangeAnalyzerFixtureTest {

    private val allExtensions =
        setOf("kt", "kts", "java", "xml", "json", "pro", "rs", "toml", "go", "ts", "js", "py", "cs")

    private val cmakeExtensions = setOf("c", "h")

    private fun analyzer(repository: File, extensions: Set<String> = ChangeAnalyzer.DEFAULT_EXTENSIONS) =
        ChangeAnalyzer(repository, "", extensions)

    private fun requiredAnalyzer(repository: File) =
        ChangeAnalyzer(repository, "main", cmakeExtensions)

    private fun onBranch(repository: File): File {
        FixtureRepository.git(repository, "checkout", "--quiet", "-b", "affected-test")
        return repository
    }

    private fun requiredCliFixture(name: String): File {
        val source = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .map { File(it, "conformance/cli-fixtures/$name") }
            .firstOrNull(File::isDirectory)
            ?: File(System.getProperty("user.dir"), "conformance/cli-fixtures/$name")
        assertTrue(source.isDirectory, "Missing required CLI fixture: $source")
        val target = createTempDirectory("affected-required-$name").toFile()
        assertTrue(source.copyRecursively(target, overwrite = true), "Could not copy $source")
        FixtureRepository.git(target, "init", "-q", "-b", "main")
        FixtureRepository.git(target, "config", "user.email", "fixture@example.com")
        FixtureRepository.git(target, "config", "user.name", "fixture")
        FixtureRepository.git(target, "add", "-A")
        FixtureRepository.git(target, "commit", "-qm", "init")
        return target
    }

    @Test
    fun `an untouched in-repo cmake fixture has no changes`() {
        val repository = requiredCliFixture("cmake")

        val changed = requiredAnalyzer(repository).collectPaths()

        assertTrue(changed.isEmpty(), "a clean in-repo fixture cannot have changes, got ${changed.size}")
    }

    @Test
    fun `a source edit in the in-repo cmake fixture is found`() {
        val repository = onBranch(requiredCliFixture("cmake"))
        val source = File(repository, "alpha.c")
        assertTrue(source.isFile, "cmake fixture must contain alpha.c")
        source.appendText("\n/* touched by the required fixture test */\n")

        val changed = requiredAnalyzer(repository).collectPaths()

        assertTrue(source in changed, "changed file ${source.name} must be listed")
    }

    @Test
    fun `a new untracked file in the in-repo cmake fixture is found`() {
        val repository = onBranch(requiredCliFixture("cmake"))
        val created = File(repository, "brand_new.c").apply { writeText("int brand_new(void) { return 1; }\n") }

        val changed = requiredAnalyzer(repository).collectPaths()

        assertTrue(created in changed, "an untracked C file must count as a change")
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
