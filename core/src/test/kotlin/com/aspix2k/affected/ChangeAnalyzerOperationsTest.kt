package com.aspix2k.affected

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The analyser was one call doing everything. Local edits now come from the IDE,
 * so the git-specific parts have to stand on their own.
 */
class ChangeAnalyzerOperationsTest {

    private fun repository(): File {
        val directory = createTempDirectory("analyzer-ops").toFile()
        run(directory, "git", "init", "-q", "-b", "main")
        run(directory, "git", "config", "user.email", "t@e.com")
        run(directory, "git", "config", "user.name", "t")
        File(directory, "Base.kt").writeText("fun base() {}\n")
        run(directory, "git", "add", "-A")
        run(directory, "git", "commit", "-qm", "base")
        return directory
    }

    private fun analyzer(directory: File) = ChangeAnalyzer(directory, "main", setOf("kt"))

    @Test
    fun `каталог без git не считается пригодным`() {
        val plain = createTempDirectory("no-vcs").toFile()

        assertFalse(analyzer(plain).isUsable(), "без git сравнивать с веткой не с чем")
    }

    @Test
    fun `репозиторий с git пригоден`() {
        assertTrue(analyzer(repository()).isUsable())
    }

    @Test
    fun `сравнение с базой видит только коммиты ветки`() {
        val directory = repository()
        run(directory, "git", "checkout", "-q", "-b", "feature")
        File(directory, "Committed.kt").writeText("fun committed() {}\n")
        run(directory, "git", "add", "-A")
        run(directory, "git", "commit", "-qm", "committed")
        File(directory, "OnlyLocal.kt").writeText("fun local() {}\n")

        val againstBase = analyzer(directory).againstBase().map { it.name }

        assertEquals(listOf("Committed.kt"), againstBase, "незакоммиченное к базе не относится")
    }

    @Test
    fun `публичное объявление среди изменённых файлов находится`() {
        val directory = repository()
        run(directory, "git", "checkout", "-q", "-b", "feature")
        val api = File(directory, "Api.kt").apply { writeText("fun added(): Int = 1\n") }
        val body = File(directory, "Base.kt").apply { appendText("// only a comment\n") }

        val touched = analyzer(directory).apiTouchedAmong(listOf(api, body))

        assertTrue(api in touched, "новая функция — изменение API")
        assertFalse(body in touched, "комментарий — нет")
    }

    @Test
    fun `сравнение с базой ничего не даёт когда базы нет`() {
        val directory = createTempDirectory("no-base").toFile()
        run(directory, "git", "init", "-q", "-b", "solo")
        run(directory, "git", "config", "user.email", "t@e.com")
        run(directory, "git", "config", "user.name", "t")
        File(directory, "A.kt").writeText("fun a() {}\n")
        run(directory, "git", "add", "-A")
        run(directory, "git", "commit", "-qm", "a")

        val analyzer = ChangeAnalyzer(directory, "nonexistent", setOf("kt"))

        assertEquals(emptyList(), analyzer.againstBase(), "нет ни настроенной, ни запасной ветки — нет и сравнения")
    }

    private fun run(directory: File, vararg args: String) {
        ProcessBuilder(*args).directory(directory).redirectErrorStream(true).start().waitFor()
    }
}
