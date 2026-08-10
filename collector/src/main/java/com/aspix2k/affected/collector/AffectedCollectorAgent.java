package com.aspix2k.affected.collector;

import java.io.File;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.lang.instrument.Instrumentation;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class AffectedCollectorAgent {
    static final String CODE_SOURCES_PROPERTY = "affected.collector.codeSources";
    private static final CollectorState STATE = new CollectorState();
    private static volatile AffectedMavenConfig.ProjectConfig mavenConfig;

    private AffectedCollectorAgent() {
    }

    public static void premain(String arguments, Instrumentation instrumentation) {
        try {
            ensureWorkerId();
            if (arguments != null && !arguments.trim().isEmpty()) {
                configureMaven(arguments, Paths.get(System.getProperty("user.dir")), System.getProperties());
            }
            STATE.configure(codeSources());
            instrumentation.addTransformer(new CollectorTransformer(STATE), false);
        } catch (Exception failure) {
            STATE.fail(failure);
        }
    }

    public static List<Dependency> dependencies() {
        return STATE.snapshot();
    }

    public static boolean isSupported() {
        return STATE.isSupported();
    }

    public static void markUnsupported() {
        STATE.fail(new IllegalStateException());
    }

    static CollectorState state() {
        return STATE;
    }

    static void reapplyMavenConfig(java.util.Properties properties) {
        AffectedMavenConfig.ProjectConfig current = mavenConfig;
        if (current != null) current.apply(properties);
    }

    static void configureMaven(String manifest, Path basedir, java.util.Properties properties) throws Exception {
        mavenConfig = AffectedMavenConfig.read(Paths.get(manifest), basedir);
        mavenConfig.apply(properties);
    }

    static void resetForTests() {
        STATE.reset();
        mavenConfig = null;
    }

    static Set<Path> codeSources() {
        String value = System.getProperty(CODE_SOURCES_PROPERTY);
        if (value == null || value.trim().isEmpty()) throw new IllegalStateException(CODE_SOURCES_PROPERTY);
        Set<Path> sources = new HashSet<Path>();
        for (String entry : value.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            if (entry.trim().isEmpty()) throw new IllegalStateException(CODE_SOURCES_PROPERTY);
            sources.add(Paths.get(entry).toAbsolutePath().normalize());
        }
        return sources;
    }

    private static void ensureWorkerId() {
        String worker = System.getProperty(CollectorOutput.WORKER_PROPERTY);
        if (worker == null || worker.trim().isEmpty()) {
            System.setProperty(CollectorOutput.WORKER_PROPERTY, UUID.randomUUID().toString());
        }
    }

    static final class CollectorTransformer implements ClassFileTransformer {
        private final CollectorState state;

        CollectorTransformer(CollectorState state) {
            this.state = state;
        }

        @Override
        public byte[] transform(
            ClassLoader loader,
            String className,
            Class<?> classBeingRedefined,
            ProtectionDomain protectionDomain,
            byte[] classfileBuffer
        ) throws IllegalClassFormatException {
            try {
                state.observe(className, protectionDomain);
            } catch (Exception failure) {
                state.fail(failure);
            }
            return null;
        }
    }

    static final class CollectorState {
        private final AtomicBoolean initialized = new AtomicBoolean();
        private final AtomicReference<Exception> failure = new AtomicReference<Exception>();
        private final ConcurrentMap<String, Dependency> dependencies = new ConcurrentHashMap<String, Dependency>();
        private volatile Map<Path, Path> codeSources = Collections.emptyMap();

        void configure(Collection<Path> sources) throws Exception {
            initialized.set(false);
            failure.set(null);
            dependencies.clear();
            codeSources = Collections.emptyMap();
            if (sources.isEmpty()) throw new IllegalStateException(CODE_SOURCES_PROPERTY);
            Map<Path, Path> resolved = new HashMap<Path, Path>();
            for (Path source : sources) {
                Path requested = source.toAbsolutePath().normalize();
                Path path = requested.toRealPath();
                if (!Files.isDirectory(path) || !Files.isReadable(path)) throw new IllegalStateException(path.toString());
                resolved.put(requested, path);
                resolved.put(path, path);
            }
            codeSources = Collections.unmodifiableMap(resolved);
            initialized.set(true);
        }

        void observe(String internalClassName, ProtectionDomain protectionDomain) throws Exception {
            if (!initialized.get() || internalClassName == null || protectionDomain == null) return;
            if (protectionDomain.getCodeSource() == null || protectionDomain.getCodeSource().getLocation() == null) return;
            URI location = protectionDomain.getCodeSource().getLocation().toURI().normalize();
            if (!"file".equalsIgnoreCase(location.getScheme())) return;
            Path source = codeSources.get(Paths.get(location).toAbsolutePath().normalize());
            if (source == null) return;

            String className = internalClassName.replace('/', '.');
            String codeSource = source.toUri().toString();
            String key = className + "\n" + codeSource;
            if (dependencies.containsKey(key)) return;

            Path classFile = source.resolve(internalClassName + ".class").normalize().toRealPath();
            if (!classFile.startsWith(source) || !Files.isRegularFile(classFile) || !Files.isReadable(classFile)) {
                throw new IllegalStateException(internalClassName);
            }
            byte[] bytes = Files.readAllBytes(classFile);
            Dependency dependency = new Dependency(className, codeSource, sha256(bytes));
            Dependency previous = dependencies.putIfAbsent(key, dependency);
            if (previous != null && !previous.equals(dependency)) throw new IllegalStateException(dependency.getClassName());
        }

        void fail(Exception exception) {
            failure.compareAndSet(null, exception);
        }

        boolean isSupported() {
            return initialized.get() && failure.get() == null;
        }

        List<Dependency> snapshot() {
            List<Dependency> result = new ArrayList<Dependency>(dependencies.values());
            Collections.sort(result, new Comparator<Dependency>() {
                @Override
                public int compare(Dependency first, Dependency second) {
                    int classOrder = first.className.compareTo(second.className);
                    return classOrder != 0 ? classOrder : first.codeSource.compareTo(second.codeSource);
                }
            });
            return result;
        }

        void reset() {
            initialized.set(false);
            failure.set(null);
            dependencies.clear();
            codeSources = Collections.emptyMap();
        }
    }

    public static final class Dependency {
        private final String className;
        private final String codeSource;
        private final String sha256;

        Dependency(String className, String codeSource, String sha256) {
            this.className = className;
            this.codeSource = codeSource;
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

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Dependency)) return false;
            Dependency dependency = (Dependency) other;
            return className.equals(dependency.className)
                && codeSource.equals(dependency.codeSource)
                && sha256.equals(dependency.sha256);
        }

        @Override
        public int hashCode() {
            int result = className.hashCode();
            result = 31 * result + codeSource.hashCode();
            return 31 * result + sha256.hashCode();
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder result = new StringBuilder(digest.length * 2);
        for (byte value : digest) {
            int unsigned = value & 0xff;
            if (unsigned < 16) result.append('0');
            result.append(Integer.toHexString(unsigned));
        }
        return result.toString();
    }
}
