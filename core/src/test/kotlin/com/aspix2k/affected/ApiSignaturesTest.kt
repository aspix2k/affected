package com.aspix2k.affected

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class ApiSignaturesTest : GitRepositoryTest() {

    @Test
    fun `an edit inside a body does not affect the public API`() = repo { dir ->
        val file = File(dir, "lib/src/main/kotlin/Sample.kt")
        file.writeText(file.readText().replace("val internalValue = 1", "val internalValue = 2"))

        val changes = analyze(dir)
        assertEquals(1, changes.files.size, "the file must be listed as changed")
        assertTrue(changes.apiTouched.isEmpty(), "a body edit does not change the API")
    }

    @Test
    fun `a new public function changes the API`() = repo { dir ->
        val file = File(dir, "lib/src/main/kotlin/Sample.kt")
        file.appendText("\nfun added(): Int = 5\n")

        assertEquals(1, analyze(dir).apiTouched.size, "a new public declaration changes the API")
    }

    @Test
    fun `a private function does not change the API`() = repo { dir ->
        val file = File(dir, "lib/src/main/kotlin/Sample.kt")
        file.appendText("\nprivate fun hidden(): Int = 5\n")

        assertTrue(analyze(dir).apiTouched.isEmpty(), "a private declaration is not externally visible")
    }

    @Test
    fun `a signature change changes the API`() = repo { dir ->
        val file = File(dir, "lib/src/main/kotlin/Sample.kt")
        file.writeText(file.readText().replace("fun visible(): Int", "fun visible(flag: Boolean): Int"))

        assertEquals(1, analyze(dir).apiTouched.size, "a signature change breaks consumers")
    }

    @Test
    fun `a constructor with private properties is still public API`() = repo { dir ->
        val file = File(dir, "lib/src/main/kotlin/Service.kt")
        file.writeText("package probe\n\nclass Service(private val name: String)\n")
        commit(dir)
        file.writeText("package probe\n\nclass Service(private val name: String, private val retries: Int)\n")

        assertEquals(1, analyze(dir).apiTouched.size, "callers of the constructor break")
    }

    @Test
    fun `a private constructor property on its own line changes the API`() = repo { dir ->
        val file = File(dir, "lib/src/main/kotlin/Service.kt")
        file.writeText("package probe\n\nclass Service(\n    private val name: String,\n)\n")
        commit(dir)
        file.writeText(
            "package probe\n\nclass Service(\n    private val name: String,\n    private val retries: Int,\n)\n",
        )

        assertEquals(1, analyze(dir).apiTouched.size, "callers of the constructor break")
    }

    @Test
    fun `a name that merely contains private is not a private declaration`() = repo { dir ->
        val file = File(dir, "lib/src/main/kotlin/Sample.kt")
        file.appendText("\nfun sign(privateKey: String): String = privateKey\n")

        assertEquals(1, analyze(dir).apiTouched.size)
    }

    @Test
    fun `a protected member changes the API`() = repo { dir ->
        val file = File(dir, "lib/src/main/kotlin/Sample.kt")
        val hook = "    protected fun hook(): Int = 1\n\n"
        file.writeText(file.readText().replace("    fun visible", "$hook    fun visible"))

        assertEquals(1, analyze(dir).apiTouched.size, "subclasses in other modules see protected members")
    }

    @Test
    fun `a parameter after a default value changes the API`() = repo { dir ->
        val file = File(dir, "lib/src/main/kotlin/Extra.kt")
        file.writeText("package probe\n\nfun extra(count: Int = 1, name: String): Int = count\n")
        commit(dir)
        file.writeText("package probe\n\nfun extra(count: Int = 1, name: Long): Int = count\n")

        assertEquals(1, analyze(dir).apiTouched.size)
    }

    @Test
    fun `a constant value changes the API`() = repo { dir ->
        val file = File(dir, "lib/src/main/kotlin/Extra.kt")
        file.writeText("package probe\n\nconst val LIMIT = 1\n")
        commit(dir)
        file.writeText("package probe\n\nconst val LIMIT = 2\n")

        assertEquals(1, analyze(dir).apiTouched.size, "consumers inline the old value")
    }

    @Test
    fun `a member of a nested class changes the API`() = repo { dir ->
        val file = File(dir, "lib/src/main/kotlin/Extra.kt")
        file.writeText("package probe\n\nclass Outer {\n    class Inner {\n        fun first(): Int = 1\n    }\n}\n")
        commit(dir)
        file.writeText(
            "package probe\n\nclass Outer {\n    class Inner {\n        fun first(flag: Boolean): Int = 1\n    }\n}\n",
        )

        assertEquals(1, analyze(dir).apiTouched.size)
    }

    @Test
    fun `a new file with a public declaration changes the API`() = repo { dir ->
        File(dir, "lib/src/main/kotlin/Added.kt").writeText("package probe\n\nclass Added\n")

        assertEquals(1, analyze(dir).apiTouched.size, "a new public class extends the API")
    }

    @Test
    fun `a new file with only private content does not change the API`() = repo { dir ->
        File(dir, "lib/src/main/kotlin/Hidden.kt").writeText("package probe\n\nprivate fun x() = 1\n")

        assertTrue(analyze(dir).apiTouched.isEmpty(), "private content is not externally visible")
    }

    @Test
    fun `an enum constant changes the API`() = repo { dir ->
        val file = File(dir, "lib/src/main/kotlin/Mode.kt")
        file.writeText("package probe\n\nenum class Mode {\n    FAST,\n}\n")
        commit(dir)
        file.writeText("package probe\n\nenum class Mode {\n    FAST,\n    SLOW,\n}\n")

        assertEquals(1, analyze(dir).apiTouched.size, "an exhaustive when in a consumer stops compiling")
    }
}
