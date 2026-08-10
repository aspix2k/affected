package com.aspix2k.affected.collector;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.Assume;
import org.junit.platform.engine.EngineDiscoveryRequest;
import org.junit.platform.engine.EngineExecutionListener;
import org.junit.platform.engine.ExecutionRequest;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.TestEngine;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.engine.support.descriptor.AbstractTestDescriptor;
import org.junit.platform.engine.support.descriptor.EngineDescriptor;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.core.LauncherConfig;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

public class CollectorContractTest {
    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    private String previousOutput;
    private String previousWorker;
    private String previousCodeSources;

    @Before
    public void setUp() {
        previousOutput = System.getProperty(CollectorOutput.OUTPUT_PROPERTY);
        previousWorker = System.getProperty(CollectorOutput.WORKER_PROPERTY);
        previousCodeSources = System.getProperty(AffectedCollectorAgent.CODE_SOURCES_PROPERTY);
        AffectedCollectorAgent.resetForTests();
        StableJupiterFixture.executions.set(0);
        StableVintageFixture.executions.set(0);
    }

    @After
    public void tearDown() {
        restore(CollectorOutput.OUTPUT_PROPERTY, previousOutput);
        restore(CollectorOutput.WORKER_PROPERTY, previousWorker);
        restore(AffectedCollectorAgent.CODE_SOURCES_PROPERTY, previousCodeSources);
        AffectedCollectorAgent.resetForTests();
    }

    @Test
    public void transformerFingerprintsOriginalBytesAndNeverTransformsThem() throws Exception {
        AffectedCollectorAgent.CollectorState state = new AffectedCollectorAgent.CollectorState();
        Path codeSource = codeSource(ObservedFixture.class);
        state.configure(Collections.singleton(codeSource));
        byte[] classBytes = classBytes(ObservedFixture.class);

        byte[] transformed = new AffectedCollectorAgent.CollectorTransformer(state).transform(
            ObservedFixture.class.getClassLoader(),
            ObservedFixture.class.getName().replace('.', '/'),
            null,
            ObservedFixture.class.getProtectionDomain(),
            classBytes
        );

        assertNull(transformed);
        assertEquals(1, state.snapshot().size());
        AffectedCollectorAgent.Dependency dependency = state.snapshot().get(0);
        assertEquals(ObservedFixture.class.getName(), dependency.getClassName());
        assertEquals(codeSource.toRealPath().toUri().toString(), dependency.getCodeSource());
        assertEquals(sha256(classBytes), dependency.getSha256());
        assertArrayEquals(classBytes, classBytes(ObservedFixture.class));
    }

    @Test
    public void transformerIgnoresCodeSourcesOutsideTheAllowList() throws Exception {
        AffectedCollectorAgent.CollectorState state = new AffectedCollectorAgent.CollectorState();
        state.configure(Collections.singleton(temporary.newFolder("disallowed").toPath()));

        byte[] transformed = new AffectedCollectorAgent.CollectorTransformer(state).transform(
            ObservedFixture.class.getClassLoader(),
            ObservedFixture.class.getName().replace('.', '/'),
            null,
            ObservedFixture.class.getProtectionDomain(),
            classBytes(ObservedFixture.class)
        );

        assertNull(transformed);
        assertTrue(state.snapshot().isEmpty());
        assertTrue(state.isSupported());
    }

    @Test
    public void propertyCodeSourceAliasesRemainAllowlisted() throws Exception {
        Path alias = temporary.getRoot().toPath().resolve("classes-alias");
        try {
            Files.createSymbolicLink(alias, codeSource(ObservedFixture.class).toRealPath());
        } catch (UnsupportedOperationException | java.io.IOException failure) {
            Assume.assumeNoException(failure);
        }
        try {
            System.setProperty(AffectedCollectorAgent.CODE_SOURCES_PROPERTY, alias.toString());
            AffectedCollectorAgent.CollectorState state = new AffectedCollectorAgent.CollectorState();
            state.configure(AffectedCollectorAgent.codeSources());
            java.security.ProtectionDomain aliasDomain = new java.security.ProtectionDomain(
                new java.security.CodeSource(alias.toUri().toURL(), (java.security.cert.Certificate[]) null),
                null
            );

            new AffectedCollectorAgent.CollectorTransformer(state).transform(
                ObservedFixture.class.getClassLoader(),
                ObservedFixture.class.getName().replace('.', '/'),
                null,
                aliasDomain,
                classBytes(ObservedFixture.class)
            );

            assertTrue(state.isSupported());
            assertEquals(1, state.snapshot().size());
        } finally {
            Files.deleteIfExists(alias);
        }
    }

