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
import java.util.Comparator;
import java.util.List;
import java.util.Properties;

public final class AffectedMavenConfig {
    private static final int MAX_PROJECTS = 10_000;
    private static final long MAX_MANIFEST_BYTES = 8L * 1024L * 1024L;

    private AffectedMavenConfig() {
    }

    public static void write(Path target, List<ProjectConfig> projects) throws Exception {
        if (projects.isEmpty() || projects.size() > MAX_PROJECTS) throw new IllegalArgumentException("projects");
        List<ProjectConfig> sorted = new ArrayList<ProjectConfig>(projects);
        Collections.sort(sorted, new Comparator<ProjectConfig>() {
            @Override
            public int compare(ProjectConfig first, ProjectConfig second) {
                return first.basedir.compareTo(second.basedir);
            }
        });
        StringBuilder payload = new StringBuilder();
        String previous = null;
        for (ProjectConfig project : sorted) {
            if (project.basedir.equals(previous)) throw new IllegalArgumentException("duplicate project");
            payload.append(project.line());
            previous = project.basedir;
        }
        String content = "format=3\n" +
            "projects=" + sorted.size() + "\n" +
            "checksum=" + sha256(payload.toString().getBytes(StandardCharsets.UTF_8)) + "\n" +
            payload;
        if (content.getBytes(StandardCharsets.UTF_8).length > MAX_MANIFEST_BYTES) {
            throw new IllegalArgumentException("manifest size");
        }
        writeAtomically(target, content.getBytes(StandardCharsets.UTF_8));
    }

    public static ProjectConfig read(Path requestedManifest, Path requestedBasedir) throws Exception {
        Path manifest = secureFile(requestedManifest);
        long size = Files.size(manifest);
        if (size <= 0L || size > MAX_MANIFEST_BYTES) throw new IllegalStateException("manifest size");
        String content = new String(Files.readAllBytes(manifest), StandardCharsets.UTF_8);
        String[] lines = content.split("\n", -1);
        if (lines.length < 5 || !lines[lines.length - 1].isEmpty()) throw new IllegalStateException("manifest format");
        if (!"format=3".equals(lines[0])) throw new IllegalStateException("manifest format");
        int count = positiveInt(value(lines[1], "projects="));
        if (count > MAX_PROJECTS || lines.length != count + 4) throw new IllegalStateException("manifest count");
        String checksum = hash(value(lines[2], "checksum="));
        StringBuilder payload = new StringBuilder();
        for (int index = 3; index < lines.length - 1; index++) payload.append(lines[index]).append('\n');
        if (!checksum.equals(sha256(payload.toString().getBytes(StandardCharsets.UTF_8)))) {
            throw new IllegalStateException("manifest checksum");
        }

        Path basedir = secureDirectory(requestedBasedir);
        ProjectConfig match = null;
        String previous = null;
        for (int index = 3; index < lines.length - 1; index++) {
            ProjectConfig project = ProjectConfig.parse(lines[index]);
            if (previous != null && previous.compareTo(project.basedir) >= 0) {
                throw new IllegalStateException("manifest order");
            }
            previous = project.basedir;
            Path projectRoot = secureDirectory(Paths.get(project.basedir));
            if (projectRoot.equals(basedir) || Files.isSameFile(projectRoot, basedir)) {
                if (match != null) throw new IllegalStateException("duplicate project");
                match = project;
            }
        }
        if (match == null) throw new IllegalStateException("project config");
        return match;
    }

