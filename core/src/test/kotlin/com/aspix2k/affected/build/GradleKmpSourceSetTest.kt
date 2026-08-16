package com.aspix2k.affected.build

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

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
    fun `a commonMain change explains target fan out and native fallback`() {
        val file = publicFixtureFile("shared/src/commonMain/kotlin/Common.kt")
        val tasks = listOf(":shared:testDebugUnitTest", ":shared:iosSimulatorArm64Test")

        val selection = gradleTaskSelection(tasks, exactChange(file))

        assertEquals(tasks, selection.taskNames)
        assertEquals(
            listOf(
                GradleSelectionReason.COMMON_SOURCE_SET_FAN_OUT,
                GradleSelectionReason.KOTLIN_NATIVE_EXACT_UNSUPPORTED,
            ),
            selection.reasons,
        )
        assertEquals(
            listOf(
                "-Daffected.selection.reasons=" +
                    "COMMON_SOURCE_SET_FAN_OUT,KOTLIN_NATIVE_EXACT_UNSUPPORTED",
            ),
            selection.diagnosticArguments,
        )
        assertFalse(selection.diagnosticArguments.joinToString().contains(file.parentFile.absolutePath))
    }

    @Test
    fun `a commonTest change explains the same target fan out`() {
        val file = publicFixtureFile("shared/src/commonTest/kotlin/CommonTest.kt")

        val selection = gradleTaskSelection(
            listOf(":shared:testDebugUnitTest", ":shared:iosSimulatorArm64Test"),
            exactChange(file),
        )

        assertEquals(
            listOf(
                GradleSelectionReason.COMMON_SOURCE_SET_FAN_OUT,
                GradleSelectionReason.KOTLIN_NATIVE_EXACT_UNSUPPORTED,
            ),
            selection.reasons,
        )
    }

    @Test
    fun `an Android source change does not invent a fallback reason`() {
        val file = publicFixtureFile("shared/src/androidMain/kotlin/Android.kt")

        val selection = gradleTaskSelection(
            listOf(":shared:testDebugUnitTest", ":shared:iosSimulatorArm64Test"),
            exactChange(file),
        )

        assertEquals(listOf(":shared:testDebugUnitTest"), selection.taskNames)
        assertEquals(emptyList(), selection.reasons)
        assertEquals(emptyList(), selection.diagnosticArguments)
    }

    @Test
    fun `an unavailable comparison explains full target selection`() {
        val file = publicFixtureFile("shared/src/androidMain/kotlin/Android.kt")

        val selection = gradleTaskSelection(
            listOf(":shared:testDebugUnitTest", ":shared:iosSimulatorArm64Test"),
            BuildChanges(listOf(file.path), setOf(file.path), comparedToBase = false),
        )

        assertEquals(
            listOf(
                GradleSelectionReason.CHANGE_BASE_UNAVAILABLE,
                GradleSelectionReason.KOTLIN_NATIVE_EXACT_UNSUPPORTED,
            ),
            selection.reasons,
        )
    }

    @Test
    fun `configuration and source identity fallbacks remain distinct`() {
        val configuration = publicFixtureFile("shared/build.gradle")

        assertEquals(
            listOf(
                GradleSelectionReason.BUILD_CONFIGURATION_CHANGE,
                GradleSelectionReason.KOTLIN_NATIVE_EXACT_UNSUPPORTED,
            ),
            gradleTaskSelection(allTargets(":shared"), exactChange(configuration)).reasons,
        )
    }

    @Test
    fun `added and deleted sources retain the same bounded identity reason`() {
        val fixture = CliConformanceRepository.configured.fixture("gradle-kmp-fallback")
        val added = publicFixtureFile("shared/src/androidMain/kotlin/Android.kt")
        val deleted = File(fixture, "shared/src/androidMain/kotlin/Deleted.kt")

        for (source in listOf(added, deleted)) {
            assertEquals(
                listOf(GradleSelectionReason.SOURCE_IDENTITY_UNPROVEN),
                gradleTaskSelection(
                    listOf(":shared:testDebugUnitTest"),
                    BuildChanges(listOf(source.path), emptySet(), comparedToBase = true),
                ).reasons,
            )
        }
    }

    @Test
    fun `an unknown task family explains why its task is retained`() {
        val file = publicFixtureFile("shared/src/androidMain/kotlin/Android.kt")

        val selection = gradleTaskSelection(
            listOf(":shared:testDebugUnitTest", ":shared:customTest"),
            exactChange(file),
        )

        assertEquals(listOf(":shared:testDebugUnitTest", ":shared:customTest"), selection.taskNames)
        assertEquals(listOf(GradleSelectionReason.TASK_FAMILY_UNPROVEN), selection.reasons)
    }

    @Test
    fun `an unclassified source set explains full target selection`() {
        val file = publicFixtureFile("shared/src/desktopMain/kotlin/Desktop.kt")

        val selection = gradleTaskSelection(allTargets(":shared"), exactChange(file))

        assertEquals(
            listOf(
                GradleSelectionReason.UNCLASSIFIED_SOURCE_SET,
                GradleSelectionReason.KOTLIN_NATIVE_EXACT_UNSUPPORTED,
            ),
            selection.reasons,
        )
    }

    @Test
    fun `a plain invocation does not invent selection reasons`() {
        val selection = gradleTaskSelection(
            listOf(":shared:testDebugUnitTest", ":shared:iosSimulatorArm64Test", ":shared:customTest"),
            BuildChanges(emptyList(), emptySet(), comparedToBase = false),
        )

        assertEquals(emptyList(), selection.reasons)
        assertEquals(emptyList(), selection.diagnosticArguments)
    }

    @Test
    fun `a standard JVM test source relies on the collector instead of claiming a KMP fallback`() {
        val file = publicFixtureFile("shared/src/test/java/StandardTest.java")

        val selection = gradleTaskSelection(listOf(":shared:test"), exactChange(file))

        assertEquals(emptyList(), selection.reasons)
    }

    @Test
    fun `an ordinary custom JVM test task does not claim an unproved KMP family`() {
        val file = publicFixtureFile("shared/src/integrationTest/java/IntegrationTest.java")

        val selection = gradleTaskSelection(listOf(":shared:integrationTest"), exactChange(file))

        assertEquals(emptyList(), selection.reasons)
    }

    @Test
    fun `compile and test class tasks are not classified as runnable test tasks`() {
        val file = publicFixtureFile("shared/src/androidMain/kotlin/Android.kt")

        val selection = gradleTaskSelection(
            listOf(":shared:compileTestKotlin", ":shared:testClasses"),
            exactChange(file),
        )

        assertEquals(emptyList(), selection.reasons)
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

    private fun publicFixtureFile(relative: String): File = CliConformanceRepository.configured.repositoryFile(
        "conformance/cli-fixtures/gradle-kmp-fallback/$relative",
    )

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

    private fun exactChange(file: File): BuildChanges =
        BuildChanges(listOf(file.path), setOf(file.path), comparedToBase = true)
}
