package com.aspix2k.affected

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TestRootResolverTest {

    private fun module(block: (File) -> Unit): File {
        val dir = createTempDirectory("test-root").toFile()
        block(dir)
        return dir
    }

    @Test
    fun `спускается по цепочке пакетов до первой папки с исходниками`() {
        val dir = module {
            File(it, "src/test/kotlin/ru/tander/app/integration").mkdirs()
            File(it, "src/test/kotlin/ru/tander/app/integration/SomeTest.kt").writeText("class SomeTest")
        }
        assertEquals(
            File(dir, "src/test/kotlin/ru/tander/app/integration").path,
            TestRootResolver.resolve(dir.path),
        )
    }

    @Test
    fun `останавливается там где пакет разветвляется`() {
        val dir = module {
            File(it, "src/test/kotlin/ru/tander/first").mkdirs()
            File(it, "src/test/kotlin/ru/tander/second").mkdirs()
            File(it, "src/test/kotlin/ru/tander/first/A.kt").writeText("class A")
            File(it, "src/test/kotlin/ru/tander/second/B.kt").writeText("class B")
        }
        assertEquals(
            File(dir, "src/test/kotlin/ru/tander").path,
            TestRootResolver.resolve(dir.path),
            "при двух ветках нельзя выбирать одну наугад",
        )
    }

    @Test
    fun `останавливается на папке где есть и файлы и подпапки`() {
        val dir = module {
            File(it, "src/test/kotlin/ru/nested").mkdirs()
            File(it, "src/test/kotlin/ru/Top.kt").writeText("class Top")
            File(it, "src/test/kotlin/ru/nested/Deep.kt").writeText("class Deep")
        }
        assertEquals(
            File(dir, "src/test/kotlin/ru").path,
            TestRootResolver.resolve(dir.path),
        )
    }

    @Test
    fun `java исходники тоже находятся`() {
        val dir = module {
            File(it, "src/test/java/com/example").mkdirs()
            File(it, "src/test/java/com/example/LegacyTest.java").writeText("class LegacyTest {}")
        }
        assertEquals(
            File(dir, "src/test/java/com/example").path,
            TestRootResolver.resolve(dir.path),
        )
    }

    @Test
    fun `модуль без тестов даёт null`() {
        val dir = module {
            File(it, "src/main/kotlin").mkdirs()
            File(it, "src/main/kotlin/Main.kt").writeText("class Main")
        }
        assertNull(TestRootResolver.resolve(dir.path))
    }

    @Test
    fun `пустая папка тестов даёт саму папку`() {
        val dir = module { File(it, "src/test/kotlin").mkdirs() }
        assertEquals(File(dir, "src/test/kotlin").path, TestRootResolver.resolve(dir.path))
    }

    @Test
    fun `kmp раскладка находится`() {
        val dir = module {
            File(it, "src/commonTest/kotlin/app").mkdirs()
            File(it, "src/commonTest/kotlin/app/CommonTest.kt").writeText("class CommonTest")
        }
        assertEquals(
            File(dir, "src/commonTest/kotlin/app").path,
            TestRootResolver.resolve(dir.path),
        )
    }

    @Test
    fun `несуществующий модуль не роняет резолвер`() {
        assertNull(TestRootResolver.resolve("/no/such/module/anywhere"))
    }
}
