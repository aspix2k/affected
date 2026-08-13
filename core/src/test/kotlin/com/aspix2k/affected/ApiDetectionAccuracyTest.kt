package com.aspix2k.affected

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
    fun `changing a Java method signature is an API change`() {
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

        assertTrue(apiTouched(directory, "Service.java"), "changing a parameter type breaks every consumer")
    }

    @Test
    fun `a new public Java method is an API change`() {
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
    fun `changing a Java method body is not an API change`() {
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

        assertFalse(apiTouched(directory, "Service.java"), "a method body does not affect consumers")
    }

    @Test
    fun `changing a parameter in a multiline signature is an API change`() {
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
            "a parameter on its own line is still a signature change",
        )
    }

    @Test
    fun `removing a public function is an API change`() {
        val directory = repository(
            "Api.kt" to """
                object Api {
                    fun first() {}
                    fun second() {}
                }
            """.trimIndent(),
        )

        edit(directory, "Api.kt") { it.replace("    fun second() {}\n", "") }

        assertTrue(apiTouched(directory, "Api.kt"), "a removed function breaks consumers")
    }

    @Test
    fun `reducing visibility is an API change`() {
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
    fun `a local variable inside a function is not an API change`() {
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

        assertFalse(apiTouched(directory, "Worker.kt"), "a local variable is not externally visible")
    }

    @Test
    fun `a private member is not an API change`() {
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
    fun `changes in test sources are not API changes`() {
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
            "tests have no consumers",
        )
    }

    @Test
    fun `a comment above a public function is not an API change`() {
        val directory = repository(
            "Api.kt" to """
                object Api {
                    fun documented() {}
                }
            """.trimIndent(),
        )

        edit(directory, "Api.kt") { it.replace("    fun documented()", "    // explains why\n    fun documented()") }

        assertFalse(apiTouched(directory, "Api.kt"), "a comment changes nothing for consumers")
    }
}
