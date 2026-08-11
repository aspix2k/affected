package com.aspix2k.affected.collector;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Set;

public final class CollectorOutput {
    public static final String OUTPUT_PROPERTY = "affected.collector.output";
    public static final String WORKER_PROPERTY = "affected.collector.worker";
    private static final String RUNNER_PROPERTY = "affected.collector.runner";
    private static final String REUSE_FORKS_PROPERTY = "affected.collector.reuseForks";
    private static final String GRADLE_WORKER_PROPERTY = "org.gradle.test.worker";
    private final String workerId;
    private final Path workerDirectory;

    private CollectorOutput(Path root, String workerId) throws Exception {
        this.workerId = workerId;
        Path requestedRoot = root.toAbsolutePath().normalize();
        Files.createDirectories(requestedRoot);
        if (Files.isSymbolicLink(requestedRoot)) throw new IOException(requestedRoot.toString());
        Path resolvedRoot = requestedRoot.toRealPath();
        if (!Files.isDirectory(resolvedRoot) || !Files.isWritable(resolvedRoot)) {
            throw new IOException(resolvedRoot.toString());
        }
        Path requestedWorker = resolvedRoot.resolve("worker-" + sha256(workerId));
        if (Files.exists(requestedWorker, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(requestedWorker)
                || !Files.isDirectory(requestedWorker, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException(requestedWorker.toString());
            }
        } else {
            Files.createDirectory(requestedWorker);
        }
        workerDirectory = requestedWorker.toRealPath(LinkOption.NOFOLLOW_LINKS);
        if (!workerDirectory.getParent().equals(resolvedRoot) || !Files.isWritable(workerDirectory)) {
            throw new IOException(workerDirectory.toString());
        }
        Files.deleteIfExists(workerDirectory.resolve("complete.manifest"));
        try (java.nio.file.DirectoryStream<Path> files = Files.newDirectoryStream(workerDirectory)) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                if (name.matches("test-[0-9a-f]{64}\\.map")) Files.delete(file);
            }
        }
        writeAtomically(
            workerDirectory.resolve("started.manifest"),
            ("format=1\nworker=" + encode(workerId) + "\n").getBytes(StandardCharsets.UTF_8)
        );
    }

    public static CollectorOutput fromSystemProperties() throws Exception {
        return fromSystemProperties(Collections.<String>emptySet());
    }

    public static CollectorOutput fromSystemProperties(Set<String> expectedTestClasses) throws Exception {
        return fromSystemProperties(expectedTestClasses, null);
    }

    static CollectorOutput fromSystemProperties(
        Set<String> expectedTestClasses,
        String unsupportedWorker
    ) throws Exception {
        String output = required(System.getProperty(OUTPUT_PROPERTY), OUTPUT_PROPERTY);
        String worker;
        if ("maven".equals(System.getProperty(RUNNER_PROPERTY))
            && "false".equals(System.getProperty(REUSE_FORKS_PROPERTY))) {
            String fork = required(System.getProperty(WORKER_PROPERTY), WORKER_PROPERTY);
            worker = expectedTestClasses != null && expectedTestClasses.size() == 1
                ? "fork:" + fork + "|class:" + expectedTestClasses.iterator().next()
                : "fork:" + fork + "|unsupported:" + sha256(
                    required(unsupportedWorker, "affected.collector.expectedTests")
                );
        } else {
            worker = System.getProperty(WORKER_PROPERTY);
            if (worker == null || worker.trim().isEmpty()) worker = System.getProperty(GRADLE_WORKER_PROPERTY);
        }
        worker = required(worker, WORKER_PROPERTY);
        return new CollectorOutput(Paths.get(output).toAbsolutePath().normalize(), worker);
    }

    public void writeMap(String testClass, List<AffectedCollectorAgent.Dependency> dependencies) throws Exception {
        if (testClass == null || testClass.trim().isEmpty() || dependencies == null) {
            throw new IllegalArgumentException("test dependency map");
        }
        List<AffectedCollectorAgent.Dependency> sorted = new ArrayList<AffectedCollectorAgent.Dependency>(dependencies);
        Collections.sort(sorted, (first, second) -> {
            int classOrder = first.getClassName().compareTo(second.getClassName());
            return classOrder != 0 ? classOrder : first.getCodeSource().compareTo(second.getCodeSource());
        });
        StringBuilder content = new StringBuilder("format=1\n");
        content.append("test=").append(encode(testClass)).append('\n');
        for (AffectedCollectorAgent.Dependency dependency : sorted) {
            content.append("dependency=")
                .append(encode(dependency.getClassName())).append('|')
                .append(encode(dependency.getCodeSource())).append('|')
                .append(dependency.getSha256()).append('\n');
        }
        writeAtomically(
            workerDirectory.resolve("test-" + sha256(testClass) + ".map"),
            content.toString().getBytes(StandardCharsets.UTF_8)
        );
    }

    public void writeCompletion(boolean supported, Set<String> testClasses) throws Exception {
        if (supported && testClasses.isEmpty()) throw new IllegalArgumentException("completed tests");
        List<String> sorted = new ArrayList<String>(testClasses);
        Collections.sort(sorted);
        StringBuilder content = new StringBuilder("format=1\n");
        content.append("worker=").append(encode(workerId)).append('\n');
        content.append("supported=").append(supported).append('\n');
        for (String testClass : sorted) content.append("test=").append(encode(testClass)).append('\n');
        writeAtomically(workerDirectory.resolve("complete.manifest"), content.toString().getBytes(StandardCharsets.UTF_8));
    }

    public void writeExpected(boolean supported, Set<String> testClasses) throws Exception {
        if (supported && testClasses.isEmpty()) throw new IllegalArgumentException("expected tests");
        List<String> sorted = new ArrayList<String>(testClasses);
        Collections.sort(sorted);
        StringBuilder content = new StringBuilder("format=1\n");
        content.append("supported=").append(supported).append('\n');
        for (String testClass : sorted) content.append("test=").append(encode(testClass)).append('\n');
        writeAtomically(
            workerDirectory.resolve("expected.manifest"),
            content.toString().getBytes(StandardCharsets.UTF_8)
        );
    }

    private static void writeAtomically(Path target, byte[] bytes) throws Exception {
        Path directory = target.getParent();
        if (!Files.isDirectory(directory) || !Files.isWritable(directory)) throw new IOException(directory.toString());
        Path temporary = Files.createTempFile(directory, target.getFileName().toString() + ".", ".tmp");
        try {
            Files.write(temporary, bytes);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException failure) {
                throw new IOException("atomic move unavailable", failure);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String required(String value, String property) {
        if (value == null || value.trim().isEmpty()) throw new IllegalStateException(property);
        return value;
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
