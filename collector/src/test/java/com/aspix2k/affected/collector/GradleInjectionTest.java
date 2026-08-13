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
import java.nio.file.Path;
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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class GradleInjectionTest {
    private static final long COMPLETE_MAP_SCENARIO_TIMEOUT_MILLIS = 240_000L;

    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

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
        BuildResult legacy = run(project, legacyOutput, "legacyTest", false);
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
        assertDecision(legacy, ":legacyTest", "full fallback (unsupported framework)");
        assertDecision(stale, ":testDebugUnitTest", "full fallback (baseline stale)");
        assertDecision(corrupt, ":testDebugUnitTest", "full fallback (baseline corrupt)");
        assertComplete(taggedOutput, 6, tagged.getOutput());
        assertComplete(selectedOutput, 1, false, selected.getOutput());
        assertEquals(manifestValue(firstOutput, "input="), manifestValue(exactOutput, "input="));
        assertEquals(manifestValue(firstOutput, "runtime="), manifestValue(exactOutput, "runtime="));
        assertNotEquals(manifestValue(firstOutput, "runtime="), manifestValue(taggedOutput, "runtime="));
        assertNotEquals(dependencyHashes(firstOutput, "fixture.Alpha"), dependencyHashes(exactOutput, "fixture.Alpha"));
        assertTrue(selected.getOutput(), readTaskManifest(selectedOutput).endsWith("all=false\n"));
        assertFallback(legacyOutput);
        assertTrue(unchanged.getOutput(), unchanged.getOutput().contains("Reusing configuration cache"));
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

    private BuildResult run(Path project, Path output) {
        return run(project, output, "testDebugUnitTest", true);
    }

    private BuildResult run(
        Path project,
        Path output,
        String task,
        boolean configurationCache,
        String... additionalArguments
    ) {
        return execute(project, output, task, configurationCache, null, additionalArguments);
    }

    private BuildResult execute(
        Path project,
        Path output,
        String task,
        boolean configurationCache,
        String gradleVersion,
        String... additionalArguments
    ) {
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
        return runner.build();
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
                "tasks.register('legacyTest', Test) {\n" +
                "    testClassesDirs = sourceSets.test.output.classesDirs\n" +
                "    classpath = sourceSets.test.runtimeClasspath\n" +
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
