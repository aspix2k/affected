package com.aspix2k.affected.collector;

import java.io.File;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.ProtectionDomain;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

public final class AffectedCollectorAgent {
    static final String CODE_SOURCES_PROPERTY = "affected.collector.codeSources";
    static final String TEST_CODE_SOURCES_PROPERTY = "affected.collector.testClasses";
    private static final long[] THREAD_EXECUTION_IDS = new long[65_536];
    private static final CollectorState STATE = new CollectorState();
    private static volatile AffectedMavenConfig.ProjectConfig mavenConfig;

    private AffectedCollectorAgent() {
    }

    public static void premain(String arguments, Instrumentation instrumentation) {
        try {
            if (arguments != null && !arguments.trim().isEmpty()) {
                configureMaven(arguments, Paths.get(System.getProperty("user.dir")), System.getProperties());
            }
            STATE.configure(codeSources(), paths(TEST_CODE_SOURCES_PROPERTY));
            AffectedClassInstrumenter.initialize();
            instrumentation.addTransformer(new CollectorTransformer(STATE), false);
        } catch (Throwable failure) {
            STATE.fail(failure);
        }
    }

    public static List<Dependency> dependencies(String testClass) {
        return STATE.snapshot(testClass);
    }

    public static void beginExecution(String token, String testClass) {
        try {
            STATE.beginExecution(token, testClass);
        } catch (Throwable failure) {
            STATE.fail(failure);
        }
    }

    public static void endExecution(String token) {
        try {
            STATE.endExecution(token);
        } catch (Throwable failure) {
            STATE.fail(failure);
        }
    }

    public static void junit4Started(Object description) {
        AffectedJUnit4Bridge.started(description);
    }

    public static void junit4Finished(Object description) {
        AffectedJUnit4Bridge.finished(description);
    }

    public static void junit4SuiteFinished(Object description) {
        AffectedJUnit4Bridge.suiteFinished(description);
    }

    public static void junit4RunFinished() {
        AffectedJUnit4Bridge.runFinished();
    }

    public static void hit(Class<?> type) {
        try {
            STATE.hit(type);
        } catch (Throwable failure) {
            STATE.fail(failure);
        }
    }

    public static void hitProduction(Class<?> type) {
        try {
            STATE.hitProduction(type);
        } catch (Throwable failure) {
            STATE.fail(failure);
        }
    }

    public static long executionId() {
        return STATE.executionId();
    }

    public static long currentExecutionId() {
        long threadId = Thread.currentThread().getId();
        if (threadId >= 0L && threadId < THREAD_EXECUTION_IDS.length) {
            long executionId = THREAD_EXECUTION_IDS[(int) threadId];
            if (executionId != 0L) return executionId;
        }
        return executionId();
    }

    public static void hitField(Field field) {
        if (field == null) return;
        hit(field.getDeclaringClass());
        hit(field.getType());
    }

    public static void hitFields(Field[] fields) {
        if (fields == null) return;
        for (Field field : fields) hitField(field);
    }

    public static void hitMethod(Method method) {
        if (method == null) return;
        hit(method.getDeclaringClass());
        hit(method.getReturnType());
        for (Class<?> type : method.getParameterTypes()) hit(type);
        for (Class<?> type : method.getExceptionTypes()) hit(type);
    }

    public static void hitMethods(Method[] methods) {
        if (methods == null) return;
        for (Method method : methods) hitMethod(method);
    }

    public static void hitConstructor(Constructor<?> constructor) {
        if (constructor == null) return;
        hit(constructor.getDeclaringClass());
        for (Class<?> type : constructor.getParameterTypes()) hit(type);
        for (Class<?> type : constructor.getExceptionTypes()) hit(type);
    }

    public static void hitConstructors(Constructor<?>[] constructors) {
        if (constructors == null) return;
        for (Constructor<?> constructor : constructors) hitConstructor(constructor);
    }

    public static void hitClasses(Class<?>[] classes) {
        if (classes == null) return;
        for (Class<?> type : classes) hit(type);
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
        return paths(CODE_SOURCES_PROPERTY);
    }

