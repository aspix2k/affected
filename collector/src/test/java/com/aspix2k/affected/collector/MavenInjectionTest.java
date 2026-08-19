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
        assertDecision(result.log, "fixture:app:test", "full fallback (baseline not collected yet)");
        assertEquals(setOf("AlphaTest", "VintageOmegaTest", "ZetaBetaTest"), executedTests(project));
        Path task = onlyDirectory(output, result.log);
        assertTrue(result.log + fallback(output), Files.isRegularFile(task.resolve("task.manifest")));
        assertTrue(Files.isRegularFile(task.resolve("catalog.manifest")));
        Path worker = onlyDirectory(task, result.log);
        assertTrue(Files.isRegularFile(worker.resolve("expected.manifest")));
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
        assertDecision(exact.log, "fixture:app:test", "exact (2 test classes)");
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
        assertDecision(result.log, "fixture:app:test", "full fallback (unsupported runtime)");
        assertEquals(setOf("AlphaTest", "VintageOmegaTest", "ZetaBetaTest"), executedTests(project));
        try (Stream<Path> files = Files.list(output)) {
            assertEquals(result.log, 0, files.count());
        }
    }

    @Test(timeout = 180_000L)
    public void decisionStaysInMavenOutputWhenSurefireRedirectsTestOutput() throws Exception {
        Path project = temporary.newFolder("redirected output project").toPath();
        Path output = temporary.newFolder("redirected output collector").toPath();
        Path maps = temporary.newFolder("redirected output maps").toPath();
        writeFixture(project);
        Path pom = project.resolve("pom.xml");
        write(
            pom,
            read(pom).replace(
                "<useModulePath>false</useModulePath>",
                "<useModulePath>false</useModulePath><redirectTestOutputToFile>true</redirectTestOutputToFile>"
            )
        );

        Result result = run(project, output, maps, mavenHomes().get(mavenHomes().size() - 1));

        assertEquals(result.log, 0, result.exitCode);
        assertDecision(result.log, "fixture:app:test", "full fallback (baseline not collected yet)");
        assertEquals(setOf("AlphaTest", "VintageOmegaTest", "ZetaBetaTest"), executedTests(project));
    }

    @Test(timeout = 180_000L)
    public void unsupportedSurefireConfigurationReportsTheFullFallback() throws Exception {
        Path project = temporary.newFolder("core scaled fork project").toPath();
        Path output = temporary.newFolder("core scaled fork output").toPath();
        Path maps = temporary.newFolder("core scaled fork maps").toPath();
        writeFixture(project);
        Path pom = project.resolve("pom.xml");
        write(
            pom,
            read(pom).replace(
                "<useModulePath>false</useModulePath>",
                "<useModulePath>false</useModulePath><forkCount>1C</forkCount>"
            )
        );

        Result result = run(project, output, maps, mavenHomes().get(mavenHomes().size() - 1));

        assertEquals(result.log, 0, result.exitCode);
        assertDecision(result.log, "fixture:app:test", "full fallback (unsupported configuration)");
        assertEquals(setOf("AlphaTest", "VintageOmegaTest", "ZetaBetaTest"), executedTests(project));
        try (Stream<Path> files = Files.list(output)) {
            assertEquals(result.log, 0, files.count());
        }
    }

    @Test(timeout = 180_000L)
    public void reusableMultiForkSurefireProducesACompleteMapAndSelectsExactTests() throws Exception {
        multiForkSurefireScenario(true);
    }

    @Test(timeout = 180_000L)
    public void isolatedMultiForkSurefireProducesACompleteMapAndSelectsExactTests() throws Exception {
        multiForkSurefireScenario(false);
    }

    @Test(timeout = 180_000L)
    public void reusableMultiForkFailsafeProducesACompleteMapAndSelectsExactTests() throws Exception {
        multiForkFailsafeScenario(true);
    }

    @Test(timeout = 180_000L)
    public void isolatedMultiForkFailsafeProducesACompleteMapAndSelectsExactTests() throws Exception {
        multiForkFailsafeScenario(false);
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
        assertDecision(exact.log, "fixture:first:test", "exact (1 test class)");
        assertDecision(exact.log, "fixture:second:test", "proven-empty");
        assertEquals(Collections.singleton("VintageOmegaTest"), executedTests(first));
        assertEquals(Collections.emptySet(), executedTests(second));
        assertEquals(exact.log, 2, directories(exactOutput).size());
    }

    @Test(timeout = 180_000L)
    public void failsafeIntegrationTestAndVerifyUseAnIndependentExactMap() throws Exception {
        Path project = temporary.newFolder("failsafe project").toPath();
        Path fullOutput = temporary.newFolder("failsafe full output").toPath();
        Path exactOutput = temporary.newFolder("failsafe exact output").toPath();
        Path jupiterOutput = temporary.newFolder("failsafe jupiter output").toPath();
        Path maps = temporary.newFolder("failsafe maps").toPath();
        writeFailsafeFixture(project);
        Path mavenHome = mavenHomes().get(mavenHomes().size() - 1);

        Result full = run(project, fullOutput, maps, mavenHome, "verify");

        assertEquals(full.log, 0, full.exitCode);
        assertDecision(full.log, "fixture:app:test", "full fallback (baseline not collected yet)");
        assertDecision(full.log, "fixture:app:integration-test", "full fallback (baseline not collected yet)");
        assertEquals(
            setOf(
                "AlphaTest", "VintageOmegaTest", "ZetaBetaTest",
                "AlphaIT", "VintageOmegaIT", "ZetaBetaIT"
            ),
            executedTests(project)
        );
        assertEquals(full.log, 2, directories(fullOutput).size());
        promote(fullOutput, maps);
        clearExecuted(project);
        write(
            project.resolve("src/main/java/fixture/IntegrationOmega.java"),
            "package fixture; public final class IntegrationOmega { public static int value() { " +
                "int result = 30; return result; } }\n"
        );

        Result exact = run(project, exactOutput, maps, mavenHome, "integration-test");

        assertEquals(exact.log, 0, exact.exitCode);
        assertDecision(exact.log, "fixture:app:test", "proven-empty");
        assertDecision(exact.log, "fixture:app:integration-test", "exact (1 test class)");
        assertEquals(Collections.singleton("VintageOmegaIT"), executedTests(project));
        assertEquals(exact.log, 2, directories(exactOutput).size());
        assertTrue(exact.log, Files.isRegularFile(project.resolve("target/failsafe-reports/failsafe-summary.xml")));

        clearExecuted(project);
        write(project.resolve("src/main/java/fixture/IntegrationOmega.java"),
            "package fixture; public final class IntegrationOmega { public static int value() { return 30; } }\n");
        write(
            project.resolve("src/main/java/fixture/IntegrationAlpha.java"),
            "package fixture; public final class IntegrationAlpha { public static int value() { " +
                "int result = 10; return result; } }\n"
        );

        Result jupiter = run(project, jupiterOutput, maps, mavenHome, "verify");

        assertEquals(jupiter.log, 0, jupiter.exitCode);
        assertDecision(jupiter.log, "fixture:app:test", "proven-empty");
        assertDecision(jupiter.log, "fixture:app:integration-test", "exact (1 test class)");
        assertEquals(Collections.singleton("AlphaIT"), executedTests(project));
    }

    @Test(timeout = 180_000L)
    public void failedDirectFailsafeIntegrationTestCannotProduceACompleteBaseline() throws Exception {
        Path project = temporary.newFolder("failing direct failsafe project").toPath();
        Path output = temporary.newFolder("failing direct failsafe output").toPath();
        Path maps = temporary.newFolder("failing direct failsafe maps").toPath();
        writeFailsafeFixture(project);
        write(project.resolve("src/test/java/fixture/AlphaIT.java"),
            "package fixture; public class AlphaIT { @org.junit.jupiter.api.Test void test() { " +
                "org.junit.jupiter.api.Assertions.assertEquals(11, IntegrationAlpha.value()); } }\n");

        Result result = run(
            project,
            output,
            maps,
            mavenHomes().get(mavenHomes().size() - 1),
            "integration-test"
        );

        assertEquals(result.log, 0, result.exitCode);
        assertDecision(result.log, "fixture:app:integration-test", "full fallback (baseline not collected yet)");
        String manifest = read(taskDirectory(output, "|integration-test").resolve("task.manifest"));
        assertTrue(manifest, manifest.contains("all=false"));
    }

    @Test(timeout = 180_000L)
    public void failsafeRunsOnTheLowestSupportedMavenRuntime() throws Exception {
        Path project = temporary.newFolder("failsafe lowest maven project").toPath();
        Path output = temporary.newFolder("failsafe lowest maven output").toPath();
        Path maps = temporary.newFolder("failsafe lowest maven maps").toPath();
        writeFailsafeFixture(project);

        Result result = run(project, output, maps, mavenHomes().get(0), "verify");

        assertEquals(result.log, 0, result.exitCode);
        assertDecision(result.log, "fixture:app:test", "full fallback (baseline not collected yet)");
        assertDecision(result.log, "fixture:app:integration-test", "full fallback (baseline not collected yet)");
        assertEquals(result.log, 2, directories(output).size());
    }

    @Test(timeout = 180_000L)
    public void failsafeReactorModulesKeepSurefireAndFailsafeMapsIsolated() throws Exception {
        Path project = temporary.newFolder("failsafe reactor").toPath();
        Path first = project.resolve("first");
        Path second = project.resolve("second");
        Path fullOutput = temporary.newFolder("failsafe reactor full output").toPath();
        Path exactOutput = temporary.newFolder("failsafe reactor exact output").toPath();
        Path maps = temporary.newFolder("failsafe reactor maps").toPath();
        write(
            project.resolve("pom.xml"),
            "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n" +
                "  <modelVersion>4.0.0</modelVersion><groupId>fixture</groupId>" +
                "<artifactId>reactor</artifactId><version>1</version><packaging>pom</packaging>\n" +
                "  <modules><module>first</module><module>second</module></modules>\n" +
                "</project>\n"
        );
        writeFailsafeModuleFixture(first, "first");
        writeFailsafeModuleFixture(second, "second");
        Path mavenHome = mavenHomes().get(mavenHomes().size() - 1);

        Result full = run(project, fullOutput, maps, mavenHome, "verify");

        assertEquals(full.log, 0, full.exitCode);
        assertEquals(full.log, 4, directories(fullOutput).size());
        promote(fullOutput, maps);
        clearExecuted(first);
        clearExecuted(second);
        write(
            first.resolve("src/main/java/fixture/IntegrationOmega.java"),
            "package fixture; public final class IntegrationOmega { public static int value() { " +
                "int result = 30; return result; } }\n"
        );

        Result exact = run(project, exactOutput, maps, mavenHome, "verify");

        assertEquals(exact.log, 0, exact.exitCode);
        assertDecision(exact.log, "fixture:first:test", "proven-empty");
        assertDecision(exact.log, "fixture:first:integration-test", "exact (1 test class)");
        assertDecision(exact.log, "fixture:second:test", "proven-empty");
        assertDecision(exact.log, "fixture:second:integration-test", "proven-empty");
        assertEquals(Collections.singleton("VintageOmegaIT"), executedTests(first));
        assertEquals(Collections.emptySet(), executedTests(second));
        assertEquals(exact.log, 4, directories(exactOutput).size());
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
        assertDecision(exact.log, "fixture:app:test", "exact (1 test class)");
        Path task = onlyDirectory(exactOutput, exact.log);
        String manifest = read(task.resolve("task.manifest"));
        assertEquals(
            exact.log + "\nfull:\n" + fullManifest + "\nexact:\n" + manifest,
            Collections.singleton(dependentTest),
            executedTests(project)
        );
        assertTrue(manifest, manifest.contains("all=false"));
    }

    private void multiForkSurefireScenario(boolean reuseForks) throws Exception {
        String mode = reuseForks ? "reusable" : "isolated";
        Path project = temporary.newFolder(mode + " multi fork project").toPath();
        Path fullOutput = temporary.newFolder(mode + " multi fork full output").toPath();
        Path exactOutput = temporary.newFolder(mode + " multi fork exact output").toPath();
        Path maps = temporary.newFolder(mode + " multi fork maps").toPath();
        writeFixture(project);
        Path pom = project.resolve("pom.xml");
        write(
            pom,
            read(pom).replace(
                "<useModulePath>false</useModulePath>",
                "<useModulePath>false</useModulePath><forkCount>2</forkCount>" +
                    "<reuseForks>" + reuseForks + "</reuseForks>"
            )
        );
        Path mavenHome = mavenHomes().get(mavenHomes().size() - 1);

        Result full = run(project, fullOutput, maps, mavenHome);

        assertEquals(full.log, 0, full.exitCode);
        assertDecision(full.log + fallback(fullOutput), "fixture:app:test", "full fallback (baseline not collected yet)");
        assertEquals(setOf("AlphaTest", "VintageOmegaTest", "ZetaBetaTest"), executedTests(project));
        Path task = onlyDirectory(fullOutput, full.log);
        assertEquals(full.log + fallback(fullOutput), reuseForks ? 2 : 3, directories(task).size());
        promote(fullOutput, maps);
        clearExecuted(project);
        write(
            project.resolve("src/main/java/fixture/Beta.java"),
            "package fixture; public final class Beta { public static int value() { int result = 2; return result; } }\n"
        );

        Result exact = run(project, exactOutput, maps, mavenHome);

        assertEquals(exact.log, 0, exact.exitCode);
        assertDecision(exact.log, "fixture:app:test", "exact (1 test class)");
        assertEquals(Collections.singleton("ZetaBetaTest"), executedTests(project));
    }

    private void multiForkFailsafeScenario(boolean reuseForks) throws Exception {
        String mode = reuseForks ? "reusable" : "isolated";
        Path project = temporary.newFolder(mode + " multi fork failsafe project").toPath();
        Path fullOutput = temporary.newFolder(mode + " multi fork failsafe full output").toPath();
        Path exactOutput = temporary.newFolder(mode + " multi fork failsafe exact output").toPath();
        Path maps = temporary.newFolder(mode + " multi fork failsafe maps").toPath();
        writeFailsafeFixture(project);
        Path pom = project.resolve("pom.xml");
        write(
            pom,
            read(pom).replace(
                "<useModulePath>false</useModulePath><runOrder>alphabetical</runOrder>",
                "<useModulePath>false</useModulePath><runOrder>alphabetical</runOrder>" +
                    "<forkCount>2</forkCount><reuseForks>" + reuseForks + "</reuseForks>"
            )
        );
        Path mavenHome = mavenHomes().get(mavenHomes().size() - 1);

        Result full = run(project, fullOutput, maps, mavenHome, "verify");

        assertEquals(full.log, 0, full.exitCode);
        assertDecision(full.log, "fixture:app:integration-test", "full fallback (baseline not collected yet)");
        assertEquals(
            setOf(
                "AlphaTest", "VintageOmegaTest", "ZetaBetaTest",
                "AlphaIT", "VintageOmegaIT", "ZetaBetaIT"
            ),
            executedTests(project)
        );
        Path task = taskDirectory(fullOutput, "|integration-test");
        assertEquals(full.log + fallback(fullOutput), reuseForks ? 2 : 3, directories(task).size());
        promote(fullOutput, maps);
        clearExecuted(project);
        write(
            project.resolve("src/main/java/fixture/IntegrationBeta.java"),
            "package fixture; public final class IntegrationBeta { public static int value() { " +
                "int result = 20; return result; } }\n"
        );

        Result exact = run(project, exactOutput, maps, mavenHome, "verify");

        assertEquals(exact.log, 0, exact.exitCode);
        assertDecision(exact.log, "fixture:app:integration-test", "exact (1 test class)");
        assertEquals(Collections.singleton("ZetaBetaIT"), executedTests(project));
    }

    private Result run(Path project, Path output, Path maps, Path mavenHome) throws Exception {
        return run(project, output, maps, mavenHome, "test");
    }

    private Result run(Path project, Path output, Path maps, Path mavenHome, String goal) throws Exception {
        Path log = Files.createTempFile(temporary.getRoot().toPath(), "maven-", ".log");
        Path executable = mavenHome.resolve("bin").resolve(isWindows() ? "mvn.cmd" : "mvn");
        ProcessBuilder builder = new ProcessBuilder(
            executable.toString(),
            "-B",
            "-ntp",
            "-Dmaven.repo.local=" + required("affected.test.mavenLocalRepo"),
            "-Dmaven.ext.class.path=" + required("affected.test.mavenExtension"),
            "-Daffected.collector.mavenAgent=" + required("affected.test.mavenAgent"),
            "-Daffected.collector.output=" + output,
            "-Daffected.collector.maps=" + maps,
            "-Daffected.collector.version=fixture-version",
            goal
        );
        builder.directory(project.toFile());
        builder.redirectErrorStream(true);
        builder.redirectOutput(log.toFile());
        Process process = builder.start();
        boolean finished = process.waitFor(120, TimeUnit.SECONDS);
        if (!finished) process.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
        String content = Files.exists(log) ? read(log) : "";
        assertTrue(content, finished);
        return new Result(process.exitValue(), content);
    }

    private static void assertDecision(String log, String task, String decision) {
        String expected = "[Affected] " + task + " - " + decision;
        assertTrue(log, log.contains(expected));
        assertEquals(log, 1, occurrences(log, expected));
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

    private static void writeFixture(Path project) throws Exception {
        write(project.resolve("pom.xml"),
            "<project xmlns=\"http://maven.apache.org/POM/4.0.0\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" " +
                "xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd\">\n" +
                "  <modelVersion>4.0.0</modelVersion>\n" +
                "  <groupId>fixture</groupId><artifactId>app</artifactId><version>1</version>\n" +
                "  <properties><maven.compiler.release>8</maven.compiler.release></properties>\n" +
                "  <dependencies>\n" +
                "    <dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId><version>5.14.4</version><scope>test</scope></dependency>\n" +
                "    <dependency><groupId>org.junit.vintage</groupId><artifactId>junit-vintage-engine</artifactId><version>5.14.4</version><scope>test</scope></dependency>\n" +
                "    <dependency><groupId>junit</groupId><artifactId>junit</artifactId><version>4.13.2</version><scope>test</scope></dependency>\n" +
                "  </dependencies>\n" +
                "  <build><plugins>\n" +
                "    <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>3.15.0</version></plugin>\n" +
                "    <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-surefire-plugin</artifactId><version>3.5.6</version>\n" +
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

    private static void writeFailsafeFixture(Path project) throws Exception {
        writeFixture(project);
        Path pom = project.resolve("pom.xml");
        write(
            pom,
            read(pom).replace(
                "    </plugin>\n  </plugins></build>",
                "    </plugin>\n" +
                    "    <plugin><groupId>org.apache.maven.plugins</groupId>" +
                    "<artifactId>maven-failsafe-plugin</artifactId><version>3.5.6</version>\n" +
                    "      <executions><execution><goals><goal>integration-test</goal>" +
                    "<goal>verify</goal></goals></execution></executions>\n" +
                    "      <configuration><useModulePath>false</useModulePath><runOrder>alphabetical</runOrder>" +
                    "<argLine>-Dfixture.argLine=preserved</argLine><systemPropertyVariables>" +
                    "<fixture.executed>${project.basedir}/executed</fixture.executed>" +
                    "</systemPropertyVariables></configuration>\n" +
                    "    </plugin>\n  </plugins></build>"
            )
        );
        write(project.resolve("src/main/java/fixture/IntegrationAlpha.java"),
            "package fixture; public final class IntegrationAlpha { public static int value() { return 10; } }\n");
        write(project.resolve("src/main/java/fixture/IntegrationBeta.java"),
            "package fixture; public final class IntegrationBeta { public static int value() { return 20; } }\n");
        write(project.resolve("src/main/java/fixture/IntegrationOmega.java"),
            "package fixture; public final class IntegrationOmega { public static int value() { return 30; } }\n");
        write(project.resolve("src/test/java/fixture/AlphaIT.java"),
            "package fixture; public class AlphaIT { @org.junit.jupiter.api.Test void test() throws Exception { " +
                "org.junit.jupiter.api.Assertions.assertEquals(\"preserved\", System.getProperty(\"fixture.argLine\")); " +
                "org.junit.jupiter.api.Assertions.assertEquals(10, IntegrationAlpha.value()); Marks.add(\"AlphaIT\"); } }\n");
        write(project.resolve("src/test/java/fixture/ZetaBetaIT.java"),
            "package fixture; public class ZetaBetaIT { @org.junit.jupiter.api.Test void test() throws Exception { " +
                "org.junit.jupiter.api.Assertions.assertEquals(\"preserved\", System.getProperty(\"fixture.argLine\")); " +
                "org.junit.jupiter.api.Assertions.assertEquals(20, IntegrationBeta.value()); Marks.add(\"ZetaBetaIT\"); } }\n");
        write(project.resolve("src/test/java/fixture/VintageOmegaIT.java"),
            "package fixture; public class VintageOmegaIT { @org.junit.Test public void test() throws Exception { " +
                "org.junit.Assert.assertEquals(\"preserved\", System.getProperty(\"fixture.argLine\")); " +
                "org.junit.Assert.assertEquals(30, IntegrationOmega.value()); Marks.add(\"VintageOmegaIT\"); } }\n");
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

    private static void writeFailsafeModuleFixture(Path project, String artifactId) throws Exception {
        writeFailsafeFixture(project);
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
        assertEquals(read(task.resolve("task.manifest")), "true", manifest.get("all"));
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

    private static Path taskDirectory(Path root, String taskSuffix) throws Exception {
        for (Path directory : directories(root)) {
            String task = decode(values(directory.resolve("task.manifest")).get("task"));
            if (task.endsWith(taskSuffix)) return directory;
        }
        throw new AssertionError(taskSuffix);
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

        private Result(int exitCode, String log) {
            this.exitCode = exitCode;
            this.log = log;
        }
    }
}
