package com.aspix2k.affected

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChangeAnalyzerTest {

    private fun repo(block: (File) -> Unit) {
        val dir = createTempDirectory("affected-test").toFile()
        run(dir, "git", "init", "-q", "-b", "main")
        run(dir, "git", "config", "user.email", "test@example.com")
        run(dir, "git", "config", "user.name", "test")
        File(dir, "settings.gradle.kts").writeText("rootProject.name = \"probe\"")
        File(dir, "lib/src/main/kotlin").mkdirs()
        File(dir, "lib/build.gradle.kts").writeText("")
        File(dir, "lib/src/main/kotlin/Sample.kt").writeText(
            """
            package probe

            class Sample {
                fun visible(): Int {
                    val internalValue = 1
                    return internalValue
                }
            }
            """.trimIndent()
        )
        run(dir, "git", "add", "-A")
        run(dir, "git", "commit", "-qm", "init")
        block(dir)
    }

    private fun run(dir: File, vararg args: String) {
        ProcessBuilder(*args).directory(dir).redirectErrorStream(true).start().waitFor()
    }

    private fun analyze(dir: File) = ChangeAnalyzer(dir, "main").collect()

    @Test
    fun `без изменений список пуст`() = repo { dir ->
        assertTrue(analyze(dir).files.isEmpty(), "чистое дерево не должно давать изменений")
    }

    @Test
    fun `правка внутри тела не трогает публичный API`() = repo { dir ->
        val file = File(dir, "lib/src/main/kotlin/Sample.kt")
        file.writeText(file.readText().replace("val internalValue = 1", "val internalValue = 2"))

        val changes = analyze(dir)
        assertEquals(1, changes.files.size, "файл должен попасть в изменённые")
        assertTrue(changes.apiTouched.isEmpty(), "правка тела не меняет API")
    }

    @Test
    fun `новая публичная функция меняет API`() = repo { dir ->
        val file = File(dir, "lib/src/main/kotlin/Sample.kt")
        file.appendText("\nfun added(): Int = 5\n")

        assertEquals(1, analyze(dir).apiTouched.size, "новое публичное объявление меняет API")
    }

    @Test
    fun `приватная функция API не меняет`() = repo { dir ->
        val file = File(dir, "lib/src/main/kotlin/Sample.kt")
        file.appendText("\nprivate fun hidden(): Int = 5\n")

        assertTrue(analyze(dir).apiTouched.isEmpty(), "приватное объявление наружу не видно")
    }

    @Test
    fun `изменение сигнатуры меняет API`() = repo { dir ->
        val file = File(dir, "lib/src/main/kotlin/Sample.kt")
        file.writeText(file.readText().replace("fun visible(): Int", "fun visible(flag: Boolean): Int"))

        assertEquals(1, analyze(dir).apiTouched.size, "смена сигнатуры ломает потребителей")
    }

    @Test
    fun `тестовые исходники API не меняют`() = repo { dir ->
        File(dir, "lib/src/test/kotlin").mkdirs()
        File(dir, "lib/src/test/kotlin/SampleTest.kt").writeText("class SampleTest { fun check() {} }")

        val changes = analyze(dir)
        assertTrue(changes.files.isNotEmpty(), "тестовый файл всё равно попадает в изменения")
        assertTrue(changes.apiTouched.isEmpty(), "тесты не входят в артефакт модуля")
    }

    @Test
    fun `xml ресурс API не меняет`() = repo { dir ->
        File(dir, "lib/src/main/res/values").mkdirs()
        File(dir, "lib/src/main/res/values/colors.xml").writeText("<resources><color name=\"c\">#fff</color></resources>")

        val changes = analyze(dir)
        assertTrue(changes.files.isNotEmpty(), "ресурс попадает в изменения")
        assertTrue(changes.apiTouched.isEmpty(), "цвет иконки потребителей не ломает")
    }

    @Test
    fun `новый файл с публичным объявлением меняет API`() = repo { dir ->
        File(dir, "lib/src/main/kotlin/Added.kt").writeText("package probe\n\nclass Added\n")

        assertEquals(1, analyze(dir).apiTouched.size, "новый публичный класс расширяет API")
    }

    @Test
    fun `новый файл только с приватным содержимым API не меняет`() = repo { dir ->
        File(dir, "lib/src/main/kotlin/Hidden.kt").writeText("package probe\n\nprivate fun x() = 1\n")

        assertTrue(analyze(dir).apiTouched.isEmpty(), "приватное содержимое наружу не видно")
    }

    @Test
    fun `посторонние файлы игнорируются`() = repo { dir ->
        File(dir, "README.md").writeText("# doc")
        File(dir, "notes.txt").writeText("hello")

        assertTrue(analyze(dir).files.isEmpty(), "документация не влияет на сборку")
    }

    @Test
    fun `удалённые файлы не попадают в список`() = repo { dir ->
        File(dir, "lib/src/main/kotlin/Sample.kt").delete()

        assertTrue(analyze(dir).files.none { it.name == "Sample.kt" }, "удалённого файла на диске нет")
    }

    @Test
    fun `коммит в ветке остаётся видимым`() = repo { dir ->
        run(dir, "git", "checkout", "-qb", "feature")
        File(dir, "lib/src/main/kotlin/Sample.kt").appendText("\nfun afterCommit(): Int = 7\n")
        run(dir, "git", "add", "-A")
        run(dir, "git", "commit", "-qm", "work")

        val changes = analyze(dir)
        assertTrue(changes.files.isNotEmpty(), "закоммиченная работа в ветке всё ещё требует тестов")
        assertEquals(1, changes.apiTouched.size, "и её API-изменение тоже видно")
    }

    @Test
    fun `отсутствие базовой ветки не роняет анализ`() = repo { dir ->
        File(dir, "lib/src/main/kotlin/Sample.kt").appendText("\nfun another(): Int = 1\n")

        val changes = ChangeAnalyzer(dir, "no-such-branch").collect()
        assertTrue(changes.files.isNotEmpty(), "рабочее дерево читается и без базовой ветки")
    }

    @Test
    fun `androidTest исходники API не меняют`() = repo { dir ->
        File(dir, "lib/src/androidTest/kotlin").mkdirs()
        File(dir, "lib/src/androidTest/kotlin/UiTest.kt").writeText("class UiTest { fun check() {} }")

        val changes = analyze(dir)
        assertTrue(changes.files.isNotEmpty())
        assertTrue(changes.apiTouched.isEmpty(), "инструментальные тесты в артефакт модуля не входят")
    }

    @Test
    fun `java файл участвует в анализе API`() = repo { dir ->
        File(dir, "lib/src/main/java/probe").mkdirs()
        File(dir, "lib/src/main/java/probe/Legacy.java").writeText(
            "package probe;\n\npublic class Legacy {\n    public int value() { return 1; }\n}\n"
        )

        assertEquals(1, analyze(dir).apiTouched.size, "публичный java-класс тоже расширяет API")
    }

    @Test
    fun `правка тела java метода API не меняет`() = repo { dir ->
        val file = File(dir, "lib/src/main/java/probe/Legacy.java")
        file.parentFile.mkdirs()
        file.writeText("package probe;\n\npublic class Legacy {\n    public int value() { return 1; }\n}\n")
        run(dir, "git", "add", "-A")
        run(dir, "git", "commit", "-qm", "legacy")

        file.writeText("package probe;\n\npublic class Legacy {\n    public int value() { return 2; }\n}\n")

        assertTrue(analyze(dir).apiTouched.isEmpty(), "изменилось только тело метода")
    }

    @Test
    fun `kts файл попадает в изменения но не в анализ API`() = repo { dir ->
        File(dir, "lib/build.gradle.kts").writeText("// changed\n")

        val changes = analyze(dir)
        assertTrue(changes.files.any { it.name == "build.gradle.kts" })
        assertTrue(changes.apiTouched.isEmpty(), "скрипт сборки не является публичным API модуля")
    }

    @Test
    fun `быстрый путь возвращает те же файлы что и полный`() = repo { dir ->
        File(dir, "lib/src/main/kotlin/Sample.kt").appendText("\nfun quick(): Int = 1\n")

        val quick = ChangeAnalyzer(dir, "main").collectPaths()
        val full = analyze(dir).files
        assertEquals(full.map { it.path }.sorted(), quick.map { it.path }.sorted())
    }

    @Test
    fun `быстрый путь не падает вне репозитория`() {
        val dir = createTempDirectory("affected-quick").toFile()
        assertTrue(ChangeAnalyzer(dir, "main").collectPaths().isEmpty())
    }

    @Test
    fun `быстрый путь отсекает посторонние расширения`() = repo { dir ->
        File(dir, "notes.txt").writeText("x")
        File(dir, "lib/src/main/kotlin/Sample.kt").appendText("\nfun q(): Int = 1\n")

        val paths = ChangeAnalyzer(dir, "main").collectPaths()
        assertTrue(paths.none { it.name == "notes.txt" })
        assertTrue(paths.any { it.name == "Sample.kt" })
    }

    @Test
    fun `база определяется автоматически если настроенной ветки нет`() = repo { dir ->
        run(dir, "git", "checkout", "-qb", "feature")
        File(dir, "lib/src/main/kotlin/Sample.kt").appendText("\nfun auto(): Int = 1\n")
        run(dir, "git", "add", "-A")
        run(dir, "git", "commit", "-qm", "work")

        val changes = ChangeAnalyzer(dir, "no-such-branch").collect()
        assertTrue(changes.files.isNotEmpty(), "должен найти main как запасную базу")
        assertEquals(1, changes.apiTouched.size)
    }

    @Test
    fun `настроенная ветка имеет приоритет над запасными`() = repo { dir ->
        run(dir, "git", "checkout", "-qb", "release")
        File(dir, "lib/src/main/kotlin/Sample.kt").appendText("\nfun onRelease(): Int = 1\n")
        run(dir, "git", "add", "-A")
        run(dir, "git", "commit", "-qm", "release work")
        run(dir, "git", "checkout", "-qb", "feature")
        File(dir, "lib/src/main/kotlin/Other.kt").writeText("package probe\n\nclass Other\n")
        run(dir, "git", "add", "-A")
        run(dir, "git", "commit", "-qm", "feature work")

        val fromRelease = ChangeAnalyzer(dir, "release").collect().files.map { it.name }
        assertTrue(fromRelease.contains("Other.kt"), "относительно release видна только работа ветки")
        assertFalse(fromRelease.contains("Sample.kt"), "то, что уже в release, повторно не тестируем")
    }

    @Test
    fun `путь к тестам распознаётся независимо от разделителя ОС`() = repo { dir ->
        File(dir, "lib/src/test/kotlin").mkdirs()
        File(dir, "lib/src/test/kotlin/PlatformTest.kt").writeText("class PlatformTest { fun check() {} }")

        val changes = analyze(dir)
        val relative = changes.files.single().relativeTo(dir).invariantSeparatorsPath
        assertTrue(relative.contains("/src/test"), "разделители приводятся к прямым слешам")
        assertTrue(changes.apiTouched.isEmpty(), "тестовый исходник не меняет API на любой ОС")
    }

    @Test
    fun `не git каталог не роняет анализ`() {
        val dir = createTempDirectory("affected-nogit").toFile()
        val changes = ChangeAnalyzer(dir, "main").collect()
        assertTrue(changes.files.isEmpty(), "вне репозитория анализатор просто молчит")
        assertFalse(dir.resolve(".git").exists())
    }
}
