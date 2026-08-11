package com.aspix2k.affected.collector;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Arrays;
import java.util.Locale;
import java.util.Properties;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class AffectedMavenConfigTest {
    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void exactProjectRecordAppliesBeforeSurefireBootstraps() throws Exception {
        Path root = temporary.newFolder("project").toPath();
        Path output = temporary.newFolder("output").toPath();
        Path maps = temporary.newFolder("maps").toPath();
        Path manifest = temporary.getRoot().toPath().resolve("projects.manifest");
        AffectedMavenConfig.ProjectConfig expected = new AffectedMavenConfig.ProjectConfig(
            root.toRealPath().toString(),
            output.toRealPath().toString(),
            maps.toRealPath().toString(),
            "collector-version",
            root.toRealPath().toUri() + "|test",
            "fixture:app:test",
            "runtime",
            true,
            root.resolve("target/classes").toString(),
            root.resolve("target/test-classes").toString(),
            root.resolve("target/test-classes") + java.io.File.pathSeparator + root.resolve("target/classes")
        );
        AffectedMavenConfig.write(manifest, Collections.singletonList(expected));

        AffectedMavenConfig.ProjectConfig actual = AffectedMavenConfig.read(manifest, root);
        Properties properties = new Properties();
        actual.apply(properties);

        assertEquals("maven", properties.getProperty("affected.collector.runner"));
        assertEquals(expected.getTask(), properties.getProperty("affected.collector.task"));
        assertEquals(expected.getDisplay(), properties.getProperty("affected.collector.display"));
        assertEquals(expected.getCodeSources(), properties.getProperty("affected.collector.codeSources"));
        assertEquals(expected.getClasspath(), properties.getProperty("affected.collector.classpath"));
    }

    @Test
    public void truncatedManifestFailsClosed() throws Exception {
        Path root = temporary.newFolder("truncated-project").toPath();
        Path manifest = temporary.getRoot().toPath().resolve("truncated.manifest");
        Files.write(
            manifest,
            ("format=1\nprojects=1\nchecksum=" + repeat('0', 64) + "\nproject=x\n").getBytes("UTF-8")
        );

        try {
            AffectedMavenConfig.read(manifest, root);
            fail("truncated manifest must fail");
        } catch (IllegalStateException expected) {
            assertEquals("manifest checksum", expected.getMessage());
        }
    }

    @Test
    public void collectorConfigurationWinsOverSurefireSystemPropertyInjection() throws Exception {
        Path root = temporary.newFolder("overridden-project").toPath();
        Path output = temporary.newFolder("overridden-output").toPath();
        Path maps = temporary.newFolder("overridden-maps").toPath();
        Path manifest = temporary.getRoot().toPath().resolve("overridden.manifest");
        AffectedMavenConfig.ProjectConfig config = new AffectedMavenConfig.ProjectConfig(
            root.toRealPath().toString(),
            output.toString(),
            maps.toString(),
            "version",
            "correct-task",
            "fixture:app:test",
            "runtime",
            true,
            root.resolve("target/classes").toString(),
            root.resolve("target/test-classes").toString(),
            root.resolve("target/test-classes") + java.io.File.pathSeparator + root.resolve("target/classes")
        );
        AffectedMavenConfig.write(manifest, Collections.singletonList(config));
        Properties properties = new Properties();
        AffectedCollectorAgent.configureMaven(manifest.toString(), root, properties);
        properties.setProperty("affected.collector.task", "project-value");

        AffectedCollectorAgent.reapplyMavenConfig(properties);

        assertEquals("correct-task", properties.getProperty("affected.collector.task"));
        AffectedCollectorAgent.resetForTests();
    }

    @Test
    public void reactorProjectsReadOnlyTheirOwnConfiguration() throws Exception {
        Path first = temporary.newFolder("first-project").toPath();
        Path second = temporary.newFolder("second-project").toPath();
        Path output = temporary.newFolder("reactor-output").toPath();
        Path maps = temporary.newFolder("reactor-maps").toPath();
        Path manifest = temporary.getRoot().toPath().resolve("reactor.manifest");
        AffectedMavenConfig.write(
            manifest,
            Arrays.asList(config(first, output, maps, "first-task"), config(second, output, maps, "second-task"))
        );

        assertEquals("first-task", AffectedMavenConfig.read(manifest, first).getTask());
        assertEquals("second-task", AffectedMavenConfig.read(manifest, second).getTask());
    }

    @Test
    public void nativePathsAndAtomicReplacementPreserveTheLatestConfiguration() throws Exception {
        Path root = temporary.newFolder("Case Project").toPath();
        Path output = temporary.newFolder("output with spaces").toPath();
        Path maps = temporary.newFolder("maps with spaces").toPath();
        Path manifest = temporary.getRoot().toPath().resolve("native-paths.manifest");
        AffectedMavenConfig.write(manifest, Collections.singletonList(config(root, output, maps, "first-task")));
        AffectedMavenConfig.write(manifest, Collections.singletonList(config(root, output, maps, "second-task")));

        Path caseAlias = root.resolveSibling(root.getFileName().toString().toLowerCase(Locale.ROOT));
        boolean caseSensitive = !Files.exists(caseAlias);
        Path requestedRoot = caseSensitive ? root : caseAlias;
        if (!caseSensitive) assertEquals(true, Files.isSameFile(root, caseAlias));
        AffectedMavenConfig.ProjectConfig actual = AffectedMavenConfig.read(manifest, requestedRoot);

        assertEquals("second-task", actual.getTask());
        assertEquals(2, actual.getClasspath().split(Pattern.quote(File.pathSeparator), -1).length);
        try (java.util.stream.Stream<Path> files = Files.list(manifest.getParent())) {
            assertEquals(0, files.filter(path -> path.getFileName().toString().endsWith(".tmp")).count());
        }
        if (System.getProperty("affected.test.conformanceReport") != null) {
            System.out.println(
                "[Affected conformance] filesystemCase=" + (caseSensitive ? "sensitive" : "insensitive") +
                    " pathSeparator=" + (int) File.pathSeparatorChar + " atomicReplacement=true"
            );
        }
    }

    private static AffectedMavenConfig.ProjectConfig config(
        Path root,
        Path output,
        Path maps,
        String task
    ) throws Exception {
        return new AffectedMavenConfig.ProjectConfig(
            root.toRealPath().toString(),
            output.toRealPath().toString(),
            maps.toRealPath().toString(),
            "collector-version",
            task,
            "fixture:" + task + ":test",
            "runtime",
            true,
            root.resolve("target/classes").toString(),
            root.resolve("target/test-classes").toString(),
            root.resolve("target/test-classes") + java.io.File.pathSeparator + root.resolve("target/classes")
        );
    }

    private static String repeat(char value, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int index = 0; index < count; index++) result.append(value);
        return result.toString();
    }
}
