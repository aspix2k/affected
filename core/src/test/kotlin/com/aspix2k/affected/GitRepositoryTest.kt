package com.aspix2k.affected

import java.io.File
import kotlin.io.path.createTempDirectory

internal abstract class GitRepositoryTest {

    protected fun repo(block: (File) -> Unit) {
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

    protected fun commit(dir: File) {
        run(dir, "git", "add", "-A")
        run(dir, "git", "commit", "-qm", "next")
    }

    protected fun run(dir: File, vararg args: String) {
        ProcessBuilder(*args).directory(dir).redirectErrorStream(true).start().waitFor()
    }

    protected fun analyze(dir: File) = ChangeAnalyzer(dir, "main").collect()

    protected fun analyze(dir: File, extensions: Set<String>) = ChangeAnalyzer(dir, "main", extensions).collect()
}