    private static Set<Path> paths(String property) {
        String value = System.getProperty(property);
        if (value == null || value.trim().isEmpty()) throw new IllegalStateException(property);
        Set<Path> sources = new HashSet<Path>();
        for (String entry : value.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            if (entry.trim().isEmpty()) throw new IllegalStateException(property);
            sources.add(Paths.get(entry).toAbsolutePath().normalize());
        }
        return sources;
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
                if (!state.isSupported()) return null;
                if ("org/junit/runner/notification/RunNotifier".equals(className)
                    && AffectedJUnit4Bridge.enabled()) {
                    return AffectedClassInstrumenter.instrumentRunNotifier(classfileBuffer);
                }
                boolean productionClass = state.observe(className, protectionDomain);
                if (!productionClass && !state.shouldInstrument(protectionDomain)) return null;
                if (loader != null && !bridgeVisible(loader)) {
                    if (productionClass) state.fail(new IllegalStateException("collector bridge classloader"));
                    return null;
                }
                return AffectedClassInstrumenter.instrument(loader, className, classfileBuffer, productionClass, state);
            } catch (Throwable failure) {
                state.fail(failure);
            }
            return null;
        }

        private static boolean bridgeVisible(ClassLoader loader) {
            try {
                return Class.forName(AffectedCollectorAgent.class.getName(), false, loader)
                    == AffectedCollectorAgent.class;
            } catch (Throwable failure) {
                return false;
            }
        }
    }

    static final class CollectorState {
        private static final int MAX_CLASSES = 1_000_000;
        private final AtomicBoolean initialized = new AtomicBoolean();
        private final AtomicLong executionIds = new AtomicLong();
        private final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        private final ConcurrentMap<String, Dependency> catalog = new ConcurrentHashMap<String, Dependency>();
        private final ConcurrentMap<String, ConcurrentMap<String, Dependency>> dependencies =
            new ConcurrentHashMap<String, ConcurrentMap<String, Dependency>>();
        private final ConcurrentMap<String, ExecutionContext> executions =
            new ConcurrentHashMap<String, ExecutionContext>();
        private final ConcurrentMap<String, Set<String>> staticReferences =
            new ConcurrentHashMap<String, Set<String>>();
        private final ThreadLocal<ThreadState> threadStates =
            new ThreadLocal<ThreadState>() {
                @Override
                protected ThreadState initialValue() {
                    return new ThreadState();
                }
            };
        private volatile Map<Path, Path> codeSources = Collections.emptyMap();
        private volatile Map<Path, Path> instrumentationSources = Collections.emptyMap();
        private volatile Set<String> productionClasses = Collections.emptySet();
        private volatile Set<String> instrumentationClasses = Collections.emptySet();
        private volatile Map<String, List<ClassArtifact>> classFiles = Collections.emptyMap();

        void configure(Collection<Path> sources) throws Exception {
            configure(sources, Collections.<Path>emptyList());
        }

        void configure(Collection<Path> sources, Collection<Path> testSources) throws Exception {
            initialized.set(false);
            executionIds.set(0L);
            Arrays.fill(THREAD_EXECUTION_IDS, 0L);
            failure.set(null);
            dependencies.clear();
            catalog.clear();
            executions.clear();
            staticReferences.clear();
            threadStates.remove();
            codeSources = Collections.emptyMap();
            instrumentationSources = Collections.emptyMap();
            productionClasses = Collections.emptySet();
            instrumentationClasses = Collections.emptySet();
            classFiles = Collections.emptyMap();
            if (sources.isEmpty()) throw new IllegalStateException(CODE_SOURCES_PROPERTY);
            Map<Path, Path> resolved = new HashMap<Path, Path>();
            Map<Path, Path> instrumentation = new HashMap<Path, Path>();
            Set<String> classes = new HashSet<String>();
            Set<String> instrumented = new HashSet<String>();
            Map<String, List<ClassArtifact>> discoveredFiles = new HashMap<String, List<ClassArtifact>>();
            for (Path source : sources) {
                Path requested = source.toAbsolutePath().normalize();
                Path path = requested.toRealPath();
                if (!Files.isDirectory(path) || !Files.isReadable(path)) throw new IllegalStateException(path.toString());
                resolved.put(requested, path);
                resolved.put(path, path);
                instrumentation.put(requested, path);
                instrumentation.put(path, path);
                try (Stream<Path> files = Files.walk(path)) {
                    Iterator<Path> iterator = files.iterator();
                    while (iterator.hasNext()) {
                        Path file = iterator.next();
                        Path fileName = file.getFileName();
                        if (!Files.isRegularFile(file) || fileName == null || !fileName.toString().endsWith(".class")) {
                            continue;
                        }
                        String relative = path.relativize(file).toString().replace(File.separatorChar, '/');
                        String internalName = relative.substring(0, relative.length() - ".class".length());
                        classes.add(internalName);
                        instrumented.add(internalName);
                        List<ClassArtifact> artifacts = discoveredFiles.get(internalName);
                        if (artifacts == null) {
                            artifacts = new ArrayList<ClassArtifact>();
                            discoveredFiles.put(internalName, artifacts);
                        }
                        Path realFile = file.toRealPath();
                        boolean duplicate = false;
                        for (ClassArtifact artifact : artifacts) {
                            if (artifact.file.equals(realFile)) duplicate = true;
                        }
                        if (!duplicate) artifacts.add(new ClassArtifact(path, realFile));
                        if (classes.size() > MAX_CLASSES) throw new IllegalStateException("class catalog");
                    }
                }
            }
            for (Path source : testSources) {
                Path requested = source.toAbsolutePath().normalize();
                Path path = requested.toRealPath();
                if (!Files.isDirectory(path) || !Files.isReadable(path)) throw new IllegalStateException(path.toString());
                instrumentation.put(requested, path);
                instrumentation.put(path, path);
                try (Stream<Path> files = Files.walk(path)) {
                    Iterator<Path> iterator = files.iterator();
                    while (iterator.hasNext()) {
                        Path file = iterator.next();
                        Path fileName = file.getFileName();
                        if (!Files.isRegularFile(file) || fileName == null || !fileName.toString().endsWith(".class")) {
                            continue;
                        }
                        String relative = path.relativize(file).toString().replace(File.separatorChar, '/');
                        instrumented.add(relative.substring(0, relative.length() - ".class".length()));
                        if (instrumented.size() > MAX_CLASSES) throw new IllegalStateException("instrumentation catalog");
                    }
                }
            }
            Map<String, List<ClassArtifact>> immutableFiles = new HashMap<String, List<ClassArtifact>>();
            for (Map.Entry<String, List<ClassArtifact>> entry : discoveredFiles.entrySet()) {
                immutableFiles.put(entry.getKey(), Collections.unmodifiableList(entry.getValue()));
            }
            codeSources = Collections.unmodifiableMap(resolved);
            instrumentationSources = Collections.unmodifiableMap(instrumentation);
            productionClasses = Collections.unmodifiableSet(classes);
            instrumentationClasses = Collections.unmodifiableSet(instrumented);
            classFiles = Collections.unmodifiableMap(immutableFiles);
            initialized.set(true);
        }

        boolean observe(String internalClassName, ProtectionDomain protectionDomain) throws Exception {
            if (!initialized.get() || internalClassName == null || protectionDomain == null) return false;
            Path source = source(protectionDomain);
            if (source == null) return false;

            String className = internalClassName.replace('/', '.');
            String codeSource = source.toUri().toString();
            String key = className + "\n" + codeSource;
            if (catalog.containsKey(key)) return true;

            Path classFile = source.resolve(internalClassName + ".class").normalize().toRealPath();
            if (!classFile.startsWith(source) || !Files.isRegularFile(classFile) || !Files.isReadable(classFile)) {
                throw new IllegalStateException(internalClassName);
            }
            byte[] bytes = Files.readAllBytes(classFile);
            Dependency dependency = new Dependency(className, codeSource, sha256(bytes));
            Dependency previous = catalog.putIfAbsent(key, dependency);
            if (previous != null && !previous.equals(dependency)) throw new IllegalStateException(dependency.getClassName());
            return true;
        }

        boolean isProductionClass(String internalClassName) {
            return internalClassName != null && productionClasses.contains(internalClassName);
        }

        boolean shouldInstrument(ProtectionDomain protectionDomain) throws Exception {
            return source(protectionDomain, instrumentationSources) != null;
        }

        void staticReference(String source, String target) {
            if (source == null || target == null || source.equals(target)
                || !instrumentationClasses.contains(source) || !instrumentationClasses.contains(target)) {
                return;
            }
            Set<String> references = staticReferences.get(source);
            if (references == null) {
                Set<String> created = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
                Set<String> previous = staticReferences.putIfAbsent(source, created);
                references = previous == null ? created : previous;
            }
            references.add(target);
        }

        void beginExecution(String token, String testClass) {
            required(token);
            required(testClass);
            long executionId = executionIds.incrementAndGet();
            if (executionId <= 0L) throw new IllegalStateException("execution id");
            ExecutionContext context = new ExecutionContext(token, testClass, executionId);
            if (executions.putIfAbsent(token, context) != null) throw new IllegalStateException("execution token");
            threadStates.get().push(context);
            publishExecutionId(executionId);
        }

        void endExecution(String token) {
            required(token);
            ExecutionContext context = executions.remove(token);
            if (context == null || !context.active.compareAndSet(true, false)) {
                throw new IllegalStateException("execution token");
            }
            ThreadState thread = threadStates.get();
            thread.remove(token);
            publishExecutionId(thread.current == null ? 0L : thread.current.id);
        }

        void hit(Class<?> type) throws Exception {
            if (!isSupported() || type == null || type.isPrimitive() || type == Void.TYPE) return;
            if (type.isArray()) {
                hit(type.getComponentType());
                return;
            }
            if (!isProductionType(type) && !hasProductionAncestor(type)) return;
            ThreadState thread = threadStates.get();
            ExecutionContext context = currentExecution(thread);
            if (thread.seenTop(context, type)) return;
            hit(type, context, thread.cache);
        }

        void hitProduction(Class<?> type) throws Exception {
            if (failure.get() != null) return;
            ThreadState thread = threadStates.get();
            ExecutionContext context = currentExecution(thread);
            if (thread.seenTop(context, type)) return;
            hit(type, context, thread.cache);
        }

        long executionId() {
            ThreadState thread = threadStates.get();
            ExecutionContext context = thread.current;
            if (context != null && context.active.get()) return context.id;
            fail(new IllegalStateException("asynchronous or unattributed production access"));
            return Long.MIN_VALUE;
        }

        void nativeMethod() {
            fail(new IllegalStateException("production native method"));
        }

        private void hit(Class<?> type, ExecutionContext context, AttributionCache cache) throws Exception {
            if (type == null || type.isPrimitive() || type == Void.TYPE || cache.seen(context.testClass, type)) return;
            if (type.isArray()) {
                hit(type.getComponentType(), context, cache);
                return;
            }
            Dependency dependency = dependency(type);
            if (dependency != null) record(context.testClass, dependency);
            hit(type.getSuperclass(), context, cache);
            for (Class<?> contract : type.getInterfaces()) hit(contract, context, cache);
        }

        private boolean hasProductionDependency(Class<?> type, IdentityHashMap<Class<?>, Boolean> seen) throws Exception {
            if (type == null || type.isPrimitive() || type == Void.TYPE || seen.put(type, Boolean.TRUE) != null) return false;
            if (type.isArray()) return hasProductionDependency(type.getComponentType(), seen);
            if (isProductionType(type) || hasProductionDependency(type.getSuperclass(), seen)) return true;
            for (Class<?> contract : type.getInterfaces()) {
                if (hasProductionDependency(contract, seen)) return true;
            }
            return false;
        }

        private boolean isProductionType(Class<?> type) {
            return isProductionClass(type.getName().replace('.', '/'));
        }

        private boolean hasProductionAncestor(Class<?> type) throws Exception {
            IdentityHashMap<Class<?>, Boolean> seen = new IdentityHashMap<Class<?>, Boolean>();
            seen.put(type, Boolean.TRUE);
            if (hasProductionDependency(type.getSuperclass(), seen)) return true;
            for (Class<?> contract : type.getInterfaces()) {
                if (hasProductionDependency(contract, seen)) return true;
            }
            return false;
        }

        private void record(String testClass, Dependency dependency) {
            ConcurrentMap<String, Dependency> observed = dependencies.get(testClass);
            if (observed == null) {
                ConcurrentMap<String, Dependency> created = new ConcurrentHashMap<String, Dependency>();
                ConcurrentMap<String, Dependency> previous = dependencies.putIfAbsent(testClass, created);
                observed = previous == null ? created : previous;
            }
            String key = dependency.className + "\n" + dependency.codeSource;
            Dependency previous = observed.putIfAbsent(key, dependency);
            if (previous != null && !previous.equals(dependency)) throw new IllegalStateException(dependency.className);
        }

        void fail(Throwable exception) {
            if (failure.compareAndSet(null, exception) && Boolean.getBoolean("affected.collector.debug")) {
                exception.printStackTrace(System.err);
            }
        }

        boolean isSupported() {
            return initialized.get() && failure.get() == null;
        }

        List<Dependency> snapshot(String testClass) {
            required(testClass);
            Map<String, Dependency> observed = dependencies.get(testClass);
            Map<String, Dependency> collected = new HashMap<String, Dependency>();
            if (observed != null) collected.putAll(observed);
            try {
                Set<String> visited = new HashSet<String>();
                collectStaticDependencies(testClass.replace('.', '/'), collected, visited);
                if (observed != null) {
                    for (Dependency dependency : observed.values()) {
                        collectStaticDependencies(dependency.className.replace('.', '/'), collected, visited);
                    }
                }
            } catch (Throwable exception) {
                fail(exception);
                collected.clear();
            }
            List<Dependency> result = new ArrayList<Dependency>(collected.values());
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
            executionIds.set(0L);
            Arrays.fill(THREAD_EXECUTION_IDS, 0L);
            failure.set(null);
            dependencies.clear();
            catalog.clear();
            executions.clear();
            staticReferences.clear();
            threadStates.remove();
            codeSources = Collections.emptyMap();
            instrumentationSources = Collections.emptyMap();
            productionClasses = Collections.emptySet();
            instrumentationClasses = Collections.emptySet();
            classFiles = Collections.emptyMap();
        }

        private void collectStaticDependencies(
            String source,
            Map<String, Dependency> collected,
            Set<String> visited
        ) throws Exception {
            if (!visited.add(source)) return;
            if (productionClasses.contains(source)) {
                Dependency dependency = dependency(source);
                if (dependency != null) {
                    collected.put(dependency.className + "\n" + dependency.codeSource, dependency);
                }
            }
            Set<String> references = staticReferences.get(source);
            if (references == null) return;
            for (String target : references) collectStaticDependencies(target, collected, visited);
        }

        private Path source(ProtectionDomain protectionDomain) throws Exception {
            return source(protectionDomain, codeSources);
        }

        private static Path source(ProtectionDomain protectionDomain, Map<Path, Path> sources) throws Exception {
            if (protectionDomain == null) return null;
            if (protectionDomain.getCodeSource() == null || protectionDomain.getCodeSource().getLocation() == null) {
                return null;
            }
            URI location = protectionDomain.getCodeSource().getLocation().toURI().normalize();
            if (!"file".equalsIgnoreCase(location.getScheme())) return null;
            return sources.get(Paths.get(location).toAbsolutePath().normalize());
        }

        private Dependency dependency(Class<?> type) throws Exception {
            Path source = source(type.getProtectionDomain());
            if (source == null) return null;
            String key = type.getName() + "\n" + source.toUri().toString();
            Dependency dependency = catalog.get(key);
            if (dependency != null) return dependency;
            Dependency discovered = dependency(type.getName().replace('.', '/'));
            if (discovered == null || !discovered.codeSource.equals(source.toUri().toString())) {
                throw new IllegalStateException(type.getName());
            }
            return discovered;
        }

        private Dependency dependency(String internalName) throws Exception {
            List<ClassArtifact> artifacts = classFiles.get(internalName);
            if (artifacts == null) return null;
            if (artifacts.size() != 1) throw new IllegalStateException("ambiguous production class");
            ClassArtifact artifact = artifacts.get(0);
            String className = internalName.replace('/', '.');
            String codeSource = artifact.source.toUri().toString();
            String key = className + "\n" + codeSource;
            Dependency dependency = catalog.get(key);
            if (dependency != null) return dependency;
            Dependency created = new Dependency(className, codeSource, sha256(Files.readAllBytes(artifact.file)));
            Dependency previous = catalog.putIfAbsent(key, created);
            return previous == null ? created : previous;
        }

        private static ExecutionContext currentExecution(ThreadState thread) {
            ExecutionContext context = thread.current;
            if (context != null && context.active.get()) return context;
            throw new IllegalStateException("asynchronous or unattributed production access");
        }

        private static String required(String value) {
            if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException("required value");
            return value;
        }

        private static void publishExecutionId(long executionId) {
            long threadId = Thread.currentThread().getId();
            if (threadId >= 0L && threadId < THREAD_EXECUTION_IDS.length) {
                THREAD_EXECUTION_IDS[(int) threadId] = executionId;
            }
        }

        private static final class ExecutionContext {
            private final String token;
            private final String testClass;
            private final long id;
            private final AtomicBoolean active = new AtomicBoolean(true);

            private ExecutionContext(String token, String testClass, long id) {
                this.token = token;
                this.testClass = testClass;
                this.id = id;
            }
        }

        private static final class ClassArtifact {
            private final Path source;
            private final Path file;

            private ClassArtifact(Path source, Path file) {
                this.source = source;
                this.file = file;
            }
        }

        private static final class ThreadState {
            private final Deque<ExecutionContext> executions = new ArrayDeque<ExecutionContext>();
            private final AttributionCache cache = new AttributionCache();
            private ExecutionContext current;
            private ExecutionContext lastContext;
            private Class<?> lastType;

            private void push(ExecutionContext context) {
                executions.push(context);
                current = context;
            }

            private void remove(String token) {
                Iterator<ExecutionContext> iterator = executions.iterator();
                while (iterator.hasNext()) {
                    if (iterator.next().token.equals(token)) {
                        iterator.remove();
                        break;
                    }
                }
                while (!executions.isEmpty() && !executions.peek().active.get()) executions.pop();
                current = executions.peek();
                lastContext = null;
                lastType = null;
            }

            private boolean seenTop(ExecutionContext context, Class<?> type) {
                if (context == lastContext && type == lastType) return true;
                lastContext = context;
                lastType = type;
                return cache.seenTop(context.testClass, type);
            }
        }

        private static final class AttributionCache {
            private final IdentityHashMap<Class<?>, Boolean> classes = new IdentityHashMap<Class<?>, Boolean>();
            private String testClass;
            private Class<?> topType;

            private boolean seenTop(String currentTestClass, Class<?> type) {
                reset(currentTestClass);
                if (type == topType) return true;
                topType = type;
                return false;
            }

            private boolean seen(String currentTestClass, Class<?> type) {
                reset(currentTestClass);
                return classes.put(type, Boolean.TRUE) != null;
            }

            private void reset(String currentTestClass) {
                if (!currentTestClass.equals(testClass)) {
                    testClass = currentTestClass;
                    topType = null;
                    classes.clear();
                }
            }
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
