package com.aspix2k.affected

import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Runs the analyser against real projects instead of fixtures written for it.
 * Skips itself when `scripts/fixtures.sh` has not been run, so CI stays offline.
 */
class ChangeAnalyzerFixtureTest {

    private val ALL_EXTENSIONS =
        setOf("kt", "kts", "java", "xml", "json", "pro", "rs", "toml", "go", "ts", "js", "py", "cs")

    private fun analyzer(repository: File, extensions: Set<String> = ChangeAnalyzer.DEFAULT_EXTENSIONS) =
        ChangeAnalyzer(repository, "", extensions)

    private fun onBranch(repository: File): File {
        FixtureRepository.git(repository, "checkout", "--quiet", "-b", "affected-test")
        return repository
    }

    @Test
    fun `нетронутый клон реального проекта не даёт изменений`() {
        assumeTrue(FixtureRepository.available("gradle-okhttp"))
        val repository = FixtureRepository.checkout("gradle-okhttp")

        val changed = analyzer(repository).collectPaths()

        assertTrue(changed.isEmpty(), "чистое дерево не может давать изменения, получили ${changed.size}")
    }

    @Test
    fun `правка исходника в реальном проекте находится`() {
        assumeTrue(FixtureRepository.available("gradle-okhttp"))
        val repository = onBranch(FixtureRepository.checkout("gradle-okhttp"))
        val source = FixtureRepository.sourcesIn(repository, "kt").first()

        source.appendText("\n// touched by the fixture test\n")

        val changed = analyzer(repository).collectPaths()

        assertTrue(source in changed, "изменённый файл ${source.name} обязан попасть в список")
    }

    @Test
    fun `новый непроиндексированный файл находится`() {
        assumeTrue(FixtureRepository.available("cargo-ripgrep"))
        val repository = onBranch(FixtureRepository.checkout("cargo-ripgrep"))
        val created = File(repository, "crates/core/flag/brand_new.rs").apply {
            parentFile.mkdirs()
            writeText("pub fn hello() {}\n")
        }

        val changed = analyzer(repository, setOf("rs", "toml")).collectPaths()

        assertTrue(created in changed, "untracked-файл обязан считаться изменением")
    }

    @Test
    fun `изменение внутри тела функции не считается изменением API`() {
        assumeTrue(FixtureRepository.available("gradle-detekt"))
        val repository = onBranch(FixtureRepository.checkout("gradle-detekt"))

        val source = FixtureRepository.sourcesIn(repository, "kt", limit = 400)
            .firstOrNull { file ->
                file.useLines { lines -> lines.any { it.trimStart().startsWith("private ") } }
            } ?: return

        val text = source.readText()
        source.writeText(text.replace("\n}", "\n    // body-only edit\n}"))

        val changes = analyzer(repository).collect()

        assertTrue(source in changes.files, "файл должен считаться изменённым")
        assertFalse(source in changes.apiTouched, "комментарий в теле не меняет публичный API")
    }

    @Test
    fun `добавление публичной функции считается изменением API`() {
        assumeTrue(FixtureRepository.available("gradle-detekt"))
        val repository = onBranch(FixtureRepository.checkout("gradle-detekt"))
        val source = FixtureRepository.sourcesIn(repository, "kt", limit = 200)
            .first { !it.path.contains("/test") }

        source.appendText("\nfun addedPublicApi(): Int = 42\n")

        val changes = analyzer(repository).collect()

        assertTrue(source in changes.apiTouched, "новая публичная функция обязана считаться изменением API")
    }

    @Test
    fun `анализ проходит по каждому доступному проекту без исключений`() {
        val names = FixtureRepository.names()
        assumeTrue(names.isNotEmpty())

        val failures = names.mapNotNull { name ->
            runCatching {
                val repository = File(FixtureRepository.root, name)
                ChangeAnalyzer(repository, "", ALL_EXTENSIONS).collectPaths()
            }.exceptionOrNull()?.let { "$name: ${it.message}" }
        }

        assertTrue(failures.isEmpty(), "анализатор упал на: $failures")
    }
}
