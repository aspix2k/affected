package com.aspix2k.affected.collector;

import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public final class AffectedDependencySelector {
    private static final long MAX_FILE_SIZE = 16L * 1024 * 1024;
    private static final int MAX_LINES = 200_000;
    private static final String FORMAT = "format=1";

    private AffectedDependencySelector() {
    }

    public static Decision select(
        Path requestedMapsRoot,
        String collectorVersion,
        String taskKey,
        String runtimeFingerprint,
        String inputFingerprint,
        List<Artifact> currentArtifacts
    ) {
        try {
            Baseline baseline = read(
                requestedMapsRoot,
                collectorVersion,
                taskKey,
                runtimeFingerprint,
                inputFingerprint
            );
            Map<ArtifactKey, Artifact> current = unique(currentArtifacts);
            if (!baseline.artifacts.keySet().containsAll(current.keySet())) return Decision.all();

            Set<ArtifactKey> changed = new LinkedHashSet<ArtifactKey>();
            for (Map.Entry<ArtifactKey, Artifact> entry : baseline.artifacts.entrySet()) {
                Artifact value = current.get(entry.getKey());
                if (value == null) return Decision.all();
                if (!value.sha256.equals(entry.getValue().sha256)) changed.add(entry.getKey());
            }

            Set<String> selected = new TreeSet<String>();
            for (Map.Entry<String, Set<ArtifactKey>> record : baseline.records.entrySet()) {
                for (ArtifactKey dependency : record.getValue()) {
                    if (changed.contains(dependency)) {
                        selected.add(record.getKey());
                        break;
                    }
                }
            }
            return selected.isEmpty() ? Decision.empty() : Decision.classes(selected);
        } catch (Exception failure) {
            return Decision.all();
        }
    }

    private static Baseline read(
        Path requestedMapsRoot,
        String collectorVersion,
        String taskKey,
        String runtimeFingerprint,
        String inputFingerprint
    ) throws Exception {
        required(collectorVersion);
        required(taskKey);
        required(runtimeFingerprint);
        required(inputFingerprint);
        Path mapsRoot = requestedMapsRoot.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(mapsRoot)) throw new IllegalStateException(mapsRoot.toString());
        Path resolvedRoot = mapsRoot.toRealPath(LinkOption.NOFOLLOW_LINKS);
        if (!Files.isDirectory(resolvedRoot) || !Files.isReadable(resolvedRoot)) {
            throw new IllegalStateException(resolvedRoot.toString());
        }
        Path map = resolvedRoot.resolve("map-" + sha256(taskKey) + ".map");
        List<String> lines = readLines(map);
        if (lines.size() < 11 || !FORMAT.equals(lines.get(0)) || !"schema=4".equals(lines.get(1))) {
            throw new IllegalStateException("map header");
        }
        if (!collectorVersion.equals(value(lines.get(2), "collector="))
            || !taskKey.equals(value(lines.get(3), "task="))
            || !runtimeFingerprint.equals(value(lines.get(4), "runtime="))
            || !inputFingerprint.equals(value(lines.get(5), "input="))) {
            throw new IllegalStateException("map identity");
        }
        required(value(lines.get(6), "run="));
        int expectedArtifacts = count(lines.get(7), "artifacts=");
        int expectedRecords = count(lines.get(8), "records=");
        String expectedChecksum = rawValue(lines.get(9), "checksum=");
        if (!expectedChecksum.matches("[0-9a-f]{64}")) throw new IllegalStateException("checksum");
        StringBuilder payload = new StringBuilder();
        for (int index = 10; index < lines.size(); index++) payload.append(lines.get(index)).append('\n');
        if (!sha256(payload.toString()).equals(expectedChecksum)) throw new IllegalStateException("checksum");

        List<Artifact> artifacts = new ArrayList<Artifact>();
        Map<String, Set<Artifact>> rawRecords = new LinkedHashMap<String, Set<Artifact>>();
        for (int index = 10; index < lines.size(); index++) {
            String line = lines.get(index);
            if (line.startsWith("artifact=")) {
                artifacts.add(parseArtifact(line.substring("artifact=".length())));
            } else if (line.startsWith("record=")) {
                parseRecord(line.substring("record=".length()), rawRecords);
            } else {
                throw new IllegalStateException("map entry");
            }
        }
        if (artifacts.size() != expectedArtifacts || rawRecords.size() != expectedRecords) {
            throw new IllegalStateException("map counts");
        }
        if (artifacts.isEmpty() || rawRecords.isEmpty()) throw new IllegalStateException("empty map");
        Map<ArtifactKey, Artifact> catalog = unique(artifacts);
        Map<String, Set<ArtifactKey>> records = new LinkedHashMap<String, Set<ArtifactKey>>();
        for (Map.Entry<String, Set<Artifact>> rawRecord : rawRecords.entrySet()) {
            Set<ArtifactKey> dependencies = new LinkedHashSet<ArtifactKey>();
            for (Artifact dependency : rawRecord.getValue()) {
                ArtifactKey key = dependency.key();
                if (!dependency.equals(catalog.get(key)) || !dependencies.add(key)) {
                    throw new IllegalStateException("record dependency");
                }
            }
            records.put(rawRecord.getKey(), dependencies);
        }
        return new Baseline(catalog, records);
    }

    private static void parseRecord(String value, Map<String, Set<Artifact>> records) throws Exception {
        int separator = value.indexOf('|');
        if (separator <= 0) throw new IllegalStateException("record");
        String testClass = decode(value.substring(0, separator));
        if (records.containsKey(testClass)) throw new IllegalStateException("duplicate test");
        if (separator == value.length() - 1) {
            records.put(testClass, Collections.<Artifact>emptySet());
            return;
        }
        String[] values = value.substring(separator + 1).split(";", -1);
        Set<Artifact> dependencies = new LinkedHashSet<Artifact>();
        for (String dependency : values) {
            if (!dependencies.add(parseArtifact(dependency))) throw new IllegalStateException("duplicate dependency");
        }
        records.put(testClass, dependencies);
    }

    private static Map<ArtifactKey, Artifact> unique(List<Artifact> artifacts) {
        Map<ArtifactKey, Artifact> result = new LinkedHashMap<ArtifactKey, Artifact>();
        for (Artifact artifact : artifacts) {
            if (artifact == null || result.put(artifact.key(), artifact) != null) {
                throw new IllegalStateException("duplicate artifact");
            }
        }
        return result;
    }

    private static Artifact parseArtifact(String value) throws Exception {
        String[] parts = value.split("\\|", -1);
        if (parts.length != 3) throw new IllegalStateException("artifact");
        return new Artifact(decode(parts[0]), decode(parts[1]), parts[2]);
    }

    private static List<String> readLines(Path path) throws Exception {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || !Files.isReadable(path)) {
            throw new IllegalStateException(path.toString());
        }
        long size = Files.size(path);
        if (size < 1 || size > MAX_FILE_SIZE) throw new IllegalStateException("map size");
        String content = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(Files.readAllBytes(path)))
            .toString();
        if (content.indexOf('\r') >= 0 || !content.endsWith("\n")) throw new IllegalStateException("map format");
        String[] values = content.substring(0, content.length() - 1).split("\n", -1);
        if (values.length > MAX_LINES) throw new IllegalStateException("map lines");
        List<String> result = new ArrayList<String>(values.length);
        Collections.addAll(result, values);
        return result;
    }

    private static String value(String line, String prefix) throws Exception {
        return decode(rawValue(line, prefix));
    }

    private static String rawValue(String line, String prefix) {
        if (!line.startsWith(prefix)) throw new IllegalStateException(prefix);
        String value = line.substring(prefix.length());
        return required(value);
    }

    private static int count(String line, String prefix) {
        int value = Integer.parseInt(rawValue(line, prefix));
        if (value < 0) throw new IllegalStateException(prefix);
        return value;
    }

    private static String decode(String value) throws Exception {
        byte[] bytes = Base64.getUrlDecoder().decode(value);
        if (!Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(value)) {
            throw new IllegalStateException("base64");
        }
        String decoded = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString();
        return required(decoded);
    }

    private static String required(String value) {
        if (value == null || value.trim().isEmpty()) throw new IllegalStateException("required value");
        return value;
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

    public enum Kind {
        ALL,
        CLASSES,
        EMPTY
    }

    public static final class Decision {
        private final Kind kind;
        private final List<String> testClasses;

        private Decision(Kind kind, List<String> testClasses) {
            this.kind = kind;
            this.testClasses = testClasses;
        }

        public Kind getKind() {
            return kind;
        }

        public List<String> getTestClasses() {
            return testClasses;
        }

        private static Decision all() {
            return new Decision(Kind.ALL, Collections.<String>emptyList());
        }

        private static Decision empty() {
            return new Decision(Kind.EMPTY, Collections.<String>emptyList());
        }

        private static Decision classes(Set<String> classes) {
            return new Decision(Kind.CLASSES, Collections.unmodifiableList(new ArrayList<String>(classes)));
        }
    }

    public static final class Artifact {
        private final String className;
        private final String codeSource;
        private final String sha256;

        public Artifact(String className, String codeSource, String sha256) {
            this.className = required(className);
            this.codeSource = required(codeSource);
            if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("sha256");
            this.sha256 = sha256;
        }

        public String getClassName() {
            return className;
        }

        public String getCodeSource() {
            return codeSource;
        }

        public String getSha256() {
            return sha256;
        }

        private ArtifactKey key() {
            return new ArtifactKey(className, codeSource);
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Artifact)) return false;
            Artifact artifact = (Artifact) other;
            return className.equals(artifact.className)
                && codeSource.equals(artifact.codeSource)
                && sha256.equals(artifact.sha256);
        }

        @Override
        public int hashCode() {
            int result = className.hashCode();
            result = 31 * result + codeSource.hashCode();
            result = 31 * result + sha256.hashCode();
            return result;
        }
    }

    private static final class ArtifactKey {
        private final String className;
        private final String codeSource;

        private ArtifactKey(String className, String codeSource) {
            this.className = className;
            this.codeSource = codeSource;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof ArtifactKey)) return false;
            ArtifactKey key = (ArtifactKey) other;
            return className.equals(key.className) && codeSource.equals(key.codeSource);
        }

        @Override
        public int hashCode() {
            return 31 * className.hashCode() + codeSource.hashCode();
        }
    }

    private static final class Baseline {
        private final Map<ArtifactKey, Artifact> artifacts;
        private final Map<String, Set<ArtifactKey>> records;

        private Baseline(Map<ArtifactKey, Artifact> artifacts, Map<String, Set<ArtifactKey>> records) {
            this.artifacts = artifacts;
            this.records = records;
        }
    }
}