    @Test
    public void transformerFailureStillReturnsNullAndMarksTheWorkerUnsupported() throws Exception {
        AffectedCollectorAgent.CollectorState state = new AffectedCollectorAgent.CollectorState();
        state.configure(Collections.singleton(codeSource(ObservedFixture.class)));

        byte[] transformed = new AffectedCollectorAgent.CollectorTransformer(state).transform(
            ObservedFixture.class.getClassLoader(),
            "missing/Fixture",
            null,
            ObservedFixture.class.getProtectionDomain(),
            new byte[0]
        );

        assertNull(transformed);
        assertFalse(state.isSupported());
        assertTrue(state.snapshot().isEmpty());
    }

    @Test
    public void outputUsesBoundedNamesAtomicFilesAndSeparateWorkerDirectories() throws Exception {
        Path outputRoot = temporary.getRoot().toPath().resolve("output");
        System.setProperty(CollectorOutput.OUTPUT_PROPERTY, outputRoot.toString());
        AffectedCollectorAgent.Dependency dependency = dependency();

        System.setProperty(CollectorOutput.WORKER_PROPERTY, "worker/one with unsafe characters");
        CollectorOutput first = CollectorOutput.fromSystemProperties();
        first.writeMap("fixture.ExampleTest", Collections.singletonList(dependency));
        first.writeCompletion(true, Collections.singleton("fixture.ExampleTest"));

        System.setProperty(CollectorOutput.WORKER_PROPERTY, "worker:two");
        CollectorOutput second = CollectorOutput.fromSystemProperties();
        second.writeMap("fixture.ExampleTest", Collections.singletonList(dependency));
        second.writeCompletion(true, Collections.singleton("fixture.ExampleTest"));

        List<Path> workerDirectories;
        try (Stream<Path> files = Files.list(outputRoot)) {
            workerDirectories = files.collect(Collectors.toList());
        }
        assertEquals(2, workerDirectories.size());
        assertFalse(workerDirectories.get(0).equals(workerDirectories.get(1)));
        for (Path workerDirectory : workerDirectories) {
            assertTrue(workerDirectory.getFileName().toString().matches("worker-[0-9a-f]{64}"));
            try (Stream<Path> files = Files.list(workerDirectory)) {
                List<Path> outputs = files.collect(Collectors.toList());
                assertEquals(3, outputs.size());
                assertTrue(outputs.stream().noneMatch(path -> path.getFileName().toString().endsWith(".tmp")));
                Path started = outputs.stream()
                    .filter(path -> path.getFileName().toString().equals("started.manifest"))
                    .findFirst()
                    .orElseThrow(AssertionError::new);
                Path map = outputs.stream()
                    .filter(path -> path.getFileName().toString().matches("test-[0-9a-f]{64}\\.map"))
                    .findFirst()
                    .orElseThrow(AssertionError::new);
                Path manifest = outputs.stream()
                    .filter(path -> path.getFileName().toString().equals("complete.manifest"))
                    .findFirst()
                    .orElseThrow(AssertionError::new);
                assertTrue(read(map).matches(
                    "format=1\\ntest=[A-Za-z0-9_-]+\\ndependency=[A-Za-z0-9_-]+\\|[A-Za-z0-9_-]+\\|[0-9a-f]{64}\\n"
                ));
                assertTrue(read(manifest).matches(
                    "format=1\\nworker=[A-Za-z0-9_-]+\\nsupported=true\\ntest=[A-Za-z0-9_-]+\\n"
                ));
                assertTrue(read(started).matches("format=1\\nworker=[A-Za-z0-9_-]+\\n"));
            }
        }
    }

    @Test
    public void startingTheSameWorkerInvalidatesAStaleCompletionMarker() throws Exception {
        Path outputRoot = temporary.getRoot().toPath().resolve("partial");
        System.setProperty(CollectorOutput.OUTPUT_PROPERTY, outputRoot.toString());
        System.setProperty(CollectorOutput.WORKER_PROPERTY, "worker-1");
        CollectorOutput completed = CollectorOutput.fromSystemProperties();
        completed.writeMap("fixture.ExampleTest", Collections.singletonList(dependency()));
        completed.writeCompletion(true, Collections.singleton("fixture.ExampleTest"));
        Path workerDirectory = onlyWorkerDirectory(outputRoot);
        assertTrue(Files.exists(workerDirectory.resolve("complete.manifest")));

        CollectorOutput partial = CollectorOutput.fromSystemProperties();
        partial.writeMap("fixture.ExampleTest", Collections.singletonList(dependency()));

        assertFalse(Files.exists(workerDirectory.resolve("complete.manifest")));
    }

