package com.aspix2k.affected

import com.aspix2k.affected.build.BuildChanges
import com.aspix2k.affected.build.BuildModule
import com.aspix2k.affected.build.gradle.GradleBuildSystem
import com.aspix2k.affected.build.gradle.gradleCompositeRoot
import com.aspix2k.affected.build.gradle.gradleExecutionCoordinates
import com.aspix2k.affected.build.gradle.gradleExecutionMetadata
import com.aspix2k.affected.build.gradle.gradleHoldsTests
import com.aspix2k.affected.build.gradle.gradleIsSourceFile
import com.aspix2k.affected.build.gradle.gradleKmpAdditionalTestTasks
import com.aspix2k.affected.build.gradle.gradleModulesWithDependencies
import com.aspix2k.affected.build.gradle.gradleProductionCompileTask
import com.aspix2k.affected.build.gradle.gradleProjectPath
import com.aspix2k.affected.build.gradle.gradleTestCompileTask
import com.aspix2k.affected.build.gradle.gradleTestTask
import com.aspix2k.affected.build.gradle.gradleUnitTestTasks
import com.aspix2k.affected.build.gradle.gradleUnverifiableScriptChanged
import com.aspix2k.affected.build.gradle.gradleVerificationTasks
import com.aspix2k.affected.build.gradle.isAndroidInstrumentationSource
import com.aspix2k.affected.build.gradle.selectAndroidTestTask
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
    fun `optional Gradle plugin references stay inside its extension class`() {
        val bytecode = GradleBuildSystem::class.java
            .getResourceAsStream("GradleBuildSystemKt.class")
            ?.use { it.readBytes().toString(Charsets.ISO_8859_1) }
            ?: error("GradleBuildSystemKt bytecode is missing")

        assertFalse(bytecode.contains("org/jetbrains/plugins/gradle"))
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
            gradleVerificationTasks(emptySet()),
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
            gradleProductionCompileTask(available),
        )
        assertEquals(
            "testAndroidHostTest" to "compileAndroidHostTest",
            gradleVerificationTasks(available),
        )
    }

    @Test
    fun `known Gradle tasks never invent a missing compileDebugKotlin`() {
        assertEquals(
            null,
            gradleProductionCompileTask(
                setOf("testAndroidHostTest", "iosSimulatorArm64Test", "assemble"),
            ),
        )
    }

    @Test
    fun `a plain JVM project runs test and never treats testClasses as a test task`() {
        val java = setOf(
            "assemble", "build", "classes", "clean", "jar", "testClasses", "compileJava", "compileTestJava",
            "processResources", "processTestResources", "check", "test", "javadoc",
        )

        assertEquals("test" to "compileTestJava", gradleVerificationTasks(java))
        assertEquals(emptySet(), gradleKmpAdditionalTestTasks(java, "test"))
        assertEquals(
            "test" to "compileTestKotlin",
            gradleVerificationTasks(java - "compileTestJava" + setOf("compileKotlin", "compileTestKotlin")),
        )
    }

    @Test
    fun `a JVM project with another test suite runs both test and that suite`() {
        val tasks = setOf(
            "classes", "testClasses", "integrationTestClasses", "compileJava", "compileTestJava",
            "compileIntegrationTestJava", "test", "integrationTest", "check",
        )

        assertEquals("integrationTest", gradleTestTask(tasks))
        assertEquals(setOf("test"), gradleKmpAdditionalTestTasks(tasks, "integrationTest"))
    }

    @Test
    fun `typed test tasks from the IDE model win over task names`() {
        val tasks = setOf(
            "test", "integrationTest", "testCodeCoverageReport", "testFixturesJar", "connectedDebugAndroidTest",
            "compileJava", "compileTestJava", "compileIntegrationTestJava",
        )
        val typed = setOf("test", "integrationTest", "connectedDebugAndroidTest")

        assertEquals(listOf("test", "integrationTest"), gradleUnitTestTasks(tasks, typed))
        assertEquals("integrationTest", gradleTestTask(tasks, typed))
        assertEquals(setOf("test"), gradleKmpAdditionalTestTasks(tasks, "integrationTest", typed))
    }

    @Test
    fun `the Android test aggregator is not run next to its variant tasks`() {
        val tasks = setOf(
            "test", "testDebugUnitTest", "testReleaseUnitTest", "compileDebugUnitTestKotlin",
            "compileReleaseUnitTestKotlin", "compileDebugKotlin",
        )

        assertEquals(listOf("testDebugUnitTest", "testReleaseUnitTest"), gradleUnitTestTasks(tasks))
        assertEquals(setOf("testDebugUnitTest"), gradleKmpAdditionalTestTasks(tasks, "testReleaseUnitTest"))
    }

    @Test
    fun `an unknown Gradle task list does not invent test or compile names`() {
        assertEquals(null, gradleTestTask(emptySet()))
        assertEquals(null, gradleTestCompileTask("test", emptySet()))
        assertEquals(null, gradleProductionCompileTask(emptySet()))
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
            gradleVerificationTasks(available),
        )
        assertFalse("compileTestKotlin" in available)
    }

    @Test
    fun `dependencies described by project directory resolve to module keys of the build root`() {
        val module = { id: String ->
            BuildModule(
                id = id,
                root = "/repo",
                contentRoots = listOf("/repo/${id.removePrefix(":")}"),
                testTask = "test",
                compileTask = "compileJava",
                hasTests = true,
                systemId = "GRADLE",
            )
        }
        val core = module(":core")
        val app = module(":app")
        val coreDescribed = "GRADLE|/repo/core|:core"
        val appDescribed = "GRADLE|/repo/app|:app"

        val modules = gradleModulesWithDependencies(
            mapOf(
                coreDescribed to (core to emptySet()),
                appDescribed to (app to setOf(coreDescribed, appDescribed, "GRADLE|/elsewhere|:gone")),
            ),
        )

        assertEquals(setOf(core.key), modules.single { it.id == ":app" }.dependencies)
        assertTrue(modules.single { it.id == ":core" }.dependencies.isEmpty())
    }

    @Test
    fun `a lifecycle task the IDE marks as a test is not a test task`() {
        val available = setOf("test", "check", "build", "compileTestJava", "classes")

        assertEquals(listOf("test"), gradleUnitTestTasks(available, setOf("test", "check")))
        assertEquals("test", gradleTestTask(available, setOf("test", "check")))
        assertTrue(gradleKmpAdditionalTestTasks(available, "test", setOf("test", "check")).isEmpty())
    }

    @Test
    fun `multiplatform test tasks the IDE does not type as tests are kept`() {
        val available = setOf("jvmTest", "jsTest", "iosSimulatorArm64Test", "allTests", "jvmTestClasses", "check")

        assertEquals(
            setOf("jvmTest", "jsTest", "iosSimulatorArm64Test"),
            gradleUnitTestTasks(available, setOf("jvmTest")).toSet(),
        )
    }

    @Test
    fun `a changed build script of a module without tasks widens to the whole build`() {
        val root = createTempDirectory("affected-platform-module").toFile()
        val platform = File(root, "platform").apply { mkdirs() }
        val module = BuildModule(
            id = ":platform",
            root = root.invariantSeparatorsPath,
            contentRoots = listOf(platform.invariantSeparatorsPath),
            testTask = "",
            compileTask = null,
            hasTests = false,
        )
        val script = BuildChanges(listOf(File(platform, "build.gradle.kts").path), emptySet(), comparedToBase = true)
        val other = BuildChanges(listOf(File(platform, "notes.kt").path), emptySet(), comparedToBase = true)

        assertTrue(gradleUnverifiableScriptChanged(module, script))
        assertFalse(gradleUnverifiableScriptChanged(module, other))
        assertFalse(gradleUnverifiableScriptChanged(module.copy(compileTask = "classes"), script))
    }

    @Test
    fun `tests in a directory the IDE marks as a test root count, whatever it is called`() {
        val module = createTempDirectory("affected-custom-test-root").toFile()
        val custom = File(module, "checks/java").apply { mkdirs() }
        File(custom, "AlphaCheck.java").writeText("class AlphaCheck {}")
        val empty = File(module, "specs").apply { mkdirs() }

        assertFalse(gradleHoldsTests(listOf(module.path), emptyList()))
        assertFalse(gradleHoldsTests(listOf(module.path), listOf(empty.path)))
        assertTrue(gradleHoldsTests(listOf(module.path), listOf(custom.path)))
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
            gradleVerificationTasks(available),
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
            gradleVerificationTasks(setOf("test", "compileTestKotlin", "compileKotlin")),
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
            ),
        )
        assertEquals(
            "compileKotlin",
            gradleProductionCompileTask(setOf("compileKotlin", "jar")),
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
    fun `a custom source set module resolves to its owning project`() {
        listOf("integrationTest", "testFixtures", "functionalTest", "testDebug", "jvmTest").forEach {
            assertEquals(":app", gradleProjectPath(":app:$it", null, true), it)
        }
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
