package com.aspix2k.affected.collector;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class AffectedDependencySelectorTest {
    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void changedArtifactSelectsOnlyObservingTestClasses() throws Exception {
        Path maps = temporary.newFolder("maps").toPath();
        artifactMap(maps, "task", artifact("Alpha", "alpha-1"), artifact("Beta", "beta-1"));

        AffectedDependencySelector.Decision decision = AffectedDependencySelector.select(
            maps,
            "collector",
            "task",
            "runtime",
            "input",
            Arrays.asList(artifact("Alpha", "alpha-2"), artifact("Beta", "beta-1"))
        );

        assertEquals(AffectedDependencySelector.Kind.CLASSES, decision.getKind());
        assertEquals(Collections.singletonList("fixture.AlphaTest"), decision.getTestClasses());
    }

    @Test
    public void unchangedCatalogProvesAnEmptySelection() throws Exception {
        Path maps = temporary.newFolder("maps").toPath();
        AffectedDependencySelector.Artifact alpha = artifact("Alpha", "alpha-1");
        AffectedDependencySelector.Artifact beta = artifact("Beta", "beta-1");
        artifactMap(maps, "task", alpha, beta);

        AffectedDependencySelector.Decision decision = AffectedDependencySelector.select(
            maps,
            "collector",
            "task",
            "runtime",
            "input",
            Arrays.asList(alpha, beta)
        );

        assertEquals(AffectedDependencySelector.Kind.EMPTY, decision.getKind());
        assertEquals(Collections.emptyList(), decision.getTestClasses());
    }

    @Test
    public void addedArtifactRequiresTheFullTask() throws Exception {
        Path maps = temporary.newFolder("maps").toPath();
        AffectedDependencySelector.Artifact alpha = artifact("Alpha", "alpha-1");
        artifactMap(maps, "task", alpha);

        AffectedDependencySelector.Decision decision = AffectedDependencySelector.select(
            maps,
            "collector",
            "task",
            "runtime",
            "input",
            Arrays.asList(alpha, artifact("Added", "added-1"))
        );

        assertEquals(AffectedDependencySelector.Kind.ALL, decision.getKind());
    }

    @Test
    public void deletedArtifactRequiresTheFullTask() throws Exception {
        Path maps = temporary.newFolder("maps").toPath();
        AffectedDependencySelector.Artifact alpha = artifact("Alpha", "alpha-1");
        AffectedDependencySelector.Artifact beta = artifact("Beta", "beta-1");
        artifactMap(maps, "task", alpha, beta);

        AffectedDependencySelector.Decision decision = AffectedDependencySelector.select(
            maps,
            "collector",
            "task",
            "runtime",
            "input",
            Collections.singletonList(alpha)
        );

        assertEquals(AffectedDependencySelector.Kind.ALL, decision.getKind());
    }

    @Test
    public void mismatchedOrCorruptMapsRequireTheFullTask() throws Exception {
        Path maps = temporary.newFolder("maps").toPath();
        AffectedDependencySelector.Artifact alpha = artifact("Alpha", "alpha-1");
        artifactMap(maps, "task", alpha);

        assertEquals(
            AffectedDependencySelector.Kind.ALL,
            AffectedDependencySelector.select(
                maps,
                "other-collector",
                "task",
                "runtime",
                "input",
                Collections.singletonList(alpha)
            ).getKind()
        );

        Files.write(maps.resolve("map-" + sha256("task") + ".map"), "format=1\n".getBytes(StandardCharsets.UTF_8));
        assertEquals(
            AffectedDependencySelector.Kind.ALL,
            AffectedDependencySelector.select(
                maps,
                "collector",
                "task",
                "runtime",
                "input",
                Collections.singletonList(alpha)
            ).getKind()
        );
    }

    @Test
    public void missingStoredRecordRequiresTheFullTask() throws Exception {
        Path maps = temporary.newFolder("maps").toPath();
        AffectedDependencySelector.Artifact alpha = artifact("Alpha", "alpha-1");
        AffectedDependencySelector.Artifact beta = artifact("Beta", "beta-1");
        artifactMap(maps, "task", alpha, beta);
        Path map = maps.resolve("map-" + sha256("task") + ".map");
        List<String> lines = Files.readAllLines(map, StandardCharsets.UTF_8);
        Files.write(map, lines.subList(0, lines.size() - 1), StandardCharsets.UTF_8);

        AffectedDependencySelector.Decision decision = AffectedDependencySelector.select(
            maps,
            "collector",
            "task",
            "runtime",
            "input",
            Arrays.asList(alpha, artifact("Beta", "beta-2"))
        );

        assertEquals(AffectedDependencySelector.Kind.ALL, decision.getKind());
    }

    private static void artifactMap(
        Path maps,
        String task,
        AffectedDependencySelector.Artifact... artifacts
    ) throws Exception {
        StringBuilder payload = new StringBuilder();
        for (AffectedDependencySelector.Artifact artifact : artifacts) {
            payload.append("artifact=").append(serialized(artifact)).append('\n');
        }
        payload.append("record=").append(encode("fixture.AlphaTest")).append('|')
            .append(serialized(artifacts[0])).append('\n');
        if (artifacts.length > 1) {
            payload.append("record=").append(encode("fixture.BetaTest")).append('|')
                .append(serialized(artifacts[1])).append('\n');
        }
        StringBuilder content = new StringBuilder("format=1\n")
            .append("schema=3\n")
            .append("collector=").append(encode("collector")).append('\n')
            .append("task=").append(encode(task)).append('\n')
            .append("runtime=").append(encode("runtime")).append('\n')
            .append("input=").append(encode("input")).append('\n')
            .append("run=").append(encode("run")).append('\n')
            .append("artifacts=").append(artifacts.length).append('\n')
            .append("records=").append(artifacts.length > 1 ? 2 : 1).append('\n')
            .append("checksum=").append(sha256(payload.toString())).append('\n')
            .append(payload);
        Files.write(
            maps.resolve("map-" + sha256(task) + ".map"),
            content.toString().getBytes(StandardCharsets.UTF_8)
        );
    }

    private static AffectedDependencySelector.Artifact artifact(String className, String seed) throws Exception {
        return new AffectedDependencySelector.Artifact(className, "file:///classes/", sha256(seed));
    }

    private static String serialized(AffectedDependencySelector.Artifact artifact) {
        return encode(artifact.getClassName()) + "|" + encode(artifact.getCodeSource()) + "|" + artifact.getSha256();
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder(digest.length * 2);
        for (byte item : digest) {
            int unsigned = item & 0xff;
            if (unsigned < 16) result.append('0');
            result.append(Integer.toHexString(unsigned));
        }
        return result.toString();
    }
}
