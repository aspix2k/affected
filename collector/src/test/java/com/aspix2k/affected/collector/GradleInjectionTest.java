package com.aspix2k.affected.collector;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.Rule;
import org.junit.Test;
import org.junit.Assume;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class GradleInjectionTest {
    private static final int COMPLETE_MAP_INVOCATIONS = 10;
    private static final long GRADLE_HANG_BUDGET_MILLIS = 90_000L;
    private static final long COMPLETE_MAP_SCENARIO_TIMEOUT_MILLIS =
        COMPLETE_MAP_INVOCATIONS * 30_000L;

    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @Test(timeout = 120_000L)
    public void affectedStopStrategyOverridesAnIdeContinueArgument() throws Exception {
        Path project = temporary.newFolder("stop-after-first-project").toPath();
        Path markers = temporary.newFolder("stop-after-first-markers").toPath();
        write(project.resolve("settings.gradle"), "rootProject.name = 'stop-after-first'\n");
        write(
            project.resolve("build.gradle"),
            "tasks.register('first') { doLast { file('" +
                markers.resolve("first.marker").toString().replace("\\", "\\\\") +
                "').text = 'first'; throw new GradleException('requested failure') } }\n" +
                "tasks.register('second') { doLast { file('" +
                markers.resolve("second.marker").toString().replace("\\", "\\\\") +
                "').text = 'second' } }\n"
        );

        BuildResult continued = runBounded(
            GRADLE_HANG_BUDGET_MILLIS,
            () -> GradleRunner.create()
                .withProjectDir(project.toFile())
                .withArguments("first", "second", "--continue")
                .buildAndFail()
        );

        assertTrue(continued.getOutput(), Files.isRegularFile(markers.resolve("first.marker")));
        assertTrue(continued.getOutput(), Files.isRegularFile(markers.resolve("second.marker")));
        Files.delete(markers.resolve("first.marker"));
        Files.delete(markers.resolve("second.marker"));

        BuildResult result = runBounded(
            GRADLE_HANG_BUDGET_MILLIS,
            () -> GradleRunner.create()
                .withProjectDir(project.toFile())
                .withArguments(
                    "first",
                    "second",
                    "--init-script",
                    required("affected.test.failureStrategyScript"),
                    "--continue"
                )
                .buildAndFail()
        );

        assertTrue(result.getOutput(), Files.isRegularFile(markers.resolve("first.marker")));
        assertFalse(result.getOutput(), Files.exists(markers.resolve("second.marker")));
    }

    @Test(timeout = COMPLETE_MAP_SCENARIO_TIMEOUT_MILLIS)
    public void realGradleWorkersProduceCompleteMapsAndReuseConfigurationCache() throws Exception {
        Path project = temporary.newFolder("project").toPath();
        writeFixture(project);
        Path firstOutput = temporary.newFolder("first-output").toPath();
        Path unchangedOutput = temporary.newFolder("unchanged-output").toPath();
        Path exactOutput = temporary.newFolder("exact-output").toPath();
        Path addedOutput = temporary.newFolder("added-output").toPath();
        Path resourceOutput = temporary.newFolder("resource-output").toPath();
        Path taggedOutput = temporary.newFolder("tagged-output").toPath();
        Path selectedOutput = temporary.newFolder("selected-output").toPath();
        Path legacyOutput = temporary.newFolder("legacy-output").toPath();
        Path staleOutput = temporary.newFolder("stale-output").toPath();
        Path corruptOutput = temporary.newFolder("corrupt-output").toPath();

        BuildResult first = run(project, firstOutput);
        assertDecision(first, ":testDebugUnitTest", "full fallback (baseline not collected yet)");
        assertComplete(firstOutput, 5, first.getOutput());
        assertEquals(
            setOf("AlphaTest", "BetaTest", "GammaTest", "DeltaTest", "VintageAlphaTest"),
            executedTests(project)
        );
        promote(firstOutput, project.resolve(".affected/maps"));
        Path baselineMap = onlyFile(project.resolve(".affected/maps"));
        String baseline = read(baselineMap);

        clearExecuted(project);
        BuildResult unchanged = run(project, unchangedOutput);
        assertDecision(unchanged, ":testDebugUnitTest", "proven-empty");
        assertEquals(TaskOutcome.SKIPPED, unchanged.task(":testDebugUnitTest").getOutcome());
        assertEquals(Collections.emptySet(), executedTests(project));
        assertOutputEmpty(unchangedOutput);

        writeAlpha(project, "int result = 1; return result;");
        clearExecuted(project);
        BuildResult exact = run(project, exactOutput);
        assertDecision(exact, ":testDebugUnitTest", "exact (3 test classes)");
        assertComplete(exactOutput, 3, false, exact.getOutput());
        assertEquals(setOf("AlphaTest", "GammaTest", "VintageAlphaTest"), executedTests(project));

        write(
            project.resolve("src/main/java/fixture/Added.java"),
            "package fixture; public final class Added {}\n"
        );
        BuildResult added = run(project, addedOutput);
        assertDecision(added, ":testDebugUnitTest", "full fallback (class set changed)");
        assertComplete(addedOutput, 5, added.getOutput());

        Files.delete(project.resolve("src/main/java/fixture/Added.java"));
        write(project.resolve("src/main/resources/fixture.properties"), "value=changed\n");
        BuildResult resource = run(project, resourceOutput);
        assertComplete(resourceOutput, 5, resource.getOutput());

        BuildResult tagged = run(project, taggedOutput, "testDebugUnitTest", true, "-PincludeSlow=true");
        BuildResult selected = run(
            project,
            selectedOutput,
            "testDebugUnitTest",
            true,
            "--tests",
            "fixture.AlphaTest"
        );
        BuildResult host = run(project, legacyOutput, "testAndroidHostTest", false);
        Files.write(
            baselineMap,
            baseline.replace("schema=4", "schema=3").getBytes(StandardCharsets.UTF_8)
        );
        BuildResult stale = run(project, staleOutput);
        Files.write(baselineMap, "format=1\nschema=4\n".getBytes(StandardCharsets.UTF_8));
        BuildResult corrupt = run(project, corruptOutput);
        Files.write(baselineMap, baseline.getBytes(StandardCharsets.UTF_8));

        assertDecision(tagged, ":testDebugUnitTest", "full fallback (runtime changed)");
        assertDecision(selected, ":testDebugUnitTest", "full fallback (existing test filter)");
        assertDecision(host, ":testAndroidHostTest", "full fallback (baseline not collected yet)");
        assertDecision(stale, ":testDebugUnitTest", "full fallback (baseline stale)");
        assertDecision(corrupt, ":testDebugUnitTest", "full fallback (baseline corrupt)");
        assertComplete(taggedOutput, 6, tagged.getOutput());
        assertComplete(selectedOutput, 1, false, selected.getOutput());
        assertComplete(legacyOutput, 1, host.getOutput());
        assertEquals(manifestValue(firstOutput, "input="), manifestValue(exactOutput, "input="));
        assertEquals(manifestValue(firstOutput, "runtime="), manifestValue(exactOutput, "runtime="));
        assertNotEquals(manifestValue(firstOutput, "runtime="), manifestValue(taggedOutput, "runtime="));
        assertNotEquals(dependencyHashes(firstOutput, "fixture.Alpha"), dependencyHashes(exactOutput, "fixture.Alpha"));
        assertTrue(selected.getOutput(), readTaskManifest(selectedOutput).endsWith("all=false\n"));
        assertTrue(unchanged.getOutput(), unchanged.getOutput().contains("Reusing configuration cache"));
    }

    @Test(timeout = 120_000L)
    public void mixedKmpTasksExecuteWithTheConfigurationCache() throws Exception {
        Path project = temporary.newFolder("mixed-kmp-project").toPath();
        Path firstOutput = temporary.newFolder("mixed-kmp-first-output").toPath();
        Path cachedOutput = temporary.newFolder("mixed-kmp-cached-output").toPath();
        Path markers = temporary.newFolder("mixed-kmp-markers").toPath();
        copyPublicFixture("gradle-kmp-fallback", project);

        BuildResult first = run(
            project,
            firstOutput,
            ":shared:testDebugUnitTest",
            true,
            ":shared:iosSimulatorArm64Test",
            ":shared:customTest",
            ":included:includedTest",
            "-Daffected.kmp.markers=" + markers
        );
        BuildResult result = run(
            project,
            cachedOutput,
            ":shared:testDebugUnitTest",
            true,
            ":shared:iosSimulatorArm64Test",
            ":shared:customTest",
            ":included:includedTest",
            "-Daffected.kmp.markers=" + markers
        );

        assertTrue(result.getOutput(), result.getOutput().contains("Reusing configuration cache"));
        assertEquals(TaskOutcome.SUCCESS, first.task(":shared:testDebugUnitTest").getOutcome());
        assertEquals(TaskOutcome.SUCCESS, first.task(":shared:iosSimulatorArm64Test").getOutcome());
        assertEquals(TaskOutcome.SUCCESS, first.task(":shared:customTest").getOutcome());
        assertEquals(TaskOutcome.SUCCESS, first.task(":included:includedTest").getOutcome());
        assertEquals(TaskOutcome.SUCCESS, result.task(":shared:testDebugUnitTest").getOutcome());
        assertEquals(TaskOutcome.SUCCESS, result.task(":shared:iosSimulatorArm64Test").getOutcome());
        assertEquals(TaskOutcome.SUCCESS, result.task(":shared:customTest").getOutcome());
        assertEquals(TaskOutcome.SUCCESS, result.task(":included:includedTest").getOutcome());
        assertEquals("android\n", read(markers.resolve("android.marker")));
        assertEquals("ios\n", read(markers.resolve("ios.marker")));
        assertEquals("custom\n", read(markers.resolve("custom.marker")));
        assertEquals("included\n", read(markers.resolve("included.marker")));
    }

    @Test(timeout = 5_000L)
    public void aHungGradleInvocationFailsInsideTheHangBudget() throws Exception {
        long started = System.nanoTime();
        try {
            runBounded(400L, () -> {
                Thread.sleep(10_000L);
                return null;
            });
            fail("hung work must not return");
        } catch (Exception ignored) {
            // timeout or interrupt is the expected hang detection
        }
        long elapsed = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertTrue("elapsed=" + elapsed, elapsed < 2_000L);
    }

    @Test(timeout = COMPLETE_MAP_SCENARIO_TIMEOUT_MILLIS)
    public void junit4AndroidHostTestsSelectExactDependentClasses() throws Exception {
        Path project = temporary.newFolder("junit4-host-project").toPath();
        Path baselineOutput = temporary.newFolder("junit4-host-baseline-output").toPath();
        Path unchangedOutput = temporary.newFolder("junit4-host-unchanged-output").toPath();
        Path exactOutput = temporary.newFolder("junit4-host-exact-output").toPath();
        writeFixture(project);

        BuildResult baseline = run(project, baselineOutput, "testAndroidHostTest", true);
        assertDecision(baseline, ":testAndroidHostTest", "full fallback (baseline not collected yet)");
        assertComplete(baselineOutput, 1, baseline.getOutput());
        promote(baselineOutput, project.resolve(".affected/maps"));

        clearExecuted(project);
        BuildResult unchanged = run(project, unchangedOutput, "testAndroidHostTest", true);
        assertDecision(unchanged, ":testAndroidHostTest", "proven-empty");
        assertEquals(TaskOutcome.SKIPPED, unchanged.task(":testAndroidHostTest").getOutcome());
        assertEquals(Collections.emptySet(), executedTests(project));

        writeAlpha(project, "int result = 1; return result;");
        clearExecuted(project);
        BuildResult exact = run(project, exactOutput, "testAndroidHostTest", true);
        assertDecision(exact, ":testAndroidHostTest", "exact (1 test class)");
        assertComplete(exactOutput, 1, false, exact.getOutput());
        assertEquals(setOf("LegacyTest"), executedTests(project));
    }

    @Test(timeout = COMPLETE_MAP_SCENARIO_TIMEOUT_MILLIS)
    public void junit4TwoMethodClassKeepsLaterMethodDependencies() throws Exception {
        Path project = temporary.newFolder("junit4-two-method-project").toPath();
        Path baselineOutput = temporary.newFolder("junit4-two-method-baseline-output").toPath();
        Path exactOutput = temporary.newFolder("junit4-two-method-exact-output").toPath();
        write(project.resolve("settings.gradle"), "rootProject.name = 'junit4-two-method'\n");
        write(
            project.resolve("build.gradle"),
            "plugins { id 'java' }\n" +
                "repositories { mavenCentral() }\n" +
                "dependencies { testImplementation 'junit:junit:4.13.2' }\n" +
                "tasks.register('testAndroidHostTest', Test) {\n" +
                "    testClassesDirs = sourceSets.test.output.classesDirs\n" +
                "    classpath = sourceSets.test.runtimeClasspath\n" +
                "    useJUnit()\n" +
                "    include '**/TwoMethodHostTest.class'\n" +
                "    systemProperty 'fixture.executed', file('executed').absolutePath\n" +
                "}\n"
        );
        write(
            project.resolve("src/main/java/fixture/FirstDep.java"),
            "package fixture; public final class FirstDep { public static int value() { return 1; } }\n"
        );
        write(
            project.resolve("src/main/java/fixture/SecondDep.java"),
            "package fixture; public final class SecondDep { public static int value() { return 2; } }\n"
        );
        write(
            project.resolve("src/test/java/fixture/Executions.java"),
            "package fixture; import java.nio.charset.StandardCharsets; import java.nio.file.*; " +
                "public final class Executions { public static void mark(String name) throws Exception { " +
                "Path root = Paths.get(System.getProperty(\"fixture.executed\")); Files.createDirectories(root); " +
                "Files.write(root.resolve(name), name.getBytes(StandardCharsets.UTF_8)); } }\n"
        );
        write(
            project.resolve("src/test/java/fixture/TwoMethodHostTest.java"),
            "package fixture; import org.junit.FixMethodOrder; import org.junit.Test; " +
                "import org.junit.runners.MethodSorters; import static org.junit.Assert.*; " +
                "@FixMethodOrder(MethodSorters.NAME_ASCENDING) " +
                "public final class TwoMethodHostTest { " +
                "@Test public void first() throws Exception { " +
                "assertEquals(1, FirstDep.value()); Executions.mark(\"first\"); } " +
                "@Test public void second() throws Exception { " +
                "Object value = Class.forName(\"fixture.SecondDep\").getMethod(\"value\").invoke(null); " +
                "assertEquals(2, ((Integer) value).intValue()); Executions.mark(\"second\"); } }\n"
        );

        BuildResult baseline = run(project, baselineOutput, "testAndroidHostTest", false);
        assertDecision(baseline, ":testAndroidHostTest", "full fallback (baseline not collected yet)");
        assertComplete(baselineOutput, 1, baseline.getOutput());
        assertEquals(
            baseline.getOutput() + "\n" + readWorkerMaps(baselineOutput),
            1,
            dependencyHashes(baselineOutput, "fixture.SecondDep").size()
        );
        promote(baselineOutput, project.resolve(".affected/maps"));

        write(
            project.resolve("src/main/java/fixture/SecondDep.java"),
            "package fixture; public final class SecondDep { public static int value() { int result = 2; return result; } }\n"
        );
        clearExecuted(project);
        BuildResult exact = run(project, exactOutput, "testAndroidHostTest", false);
        assertTrue(
            readWorkerMaps(baselineOutput) + "\n" + exact.getOutput(),
            exact.getOutput().contains("[Affected] :testAndroidHostTest - exact (1 test class)")
        );
        assertEquals(setOf("first", "second"), executedTests(project));
    }

    @Test(timeout = COMPLETE_MAP_SCENARIO_TIMEOUT_MILLIS)
    public void testNgHostTestsSelectExactDependentClasses() throws Exception {
        Path project = temporary.newFolder("testng-host-project").toPath();
        Path baselineOutput = temporary.newFolder("testng-host-baseline-output").toPath();
        Path unchangedOutput = temporary.newFolder("testng-host-unchanged-output").toPath();
        Path exactOutput = temporary.newFolder("testng-host-exact-output").toPath();
        write(project.resolve("settings.gradle"), "rootProject.name = 'testng-host'\n");
        write(
            project.resolve("build.gradle"),
            "plugins { id 'java' }\n" +
                "repositories { mavenCentral() }\n" +
                "dependencies { testImplementation 'org.testng:testng:7.11.0' }\n" +
                "tasks.register('testNgHost', Test) {\n" +
                "    testClassesDirs = sourceSets.test.output.classesDirs\n" +
                "    classpath = sourceSets.test.runtimeClasspath\n" +
                "    useTestNG()\n" +
                "    systemProperty 'fixture.executed', file('executed').absolutePath\n" +
                "}\n"
        );
        write(
            project.resolve("src/main/java/fixture/Host.java"),
            "package fixture; public final class Host { public static int value() { return 1; } }\n"
        );
        write(
            project.resolve("src/main/java/fixture/Other.java"),
            "package fixture; public final class Other { public static int value() { return 2; } }\n"
        );
        write(
            project.resolve("src/test/java/fixture/Executions.java"),
            "package fixture; import java.nio.charset.StandardCharsets; import java.nio.file.*; " +
                "public final class Executions { public static void mark(String name) throws Exception { " +
                "Path root = Paths.get(System.getProperty(\"fixture.executed\")); Files.createDirectories(root); " +
                "Files.write(root.resolve(name), name.getBytes(StandardCharsets.UTF_8)); } }\n"
        );
        write(
            project.resolve("src/test/java/fixture/NgHostTest.java"),
            "package fixture; import org.testng.annotations.Test; import static org.testng.Assert.*; " +
                "public final class NgHostTest { @Test public void ng() throws Exception { " +
                "assertEquals(Host.value(), 1); Executions.mark(\"NgHostTest\"); } }\n"
        );
        write(
            project.resolve("src/test/java/fixture/NgOtherTest.java"),
            "package fixture; import org.testng.annotations.Test; import static org.testng.Assert.*; " +
                "public final class NgOtherTest { @Test public void other() throws Exception { " +
                "assertEquals(Other.value(), 2); Executions.mark(\"NgOtherTest\"); } }\n"
        );

        BuildResult baseline = run(project, baselineOutput, "testNgHost", false);
        assertDecision(baseline, ":testNgHost", "full fallback (baseline not collected yet)");
        assertComplete(baselineOutput, 2, baseline.getOutput());
        promote(baselineOutput, project.resolve(".affected/maps"));

        clearExecuted(project);
        BuildResult unchanged = run(project, unchangedOutput, "testNgHost", false);
        assertDecision(unchanged, ":testNgHost", "proven-empty");
        assertEquals(TaskOutcome.SKIPPED, unchanged.task(":testNgHost").getOutcome());
        assertEquals(Collections.emptySet(), executedTests(project));

        write(
            project.resolve("src/main/java/fixture/Host.java"),
            "package fixture; public final class Host { public static int value() { int result = 1; return result; } }\n"
        );
        clearExecuted(project);
        BuildResult exact = run(project, exactOutput, "testNgHost", false);
        assertDecision(exact, ":testNgHost", "exact (1 test class)");
        assertComplete(exactOutput, 1, false, exact.getOutput());
        assertEquals(setOf("NgHostTest"), executedTests(project));
    }

    @Test(timeout = COMPLETE_MAP_SCENARIO_TIMEOUT_MILLIS)
    public void testNgTwoMethodClassKeepsLaterMethodDependencies() throws Exception {
        Path project = temporary.newFolder("testng-two-method-project").toPath();
        Path baselineOutput = temporary.newFolder("testng-two-method-baseline-output").toPath();
        Path exactOutput = temporary.newFolder("testng-two-method-exact-output").toPath();
        write(project.resolve("settings.gradle"), "rootProject.name = 'testng-two-method'\n");
        write(
            project.resolve("build.gradle"),
            "plugins { id 'java' }\n" +
                "repositories { mavenCentral() }\n" +
                "dependencies { testImplementation 'org.testng:testng:7.11.0' }\n" +
                "tasks.register('testNgHost', Test) {\n" +
                "    testClassesDirs = sourceSets.test.output.classesDirs\n" +
                "    classpath = sourceSets.test.runtimeClasspath\n" +
                "    useTestNG()\n" +
                "    include '**/TwoMethodNgTest.class'\n" +
                "    systemProperty 'fixture.executed', file('executed').absolutePath\n" +
                "}\n"
        );
        write(
            project.resolve("src/main/java/fixture/FirstDep.java"),
            "package fixture; public final class FirstDep { public static int value() { return 1; } }\n"
        );
        write(
            project.resolve("src/main/java/fixture/SecondDep.java"),
            "package fixture; public final class SecondDep { public static int value() { return 2; } }\n"
        );
        write(
            project.resolve("src/test/java/fixture/Executions.java"),
            "package fixture; import java.nio.charset.StandardCharsets; import java.nio.file.*; " +
                "public final class Executions { public static void mark(String name) throws Exception { " +
                "Path root = Paths.get(System.getProperty(\"fixture.executed\")); Files.createDirectories(root); " +
                "Files.write(root.resolve(name), name.getBytes(StandardCharsets.UTF_8)); } }\n"
        );
        write(
            project.resolve("src/test/java/fixture/TwoMethodNgTest.java"),
            "package fixture; import org.testng.annotations.Test; import static org.testng.Assert.*; " +
                "public final class TwoMethodNgTest { " +
                "@Test(priority = 1) public void first() throws Exception { " +
                "assertEquals(FirstDep.value(), 1); Executions.mark(\"first\"); } " +
                "@Test(priority = 2) public void second() throws Exception { " +
                "Object value = Class.forName(\"fixture.SecondDep\").getMethod(\"value\").invoke(null); " +
                "assertEquals(((Integer) value).intValue(), 2); Executions.mark(\"second\"); } }\n"
        );

        BuildResult baseline = run(project, baselineOutput, "testNgHost", false);
        assertDecision(baseline, ":testNgHost", "full fallback (baseline not collected yet)");
        assertComplete(baselineOutput, 1, baseline.getOutput());
        assertEquals(
            baseline.getOutput() + "\n" + readWorkerMaps(baselineOutput),
            1,
            dependencyHashes(baselineOutput, "fixture.SecondDep").size()
        );
        promote(baselineOutput, project.resolve(".affected/maps"));

        write(
            project.resolve("src/main/java/fixture/SecondDep.java"),
            "package fixture; public final class SecondDep { public static int value() { int result = 2; return result; } }\n"
        );
        clearExecuted(project);
        BuildResult exact = run(project, exactOutput, "testNgHost", false);
        assertTrue(
            readWorkerMaps(baselineOutput) + "\n" + exact.getOutput(),
            exact.getOutput().contains("[Affected] :testNgHost - exact (1 test class)")
        );
        assertEquals(setOf("first", "second"), executedTests(project));
    }

    @Test(timeout = 360_000L)
    public void oneRootPlatformJunit4AndTestNgIsolateExactSelection() throws Exception {
        Path project = temporary.newFolder("multi-runner-project").toPath();
        Path platformBaseline = temporary.newFolder("multi-runner-platform-baseline").toPath();
        Path junit4Baseline = temporary.newFolder("multi-runner-junit4-baseline").toPath();
        Path testngBaseline = temporary.newFolder("multi-runner-testng-baseline").toPath();
        Path platformExact = temporary.newFolder("multi-runner-platform-exact").toPath();
        Path junit4Exact = temporary.newFolder("multi-runner-junit4-exact").toPath();
        Path testngExact = temporary.newFolder("multi-runner-testng-exact").toPath();
        write(
            project.resolve("settings.gradle"),
            "rootProject.name = 'multi-runner'\n"
        );
        write(
            project.resolve("build.gradle"),
            "plugins { id 'java' }\n" +
                "repositories { mavenCentral() }\n" +
                "dependencies {\n" +
                "    testImplementation 'org.junit.jupiter:junit-jupiter-api:5.14.4'\n" +
                "    testRuntimeOnly 'org.junit.jupiter:junit-jupiter-engine:5.14.4'\n" +
                "    testRuntimeOnly 'org.junit.vintage:junit-vintage-engine:5.14.4'\n" +
                "    testRuntimeOnly 'org.junit.platform:junit-platform-launcher:1.14.4'\n" +
                "    testImplementation 'junit:junit:4.13.2'\n" +
                "    testImplementation 'org.testng:testng:7.11.0'\n" +
                "}\n" +
                "tasks.register('testPlatform', Test) {\n" +
                "    testClassesDirs = sourceSets.test.output.classesDirs\n" +
                "    classpath = sourceSets.test.runtimeClasspath\n" +
                "    useJUnitPlatform()\n" +
                "    include '**/PlatformHostTest.class'\n" +
                "    include '**/VintagePlatformTest.class'\n" +
                "    systemProperty 'fixture.executed', file('executed').absolutePath\n" +
                "}\n" +
                "tasks.register('testJunit4Host', Test) {\n" +
                "    testClassesDirs = sourceSets.test.output.classesDirs\n" +
                "    classpath = sourceSets.test.runtimeClasspath\n" +
                "    useJUnit()\n" +
                "    include '**/Junit4HostTest.class'\n" +
                "    systemProperty 'fixture.executed', file('executed').absolutePath\n" +
                "}\n" +
                "tasks.register('testNgHost', Test) {\n" +
                "    testClassesDirs = sourceSets.test.output.classesDirs\n" +
                "    classpath = sourceSets.test.runtimeClasspath\n" +
                "    useTestNG()\n" +
                "    include '**/NgHostTest.class'\n" +
                "    systemProperty 'fixture.executed', file('executed').absolutePath\n" +
                "}\n"
        );
        write(
            project.resolve("src/main/java/fixture/PlatformDep.java"),
            "package fixture; public final class PlatformDep { public static int value() { return 1; } }\n"
        );
        write(
            project.resolve("src/main/java/fixture/VintageDep.java"),
            "package fixture; public final class VintageDep { public static int value() { return 2; } }\n"
        );
        write(
            project.resolve("src/main/java/fixture/Junit4Dep.java"),
            "package fixture; public final class Junit4Dep { public static int value() { return 3; } }\n"
        );
        write(
            project.resolve("src/main/java/fixture/NgDep.java"),
            "package fixture; public final class NgDep { public static int value() { return 4; } }\n"
        );
        write(
            project.resolve("src/test/java/fixture/Executions.java"),
            "package fixture; import java.nio.charset.StandardCharsets; import java.nio.file.*; " +
                "public final class Executions { public static void mark(String name) throws Exception { " +
                "Path root = Paths.get(System.getProperty(\"fixture.executed\")); Files.createDirectories(root); " +
                "Files.write(root.resolve(name), name.getBytes(StandardCharsets.UTF_8)); } }\n"
        );
        write(
            project.resolve("src/test/java/fixture/PlatformHostTest.java"),
            "package fixture; import org.junit.jupiter.api.Test; import static org.junit.jupiter.api.Assertions.*; " +
                "public final class PlatformHostTest { @Test void platform() throws Exception { " +
                "assertEquals(1, PlatformDep.value()); Executions.mark(\"PlatformHostTest\"); } }\n"
        );
        write(
            project.resolve("src/test/java/fixture/VintagePlatformTest.java"),
            "package fixture; import org.junit.Test; import static org.junit.Assert.*; " +
                "public final class VintagePlatformTest { @Test public void vintage() throws Exception { " +
                "assertEquals(2, VintageDep.value()); Executions.mark(\"VintagePlatformTest\"); } }\n"
        );
        write(
            project.resolve("src/test/java/fixture/Junit4HostTest.java"),
            "package fixture; import org.junit.Test; import static org.junit.Assert.*; " +
                "public final class Junit4HostTest { @Test public void junit4() throws Exception { " +
                "assertEquals(3, Junit4Dep.value()); Executions.mark(\"Junit4HostTest\"); } }\n"
        );
        write(
            project.resolve("src/test/java/fixture/NgHostTest.java"),
            "package fixture; import org.testng.annotations.Test; import static org.testng.Assert.*; " +
                "public final class NgHostTest { @Test public void ng() throws Exception { " +
                "assertEquals(NgDep.value(), 4); Executions.mark(\"NgHostTest\"); } }\n"
        );

        BuildResult platformBaselineResult = run(project, platformBaseline, "testPlatform", false);
        assertDecision(platformBaselineResult, ":testPlatform", "full fallback (baseline not collected yet)");
        assertComplete(platformBaseline, 2, platformBaselineResult.getOutput());
        promote(platformBaseline, project.resolve(".affected/maps"));

        BuildResult junit4BaselineResult = run(project, junit4Baseline, "testJunit4Host", false);
        assertDecision(junit4BaselineResult, ":testJunit4Host", "full fallback (baseline not collected yet)");
        assertComplete(junit4Baseline, 1, junit4BaselineResult.getOutput());
        promote(junit4Baseline, project.resolve(".affected/maps"));

        BuildResult testngBaselineResult = run(project, testngBaseline, "testNgHost", false);
        assertDecision(testngBaselineResult, ":testNgHost", "full fallback (baseline not collected yet)");
        assertComplete(testngBaseline, 1, testngBaselineResult.getOutput());
        promote(testngBaseline, project.resolve(".affected/maps"));

        write(
            project.resolve("src/main/java/fixture/NgDep.java"),
            "package fixture; public final class NgDep { public static int value() { int result = 4; return result; } }\n"
        );
        clearExecuted(project);
        BuildResult platformAfterNg = run(project, platformExact, "testPlatform", false);
        assertDecision(platformAfterNg, ":testPlatform", "proven-empty");
        assertEquals(TaskOutcome.SKIPPED, platformAfterNg.task(":testPlatform").getOutcome());
        BuildResult junit4AfterNg = run(project, junit4Exact, "testJunit4Host", false);
        assertDecision(junit4AfterNg, ":testJunit4Host", "proven-empty");
        assertEquals(TaskOutcome.SKIPPED, junit4AfterNg.task(":testJunit4Host").getOutcome());
        BuildResult testngAfterNg = run(project, testngExact, "testNgHost", false);
        assertDecision(testngAfterNg, ":testNgHost", "exact (1 test class)");
        assertEquals(setOf("NgHostTest"), executedTests(project));
        promote(testngExact, project.resolve(".affected/maps"));

        Path platformAfterJunit4Out = temporary.newFolder("multi-runner-platform-junit4").toPath();
        Path junit4AfterJunit4Out = temporary.newFolder("multi-runner-junit4-junit4").toPath();
        Path testngAfterJunit4Out = temporary.newFolder("multi-runner-testng-junit4").toPath();
        write(
            project.resolve("src/main/java/fixture/Junit4Dep.java"),
            "package fixture; public final class Junit4Dep { public static int value() { int result = 3; return result; } }\n"
        );
        clearExecuted(project);
        assertDecision(run(project, platformAfterJunit4Out, "testPlatform", false), ":testPlatform", "proven-empty");
        BuildResult junit4AfterJunit4 = run(project, junit4AfterJunit4Out, "testJunit4Host", false);
        assertDecision(junit4AfterJunit4, ":testJunit4Host", "exact (1 test class)");
        assertDecision(run(project, testngAfterJunit4Out, "testNgHost", false), ":testNgHost", "proven-empty");
        assertEquals(setOf("Junit4HostTest"), executedTests(project));
        promote(junit4AfterJunit4Out, project.resolve(".affected/maps"));

        Path platformAfterPlatformOut = temporary.newFolder("multi-runner-platform-platform").toPath();
        Path junit4AfterPlatformOut = temporary.newFolder("multi-runner-junit4-platform").toPath();
        Path testngAfterPlatformOut = temporary.newFolder("multi-runner-testng-platform").toPath();
        write(
            project.resolve("src/main/java/fixture/PlatformDep.java"),
            "package fixture; public final class PlatformDep { public static int value() { int result = 1; return result; } }\n"
        );
        clearExecuted(project);
        BuildResult platformAfterPlatform = run(project, platformAfterPlatformOut, "testPlatform", false);
        assertDecision(platformAfterPlatform, ":testPlatform", "exact (1 test class)");
        assertDecision(run(project, junit4AfterPlatformOut, "testJunit4Host", false), ":testJunit4Host", "proven-empty");
        assertDecision(run(project, testngAfterPlatformOut, "testNgHost", false), ":testNgHost", "proven-empty");
        assertEquals(setOf("PlatformHostTest"), executedTests(project));
    }

    @Test(timeout = 120_000L)
    public void gradleEightSelectsExactTestClasses() throws Exception {
        Assume.assumeTrue(
            "Gradle 8.14 cannot run on this conformance JDK",
            Boolean.parseBoolean(System.getProperty("affected.test.gradle8", "true"))
        );
        Path project = temporary.newFolder("gradle-eight-project").toPath();
        Path baselineOutput = temporary.newFolder("gradle-eight-baseline-output").toPath();
        Path exactOutput = temporary.newFolder("gradle-eight-exact-output").toPath();
        writeFixture(project);

        BuildResult baseline = execute(project, baselineOutput, "testDebugUnitTest", false, "8.14.5");
        assertComplete(baselineOutput, 5, baseline.getOutput());
        promote(baselineOutput, project.resolve(".affected/maps"));
        writeAlpha(project, "int result = 1; return result;");
        clearExecuted(project);

        BuildResult exact = execute(project, exactOutput, "testDebugUnitTest", false, "8.14.5");

        assertComplete(exactOutput, 3, false, exact.getOutput());
        assertEquals(setOf("AlphaTest", "GammaTest", "VintageAlphaTest"), executedTests(project));
    }

    @Test(timeout = 120_000L)
    public void parallelClassesInOneWorkerKeepIndependentDependencies() throws Exception {
        Path project = temporary.newFolder("parallel-project").toPath();
        Path baselineOutput = temporary.newFolder("parallel-baseline-output").toPath();
        Path exactOutput = temporary.newFolder("parallel-exact-output").toPath();
        writeFixture(project);

        BuildResult baseline = run(
            project,
            baselineOutput,
            "testDebugUnitTest",
            true,
            "-PattributionParallel=true"
        );
        assertComplete(baselineOutput, 5, baseline.getOutput());
        promote(baselineOutput, project.resolve(".affected/maps"));
        writeBeta(project, "int result = 2; return result;");
        clearExecuted(project);

        BuildResult exact = run(
            project,
            exactOutput,
            "testDebugUnitTest",
            true,
            "-PattributionParallel=true"
        );

        assertComplete(exactOutput, 2, false, exact.getOutput());
        assertEquals(setOf("BetaTest", "DeltaTest"), executedTests(project));
    }

    @Test(timeout = 120_000L)
    public void symlinkedTestClassesFallBackToTheFullTask() throws Exception {
        Path project = temporary.newFolder("symlink-project").toPath();
        Path baselineOutput = temporary.newFolder("symlink-baseline-output").toPath();
        Path fallbackOutput = temporary.newFolder("symlink-fallback-output").toPath();
        writeFixture(project);

        BuildResult baseline = run(project, baselineOutput);
        assertComplete(baselineOutput, 5, baseline.getOutput());
        promote(baselineOutput, project.resolve(".affected/maps"));
        Path link = project.resolve("test-classes-link");
        PlatformCapabilities.createSymbolicLink(link, project.resolve("build/classes/java/test"));
        Path alphaTest = project.resolve("src/test/java/fixture/AlphaTest.java");
        write(alphaTest, read(alphaTest).replace("Executions.mark(\"AlphaTest\")", "Executions.mark(\"AlphaTest\"); int changed = 1"));
        clearExecuted(project);

        BuildResult fallback = run(
            project,
            fallbackOutput,
            "testDebugUnitTest",
            true,
            "-PsymlinkTests=true"
        );

        assertDecision(fallback, ":testDebugUnitTest", "full fallback (collector error)");
        assertEquals(TaskOutcome.SUCCESS, fallback.task(":testDebugUnitTest").getOutcome());
        assertEquals(
            setOf("AlphaTest", "BetaTest", "GammaTest", "DeltaTest", "VintageAlphaTest"),
            executedTests(project)
        );
        assertFallback(fallbackOutput);
    }

    private static void assertDecision(BuildResult result, String task, String decision) {
        String expected = "[Affected] " + task + " - " + decision;
        assertTrue(result.getOutput(), result.getOutput().contains(expected));
        assertEquals(result.getOutput(), 1, occurrences(result.getOutput(), expected));
    }

    private static int occurrences(String value, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = value.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }

    private BuildResult run(Path project, Path output) throws Exception {
        return run(project, output, "testDebugUnitTest", true);
    }

    private BuildResult run(
        Path project,
        Path output,
        String task,
        boolean configurationCache,
        String... additionalArguments
    ) throws Exception {
        return execute(project, output, task, configurationCache, null, additionalArguments);
    }

    private BuildResult execute(
        Path project,
        Path output,
        String task,
        boolean configurationCache,
        String gradleVersion,
        String... additionalArguments
    ) throws Exception {
        java.util.ArrayList<String> arguments = new java.util.ArrayList<String>();
        arguments.add(task);
        if (configurationCache) arguments.add("--configuration-cache");
        arguments.add("--max-workers=4");
        arguments.add("--init-script");
        arguments.add(required("affected.test.initScript"));
        arguments.add("-Daffected.collector.agent=" + required("affected.smoke.agent"));
        arguments.add("-Daffected.collector.listener=" + required("affected.test.listener"));
        arguments.add("-Daffected.collector.output=" + output);
        arguments.add("-Daffected.collector.maps=" + project.resolve(".affected/maps"));
        arguments.add("-Daffected.collector.version=" + collectorVersion());
        java.util.Collections.addAll(arguments, additionalArguments);
        GradleRunner runner = GradleRunner.create()
            .withProjectDir(project.toFile())
            .withArguments(arguments);
        if (gradleVersion != null) runner.withGradleVersion(gradleVersion);
        long started = System.nanoTime();
        BuildResult result = runBounded(GRADLE_HANG_BUDGET_MILLIS, runner::build);
        long elapsed = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        System.out.println("[Affected conformance] gradle " + task + " " + elapsed + "ms");
        return result;
    }

    private static <T> T runBounded(long budgetMillis, java.util.concurrent.Callable<T> work) throws Exception {
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "affected-gradle-budget");
            thread.setDaemon(true);
            return thread;
        });
        java.util.concurrent.Future<T> future = pool.submit(work);
        try {
            return future.get(budgetMillis, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException timeout) {
            future.cancel(true);
            throw new IllegalStateException("Gradle invocation exceeded " + budgetMillis + "ms hang budget", timeout);
        } finally {
            pool.shutdownNow();
        }
    }

    private static void assertComplete(Path output, int tests, String buildOutput) throws Exception {
        assertComplete(output, tests, true, buildOutput);
    }

    private static void assertComplete(Path output, int tests, boolean allTests, String buildOutput) throws Exception {
        Path task;
        try (Stream<Path> files = Files.list(output)) {
            List<Path> tasks = files.collect(Collectors.toList());
            assertEquals(1, tasks.size());
            task = tasks.get(0);
        }
        assertTrue(task.getFileName().toString().matches("task-[0-9a-f]{64}"));
        assertTrue(buildOutput, Files.isRegularFile(task.resolve("task.manifest")));
        assertTrue(read(task.resolve("task.manifest")).endsWith("all=" + allTests + "\n"));
        assertTrue(read(task.resolve("catalog.manifest")).contains("artifact="));
        assertEquals(
            buildOutput,
            tests,
            Stream.of(read(task.resolve("expected.manifest")).split("\n"))
                .filter(line -> line.startsWith("test="))
                .count()
        );
        int maps = 0;
        int workers = 0;
        try (Stream<Path> files = Files.list(task)) {
            for (Path worker : files.filter(Files::isDirectory).collect(Collectors.toList())) {
                workers++;
                assertTrue(Files.isRegularFile(worker.resolve("started.manifest")));
                assertTrue(read(worker.resolve("complete.manifest")).contains("supported=true"));
                try (Stream<Path> outputs = Files.list(worker)) {
                    maps += outputs.filter(path -> path.getFileName().toString().endsWith(".map")).count();
                }
            }
        }
        assertTrue(buildOutput, workers >= 1);
        assertEquals(buildOutput, tests, maps);
    }

    private static String manifestValue(Path output, String prefix) throws Exception {
        for (String line : readTaskManifest(output).split("\n")) {
            if (line.startsWith(prefix)) return line;
        }
        throw new AssertionError(prefix);
    }

    private static String readTaskManifest(Path output) throws Exception {
        try (Stream<Path> files = Files.list(output)) {
            Path task = files.findFirst().orElseThrow(AssertionError::new);
            return read(task.resolve("task.manifest"));
        }
    }

    private static String readWorkerMaps(Path output) throws Exception {
        StringBuilder text = new StringBuilder();
        try (Stream<Path> tasks = Files.list(output)) {
            for (Path task : tasks.collect(Collectors.toList())) {
                try (Stream<Path> children = Files.walk(task)) {
                    for (Path file : children.filter(path -> path.getFileName().toString().endsWith(".map"))
                        .collect(Collectors.toList())) {
                        text.append(file).append('\n').append(read(file)).append('\n');
                    }
                }
            }
        }
        return text.toString();
    }

    private static void assertFallback(Path output) throws Exception {
        Path task;
        try (Stream<Path> files = Files.list(output)) {
            task = files.findFirst().orElseThrow(AssertionError::new);
        }
        assertTrue(read(task.resolve("expected.manifest")).contains("supported=true"));
        try (Stream<Path> files = Files.list(task)) {
            assertEquals(0, files.filter(Files::isDirectory).count());
        }
    }

    private static void assertOutputEmpty(Path output) throws Exception {
        try (Stream<Path> files = Files.list(output)) {
            assertEquals(0, files.count());
        }
    }

    private static Path onlyFile(Path directory) throws Exception {
        try (Stream<Path> files = Files.list(directory)) {
            List<Path> result = files.collect(Collectors.toList());
            assertEquals(1, result.size());
            return result.get(0);
        }
    }

    private static Set<String> executedTests(Path project) throws Exception {
        Path executed = project.resolve("executed");
        if (!Files.isDirectory(executed)) return Collections.emptySet();
        try (Stream<Path> files = Files.list(executed)) {
            return files.map(path -> path.getFileName().toString()).collect(Collectors.toSet());
        }
    }

    private static void clearExecuted(Path project) throws Exception {
        Path executed = project.resolve("executed");
        if (!Files.isDirectory(executed)) return;
        try (Stream<Path> files = Files.list(executed)) {
            for (Path file : files.collect(Collectors.toList())) Files.delete(file);
        }
    }

    private static Set<String> setOf(String... values) {
        return new HashSet<String>(Arrays.asList(values));
    }

    private static void promote(Path output, Path maps) throws Exception {
        Path task;
        try (Stream<Path> files = Files.list(output)) {
            task = files.findFirst().orElseThrow(AssertionError::new);
        }
        Map<String, String> manifest = values(task.resolve("task.manifest"));
        String taskKey = decode(manifest.get("task"));
        StringBuilder payload = new StringBuilder();
        int artifactCount = 0;
        for (String line : read(task.resolve("catalog.manifest")).split("\n")) {
            if (line.startsWith("artifact=")) {
                payload.append(line).append('\n');
                artifactCount++;
            }
        }
        int recordCount = 0;
        try (Stream<Path> workers = Files.list(task)) {
            for (Path worker : workers.filter(Files::isDirectory).collect(Collectors.toList())) {
                try (Stream<Path> files = Files.list(worker)) {
                    for (Path map : files.filter(path -> path.getFileName().toString().endsWith(".map"))
                        .collect(Collectors.toList())) {
                        List<String> lines = Arrays.asList(read(map).split("\n"));
                        String test = lines.stream()
                            .filter(line -> line.startsWith("test="))
                            .findFirst()
                            .orElseThrow(AssertionError::new)
                            .substring("test=".length());
                        payload.append("record=").append(test).append('|')
                            .append(lines.stream()
                                .filter(line -> line.startsWith("dependency="))
                                .map(line -> line.substring("dependency=".length()))
                            .collect(Collectors.joining(";")))
                            .append('\n');
                        recordCount++;
                    }
                }
            }
        }
        StringBuilder content = new StringBuilder("format=1\n")
            .append("schema=4\n")
            .append("collector=").append(encode(collectorVersion())).append('\n')
            .append("task=").append(manifest.get("task")).append('\n')
            .append("runtime=").append(manifest.get("runtime")).append('\n')
            .append("input=").append(manifest.get("input")).append('\n')
            .append("run=").append(encode("fixture-baseline")).append('\n')
            .append("artifacts=").append(artifactCount).append('\n')
            .append("records=").append(recordCount).append('\n')
            .append("checksum=").append(sha256(payload.toString())).append('\n')
            .append(payload);
        Files.createDirectories(maps);
        Files.write(
            maps.resolve("map-" + sha256(taskKey) + ".map"),
            content.toString().getBytes(StandardCharsets.UTF_8)
        );
    }

    private static Map<String, String> values(Path manifest) throws Exception {
        Map<String, String> values = new LinkedHashMap<String, String>();
        for (String line : read(manifest).split("\n")) {
            int separator = line.indexOf('=');
            if (separator > 0) values.put(line.substring(0, separator), line.substring(separator + 1));
        }
        return values;
    }

    private static String decode(String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder(digest.length * 2);
        for (byte item : digest) result.append(String.format(java.util.Locale.ROOT, "%02x", item & 0xff));
        return result.toString();
    }

    private static Set<String> dependencyHashes(Path output, String className) throws Exception {
        Set<String> hashes = new HashSet<String>();
        String encodedClass = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(className.getBytes(StandardCharsets.UTF_8));
        Path task;
        try (Stream<Path> files = Files.list(output)) {
            task = files.findFirst().orElseThrow(AssertionError::new);
        }
        try (Stream<Path> workers = Files.list(task)) {
            for (Path worker : workers.filter(Files::isDirectory).collect(Collectors.toList())) {
                try (Stream<Path> files = Files.list(worker)) {
                    for (Path map : files.filter(path -> path.getFileName().toString().endsWith(".map"))
                        .collect(Collectors.toList())) {
                        for (String line : read(map).split("\n")) {
                            if (!line.startsWith("dependency=" + encodedClass + "|")) continue;
                            hashes.add(line.substring(line.lastIndexOf('|') + 1));
                        }
                    }
                }
            }
        }
        assertEquals(1, hashes.size());
        return hashes;
    }

    private static void writeFixture(Path project) throws Exception {
        write(project.resolve("settings.gradle"), "rootProject.name = 'fixture'\n");
        write(
            project.resolve("build.gradle"),
            "plugins {\n" +
                "    id 'java'\n" +
                "    id 'jacoco'\n" +
                "}\n" +
                "repositories { mavenCentral() }\n" +
                "dependencies {\n" +
                "    testImplementation 'org.junit.jupiter:junit-jupiter-api:5.14.4'\n" +
                "    testRuntimeOnly 'org.junit.jupiter:junit-jupiter-engine:5.14.4'\n" +
                "    testRuntimeOnly 'org.junit.vintage:junit-vintage-engine:5.14.4'\n" +
                "    testRuntimeOnly 'org.junit.platform:junit-platform-launcher:1.14.4'\n" +
                "    testImplementation 'junit:junit:4.13.2'\n" +
                "}\n" +
                "tasks.register('testDebugUnitTest', Test) {\n" +
                "    testClassesDirs = sourceSets.test.output.classesDirs\n" +
                "    classpath = sourceSets.test.runtimeClasspath\n" +
                "    useJUnitPlatform { if (!project.hasProperty('includeSlow')) excludeTags 'slow' }\n" +
                "    if (project.hasProperty('symlinkTests')) testClassesDirs = files(file('test-classes-link'))\n" +
                "    exclude '**/LegacyTest.class'\n" +
                "    systemProperty 'fixture.executed', file('executed').absolutePath\n" +
                "    maxParallelForks = 2\n" +
                "    forkEvery = 1\n" +
                "    if (project.hasProperty('attributionParallel')) {\n" +
                "        maxParallelForks = 1\n" +
                "        forkEvery = 0\n" +
                "        systemProperty 'junit.jupiter.execution.parallel.enabled', 'true'\n" +
                "        systemProperty 'junit.jupiter.execution.parallel.mode.default', 'concurrent'\n" +
                "        systemProperty 'junit.jupiter.execution.parallel.mode.classes.default', 'concurrent'\n" +
                "        systemProperty 'junit.jupiter.execution.parallel.config.strategy', 'fixed'\n" +
                "        systemProperty 'junit.jupiter.execution.parallel.config.fixed.parallelism', '4'\n" +
                "    }\n" +
                "}\n" +
                "tasks.register('testAndroidHostTest', Test) {\n" +
                "    testClassesDirs = sourceSets.test.output.classesDirs\n" +
                "    classpath = sourceSets.test.runtimeClasspath\n" +
                "    useJUnit()\n" +
                "    include '**/LegacyTest.class'\n" +
                "    systemProperty 'fixture.executed', file('executed').absolutePath\n" +
                "}\n"
        );
        writeAlpha(project, "return 1;");
        write(
            project.resolve("src/main/java/fixture/Beta.java"),
            "package fixture; public final class Beta { public static int value() { return 2; } }\n"
        );
        write(
            project.resolve("src/test/java/fixture/Executions.java"),
            "package fixture; import java.nio.charset.StandardCharsets; import java.nio.file.*; " +
                "public final class Executions { public static void mark(String name) throws Exception { " +
                "Path root = Paths.get(System.getProperty(\"fixture.executed\")); Files.createDirectories(root); " +
                "Files.write(root.resolve(name), name.getBytes(StandardCharsets.UTF_8)); } }\n"
        );
        write(
            project.resolve("src/test/java/fixture/AlphaTest.java"),
            "package fixture; import org.junit.jupiter.api.Test; import static org.junit.jupiter.api.Assertions.*; " +
                "public final class AlphaTest { @Test void alpha() throws Exception { " +
                "assertEquals(1, Alpha.value()); Executions.mark(\"AlphaTest\"); } }\n"
        );
        write(
            project.resolve("src/test/java/fixture/BetaTest.java"),
            "package fixture; import org.junit.jupiter.api.Test; import static org.junit.jupiter.api.Assertions.*; " +
                "public final class BetaTest { @Test void beta() throws Exception { " +
                "assertEquals(2, Beta.value()); Executions.mark(\"BetaTest\"); } }\n"
        );
        write(
            project.resolve("src/test/java/fixture/GammaTest.java"),
            "package fixture; import org.junit.jupiter.api.Test; import static org.junit.jupiter.api.Assertions.*; " +
                "public final class GammaTest { @Test void gamma() throws Exception { " +
                "assertEquals(1, Alpha.value()); Executions.mark(\"GammaTest\"); } }\n"
        );
        write(
            project.resolve("src/test/java/fixture/DeltaTest.java"),
            "package fixture; import org.junit.jupiter.api.Test; import static org.junit.jupiter.api.Assertions.*; " +
                "public final class DeltaTest { @Test void delta() throws Exception { " +
                "assertEquals(2, Beta.value()); Executions.mark(\"DeltaTest\"); } }\n"
        );
        write(
            project.resolve("src/test/java/fixture/DisabledTest.java"),
            "package fixture; import org.junit.jupiter.api.Disabled; import org.junit.jupiter.api.Test; " +
                "@Disabled public final class DisabledTest { @Test void disabled() { throw new AssertionError(); } }\n"
        );
        write(
            project.resolve("src/test/java/fixture/SlowTest.java"),
            "package fixture; import org.junit.jupiter.api.Tag; import org.junit.jupiter.api.Test; " +
                "import static org.junit.jupiter.api.Assertions.*; " +
                "public final class SlowTest { @Tag(\"slow\") @Test void slow() throws Exception { " +
                "assertEquals(1, Alpha.value()); Executions.mark(\"SlowTest\"); } }\n"
        );
        write(
            project.resolve("src/test/java/fixture/VintageAlphaTest.java"),
            "package fixture; import org.junit.Test; import static org.junit.Assert.*; " +
                "public final class VintageAlphaTest { @Test public void vintage() throws Exception { " +
                "assertEquals(1, Alpha.value()); Executions.mark(\"VintageAlphaTest\"); } }\n"
        );
        write(
            project.resolve("src/test/java/fixture/LegacyTest.java"),
            "package fixture; import org.junit.Test; import static org.junit.Assert.*; " +
                "public final class LegacyTest { @Test public void legacy() throws Exception { " +
                "assertEquals(1, Alpha.value()); Executions.mark(\"LegacyTest\"); } }\n"
        );
    }

    private static void writeAlpha(Path project, String body) throws Exception {
        write(
            project.resolve("src/main/java/fixture/Alpha.java"),
            "package fixture; public final class Alpha { public static int value() { " + body + " } }\n"
        );
    }

    private static void writeBeta(Path project, String body) throws Exception {
        write(
            project.resolve("src/main/java/fixture/Beta.java"),
            "package fixture; public final class Beta { public static int value() { " + body + " } }\n"
        );
    }

    private static void write(Path path, String content) throws Exception {
        Files.createDirectories(path.getParent());
        Files.write(path, content.getBytes(StandardCharsets.UTF_8));
    }

    private static void copyPublicFixture(String name, Path destination) throws Exception {
        Path repository = requiredDirectory("affected.test.repositoryRoot").toRealPath();
        Path source = repository.resolve("conformance/cli-fixtures").resolve(name).normalize();
        if (!source.startsWith(repository) || !Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS) ||
            Files.isSymbolicLink(source) || !Files.isReadable(source)) {
            throw new IllegalStateException("Invalid public fixture: " + source);
        }
        try (Stream<Path> paths = Files.walk(source)) {
            for (Path path : paths.sorted().collect(Collectors.toList())) {
                if (Files.isSymbolicLink(path)) throw new IllegalStateException("Fixture link: " + path);
                Path target = destination.resolve(source.relativize(path).toString()).normalize();
                if (!target.startsWith(destination)) throw new IllegalStateException("Fixture path: " + path);
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectories(target);
                } else if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && Files.isReadable(path)) {
                    Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING);
                } else {
                    throw new IllegalStateException("Unreadable fixture entry: " + path);
                }
            }
        }
    }

    private static String read(Path path) throws Exception {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static String required(String property) {
        String value = System.getProperty(property);
        if (value == null || value.trim().isEmpty()) throw new IllegalStateException(property);
        File file = new File(value);
        if (!file.isFile()) throw new IllegalStateException(file.toString());
        return file.getAbsolutePath();
    }

    private static Path requiredDirectory(String property) {
        String value = System.getProperty(property);
        if (value == null || value.trim().isEmpty()) throw new IllegalStateException(property);
        Path path = new File(value).toPath().toAbsolutePath().normalize();
        if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) ||
            !Files.isReadable(path)) {
            throw new IllegalStateException(path.toString());
        }
        return path;
    }

    private static String collectorVersion() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String property : Arrays.asList(
                "affected.smoke.agent",
                "affected.test.listener",
                "affected.test.initScript"
            )) {
                digest.update(Files.readAllBytes(new File(required(property)).toPath()));
            }
            StringBuilder result = new StringBuilder();
            for (byte item : digest.digest()) result.append(String.format(java.util.Locale.ROOT, "%02x", item & 0xff));
            return result.toString();
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }
}
