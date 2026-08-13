package com.aspix2k.affected.build

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

class GradleKmpSourceSetTest {

    @Test
    fun `an androidMain change keeps Android tests and drops iOS`() {
        val root = module()
        val file = File(root, "shared/src/androidMain/kotlin/App.kt").apply {
            parentFile.mkdirs()
            writeText("class App")
        }

        assertEquals(
            listOf(":shared:testDebugUnitTest"),
            gradleTaskNames(
                listOf(":shared:testDebugUnitTest", ":shared:iosSimulatorArm64Test"),
                productionChange(file),
            ),
        )
    }

    @Test
    fun `an iosMain change keeps iOS tests and drops Android`() {
        val root = module()
        val file = File(root, "shared/src/iosMain/kotlin/App.kt").apply {
            parentFile.mkdirs()
            writeText("class App")
        }

        assertEquals(
            listOf(":shared:iosSimulatorArm64Test"),
            gradleTaskNames(
                listOf(":shared:testDebugUnitTest", ":shared:iosSimulatorArm64Test"),
                productionChange(file),
            ),
        )
    }

    @Test
    fun `a commonMain change keeps every target test`() {
        val root = module()
        val file = File(root, "shared/src/commonMain/kotlin/App.kt").apply {
            parentFile.mkdirs()
            writeText("class App")
        }

        assertEquals(
            listOf(":shared:testDebugUnitTest", ":shared:iosSimulatorArm64Test"),
            gradleTaskNames(
                listOf(":shared:testDebugUnitTest", ":shared:iosSimulatorArm64Test"),
                productionChange(file),
            ),
        )
    }

    private fun module(): File = createTempDirectory("kmp-source-set").toFile()

    private fun productionChange(file: File): BuildChanges =
        BuildChanges(listOf(file.path), emptySet(), comparedToBase = true)
}