    @Test
    public void workerDirectorySymlinksCannotEscapeTheOutputRoot() throws Exception {
        Path outputRoot = temporary.newFolder("symlink-root").toPath();
        Path outside = temporary.newFolder("symlink-outside").toPath();
        Path workerLink = outputRoot.resolve(
            "worker-" + sha256("worker-1".getBytes(StandardCharsets.UTF_8))
        );
        try {
            Files.createSymbolicLink(workerLink, outside);
        } catch (UnsupportedOperationException | java.io.IOException failure) {
            Assume.assumeNoException(failure);
        }
        System.setProperty(CollectorOutput.OUTPUT_PROPERTY, outputRoot.toString());
        System.setProperty(CollectorOutput.WORKER_PROPERTY, "worker-1");

        try {
            CollectorOutput.fromSystemProperties();
            fail("worker symlink must be rejected");
        } catch (java.io.IOException expected) {
            assertFalse(Files.exists(outside.resolve("complete.manifest")));
        }
    }

    @Test
    public void listenerCompletesStableJupiterAndVintageClasses() throws Exception {
        Path outputRoot = prepareListener();

        Launcher launcher = LauncherFactory.create(
            LauncherConfig.builder().enableTestExecutionListenerAutoRegistration(false).build()
        );
        launcher.registerTestExecutionListeners(new AffectedTestExecutionListener());
        launcher.execute(
            LauncherDiscoveryRequestBuilder.request()
                .selectors(selectClass(StableJupiterFixture.class), selectClass(StableVintageFixture.class))
                .build()
        );

        assertEquals(1, StableJupiterFixture.executions.get());
        assertEquals(1, StableVintageFixture.executions.get());
        Path workerDirectory = onlyWorkerDirectory(outputRoot);
        assertTrue(read(workerDirectory.resolve("complete.manifest")).contains("supported=true"));
        try (Stream<Path> files = Files.list(workerDirectory)) {
            assertEquals(2, files.filter(path -> path.getFileName().toString().endsWith(".map")).count());
        }
    }

    @Test
    public void listenerMarksSourcelessTestsUnsupported() throws Exception {
        Path outputRoot = prepareListener();
        Launcher launcher = LauncherFactory.create(
            LauncherConfig.builder()
                .enableTestEngineAutoRegistration(false)
                .enableTestExecutionListenerAutoRegistration(false)
                .addTestEngines(new SourcelessEngine())
                .build()
        );
        launcher.registerTestExecutionListeners(new AffectedTestExecutionListener());

        launcher.execute(LauncherDiscoveryRequestBuilder.request().build());

        Path workerDirectory = onlyWorkerDirectory(outputRoot);
        assertTrue(read(workerDirectory.resolve("complete.manifest")).contains("supported=false"));
    }

    @Test
    public void missingOutputNeverFailsTheUserTestRun() throws Exception {
        configureAgentState();
        System.clearProperty(CollectorOutput.OUTPUT_PROPERTY);
        System.setProperty(CollectorOutput.WORKER_PROPERTY, "worker-1");
        Launcher launcher = LauncherFactory.create(
            LauncherConfig.builder().enableTestExecutionListenerAutoRegistration(false).build()
        );
        launcher.registerTestExecutionListeners(new AffectedTestExecutionListener());

        launcher.execute(
            LauncherDiscoveryRequestBuilder.request().selectors(selectClass(StableJupiterFixture.class)).build()
        );

        assertEquals(1, StableJupiterFixture.executions.get());
    }

    @Test
    public void packagedAgentAndListenerWorkAcrossProcessClassLoaders() throws Exception {
        Path outputRoot = temporary.getRoot().toPath().resolve("forked-output");
        Path processLog = temporary.getRoot().toPath().resolve("forked.log");
        String java = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        Process process = new ProcessBuilder(
            java,
            "-javaagent:" + requiredProperty("affected.smoke.agent"),
            "-D" + AffectedCollectorAgent.CODE_SOURCES_PROPERTY + "=" + requiredProperty("affected.smoke.codeSources"),
            "-D" + CollectorOutput.OUTPUT_PROPERTY + "=" + outputRoot,
            "-D" + CollectorOutput.WORKER_PROPERTY + "=forked-worker",
            "-Daffected.smoke.childClasspath=" + requiredProperty("affected.smoke.childClasspath"),
            "-cp",
            requiredProperty("affected.smoke.testClasses"),
            ClassLoaderSmokeMain.class.getName()
        ).redirectErrorStream(true).redirectOutput(processLog.toFile()).start();

        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        if (!finished) process.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
        String log = Files.exists(processLog) ? read(processLog) : "";
        assertTrue(log, finished);
        assertEquals(log, 0, process.exitValue());
        Path workerDirectory = onlyWorkerDirectory(outputRoot);
        assertTrue(read(workerDirectory.resolve("complete.manifest")).contains("supported=true"));
        try (Stream<Path> files = Files.list(workerDirectory)) {
            assertEquals(2, files.filter(path -> path.getFileName().toString().endsWith(".map")).count());
        }
    }

