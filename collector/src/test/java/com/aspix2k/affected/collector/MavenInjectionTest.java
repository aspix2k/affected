package com.aspix2k.affected.collector;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class MavenInjectionTest {
    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @Test(timeout = 180_000L)
    public void fullSurefireRunProducesACompleteCollectorTask() throws Exception {
        Path project = temporary.newFolder("maven project").toPath();
        Path output = temporary.newFolder("maven output").toPath();
        Path maps = temporary.newFolder("maven maps").toPath();
        writeFixture(project);

        Result result = run(project, output, maps, mavenHomes().get(mavenHomes().size() - 1));

        assertEquals(result.log, 0, result.exitCode);
        assertEquals(setOf("AlphaTest", "VintageOmegaTest", "ZetaBetaTest"), executedTests(project));
        Path task = onlyDirectory(output, result.log);
        assertTrue(result.log + fallback(output), Files.isRegularFile(task.resolve("task.manifest")));
        assertTrue(Files.isRegularFile(task.resolve("catalog.manifest")));
        assertTrue(Files.isRegularFile(task.resolve("expected.manifest")));
        Path worker = onlyDirectory(task, result.log);
        String completion = read(worker.resolve("complete.manifest"));
        assertTrue(result.log + "\n" + completion, completion.contains("supported=true"));
    }

    @Test(timeout = 180_000L)
    public void firstMiddleAndLastProductionClassesRunOnlyTheirDependentTests() throws Exception {
        for (Path mavenHome : mavenHomes()) {
            exactScenario(mavenHome, "Alpha", "AlphaTest", 1);
            exactScenario(mavenHome, "Omega", "VintageOmegaTest", 3);
            exactScenario(mavenHome, "Beta", "ZetaBetaTest", 2);
        }
    }

    @Test(timeout = 180_000L)
    public void sharedProductionClassRunsAllAndOnlyItsDependentTests() throws Exception {
        Path project = temporary.newFolder("shared project").toPath();
        Path fullOutput = temporary.newFolder("shared full output").toPath();
        Path exactOutput = temporary.newFolder("shared exact output").toPath();
        Path maps = temporary.newFolder("shared maps").toPath();
        writeFixture(project);
        write(project.resolve("src/test/java/fixture/SharedAlphaTest.java"),
            "package fixture; public class SharedAlphaTest { @org.junit.jupiter.api.Test void test() throws Exception { " +
                "org.junit.jupiter.api.Assertions.assertEquals(1, Alpha.value()); Marks.add(\"SharedAlphaTest\"); } }\n");
        Path mavenHome = mavenHomes().get(mavenHomes().size() - 1);
        Result full = run(project, fullOutput, maps, mavenHome);
        assertEquals(full.log, 0, full.exitCode);
        promote(fullOutput, maps);
        clearExecuted(project);
        write(
            project.resolve("src/main/java/fixture/Alpha.java"),
            "package fixture; public final class Alpha { public static int value() { int result = 1; return result; } }\n"
        );

        Result exact = run(project, exactOutput, maps, mavenHome);

        assertEquals(exact.log, 0, exact.exitCode);
        assertEquals(setOf("AlphaTest", "SharedAlphaTest"), executedTests(project));
        assertTrue(read(onlyDirectory(exactOutput, exact.log).resolve("task.manifest")).contains("all=false"));
    }

    @Test(timeout = 180_000L)
    public void unsupportedMavenRuntimeKeepsTheOriginalFullTestGoal() throws Exception {
        Path project = temporary.newFolder("unsupported maven project").toPath();
        Path output = temporary.newFolder("unsupported maven output").toPath();
        Path maps = temporary.newFolder("unsupported maven maps").toPath();
        writeFixture(project);

        Result result = run(project, output, maps, directory("affected.test.unsupportedMavenHome"));

        assertEquals(result.log, 0, result.exitCode);
        assertEquals(setOf("AlphaTest", "VintageOmegaTest", "ZetaBetaTest"), executedTests(project));
        try (Stream<Path> files = Files.list(output)) {
            assertEquals(result.log, 0, files.count());
        }
    }

    @Test(timeout = 180_000L)
    public void reactorModulesUseIndependentDependencyMaps() throws Exception {
        Path project = temporary.newFolder("reactor project").toPath();
        Path first = project.resolve("first");
        Path second = project.resolve("second");
        Path fullOutput = temporary.newFolder("reactor full output").toPath();
        Path exactOutput = temporary.newFolder("reactor exact output").toPath();
        Path maps = temporary.newFolder("reactor maps").toPath();
        write(
            project.resolve("pom.xml"),
            "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n" +
                "  <modelVersion>4.0.0</modelVersion><groupId>fixture</groupId>" +
                "<artifactId>reactor</artifactId><version>1</version><packaging>pom</packaging>\n" +
                "  <modules><module>first</module><module>second</module></modules>\n" +
                "</project>\n"
        );
        writeModuleFixture(first, "first");
        writeModuleFixture(second, "second");
        Path mavenHome = mavenHomes().get(mavenHomes().size() - 1);
        Result full = run(project, fullOutput, maps, mavenHome);
        assertEquals(full.log, 0, full.exitCode);
        assertEquals(setOf("AlphaTest", "VintageOmegaTest", "ZetaBetaTest"), executedTests(first));
        assertEquals(setOf("AlphaTest", "VintageOmegaTest", "ZetaBetaTest"), executedTests(second));
        promote(fullOutput, maps);
        clearExecuted(first);
        clearExecuted(second);
        write(
            first.resolve("src/main/java/fixture/Omega.java"),
            "package fixture; public final class Omega { public static int value() { int result = 3; return result; } }\n"
        );

        Result exact = run(project, exactOutput, maps, mavenHome);

        assertEquals(exact.log, 0, exact.exitCode);
        assertEquals(Collections.singleton("VintageOmegaTest"), executedTests(first));
        assertEquals(Collections.emptySet(), executedTests(second));
        assertEquals(exact.log, 2, directories(exactOutput).size());
    }

    private void exactScenario(Path mavenHome, String productionClass, String dependentTest, int value) throws Exception {
        String version = mavenHome.getFileName().toString();
        Path project = temporary.newFolder("exact project " + version + " " + productionClass).toPath();
        Path fullOutput = temporary.newFolder("full output " + version + " " + productionClass).toPath();
        Path exactOutput = temporary.newFolder("exact output " + version + " " + productionClass).toPath();
        Path maps = temporary.newFolder("exact maps " + version + " " + productionClass).toPath();
        writeFixture(project);
        Result full = run(project, fullOutput, maps, mavenHome);
        assertEquals(full.log, 0, full.exitCode);
        String fullManifest = read(onlyDirectory(fullOutput, full.log).resolve("task.manifest"));
        promote(fullOutput, maps);
        clearExecuted(project);
        write(
            project.resolve("src/main/java/fixture/" + productionClass + ".java"),
            "package fixture; public final class " + productionClass + " { public static int value() { " +
                "int result = " + value + "; return result; } }\n"
        );

        Result exact = run(project, exactOutput, maps, mavenHome);

        assertEquals(exact.log, 0, exact.exitCode);
        Path task = onlyDirectory(exactOutput, exact.log);
        String manifest = read(task.resolve("task.manifest"));
        assertEquals(
            exact.log + "\nfull:\n" + fullManifest + "\nexact:\n" + manifest,
            Collections.singleton(dependentTest),
            executedTests(project)
        );
        assertTrue(manifest, manifest.contains("all=false"));
        assertTrue(
            "full=" + full.durationMillis + "ms exact=" + exact.durationMillis + "ms",
            exact.durationMillis < full.durationMillis
        );
    }

    private Result run(Path project, Path output, Path maps, Path mavenHome) throws Exception {
        Path log = Files.createTempFile(temporary.getRoot().toPath(), "maven-", ".log");
        Path executable = mavenHome.resolve("bin").resolve(isWindows() ? "mvn.cmd" : "mvn");
        ProcessBuilder builder = new ProcessBuilder(
            executable.toString(),
            "-B",
            "-ntp",
            "-Dmaven.ext.class.path=" + required("affected.test.mavenExtension"),
            "-Daffected.collector.mavenAgent=" + required("affected.test.mavenAgent"),
            "-Daffected.collector.output=" + output,
            "-Daffected.collector.maps=" + maps,
            "-Daffected.collector.version=fixture-version",
            "test"
        );
        builder.directory(project.toFile());
        builder.redirectErrorStream(true);
        builder.redirectOutput(log.toFile());
        long started = System.nanoTime();
        Process process = builder.start();
        boolean finished = process.waitFor(120, TimeUnit.SECONDS);
        if (!finished) process.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
        String content = Files.exists(log) ? read(log) : "";
        assertTrue(content, finished);
        return new Result(process.exitValue(), content, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
    }

    private static void writeFixture(Path project) throws Exception {
        write(project.resolve("pom.xml"),
            "<project xmlns=\"http://maven.apache.org/POM/4.0.0\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" " +
                "xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd\">\n" +
                "  <modelVersion>4.0.0</modelVersion>\n" +
                "  <groupId>fixture</groupId><artifactId>app</artifactId><version>1</version>\n" +
                "  <properties><maven.compiler.release>8</maven.compiler.release></properties>\n" +
                "  <dependencies>\n" +
                "    <dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId><version>5.11.0</version><scope>test</scope></dependency>\n" +
                "    <dependency><groupId>org.junit.vintage</groupId><artifactId>junit-vintage-engine</artifactId><version>5.11.0</version><scope>test</scope></dependency>\n" +
                "    <dependency><groupId>junit</groupId><artifactId>junit</artifactId><version>4.13.2</version><scope>test</scope></dependency>\n" +
                "  </dependencies>\n" +
                "  <build><plugins>\n" +
                "    <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>3.13.0</version></plugin>\n" +
                "    <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-surefire-plugin</artifactId><version>3.5.4</version>\n" +
                "      <configuration><useModulePath>false</useModulePath><runOrder>alphabetical</runOrder>" +
                "<argLine>-Dfixture.argLine=preserved</argLine><systemPropertyVariables>" +
                "<fixture.executed>${project.basedir}/executed</fixture.executed>" +
                "</systemPropertyVariables></configuration>\n" +
                "    </plugin>\n" +
                "  </plugins></build>\n" +
                "</project>\n");
        write(project.resolve("src/main/java/fixture/Alpha.java"),
            "package fixture; public final class Alpha { public static int value() { return 1; } }\n");
        write(project.resolve("src/main/java/fixture/Beta.java"),
            "package fixture; public final class Beta { public static int value() { return 2; } }\n");
        write(project.resolve("src/main/java/fixture/Omega.java"),
            "package fixture; public final class Omega { public static int value() { return 3; } }\n");
        write(project.resolve("src/test/java/fixture/Marks.java"),
            "package fixture; import java.nio.file.*; final class Marks { static void add(String name) throws Exception { " +
                "Path root = Paths.get(System.getProperty(\"fixture.executed\")); Files.createDirectories(root); " +
                "Files.write(root.resolve(name), new byte[] {1}); } }\n");
        write(project.resolve("src/test/java/fixture/AlphaTest.java"),
            "package fixture; public class AlphaTest { @org.junit.jupiter.api.Test void test() throws Exception { " +
                "Thread.sleep(250); org.junit.jupiter.api.Assertions.assertEquals(\"preserved\", " +
                "System.getProperty(\"fixture.argLine\")); org.junit.jupiter.api.Assertions.assertEquals(1, Alpha.value()); " +
                "Marks.add(\"AlphaTest\"); } }\n");
        write(project.resolve("src/test/java/fixture/ZetaBetaTest.java"),
            "package fixture; public class ZetaBetaTest { @org.junit.jupiter.api.Test void test() throws Exception { " +
                "Thread.sleep(250); org.junit.jupiter.api.Assertions.assertEquals(\"preserved\", " +
                "System.getProperty(\"fixture.argLine\")); org.junit.jupiter.api.Assertions.assertEquals(2, Beta.value()); " +
                "Marks.add(\"ZetaBetaTest\"); } }\n");
        write(project.resolve("src/test/java/fixture/VintageOmegaTest.java"),
            "package fixture; public class VintageOmegaTest { @org.junit.Test public void test() throws Exception { " +
                "Thread.sleep(250); org.junit.Assert.assertEquals(\"preserved\", " +
                "System.getProperty(\"fixture.argLine\")); org.junit.Assert.assertEquals(3, Omega.value()); " +
                "Marks.add(\"VintageOmegaTest\"); } }\n");
    }

    private static void writeModuleFixture(Path project, String artifactId) throws Exception {
        writeFixture(project);
        String standalone = read(project.resolve("pom.xml"));
        write(
            project.resolve("pom.xml"),
            standalone.replace(
                "  <groupId>fixture</groupId><artifactId>app</artifactId><version>1</version>\n",
                "  <parent><groupId>fixture</groupId><artifactId>reactor</artifactId>" +
                    "<version>1</version><relativePath>..</relativePath></parent>\n" +
                    "  <artifactId>" + artifactId + "</artifactId>\n"
            )
        );
    }

    private static java.util.Set<String> executedTests(Path project) throws Exception {
        Path root = project.resolve("executed");
        if (!Files.isDirectory(root)) return Collections.emptySet();
        try (Stream<Path> files = Files.list(root)) {
            return files.map(path -> path.getFileName().toString()).collect(Collectors.toSet());
        }
    }

    private static void clearExecuted(Path project) throws Exception {
        Path root = project.resolve("executed");
        if (!Files.isDirectory(root)) return;
        try (Stream<Path> files = Files.list(root)) {
            for (Path file : files.collect(Collectors.toList())) Files.delete(file);
        }
    }

    private static void promote(Path output, Path maps) throws Exception {
        for (Path task : directories(output)) promoteTask(task, maps);
    }

    private static void promoteTask(Path task, Path maps) throws Exception {
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
        String content = new StringBuilder("format=1\n")
            .append("schema=4\n")
            .append("collector=").append(encode("fixture-version")).append('\n')
            .append("task=").append(manifest.get("task")).append('\n')
            .append("runtime=").append(manifest.get("runtime")).append('\n')
            .append("input=").append(manifest.get("input")).append('\n')
            .append("run=").append(encode("maven-fixture-baseline")).append('\n')
            .append("artifacts=").append(artifactCount).append('\n')
            .append("records=").append(recordCount).append('\n')
            .append("checksum=").append(sha256(payload.toString())).append('\n')
            .append(payload)
            .toString();
        Files.write(
            maps.resolve("map-" + sha256(taskKey) + ".map"),
            content.getBytes(StandardCharsets.UTF_8)
        );
    }

    private static Map<String, String> values(Path manifest) throws Exception {
        Map<String, String> result = new LinkedHashMap<String, String>();
        for (String line : read(manifest).split("\n")) {
            int separator = line.indexOf('=');
            if (separator > 0) result.put(line.substring(0, separator), line.substring(separator + 1));
        }
        return result;
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

    private static Path onlyDirectory(Path root, String message) throws Exception {
        List<Path> values = directories(root);
        assertEquals(message, 1, values.size());
        return values.get(0);
    }

    private static List<Path> directories(Path root) throws Exception {
        try (Stream<Path> files = Files.list(root)) {
            return files.filter(Files::isDirectory).collect(Collectors.toList());
        }
    }

    private static java.util.Set<String> setOf(String... values) {
        return new HashSet<String>(Arrays.asList(values));
    }

    private static void write(Path path, String content) throws Exception {
        Files.createDirectories(path.getParent());
        Files.write(path, content.getBytes(StandardCharsets.UTF_8));
    }

    private static String read(Path path) throws Exception {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static String fallback(Path output) throws Exception {
        Path path = output.resolve("maven-fallback.manifest");
        return Files.isRegularFile(path) ? "\n" + read(path) : "";
    }

    private static String required(String name) {
        String value = System.getProperty(name);
        if (value == null || value.trim().isEmpty()) throw new IllegalStateException(name);
        return value;
    }

    private static List<Path> mavenHomes() {
        List<Path> result = new java.util.ArrayList<Path>();
        for (String value : requiredValue("affected.test.mavenHomes").split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
            Path path = Paths.get(value);
            if (!Files.isDirectory(path)) throw new IllegalStateException(path.toString());
            result.add(path);
        }
        if (result.isEmpty()) throw new IllegalStateException("affected.test.mavenHomes");
        return result;
    }

    private static Path directory(String name) {
        Path path = Paths.get(requiredValue(name));
        if (!Files.isDirectory(path)) throw new IllegalStateException(path.toString());
        return path;
    }

    private static String requiredValue(String name) {
        String value = System.getProperty(name);
        if (value == null || value.trim().isEmpty()) throw new IllegalStateException(name);
        return value;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    private static final class Result {
        private final int exitCode;
        private final String log;
        private final long durationMillis;

        private Result(int exitCode, String log, long durationMillis) {
            this.exitCode = exitCode;
            this.log = log;
            this.durationMillis = durationMillis;
        }
    }
}
