package com.aspix2k.affected.collector;

import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.engine.support.descriptor.AbstractTestDescriptor;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Properties;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class AffectedMavenFilterTest {
    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void missingBaselineIncludesTheTestClass() {
        AffectedMavenFilter filter = new AffectedMavenFilter(new Properties());

        assertTrue(filter.apply(descriptor("fixture.AlphaTest")).included());
    }

    @Test
    public void changedProductionClassSelectsOnlyItsRecordedTest() throws Exception {
        Path root = temporary.newFolder("exact").toPath();
        Path classes = Files.createDirectories(root.resolve("classes/fixture"));
        Path tests = Files.createDirectories(root.resolve("test-classes/fixture"));
        Path output = Files.createDirectory(root.resolve("output"));
        Path maps = Files.createDirectory(root.resolve("maps"));
        Path alpha = Files.write(classes.resolve("Alpha.class"), "alpha-1".getBytes(StandardCharsets.UTF_8));
        Path beta = Files.write(classes.resolve("Beta.class"), "beta-1".getBytes(StandardCharsets.UTF_8));
        Files.write(tests.resolve("AlphaTest.class"), "alpha-test".getBytes(StandardCharsets.UTF_8));
        Files.write(tests.resolve("BetaTest.class"), "beta-test".getBytes(StandardCharsets.UTF_8));
        Properties properties = properties(root, output, maps);
        AffectedMavenFilter.Snapshot baseline = AffectedMavenFilter.snapshot(properties);
        writeMap(maps, properties, baseline, alpha, beta);
        Files.write(alpha, "alpha-2".getBytes(StandardCharsets.UTF_8));

        AffectedMavenFilter filter = new AffectedMavenFilter(properties);

        assertTrue(filter.apply(descriptor("fixture.AlphaTest")).included());
        assertFalse(filter.apply(descriptor("fixture.BetaTest")).included());
    }

    @Test
    public void equivalentClasspathSpellingsHaveTheSameRuntimeIdentity() throws Exception {
        Path root = temporary.newFolder("classpath-alias").toPath();
        Files.createDirectories(root.resolve("classes"));
        Files.createDirectories(root.resolve("test-classes"));
        Path output = Files.createDirectory(root.resolve("output"));
        Path maps = Files.createDirectory(root.resolve("maps"));
        Properties direct = properties(root, output, maps);
        Properties normalized = properties(root, output, maps);
        normalized.setProperty(
            "affected.collector.classpath",
            root.resolve("test-classes/../test-classes") + File.pathSeparator +
                root.resolve("classes/../classes")
        );

        assertEquals(
            AffectedMavenFilter.snapshot(direct).getRuntimeFingerprint(),
            AffectedMavenFilter.snapshot(normalized).getRuntimeFingerprint()
        );
    }

    @Test
    public void changedTestBytecodeFallsBackToAllTests() throws Exception {
        Fixture fixture = fixture("changed-test");
        AffectedMavenFilter.Snapshot baseline = AffectedMavenFilter.snapshot(fixture.properties);
        writeMap(fixture.maps, fixture.properties, baseline, fixture.alpha, fixture.beta);
        Files.write(fixture.alphaTest, "alpha-test-2".getBytes(StandardCharsets.UTF_8));

        AffectedMavenFilter filter = new AffectedMavenFilter(fixture.properties);

        assertTrue(filter.apply(descriptor("fixture.AlphaTest")).included());
        assertTrue(filter.apply(descriptor("fixture.BetaTest")).included());
    }

    @Test
    public void changedProductionResourceFallsBackToAllTests() throws Exception {
        Fixture fixture = fixture("changed-resource");
        Path resource = Files.write(
            fixture.alpha.getParent().resolve("settings.properties"),
            "mode=first".getBytes(StandardCharsets.UTF_8)
        );
        AffectedMavenFilter.Snapshot baseline = AffectedMavenFilter.snapshot(fixture.properties);
        writeMap(fixture.maps, fixture.properties, baseline, fixture.alpha, fixture.beta);
        Files.write(resource, "mode=second".getBytes(StandardCharsets.UTF_8));

        AffectedMavenFilter filter = new AffectedMavenFilter(fixture.properties);

        assertTrue(filter.apply(descriptor("fixture.AlphaTest")).included());
        assertTrue(filter.apply(descriptor("fixture.BetaTest")).included());
    }

    @Test
    public void corruptBaselineFallsBackToAllTests() throws Exception {
        Fixture fixture = fixture("corrupt-map");
        AffectedMavenFilter.Snapshot baseline = AffectedMavenFilter.snapshot(fixture.properties);
        Path map = writeMap(fixture.maps, fixture.properties, baseline, fixture.alpha, fixture.beta);
        Files.write(fixture.alpha, "alpha-2".getBytes(StandardCharsets.UTF_8));
        Files.write(
            map,
            new String(Files.readAllBytes(map), StandardCharsets.UTF_8)
                .replace("records=2", "records=1")
                .getBytes(StandardCharsets.UTF_8)
        );

        AffectedMavenFilter filter = new AffectedMavenFilter(fixture.properties);

        assertTrue(filter.apply(descriptor("fixture.AlphaTest")).included());
        assertTrue(filter.apply(descriptor("fixture.BetaTest")).included());
    }

    @Test
    public void addedProductionClassFallsBackToAllTests() throws Exception {
        Fixture fixture = fixture("added-class");
        AffectedMavenFilter.Snapshot baseline = AffectedMavenFilter.snapshot(fixture.properties);
        writeMap(fixture.maps, fixture.properties, baseline, fixture.alpha, fixture.beta);
        Files.write(
            fixture.alpha.getParent().resolve("Gamma.class"),
            "gamma-1".getBytes(StandardCharsets.UTF_8)
        );

        AffectedMavenFilter filter = new AffectedMavenFilter(fixture.properties);

        assertTrue(filter.apply(descriptor("fixture.AlphaTest")).included());
        assertTrue(filter.apply(descriptor("fixture.BetaTest")).included());
    }

    private static Properties properties(Path root, Path output, Path maps) {
        Properties properties = new Properties();
        properties.setProperty("affected.collector.runner", "maven");
        properties.setProperty("affected.collector.output", output.toString());
        properties.setProperty("affected.collector.maps", maps.toString());
        properties.setProperty("affected.collector.version", "collector-version");
        properties.setProperty("affected.collector.task", root.toUri() + "|test");
        properties.setProperty("affected.collector.display", "fixture:app:test");
        properties.setProperty("affected.collector.runtime", "runtime-seed");
        properties.setProperty("affected.collector.all", "true");
        properties.setProperty("affected.collector.codeSources", root.resolve("classes").toString());
        properties.setProperty("affected.collector.testClasses", root.resolve("test-classes").toString());
        properties.setProperty(
            "affected.collector.classpath",
            root.resolve("test-classes") + File.pathSeparator + root.resolve("classes")
        );
        return properties;
    }

    private Fixture fixture(String name) throws Exception {
        Path root = temporary.newFolder(name).toPath();
        Path classes = Files.createDirectories(root.resolve("classes/fixture"));
        Path tests = Files.createDirectories(root.resolve("test-classes/fixture"));
        Path output = Files.createDirectory(root.resolve("output"));
        Path maps = Files.createDirectory(root.resolve("maps"));
        Path alpha = Files.write(classes.resolve("Alpha.class"), "alpha-1".getBytes(StandardCharsets.UTF_8));
        Path beta = Files.write(classes.resolve("Beta.class"), "beta-1".getBytes(StandardCharsets.UTF_8));
        Path alphaTest = Files.write(tests.resolve("AlphaTest.class"), "alpha-test".getBytes(StandardCharsets.UTF_8));
        Files.write(tests.resolve("BetaTest.class"), "beta-test".getBytes(StandardCharsets.UTF_8));
        return new Fixture(properties(root, output, maps), maps, alpha, beta, alphaTest);
    }

    private static Path writeMap(
        Path maps,
        Properties properties,
        AffectedMavenFilter.Snapshot snapshot,
        Path alpha,
        Path beta
    ) throws Exception {
        String source = alpha.getParent().getParent().toRealPath().toUri().toString();
        String alphaArtifact = artifact("fixture.Alpha", source, sha256(Files.readAllBytes(alpha)));
        String betaArtifact = artifact("fixture.Beta", source, sha256(Files.readAllBytes(beta)));
        String payload = "artifact=" + alphaArtifact + "\n" +
            "artifact=" + betaArtifact + "\n" +
            "record=" + encode("fixture.AlphaTest") + "|" + alphaArtifact + "\n" +
            "record=" + encode("fixture.BetaTest") + "|" + betaArtifact + "\n";
        String task = properties.getProperty("affected.collector.task");
        String content = "format=1\n" +
            "schema=4\n" +
            "collector=" + encode(properties.getProperty("affected.collector.version")) + "\n" +
            "task=" + encode(task) + "\n" +
            "runtime=" + encode(snapshot.getRuntimeFingerprint()) + "\n" +
            "input=" + encode(snapshot.getInputFingerprint()) + "\n" +
            "run=" + encode("baseline") + "\n" +
            "artifacts=2\n" +
            "records=2\n" +
            "checksum=" + sha256(payload.getBytes(StandardCharsets.UTF_8)) + "\n" +
            payload;
        return Files.write(
            maps.resolve("map-" + sha256(task.getBytes(StandardCharsets.UTF_8)) + ".map"),
            content.getBytes(StandardCharsets.UTF_8)
        );
    }

    private static String artifact(String className, String source, String hash) {
        return encode(className) + "|" + encode(source) + "|" + hash;
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
        StringBuilder result = new StringBuilder(digest.length * 2);
        for (byte item : digest) result.append(String.format("%02x", item & 0xff));
        return result.toString();
    }

    private static TestDescriptor descriptor(String className) {
        return new AbstractTestDescriptor(
            UniqueId.forEngine("fixture").append("class", className),
            className,
            ClassSource.from(className)
        ) {
            @Override
            public Type getType() {
                return Type.TEST;
            }
        };
    }

    private static final class Fixture {
        private final Properties properties;
        private final Path maps;
        private final Path alpha;
        private final Path beta;
        private final Path alphaTest;

        private Fixture(Properties properties, Path maps, Path alpha, Path beta, Path alphaTest) {
            this.properties = properties;
            this.maps = maps;
            this.alpha = alpha;
            this.beta = beta;
            this.alphaTest = alphaTest;
        }
    }
}