    private Path prepareListener() throws Exception {
        Path outputRoot = temporary.getRoot().toPath().resolve("listener");
        System.setProperty(CollectorOutput.OUTPUT_PROPERTY, outputRoot.toString());
        System.setProperty(CollectorOutput.WORKER_PROPERTY, "worker-1");
        configureAgentState();
        return outputRoot;
    }

    private void configureAgentState() throws Exception {
        AffectedCollectorAgent.CollectorState state = AffectedCollectorAgent.state();
        state.configure(Collections.singleton(codeSource(ObservedFixture.class)));
        new AffectedCollectorAgent.CollectorTransformer(state).transform(
            ObservedFixture.class.getClassLoader(),
            ObservedFixture.class.getName().replace('.', '/'),
            null,
            ObservedFixture.class.getProtectionDomain(),
            classBytes(ObservedFixture.class)
        );
    }

    private static AffectedCollectorAgent.Dependency dependency() throws Exception {
        Path source = codeSource(ObservedFixture.class).toRealPath();
        return new AffectedCollectorAgent.Dependency(
            ObservedFixture.class.getName(),
            source.toUri().toString(),
            sha256(classBytes(ObservedFixture.class))
        );
    }

    private static Path onlyWorkerDirectory(Path outputRoot) throws Exception {
        try (Stream<Path> files = Files.list(outputRoot)) {
            List<Path> directories = files.collect(Collectors.toList());
            assertEquals(1, directories.size());
            return directories.get(0);
        }
    }

    private static Path codeSource(Class<?> type) throws Exception {
        return Paths.get(type.getProtectionDomain().getCodeSource().getLocation().toURI());
    }

    private static byte[] classBytes(Class<?> type) throws Exception {
        String resource = "/" + type.getName().replace('.', '/') + ".class";
        try (InputStream input = type.getResourceAsStream(resource)) {
            if (input == null) throw new IllegalStateException(resource);
            return readAllBytes(input);
        }
    }

    private static byte[] readAllBytes(InputStream input) throws Exception {
        byte[] buffer = new byte[4096];
        int read;
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
        return output.toByteArray();
    }

    private static String read(Path path) throws Exception {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static String sha256(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder result = new StringBuilder(digest.length * 2);
        for (byte value : digest) result.append(String.format("%02x", value & 0xff));
        return result.toString();
    }

    private static void restore(String property, String value) {
        if (value == null) System.clearProperty(property);
        else System.setProperty(property, value);
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.trim().isEmpty()) throw new IllegalStateException(name);
        return value;
    }

    public static final class ObservedFixture {
    }

    public static final class StableJupiterFixture {
        private static final AtomicInteger executions = new AtomicInteger();

        @org.junit.jupiter.api.Test
        void passes() {
            executions.incrementAndGet();
        }
    }

    public static final class StableVintageFixture {
        private static final AtomicInteger executions = new AtomicInteger();

        @org.junit.Test
        public void passes() {
            executions.incrementAndGet();
        }
    }

    private static final class SourcelessEngine implements TestEngine {
        @Override
        public String getId() {
            return "sourceless";
        }

        @Override
        public TestDescriptor discover(EngineDiscoveryRequest request, UniqueId uniqueId) {
            EngineDescriptor root = new EngineDescriptor(uniqueId, "sourceless");
            root.addChild(new AbstractTestDescriptor(uniqueId.append("test", "one"), "one") {
                @Override
                public Type getType() {
                    return Type.TEST;
                }
            });
            return root;
        }

        @Override
        public void execute(ExecutionRequest request) {
            TestDescriptor root = request.getRootTestDescriptor();
            EngineExecutionListener listener = request.getEngineExecutionListener();
            listener.executionStarted(root);
            for (TestDescriptor child : root.getChildren()) {
                listener.executionStarted(child);
                listener.executionFinished(child, TestExecutionResult.successful());
            }
            listener.executionFinished(root, TestExecutionResult.successful());
        }
    }
}