    private static void writeAtomically(Path requestedTarget, byte[] content) throws Exception {
        Path target = requestedTarget.toAbsolutePath().normalize();
        Path directory = target.getParent();
        if (directory == null || Files.isSymbolicLink(directory)) throw new IOException("manifest directory");
        Path realDirectory = directory.toRealPath(LinkOption.NOFOLLOW_LINKS);
        if (!Files.isDirectory(realDirectory) || !Files.isWritable(realDirectory)) {
            throw new IOException("manifest directory");
        }
        Path resolvedTarget = realDirectory.resolve(target.getFileName().toString());
        if (Files.exists(resolvedTarget, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(resolvedTarget)) {
            throw new IOException("manifest target");
        }
        Path temporary = Files.createTempFile(realDirectory, target.getFileName().toString() + ".", ".tmp");
        try {
            Files.write(temporary, content);
            try {
                Files.move(temporary, resolvedTarget, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException failure) {
                throw new IOException("atomic move unavailable", failure);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static Path secureFile(Path requested) throws Exception {
        Path absolute = requested.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(absolute)
            || !Files.isRegularFile(absolute, LinkOption.NOFOLLOW_LINKS)
            || !Files.isReadable(absolute)) {
            throw new IllegalStateException("manifest file");
        }
        return absolute.toRealPath(LinkOption.NOFOLLOW_LINKS);
    }

    private static Path secureDirectory(Path requested) throws Exception {
        Path absolute = requested.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(absolute)
            || !Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS)
            || !Files.isReadable(absolute)) {
            throw new IllegalStateException("project directory");
        }
        return absolute.toRealPath(LinkOption.NOFOLLOW_LINKS);
    }

    private static String value(String line, String prefix) {
        if (!line.startsWith(prefix) || line.length() == prefix.length()) {
            throw new IllegalStateException("manifest format");
        }
        return line.substring(prefix.length());
    }

    private static int positiveInt(String value) {
        if (!value.matches("[1-9][0-9]{0,4}")) throw new IllegalStateException("manifest count");
        return Integer.parseInt(value);
    }

    private static String hash(String value) {
        if (!value.matches("[0-9a-f]{64}")) throw new IllegalStateException("manifest checksum");
        return value;
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String value) {
        if (value.isEmpty() || !value.matches("[A-Za-z0-9_-]+")) throw new IllegalStateException("manifest value");
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
            if (decoded.trim().isEmpty() || !encode(decoded).equals(value)) throw new IllegalStateException("manifest value");
            return decoded;
        } catch (IllegalArgumentException failure) {
            throw new IllegalStateException("manifest value", failure);
        }
    }

    private static String sha256(byte[] value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
        StringBuilder result = new StringBuilder(digest.length * 2);
        for (byte item : digest) {
            int unsigned = item & 0xff;
            if (unsigned < 16) result.append('0');
            result.append(Integer.toHexString(unsigned));
        }
        return result.toString();
    }

    public static final class ProjectConfig {
        private final String basedir;
        private final String output;
        private final String maps;
        private final String version;
        private final String task;
        private final String display;
        private final String runtime;
        private final boolean allTests;
        private final boolean baselineEligible;
        private final boolean reuseForks;
        private final String codeSources;
        private final String testClasses;
        private final String classpath;

        public ProjectConfig(
            String basedir,
            String output,
            String maps,
            String version,
            String task,
            String display,
            String runtime,
            boolean allTests,
            boolean baselineEligible,
            boolean reuseForks,
            String codeSources,
            String testClasses,
            String classpath
        ) {
            this.basedir = required(basedir);
            this.output = required(output);
            this.maps = required(maps);
            this.version = required(version);
            this.task = required(task);
            this.display = required(display);
            this.runtime = required(runtime);
            this.allTests = allTests;
            this.baselineEligible = baselineEligible;
            this.reuseForks = reuseForks;
            this.codeSources = required(codeSources);
            this.testClasses = required(testClasses);
            this.classpath = required(classpath);
        }

        public void apply(Properties properties) {
            properties.setProperty("affected.collector.runner", "maven");
            properties.setProperty("affected.collector.output", output);
            properties.setProperty("affected.collector.maps", maps);
            properties.setProperty("affected.collector.version", version);
            properties.setProperty("affected.collector.task", task);
            properties.setProperty("affected.collector.display", display);
            properties.setProperty("affected.collector.runtime", runtime);
            properties.setProperty("affected.collector.all", Boolean.toString(allTests));
            properties.setProperty("affected.collector.baselineEligible", Boolean.toString(baselineEligible));
            properties.setProperty("affected.collector.reuseForks", Boolean.toString(reuseForks));
            properties.setProperty("affected.collector.codeSources", codeSources);
            properties.setProperty("affected.collector.testClasses", testClasses);
            properties.setProperty("affected.collector.classpath", classpath);
        }

        public String getOutput() {
            return output;
        }

        public String getMaps() {
            return maps;
        }

        public String getVersion() {
            return version;
        }

        public String getTask() {
            return task;
        }

        public String getDisplay() {
            return display;
        }

        public String getRuntime() {
            return runtime;
        }

        public boolean isAllTests() {
            return allTests;
        }

        public boolean isBaselineEligible() {
            return baselineEligible;
        }

        public boolean isReuseForks() {
            return reuseForks;
        }

        public String getCodeSources() {
            return codeSources;
        }

        public String getTestClasses() {
            return testClasses;
        }

        public String getClasspath() {
            return classpath;
        }

        private String line() {
            return "project=" + encode(basedir) + '|' +
                encode(output) + '|' +
                encode(maps) + '|' +
                encode(version) + '|' +
                encode(task) + '|' +
                encode(display) + '|' +
                encode(runtime) + '|' +
                allTests + '|' +
                baselineEligible + '|' +
                reuseForks + '|' +
                encode(codeSources) + '|' +
                encode(testClasses) + '|' +
                encode(classpath) + '\n';
        }

        private static ProjectConfig parse(String line) {
            String[] parts = value(line, "project=").split("\\|", -1);
            if (parts.length != 13
                || !("true".equals(parts[7]) || "false".equals(parts[7]))
                || !("true".equals(parts[8]) || "false".equals(parts[8]))
                || !("true".equals(parts[9]) || "false".equals(parts[9]))) {
                throw new IllegalStateException("project record");
            }
            ProjectConfig project = new ProjectConfig(
                decode(parts[0]),
                decode(parts[1]),
                decode(parts[2]),
                decode(parts[3]),
                decode(parts[4]),
                decode(parts[5]),
                decode(parts[6]),
                Boolean.parseBoolean(parts[7]),
                Boolean.parseBoolean(parts[8]),
                Boolean.parseBoolean(parts[9]),
                decode(parts[10]),
                decode(parts[11]),
                decode(parts[12])
            );
            if (!project.line().equals(line + '\n')) throw new IllegalStateException("project record");
            return project;
        }

        private static String required(String value) {
            if (value == null || value.trim().isEmpty() || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
                throw new IllegalArgumentException("project value");
            }
            return value;
        }
    }
}
