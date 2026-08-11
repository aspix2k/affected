package com.aspix2k.affected.collector;

import org.junit.platform.engine.FilterResult;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.TestSource;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.PostDiscoveryFilter;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

public final class AffectedMavenFilter implements PostDiscoveryFilter {
    private static final int MAX_CLASSES = 100_000;
    private static final int MAX_FILES = 200_000;
    private static final int BUFFER_SIZE = 64 * 1024;
    private final Properties properties;
    private volatile Selection selection;

    public AffectedMavenFilter() {
        this(System.getProperties());
    }

    AffectedMavenFilter(Properties properties) {
        this.properties = properties;
    }

    @Override
    public FilterResult apply(TestDescriptor descriptor) {
        Selection current = selection();
        String testClass = stableClass(descriptor);
        if (current.kind == AffectedDependencySelector.Kind.ALL || testClass == null) {
            return FilterResult.included("Affected full Maven fallback");
        }
        boolean included = current.kind == AffectedDependencySelector.Kind.CLASSES
            && current.testClasses.contains(testClass);
        return FilterResult.includedIf(included);
    }

    private Selection selection() {
        Selection current = selection;
        if (current != null) return current;
        synchronized (this) {
            if (selection != null) return selection;
            selection = decide();
            return selection;
        }
    }

    private Selection decide() {
        try {
            AffectedCollectorAgent.reapplyMavenConfig(properties);
            if (!"maven".equals(required(properties, "affected.collector.runner"))) {
                return Selection.full(AffectedDependencySelector.Reason.COLLECTOR_ERROR);
            }
            Snapshot snapshot = snapshot(properties);
            boolean allTests = requiredBoolean(properties, "affected.collector.all");
            boolean baselineEligible = requiredBoolean(properties, "affected.collector.baselineEligible");
            AffectedDependencySelector.Decision decision = allTests
                ? AffectedDependencySelector.select(
                    directory(properties, "affected.collector.maps", false),
                    required(properties, "affected.collector.version"),
                    required(properties, "affected.collector.task"),
                    snapshot.runtimeFingerprint,
                    snapshot.inputFingerprint,
                    snapshot.artifacts
                )
                : AffectedDependencySelector.Decision.full(
                    AffectedDependencySelector.Reason.EXISTING_TEST_FILTER
                );
            Selection selected = Selection.from(decision);
            Path output = taskOutput(
                directory(properties, "affected.collector.output", true),
                required(properties, "affected.collector.task")
            );
            writeCatalog(output, snapshot.artifacts);
            writeTask(
                output,
                required(properties, "affected.collector.task"),
                snapshot.runtimeFingerprint,
                snapshot.inputFingerprint,
                baselineEligible && allTests && selected.kind == AffectedDependencySelector.Kind.ALL
            );
            writeDecision(output, selected.description);
            properties.setProperty("affected.collector.output", output.toString());
            return selected;
        } catch (Exception failure) {
            writeFallback(failure);
            Selection fallback = Selection.full(AffectedDependencySelector.Reason.COLLECTOR_ERROR);
            writeFallbackDecision(fallback);
            return fallback;
        }
    }

    private void writeFallbackDecision(Selection fallback) {
        try {
            Path output = taskOutput(
                directory(properties, "affected.collector.output", true),
                required(properties, "affected.collector.task")
            );
            writeDecision(output, fallback.description);
        } catch (Exception ignored) {
        }
    }

    private void writeFallback(Exception failure) {
        try {
            Path output = directory(properties, "affected.collector.output", true);
            writeAtomically(
                output.resolve("maven-fallback.manifest"),
                "format=1\nreason=" + encode(failure.getClass().getName()) + "\n" +
                    "detail=" + encode(String.valueOf(failure.getMessage())) + "\n"
            );
        } catch (Exception ignored) {
        }
    }

