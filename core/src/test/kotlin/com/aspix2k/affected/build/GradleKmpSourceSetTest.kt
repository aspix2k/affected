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

    @Test
    fun `a nativeMain change keeps native targets and drops JVM Android JS and Wasm`() {
        val root = module()
        val file = File(root, "shared/src/nativeMain/kotlin/App.kt").apply {
            parentFile.mkdirs()
            writeText("class App")
        }

        assertEquals(
            listOf(
                ":shared:iosSimulatorArm64Test",
                ":shared:linuxX64Test",
                ":shared:macosArm64Test",
                ":shared:mingwX64Test",
            ),
            gradleTaskNames(allTargets(":shared"), productionChange(file)),
        )
    }

    @Test
    fun `an appleMain change keeps Apple tests and drops Linux and macOS`() {
        val root = module()
        val file = File(root, "shared/src/appleMain/kotlin/App.kt").apply {
            parentFile.mkdirs()
            writeText("class App")
        }

        assertEquals(
            listOf(":shared:iosSimulatorArm64Test"),
            gradleTaskNames(allTargets(":shared"), productionChange(file)),
        )
    }

    @Test
    fun `a jvmMain change keeps JVM tests only`() {
        val root = module()
        val file = File(root, "shared/src/jvmMain/kotlin/App.kt").apply {
            parentFile.mkdirs()
            writeText("class App")
        }

        assertEquals(
            listOf(":shared:jvmTest"),
            gradleTaskNames(allTargets(":shared"), productionChange(file)),
        )
    }

    @Test
    fun `an included-build androidMain change keeps Android tests on composite task paths`() {
        val root = module()
        val file = File(root, "included/shared/src/androidMain/kotlin/App.kt").apply {
            parentFile.mkdirs()
            writeText("class App")
        }

        assertEquals(
            listOf(":included:shared:testDebugUnitTest"),
            gradleTaskNames(
                listOf(":included:shared:testDebugUnitTest", ":included:shared:iosSimulatorArm64Test"),
                productionChange(file),
            ),
        )
    }

    private fun module(): File = createTempDirectory("kmp-source-set").toFile()

    private fun allTargets(module: String): List<String> = listOf(
        "$module:testDebugUnitTest",
        "$module:iosSimulatorArm64Test",
        "$module:jvmTest",
        "$module:jsBrowserTest",
        "$module:wasmJsBrowserTest",
        "$module:linuxX64Test",
        "$module:macosArm64Test",
        "$module:mingwX64Test",
    )

    private fun productionChange(file: File): BuildChanges =
        BuildChanges(listOf(file.path), emptySet(), comparedToBase = true)
}
