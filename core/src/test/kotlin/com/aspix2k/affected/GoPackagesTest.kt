package com.aspix2k.affected

import com.aspix2k.affected.build.GoPackages
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GoPackagesTest {

    private val stream = """
        {
            "Dir": "/ws/app",
            "ImportPath": "example.com/app",
            "Imports": [
                "example.com/app/internal/store",
                "fmt",
                "github.com/spf13/cobra"
            ],
            "TestGoFiles": ["app_test.go"]
        }
        {
            "Dir": "/ws/app/internal/store",
            "ImportPath": "example.com/app/internal/store",
            "Imports": ["database/sql"],
            "TestGoFiles": []
        }
        {
            "Dir": "/ws/app/cmd",
            "ImportPath": "example.com/app/cmd",
            "Imports": ["example.com/app"],
            "XTestGoFiles": ["cmd_external_test.go"]
        }
    """.trimIndent()

    @Test
    fun `каждый пакет становится модулем`() {
        val modules = GoPackages.parse(stream, "/ws/app")

        assertEquals(
            setOf("example.com/app", "example.com/app/internal/store", "example.com/app/cmd"),
            modules.map { it.id }.toSet(),
        )
    }

    @Test
    fun `зависимостями считаются только пакеты этого модуля`() {
        val app = GoPackages.parse(stream, "/ws/app").single { it.id == "example.com/app" }

        assertEquals(
            setOf("/ws/app|example.com/app/internal/store"),
            app.dependencies,
            "fmt из стандартной библиотеки и cobra из сети потребителями быть не могут",
        )
    }

    @Test
    fun `внешние тесты тоже делают пакет тестируемым`() {
        val modules = GoPackages.parse(stream, "/ws/app")

        assertTrue(modules.single { it.id == "example.com/app" }.hasTests)
        assertTrue(modules.single { it.id == "example.com/app/cmd" }.hasTests, "XTestGoFiles — тоже тесты")
        assertFalse(modules.single { it.id.endsWith("/store") }.hasTests)
    }

    @Test
    fun `поток объектов без запятых разбирается целиком`() {
        assertEquals(3, GoPackages.parse(stream, "/ws/app").size, "go list пишет объекты подряд, а не массивом")
    }

    @Test
    fun `обрыв вывода не роняет разбор`() {
        val truncated = stream.substring(0, stream.length / 2)

        val modules = GoPackages.parse(truncated, "/ws/app")

        assertTrue(modules.size <= 3, "оборванный поток даёт то, что успело разобраться, без исключения")
    }

    @Test
    fun `windows-пути приводятся к прямым слэшам`() {
        val windows = """{ "Dir": "C:\\ws\\app\\cmd", "ImportPath": "example.com/app/cmd", "Imports": [] }"""

        val module = GoPackages.parse(windows, "C:/ws/app").single()

        assertFalse('\\' in module.contentRoots.single())
        assertTrue(module.contentRoots.single().endsWith("app/cmd"))
    }
}
