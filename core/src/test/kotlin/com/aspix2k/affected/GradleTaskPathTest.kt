package com.aspix2k.affected

import com.aspix2k.affected.build.GradleBuildSystem
import com.aspix2k.affected.build.gradleCompositeRoot
import com.aspix2k.affected.build.gradleExecutionCoordinates
import com.aspix2k.affected.build.gradleExecutionMetadata
import com.aspix2k.affected.build.gradleHoldsTests
import com.aspix2k.affected.build.gradleIsSourceFile
import com.aspix2k.affected.build.gradleKmpAdditionalTestTasks
import com.aspix2k.affected.build.gradleProductionCompileTask
import com.aspix2k.affected.build.gradleProjectPath
import com.aspix2k.affected.build.gradleTestCompileTask
import com.aspix2k.affected.build.gradleTestTask
import com.aspix2k.affected.build.gradleVerificationTasks
import com.aspix2k.affected.build.isAndroidInstrumentationSource
import com.aspix2k.affected.build.selectAndroidTestTask
import com.intellij.openapi.externalSystem.model.ProjectSystemId
import com.intellij.openapi.externalSystem.model.project.ModuleData
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GradleTaskPathTest {

    @Test
    fun `owning module metadata keeps source sets in one composite invocation`() {
        val modules = listOf(
            moduleInfo("/repo/platform", ":shared-data", ":platform:shared-data"),
            moduleInfo("/repo/store", ":ui-shell", ":store:ui-shell"),
        )
        val plan = TaskPlanner.plan(modules, emptyList())

        assertEquals(1, plan.groups.size)
        assertEquals("/repo", plan.groups.single().root)
        assertEquals(
            listOf(":platform:shared-data:test", ":store:ui-shell:test"),
            plan.groups.single().tasks,
        )
    }

    @Test
    fun `module discovery uses the owning module index instead of the source set index`() {
        val bytecode = GradleBuildSystem::class.java
            .getResourceAsStream("GradleBuildSystem.class")
            ?.use { it.readBytes().toString(Charsets.ISO_8859_1) }
            ?: error("GradleBuildSystem bytecode is missing")

        assertContains(bytecode, "ExternalSystemModuleDataIndex")
        assertFalse(bytecode.contains("GradleModuleDataIndex"))
        assertFalse(bytecode.contains("GradleModuleDataKt"))
    }

    @Test
    fun `an included build uses the composite execution coordinates`() {
        assertEquals(
            "/repo" to ":platform:shared-ui",
            gradleExecutionCoordinates(
                ownerRoot = "/repo/platform",
                ownerId = ":shared-ui",
                directoryToRunTask = "/repo",
                identityPath = ":platform:shared-ui",
            ),
        )
    }

    @Test
    fun `the Gradle root identity becomes an empty task prefix`() {
        assertEquals(
            "/repo" to "",
            gradleExecutionCoordinates("/repo", "", "/repo", ":"),
        )
    }

    @Test
    fun `a renamed included build identity is preserved`() {
        assertEquals(
            "/repo" to ":legacy-renamed:ui-shell",
            gradleExecutionCoordinates(
                "/repo/legacy",
                ":ui-shell",
                "/repo",
                ":legacy-renamed:ui-shell",
            ),
        )
    }

    @Test
    fun `a source set model keeps its Gradle identity path`() {
        assertEquals(
            "/repo" to ":platform:shared-data",
            gradleExecutionCoordinates(
                "/repo/platform",
                ":shared-data",
                "/repo",
                ":platform:shared-data",
            ),
        )
    }

    @Test
    fun `incomplete included build metadata keeps one composite invocation`() {
        val modules = listOf(
            fallbackModuleInfo("/repo/features", ":screen", "features"),
            fallbackModuleInfo("/repo/application", ":integration", "application"),
        )
        val plan = TaskPlanner.plan(modules, emptyList())

        assertEquals(1, plan.groups.size)
        assertEquals(absolutePath("/repo"), plan.groups.single().root)
        assertEquals(
            listOf(":features:screen:testDebugUnitTest", ":application:integration:testDebugUnitTest"),
            plan.groups.single().tasks,
        )
    }

    @Test
    fun `composite root is recovered from linked Gradle roots`() {
        assertEquals(
            absolutePath("/repo"),
            gradleCompositeRoot(
                ownerRoot = "/repo/features",
                linkedRoots = listOf("/unrelated", "/repo"),
                buildName = "features",
            ),
        )
    }

    @Test
    fun `a separately linked nested build stays independent`() {
        assertEquals(
            absolutePath("/repo/features"),
            gradleCompositeRoot(
                ownerRoot = "/repo/features",
                linkedRoots = listOf("/repo", "/repo/features"),
                buildName = "features",
            ),
        )
    }

    @Test
    fun `a missing Gradle task list does not invent Android task names`() {
        val module = createTempDirectory("affected-android-module").toFile()
        File(module, "src/main/AndroidManifest.xml").apply {
            parentFile.mkdirs()
            writeText("<manifest />")
        }

        assertEquals(
            null to null,
            gradleVerificationTasks(module.path, emptyList(), emptySet()),
        )
    }

    @Test
    fun `an instrumentation-only Android change uses connectedDebugAndroidTest`() {
        assertEquals(
            "connectedDebugAndroidTest",
            selectAndroidTestTask(
                "testDebugUnitTest",
                setOf("testDebugUnitTest", "connectedDebugAndroidTest"),
                instrumentationOnly = true,
            ),
        )
    }

    @Test
    fun `a unit Android change keeps testDebugUnitTest when a connected task exists`() {
        assertEquals(
            "testDebugUnitTest",
            selectAndroidTestTask(
                "testDebugUnitTest",
                setOf("testDebugUnitTest", "connectedDebugAndroidTest"),
                instrumentationOnly = false,
            ),
        )
    }

    @Test
    fun `an instrumentation-only change without a connected task keeps the unit task`() {
        assertEquals(
            "testDebugUnitTest",
            selectAndroidTestTask("testDebugUnitTest", setOf("testDebugUnitTest"), instrumentationOnly = true),
        )
    }

    @Test
    fun `androidTest sources are instrumentation and unit trees are not`() {
        assertTrue(isAndroidInstrumentationSource("/app/src/androidTest/java/Ui.kt"))
        assertTrue(isAndroidInstrumentationSource("/app/src/androidInstrumentedTest/kotlin/Ui.kt"))
        assertFalse(isAndroidInstrumentationSource("/app/src/test/java/Unit.kt"))
        assertFalse(isAndroidInstrumentationSource("/app/src/androidUnitTest/kotlin/Unit.kt"))
        assertFalse(isAndroidInstrumentationSource("/app/src/main/java/App.kt"))
    }

    @Test
    fun `a production-only Android module compiles instead of running missing unit tests`() {
        assertEquals(
            "compileDebugKotlin",
            gradleProductionCompileTask(
                setOf("compileDebugKotlin", "compileDebugUnitTestKotlin", "assembleDebug"),
                android = true,
            ),
        )
    }

    @Test
    fun `a KMP Android library does not plan missing compileDebugKotlin`() {
        val available = setOf(
            "testAndroid",
            "testAndroidHostTest",
            "iosSimulatorArm64Test",
            "compileKotlinMetadata",
            "compileAndroidMain",
            "compileAndroidHostTest",
        )

        assertEquals(
            "compileAndroidMain",
            gradleProductionCompileTask(available, android = true),
        )
        assertEquals(
            "testAndroidHostTest" to "compileAndroidHostTest",
            gradleVerificationTasks("/repo/shared/feature/capture", listOf("/repo/shared/feature/capture"), available),
        )
    }

    @Test
    fun `known Gradle tasks never invent a missing compileDebugKotlin`() {
        assertEquals(
            null,
            gradleProductionCompileTask(
                setOf("testAndroidHostTest", "iosSimulatorArm64Test", "assemble"),
                android = true,
            ),
        )
    }

    @Test
    fun `an unknown Gradle task list does not invent test or compile names`() {
        assertEquals(null, gradleTestTask(emptySet(), android = true))
        assertEquals(null, gradleTestCompileTask("test", emptySet(), android = true))
        assertEquals(null, gradleProductionCompileTask(emptySet(), android = true, kmp = true))
    }

    @Test
    fun `a KMP iOS module does not plan ambiguous compileTestKotlin`() {
        val available = setOf(
            "iosSimulatorArm64Test",
            "compileKotlinMetadata",
            "compileAndroidMain",
            "compileKotlinIosArm64",
            "compileKotlinIosSimulatorArm64",
            "compileTestKotlinIosArm64",
            "compileTestKotlinIosSimulatorArm64",
        )

        assertEquals(
            "iosSimulatorArm64Test" to "compileTestKotlinIosSimulatorArm64",
            gradleVerificationTasks("/repo/shared/feature/auth", listOf("/repo/shared/feature/auth"), available),
        )
        assertFalse("compileTestKotlin" in available)
    }

    @Test
    fun `Scala and Groovy files count as Gradle sources and tests`() {
        val module = createTempDirectory("affected-scala-groovy").toFile()
        val scala = File(module, "src/test/scala/AlphaSpec.scala").apply {
            parentFile.mkdirs()
            writeText("class AlphaSpec")
        }
        val groovy = File(module, "src/test/groovy/BetaSpec.groovy").apply {
            parentFile.mkdirs()
            writeText("class BetaSpec {}")
        }

        assertTrue(gradleIsSourceFile(scala))
        assertTrue(gradleIsSourceFile(groovy))
        assertTrue(gradleHoldsTests(module.path))
        assertContains(GradleBuildSystem().sourceExtensions, "scala")
        assertContains(GradleBuildSystem().sourceExtensions, "groovy")
    }

    @Test
    fun `a KMP Android module without an exact test task does not plan test`() {
        val available = setOf(
            "testAndroid",
            "testAndroidHostTest",
            "iosSimulatorArm64Test",
            "compileKotlinMetadata",
            "compileAndroidHostTestKotlin",
        )

        assertEquals(
            "testAndroidHostTest" to "compileAndroidHostTestKotlin",
            gradleVerificationTasks("/repo/shared/core/ui", listOf("/repo/shared/core/ui"), available),
        )
        assertEquals(
            setOf("iosSimulatorArm64Test"),
            gradleKmpAdditionalTestTasks(available, "testAndroidHostTest"),
        )
    }

    @Test
    fun `an imported JVM test task is still planned exactly`() {
        assertEquals(
            "test" to "compileTestKotlin",
            gradleVerificationTasks(
                "/repo/lib",
                listOf("/repo/lib"),
                setOf("test", "compileTestKotlin", "compileKotlin"),
            ),
        )
    }

    @Test
    fun `KMP additional tests exclude the primary task`() {
        assertEquals(
            setOf("iosSimulatorArm64Test", "testDebugUnitTest"),
            gradleKmpAdditionalTestTasks(
                setOf("test", "testDebugUnitTest", "iosSimulatorArm64Test", "assemble"),
                "test",
            ),
        )
        assertEquals(
            emptySet(),
            gradleKmpAdditionalTestTasks(setOf("test", "assemble"), "test"),
        )
    }

    @Test
    fun `a production-only Kotlin module compiles metadata or main Kotlin`() {
        assertEquals(
            "compileDebugKotlinAndroid",
            gradleProductionCompileTask(
                setOf("compileKotlinMetadata", "compileDebugKotlinAndroid"),
                android = false,
            ),
        )
        assertEquals(
            "compileKotlin",
            gradleProductionCompileTask(setOf("compileKotlin", "jar"), android = false),
        )
    }

    @Test
    fun `incomplete standalone metadata stays on the owning build`() {
        assertEquals(
            "/repo/platform" to ":shared-data",
            gradleExecutionCoordinates(
                ownerRoot = "/repo/platform",
                ownerId = ":shared-data",
                directoryToRunTask = null,
                identityPath = null,
                linkedRoot = "/repo/platform",
                buildName = "platform",
            ),
        )
    }

    @Test
    fun `a composite build identity path becomes a path inside its build root`() {
        assertEquals(":shared-data", gradleProjectPath(":platform:shared-data:main", "platform", true))
    }

    @Test
    fun `a nested Gradle path is preserved`() {
        assertEquals(":ui:flow", gradleProjectPath(":ui:flow:test", null, true))
    }

    @Test
    fun `a regular project id without a leading colon is supported`() {
        assertEquals(":core", gradleProjectPath("root:core:main", "root", true))
    }

    @Test
    fun `a root source set runs a root project task`() {
        assertEquals("", gradleProjectPath(":features:main", "features", true))
    }

    @Test
    fun `a project named test is not mistaken for a source set`() {
        assertEquals(":test", gradleProjectPath(":test", null, false))
    }

    private fun moduleInfo(ownerRoot: String, ownerId: String, identityPath: String): ModuleInfo {
        val moduleData = ModuleData(
            ownerId,
            ProjectSystemId("GRADLE"),
            "JAVA_MODULE",
            ownerId,
            "$ownerRoot/${ownerId.removePrefix(":")}",
            "$ownerRoot/${ownerId.removePrefix(":")}",
        ).apply {
            setProperty("directoryToRunTask", "/repo")
            setProperty("gradleIdentityPath", identityPath)
        }
        val metadata = gradleExecutionMetadata(moduleData)
        val (executionRoot, executionId) = gradleExecutionCoordinates(
            ownerRoot,
            ownerId,
            metadata.first,
            metadata.second,
        )
        return ModuleInfo(
            id = ownerId,
            systemId = "GRADLE",
            buildRoot = ownerRoot,
            testTask = "test",
            compileTask = "compileTestKotlin",
            hasTests = true,
            executionRoot = executionRoot,
            executionId = executionId,
        )
    }

    private fun fallbackModuleInfo(ownerRoot: String, ownerId: String, buildName: String): ModuleInfo {
        val (executionRoot, executionId) = gradleExecutionCoordinates(
            ownerRoot = ownerRoot,
            ownerId = ownerId,
            directoryToRunTask = null,
            identityPath = null,
            linkedRoot = "/repo",
            buildName = buildName,
        )
        return ModuleInfo(
            id = ownerId,
            systemId = "GRADLE",
            buildRoot = ownerRoot,
            testTask = "testDebugUnitTest",
            compileTask = "compileDebugUnitTestKotlin",
            hasTests = true,
            executionRoot = executionRoot,
            executionId = executionId,
        )
    }

    private fun absolutePath(path: String): String =
        File(path).toPath().toAbsolutePath().normalize().toFile().invariantSeparatorsPath
}
