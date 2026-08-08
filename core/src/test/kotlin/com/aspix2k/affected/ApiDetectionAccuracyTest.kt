package com.aspix2k.affected

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The consumer check rests entirely on this: a missed API change means a
 * consumer is never compiled and the breakage is found by CI instead. False
 * alarms only cost time, so every case here is written from the consumer's
 * point of view.
 */
class ApiDetectionAccuracyTest {

    private fun repository(vararg files: Pair<String, String>): File {
        val directory = createTempDirectory("api-accuracy").toFile()
        run(directory, "git", "init", "-q", "-b", "main")
        run(directory, "git", "config", "user.email", "t@e.com")
        run(directory, "git", "config", "user.name", "t")
        files.forEach { (path, content) ->
            File(directory, path).apply { parentFile.mkdirs() }.writeText(content)
        }
        run(directory, "git", "add", "-A")
        run(directory, "git", "commit", "-qm", "base")
        run(directory, "git", "checkout", "-q", "-b", "feature")
        return directory
    }

    private fun apiTouched(directory: File, path: String): Boolean {
        val analyzer = ChangeAnalyzer(directory, "main", setOf("kt", "java"))
        return File(directory, path) in analyzer.collect().apiTouched
    }

    private fun edit(directory: File, path: String, body: (String) -> String) {
        val file = File(directory, path)
        file.writeText(body(file.readText()))
    }

    private fun run(directory: File, vararg args: String) {
        ProcessBuilder(*args).directory(directory).redirectErrorStream(true).start().waitFor()
    }

    @Test
    fun `изменение сигнатуры java-метода считается изменением API`() {
        val directory = repository(
            "Service.java" to """
                public class Service {
                    public String find(int id) {
                        return "x";
                    }
                }
            """.trimIndent(),
        )

        edit(directory, "Service.java") { it.replace("public String find(int id)", "public String find(long id)") }

        assertTrue(apiTouched(directory, "Service.java"), "смена типа параметра ломает каждого потребителя")
    }

    @Test
    fun `новый публичный java-метод считается изменением API`() {
        val directory = repository(
            "Service.java" to """
                public class Service {
                    public void run() {}
                }
            """.trimIndent(),
        )

        edit(directory, "Service.java") {
            it.replace("public void run() {}", "public void run() {}\n    public int size() { return 0; }")
        }

        assertTrue(apiTouched(directory, "Service.java"))
    }

    @Test
    fun `изменение тела java-метода не считается изменением API`() {
        val directory = repository(
            "Service.java" to """
                public class Service {
                    public String find(int id) {
                        return "x";
                    }
                }
            """.trimIndent(),
        )

        edit(directory, "Service.java") { it.replace("""return "x";""", """return "y";""") }

        assertFalse(apiTouched(directory, "Service.java"), "тело метода потребителя не касается")
    }

    @Test
    fun `изменение параметра в многострочной сигнатуре считается изменением API`() {
        val directory = repository(
            "Repository.kt" to """
                class Repository {
                    fun load(
                        id: Int,
                        force: Boolean,
                    ): String = ""
                }
            """.trimIndent(),
        )

        edit(directory, "Repository.kt") { it.replace("force: Boolean,", "force: Boolean, retries: Int,") }

        assertTrue(
            apiTouched(directory, "Repository.kt"),
            "параметр на отдельной строке — такая же смена сигнатуры",
        )
    }

    @Test
    fun `удаление публичной функции считается изменением API`() {
        val directory = repository(
            "Api.kt" to """
                object Api {
                    fun first() {}
                    fun second() {}
                }
            """.trimIndent(),
        )

        edit(directory, "Api.kt") { it.replace("    fun second() {}\n", "") }

        assertTrue(apiTouched(directory, "Api.kt"), "удалённая функция ломает потребителя сильнее прочего")
    }

    @Test
    fun `сужение видимости считается изменением API`() {
        val directory = repository(
            "Api.kt" to """
                object Api {
                    fun visible() {}
                }
            """.trimIndent(),
        )

        edit(directory, "Api.kt") { it.replace("fun visible()", "internal fun visible()") }

        assertTrue(apiTouched(directory, "Api.kt"))
    }

    @Test
    fun `локальная переменная внутри функции не считается изменением API`() {
        val directory = repository(
            "Worker.kt" to """
                class Worker {
                    fun work() {
                        val step = 1
                        println(step)
                    }
                }
            """.trimIndent(),
        )

        edit(directory, "Worker.kt") { it.replace("val step = 1", "val step = 2") }

        assertFalse(apiTouched(directory, "Worker.kt"), "локальная переменная не видна снаружи")
    }

    @Test
    fun `приватный член не считается изменением API`() {
        val directory = repository(
            "Worker.kt" to """
                class Worker {
                    private val cache = mutableMapOf<String, String>()
                }
            """.trimIndent(),
        )

        edit(directory, "Worker.kt") { it.replace("mutableMapOf<String, String>()", "HashMap<String, String>()") }

        assertFalse(apiTouched(directory, "Worker.kt"))
    }

    @Test
    fun `изменения в тестовых исходниках не считаются изменением API`() {
        val directory = repository(
            "src/test/kotlin/WorkerTest.kt" to """
                class WorkerTest {
                    fun testSomething() {}
                }
            """.trimIndent(),
        )

        edit(directory, "src/test/kotlin/WorkerTest.kt") {
            it.replace("fun testSomething() {}", "fun testSomethingElse() {}")
        }

        assertFalse(
            apiTouched(directory, "src/test/kotlin/WorkerTest.kt"),
            "у теста нет потребителей",
        )
    }

    @Test
    fun `комментарий над публичной функцией не считается изменением API`() {
        val directory = repository(
            "Api.kt" to """
                object Api {
                    fun documented() {}
                }
            """.trimIndent(),
        )

        edit(directory, "Api.kt") { it.replace("    fun documented()", "    // explains why\n    fun documented()") }

        assertFalse(apiTouched(directory, "Api.kt"), "комментарий ничего не меняет для потребителя")
    }
}
