package com.aspix2k.affected.collector;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class GradleInjectionTest {
    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @Test(timeout = 120_000L)
    public void realGradleWorkersProduceCompleteMapsAndReuseConfigurationCache() throws Exception {
        Path project = temporary.newFolder("project").toPath();
        writeFixture(project);
        Path firstOutput = temporary.newFolder("first-output").toPath();
        Path secondOutput = temporary.newFolder("second-output").toPath();
        Path taggedOutput = temporary.newFolder("tagged-output").toPath();
        Path selectedOutput = temporary.newFolder("selected-output").toPath();
        Path legacyOutput = temporary.newFolder("legacy-output").toPath();

        BuildResult first = run(project, firstOutput);
        writeAlpha(project, "int result = 1; return result;");
        BuildResult second = run(project, secondOutput);
        BuildResult tagged = run(project, taggedOutput, "testDebugUnitTest", true, "-PincludeSlow=true");
        BuildResult selected = run(
            project,
            selectedOutput,
            "testDebugUnitTest",
            true,
            "--tests",
            "fixture.AlphaTest"
        );
        run(project, legacyOutput, "legacyTest", false);

        assertComplete(firstOutput, 4, first.getOutput());
        assertComplete(secondOutput, 4, second.getOutput());
        assertComplete(taggedOutput, 5, tagged.getOutput());
        assertComplete(selectedOutput, 1, false, selected.getOutput());
        assertEquals(manifestValue(firstOutput, "input="), manifestValue(secondOutput, "input="));
        assertEquals(manifestValue(firstOutput, "runtime="), manifestValue(secondOutput, "runtime="));
        assertNotEquals(manifestValue(firstOutput, "runtime="), manifestValue(taggedOutput, "runtime="));
        assertNotEquals(dependencyHashes(firstOutput, "fixture.Alpha"), dependencyHashes(secondOutput, "fixture.Alpha"));
        assertTrue(selected.getOutput(), readTaskManifest(selectedOutput).endsWith("all=false\n"));
        assertFallback(legacyOutput);
        assertTrue(second.getOutput(), second.getOutput().contains("Reusing configuration cache"));
    }

    @Test(timeout = 120_000L)
    public void gradleEightProducesCompleteMaps() throws Exception {
        Path project = temporary.newFolder("gradle-eight-project").toPath();
        Path output = temporary.newFolder("gradle-eight-output").toPath();
        writeFixture(project);

        BuildResult result = execute(project, output, "testDebugUnitTest", false, "8.14.3");

        assertComplete(output, 4, result.getOutput());
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
        arguments.add("--info");
        arguments.add("--init-script");
        arguments.add(required("affected.test.initScript"));
        arguments.add("-Daffected.collector.agent=" + required("affected.smoke.agent"));
        arguments.add("-Daffected.collector.listener=" + required("affected.test.listener"));
        arguments.add("-Daffected.collector.output=" + output);
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
        assertTrue(read(task.resolve("task.manifest")).endsWith("all=" + allTests + "\n"));
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
                "    testImplementation 'org.junit.jupiter:junit-jupiter-api:5.11.0'\n" +
                "    testRuntimeOnly 'org.junit.jupiter:junit-jupiter-engine:5.11.0'\n" +
                "    testRuntimeOnly 'org.junit.platform:junit-platform-launcher:1.11.0'\n" +
                "    testImplementation 'junit:junit:4.13.2'\n" +
                "}\n" +
                "tasks.register('testDebugUnitTest', Test) {\n" +
                "    testClassesDirs = sourceSets.test.output.classesDirs\n" +
                "    classpath = sourceSets.test.runtimeClasspath\n" +
                "    useJUnitPlatform { if (!project.hasProperty('includeSlow')) excludeTags 'slow' }\n" +
                "    exclude '**/LegacyTest.class'\n" +
                "    maxParallelForks = 2\n" +
                "    forkEvery = 1\n" +
                "}\n" +
                "tasks.register('legacyTest', Test) {\n" +
                "    testClassesDirs = sourceSets.test.output.classesDirs\n" +
                "    classpath = sourceSets.test.runtimeClasspath\n" +
                "    include '**/LegacyTest.class'\n" +
                "}\n"
        );
        writeAlpha(project, "return 1;");
        write(
            project.resolve("src/main/java/fixture/Beta.java"),
            "package fixture; public final class Beta { public static int value() { return 2; } }\n"
        );
        write(
            project.resolve("src/test/java/fixture/AlphaTest.java"),
            "package fixture; import org.junit.jupiter.api.Test; import static org.junit.jupiter.api.Assertions.*; " +
                "public final class AlphaTest { @Test void alpha() { assertEquals(1, Alpha.value()); } }\n"
        );
        write(
            project.resolve("src/test/java/fixture/BetaTest.java"),
            "package fixture; import org.junit.jupiter.api.Test; import static org.junit.jupiter.api.Assertions.*; " +
                "public final class BetaTest { @Test void beta() { assertEquals(2, Beta.value()); } }\n"
        );
        write(
            project.resolve("src/test/java/fixture/GammaTest.java"),
            "package fixture; import org.junit.jupiter.api.Test; import static org.junit.jupiter.api.Assertions.*; " +
                "public final class GammaTest { @Test void gamma() { assertEquals(1, Alpha.value()); } }\n"
        );
        write(
            project.resolve("src/test/java/fixture/DeltaTest.java"),
            "package fixture; import org.junit.jupiter.api.Test; import static org.junit.jupiter.api.Assertions.*; " +
                "public final class DeltaTest { @Test void delta() { assertEquals(2, Beta.value()); } }\n"
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
                "public final class SlowTest { @Tag(\"slow\") @Test void slow() { assertEquals(1, Alpha.value()); } }\n"
        );
        write(
            project.resolve("src/test/java/fixture/LegacyTest.java"),
            "package fixture; import org.junit.Test; import static org.junit.Assert.*; " +
                "public final class LegacyTest { @Test public void legacy() { assertEquals(1, Alpha.value()); } }\n"
        );
    }

    private static void writeAlpha(Path project, String body) throws Exception {
        write(
            project.resolve("src/main/java/fixture/Alpha.java"),
            "package fixture; public final class Alpha { public static int value() { " + body + " } }\n"
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
}