    static Snapshot snapshot(Properties properties) throws Exception {
        List<Path> codeSources = paths(required(properties, "affected.collector.codeSources"));
        List<Path> testClasses = paths(required(properties, "affected.collector.testClasses"));
        List<Path> classpath = paths(required(properties, "affected.collector.classpath"));
        List<Path> resolvedSources = secureDirectories(codeSources);
        List<Path> resolvedTests = secureDirectories(testClasses);
        return new Snapshot(
            runtimeFingerprint(properties),
            inputFingerprint(resolvedTests, classpath, new HashSet<Path>(resolvedSources)),
            classCatalog(resolvedSources)
        );
    }

    private static String runtimeFingerprint(Properties properties) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        update(digest, required(properties, "affected.collector.runtime"));
        update(digest, properties.getProperty("java.version", System.getProperty("java.version", "")));
        update(digest, properties.getProperty("user.dir", System.getProperty("user.dir", "")));
        List<String> environment = new ArrayList<String>();
        for (Map.Entry<String, String> entry : System.getenv().entrySet()) {
            if ("MAVEN_CMD_LINE_ARGS".equals(entry.getKey())) continue;
            environment.add(entry.getKey() + "\n" + entry.getValue());
        }
        Collections.sort(environment);
        for (String value : environment) update(digest, value);
        return hex(digest.digest());
    }

    private static String inputFingerprint(
        List<Path> testClasses,
        List<Path> classpath,
        Set<Path> codeSources
    ) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (Path root : testClasses) {
            update(digest, "testClasses");
            update(digest, root.toUri().toString());
            hashTree(digest, root, false);
        }
        Set<Path> testRoots = new HashSet<Path>(testClasses);
        for (int index = 0; index < classpath.size(); index++) {
            Path requested = classpath.get(index).toAbsolutePath().normalize();
            update(digest, "classpath");
            update(digest, Integer.toString(index));
            if (Files.isSymbolicLink(requested)) throw new IllegalStateException(requested.toString());
            if (Files.isRegularFile(requested, LinkOption.NOFOLLOW_LINKS)) {
                Path real = requested.toRealPath();
                update(digest, "file");
                update(digest, real.toUri().toString());
                update(digest, fileHash(real));
            } else if (Files.isDirectory(requested, LinkOption.NOFOLLOW_LINKS)) {
                Path real = requested.toRealPath();
                update(digest, "directory");
                update(digest, real.toUri().toString());
                if (!testRoots.contains(real)) hashTree(digest, real, codeSources.contains(real));
            } else if (!Files.exists(requested, LinkOption.NOFOLLOW_LINKS)) {
                update(digest, "missing");
                update(digest, requested.toString());
            } else {
                throw new IllegalStateException(requested.toString());
            }
        }
        return hex(digest.digest());
    }

    private static void hashTree(MessageDigest digest, Path root, boolean excludeClasses) throws Exception {
        AtomicInteger count = new AtomicInteger();
        try (Stream<Path> entries = Files.walk(root)) {
            List<Path> files = new ArrayList<Path>();
            entries.forEach(path -> {
                if (count.incrementAndGet() > MAX_FILES || Files.isSymbolicLink(path)) {
                    throw new IllegalStateException(path.toString());
                }
                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    && (!excludeClasses || !path.getFileName().toString().endsWith(".class"))) {
                    files.add(path);
                }
            });
            Collections.sort(files);
            for (Path file : files) {
                update(digest, root.relativize(file).toString().replace(File.separatorChar, '/'));
                update(digest, fileHash(file));
            }
        }
    }

    private static List<AffectedDependencySelector.Artifact> classCatalog(List<Path> roots) throws Exception {
        List<AffectedDependencySelector.Artifact> artifacts = new ArrayList<AffectedDependencySelector.Artifact>();
        Set<String> identities = new HashSet<String>();
        for (Path root : roots) {
            AtomicInteger count = new AtomicInteger();
            try (Stream<Path> entries = Files.walk(root)) {
                List<Path> classes = new ArrayList<Path>();
                entries.forEach(path -> {
                    if (count.incrementAndGet() > MAX_FILES || Files.isSymbolicLink(path)) {
                        throw new IllegalStateException(path.toString());
                    }
                    if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                        && path.getFileName().toString().endsWith(".class")) {
                        classes.add(path);
                    }
                });
                Collections.sort(classes);
                for (Path file : classes) {
                    if (artifacts.size() >= MAX_CLASSES) throw new IllegalStateException("class catalog");
                    String relative = root.relativize(file).toString().replace(File.separatorChar, '/');
                    String className = relative.substring(0, relative.length() - ".class".length()).replace('/', '.');
                    String source = root.toUri().toString();
                    if (!identities.add(className + "\n" + source)) throw new IllegalStateException(className);
                    artifacts.add(new AffectedDependencySelector.Artifact(className, source, hex(fileHash(file))));
                }
            }
        }
        Collections.sort(artifacts, new Comparator<AffectedDependencySelector.Artifact>() {
            @Override
            public int compare(
                AffectedDependencySelector.Artifact first,
                AffectedDependencySelector.Artifact second
            ) {
                int classes = first.getClassName().compareTo(second.getClassName());
                return classes != 0 ? classes : first.getCodeSource().compareTo(second.getCodeSource());
            }
        });
        return artifacts;
    }

    private static List<Path> secureDirectories(List<Path> paths) throws Exception {
        List<Path> result = new ArrayList<Path>();
        for (Path requested : paths) {
            Path absolute = requested.toAbsolutePath().normalize();
            if (Files.isSymbolicLink(absolute)
                || !Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS)
                || !Files.isReadable(absolute)) {
                throw new IllegalStateException(absolute.toString());
            }
            Path real = absolute.toRealPath();
            if (!result.contains(real)) result.add(real);
        }
        Collections.sort(result);
        return result;
    }

    private static List<Path> paths(String value) {
        List<Path> result = new ArrayList<Path>();
        for (String item : value.split(java.util.regex.Pattern.quote(File.pathSeparator), -1)) {
            if (item.trim().isEmpty()) throw new IllegalStateException("path list");
            result.add(Paths.get(item));
        }
        if (result.isEmpty()) throw new IllegalStateException("path list");
        return result;
    }

    private static Path directory(Properties properties, String name, boolean writable) throws Exception {
        Path requested = Paths.get(required(properties, name)).toAbsolutePath().normalize();
        if (Files.isSymbolicLink(requested)) throw new IllegalStateException(name);
        Path real = requested.toRealPath(LinkOption.NOFOLLOW_LINKS);
        if (!Files.isDirectory(real) || !Files.isReadable(real) || (writable && !Files.isWritable(real))) {
            throw new IllegalStateException(name);
        }
        return real;
    }

    private static Path taskOutput(Path root, String taskKey) throws Exception {
        Path requested = root.resolve("task-" + sha256(taskKey));
        if (Files.exists(requested, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(requested) || !Files.isDirectory(requested, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException(requested.toString());
            }
        } else {
            try {
                Files.createDirectory(requested);
            } catch (FileAlreadyExistsException ignored) {
                if (Files.isSymbolicLink(requested)
                    || !Files.isDirectory(requested, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IllegalStateException(requested.toString());
                }
            }
        }
        Path real = requested.toRealPath(LinkOption.NOFOLLOW_LINKS);
        if (!root.equals(real.getParent()) || !Files.isWritable(real)) throw new IllegalStateException(real.toString());
        return real;
    }

    private static void writeTask(
        Path directory,
        String task,
        String runtime,
        String input,
        boolean all
    ) throws Exception {
        String content = "format=1\n" +
            "task=" + encode(task) + "\n" +
            "runtime=" + encode(runtime) + "\n" +
            "input=" + encode(input) + "\n" +
            "all=" + all + "\n";
        writeAtomically(directory.resolve("task.manifest"), content);
    }

    private static void writeDecision(Path directory, String decision) throws Exception {
        writeAtomically(
            directory.resolve("decision.manifest"),
            "format=1\ndecision=" + encode(decision) + "\n"
        );
    }

    private static void writeCatalog(
        Path directory,
        List<AffectedDependencySelector.Artifact> artifacts
    ) throws Exception {
        StringBuilder content = new StringBuilder("format=1\n");
        for (AffectedDependencySelector.Artifact artifact : artifacts) {
            content.append("artifact=")
                .append(encode(artifact.getClassName())).append('|')
                .append(encode(artifact.getCodeSource())).append('|')
                .append(artifact.getSha256()).append('\n');
        }
        writeAtomically(directory.resolve("catalog.manifest"), content.toString());
    }

    private static void writeAtomically(Path target, String content) throws Exception {
        Path directory = target.getParent();
        Path temporary = Files.createTempFile(directory, target.getFileName().toString() + ".", ".tmp");
        try {
            Files.write(temporary, content.getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException failure) {
                throw new IOException("atomic move unavailable", failure);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String stableClass(TestDescriptor descriptor) {
        TestDescriptor current = descriptor;
        while (current != null) {
            Optional<TestSource> source = current.getSource();
            if (source.isPresent()) {
                if (source.get() instanceof ClassSource) return ((ClassSource) source.get()).getClassName();
                if (source.get() instanceof MethodSource) return ((MethodSource) source.get()).getClassName();
            }
            Optional<TestDescriptor> parent = current.getParent();
            current = parent.isPresent() ? parent.get() : null;
        }
        return null;
    }

    private static byte[] fileHash(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) digest.update(buffer, 0, read);
            }
        }
        return digest.digest();
    }

    private static void update(MessageDigest digest, byte[] value) {
        digest.update((byte) (value.length >>> 24));
        digest.update((byte) (value.length >>> 16));
        digest.update((byte) (value.length >>> 8));
        digest.update((byte) value.length);
        digest.update(value);
    }

    private static void update(MessageDigest digest, String value) {
        update(digest, value.getBytes(StandardCharsets.UTF_8));
    }

    private static String required(Properties properties, String name) {
        String value = properties.getProperty(name);
        if (value == null || value.trim().isEmpty()) throw new IllegalStateException(name);
        return value;
    }

    private static boolean requiredBoolean(Properties properties, String name) {
        String value = required(properties, name);
        if (!("true".equals(value) || "false".equals(value))) throw new IllegalStateException(name);
        return Boolean.parseBoolean(value);
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(String value) throws Exception {
        return hex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static String hex(byte[] value) {
        StringBuilder result = new StringBuilder(value.length * 2);
        for (byte item : value) {
            int unsigned = item & 0xff;
            if (unsigned < 16) result.append('0');
            result.append(Integer.toHexString(unsigned));
        }
        return result.toString();
    }

    static final class Snapshot {
        private final String runtimeFingerprint;
        private final String inputFingerprint;
        private final List<AffectedDependencySelector.Artifact> artifacts;

        private Snapshot(
            String runtimeFingerprint,
            String inputFingerprint,
            List<AffectedDependencySelector.Artifact> artifacts
        ) {
            this.runtimeFingerprint = runtimeFingerprint;
            this.inputFingerprint = inputFingerprint;
            this.artifacts = Collections.unmodifiableList(new ArrayList<AffectedDependencySelector.Artifact>(artifacts));
        }

        String getRuntimeFingerprint() {
            return runtimeFingerprint;
        }

        String getInputFingerprint() {
            return inputFingerprint;
        }
    }

    private static final class Selection {
        private final AffectedDependencySelector.Kind kind;
        private final Set<String> testClasses;
        private final String description;

        private Selection(AffectedDependencySelector.Kind kind, Set<String> testClasses, String description) {
            this.kind = kind;
            this.testClasses = testClasses;
            this.description = description;
        }

        private static Selection from(AffectedDependencySelector.Decision decision) {
            Set<String> testClasses = new TreeSet<String>(decision.getTestClasses());
            return new Selection(decision.getKind(), testClasses, decision.describe());
        }

        private static Selection full(AffectedDependencySelector.Reason reason) {
            return from(AffectedDependencySelector.Decision.full(reason));
        }
    }
}
