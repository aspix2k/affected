package com.aspix2k.affected.collector;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
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
import org.jetbrains.org.objectweb.asm.ClassReader;
import org.jetbrains.org.objectweb.asm.ClassVisitor;
import org.jetbrains.org.objectweb.asm.MethodVisitor;
import org.jetbrains.org.objectweb.asm.Opcodes;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
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
    private String previousRunner;
    private String previousReuseForks;

    @Before
    public void setUp() {
        previousOutput = System.getProperty(CollectorOutput.OUTPUT_PROPERTY);
        previousWorker = System.getProperty(CollectorOutput.WORKER_PROPERTY);
        previousCodeSources = System.getProperty(AffectedCollectorAgent.CODE_SOURCES_PROPERTY);
        previousRunner = System.getProperty("affected.collector.runner");
        previousReuseForks = System.getProperty("affected.collector.reuseForks");
        AffectedCollectorAgent.resetForTests();
        StableJupiterFixture.executions.set(0);
        StableVintageFixture.executions.set(0);
        ParallelFixtures.barrier = new CyclicBarrier(2);
    }

    @After
    public void tearDown() {
        restore(CollectorOutput.OUTPUT_PROPERTY, previousOutput);
        restore(CollectorOutput.WORKER_PROPERTY, previousWorker);
        restore(AffectedCollectorAgent.CODE_SOURCES_PROPERTY, previousCodeSources);
        restore("affected.collector.runner", previousRunner);
        restore("affected.collector.reuseForks", previousReuseForks);
        AffectedCollectorAgent.resetForTests();
    }

    @Test
    public void transformerFingerprintsOriginalBytesAndAddsAttributionHooks() throws Exception {
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

        assertNotNull(transformed);
        String transformedConstants = new String(transformed, StandardCharsets.ISO_8859_1);
        assertTrue(transformedConstants.contains("$affectedExecutionId"));
        assertTrue(transformedConstants.contains("currentExecutionId"));
        state.beginExecution("execution", "fixture.ExampleTest");
        state.hit(ObservedFixture.class);
        state.endExecution("execution");
        assertEquals(1, state.snapshot("fixture.ExampleTest").size());
        AffectedCollectorAgent.Dependency dependency = state.snapshot("fixture.ExampleTest").get(0);
        assertEquals(ObservedFixture.class.getName(), dependency.getClassName());
        assertEquals(codeSource.toRealPath().toUri().toString(), dependency.getCodeSource());
        assertEquals(sha256(classBytes), dependency.getSha256());
        assertArrayEquals(classBytes, classBytes(ObservedFixture.class));

        byte[] java26 = classBytes.clone();
        java26[6] = 0;
        java26[7] = 70;
        assertNotNull(new AffectedCollectorAgent.CollectorTransformer(state).transform(
            ObservedFixture.class.getClassLoader(),
            ObservedFixture.class.getName().replace('.', '/'),
            null,
            ObservedFixture.class.getProtectionDomain(),
            java26
        ));
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
        assertTrue(state.snapshot("fixture.ExampleTest").isEmpty());
        assertTrue(state.isSupported());
    }

    @Test
    public void transformerOnlyInstrumentsProductionAndTestOutputs() throws Exception {
        Path production = temporary.newFolder("production").toPath();
        Path productionClass = production.resolve(ObservedFixture.class.getName().replace('.', '/') + ".class");
        Files.createDirectories(productionClass.getParent());
        Files.write(productionClass, classBytes(ObservedFixture.class));
        AffectedCollectorAgent.CollectorState state = new AffectedCollectorAgent.CollectorState();
        state.configure(Collections.singleton(production));

        byte[] ignored = new AffectedCollectorAgent.CollectorTransformer(state).transform(
            CollectorContractTest.class.getClassLoader(),
            CollectorContractTest.class.getName().replace('.', '/'),
            null,
            CollectorContractTest.class.getProtectionDomain(),
            classBytes(CollectorContractTest.class)
        );

        state.configure(Collections.singleton(production), Collections.singleton(codeSource(CollectorContractTest.class)));
        byte[] instrumented = new AffectedCollectorAgent.CollectorTransformer(state).transform(
            CollectorContractTest.class.getClassLoader(),
            CollectorContractTest.class.getName().replace('.', '/'),
            null,
            CollectorContractTest.class.getProtectionDomain(),
            classBytes(CollectorContractTest.class)
        );

        assertNull(ignored);
        assertNotNull(instrumented);
    }

    @Test
    public void propertyCodeSourceAliasesRemainAllowlisted() throws Exception {
        Path alias = temporary.getRoot().toPath().resolve("classes-alias");
        PlatformCapabilities.createSymbolicLink(alias, codeSource(ObservedFixture.class).toRealPath());
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

            state.beginExecution("execution", "fixture.ExampleTest");
            state.hit(ObservedFixture.class);
            state.endExecution("execution");
            assertTrue(state.isSupported());
            assertEquals(1, state.snapshot("fixture.ExampleTest").size());
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
        assertTrue(state.snapshot("fixture.ExampleTest").isEmpty());
    }

    @Test
    public void unsupportedStateStopsInstrumentationAndAttribution() throws Exception {
        AffectedCollectorAgent.CollectorState state = configuredState(ObservedFixture.class);
        state.fail(new IllegalStateException("unsupported"));

        state.hit(ObservedFixture.class);
        byte[] transformed = new AffectedCollectorAgent.CollectorTransformer(state).transform(
            ObservedFixture.class.getClassLoader(),
            ObservedFixture.class.getName().replace('.', '/'),
            null,
            ObservedFixture.class.getProtectionDomain(),
            classBytes(ObservedFixture.class)
        );

        assertNull(transformed);
        assertTrue(state.snapshot("fixture.ExampleTest").isEmpty());
    }

    @Test
    public void unattributedNonProductionReflectionDoesNotFailTheCollector() throws Exception {
        AffectedCollectorAgent.CollectorState state = configuredState(ObservedFixture.class);

        state.hit(String.class);

        assertTrue(state.isSupported());
        assertTrue(state.snapshot("fixture.ExampleTest").isEmpty());
    }

    @Test
    public void productionAccessBeforeTheFirstTestFailsClosed() throws Exception {
        AffectedCollectorAgent.CollectorState state = configuredState(ObservedFixture.class);

        assertEquals(Long.MIN_VALUE, state.executionId());

        assertFalse(state.isSupported());
    }

    @Test
    public void staticCallGraphAddsTransitiveProductionDependencies() throws Exception {
        Path production = temporary.newFolder("static-production").toPath();
        writeClass(production, ObservedFixture.class);
        writeClass(production, SecondObservedFixture.class);
        AffectedCollectorAgent.CollectorState state = new AffectedCollectorAgent.CollectorState();
        state.configure(Collections.singleton(production), Collections.singleton(codeSource(CollectorContractTest.class)));
        String testClass = CollectorContractTest.class.getName();
        state.staticReference(testClass.replace('.', '/'), ObservedFixture.class.getName().replace('.', '/'));
        state.staticReference(
            ObservedFixture.class.getName().replace('.', '/'),
            SecondObservedFixture.class.getName().replace('.', '/')
        );

        List<AffectedCollectorAgent.Dependency> dependencies = state.snapshot(testClass);

        assertEquals(2, dependencies.size());
        assertEquals(ObservedFixture.class.getName(), dependencies.get(0).getClassName());
        assertEquals(SecondObservedFixture.class.getName(), dependencies.get(1).getClassName());
    }

    @Test
    public void dynamicallyObservedProductionClassesRootTheirStaticDependencies() throws Exception {
        AffectedCollectorAgent.CollectorState state = new AffectedCollectorAgent.CollectorState();
        state.configure(Collections.singleton(codeSource(DynamicProductionFixture.class)));
        state.staticReference(
            DynamicProductionFixture.class.getName().replace('.', '/'),
            SecondObservedFixture.class.getName().replace('.', '/')
        );
        state.beginExecution("execution", "fixture.ExampleTest");
        state.hit(DynamicProductionFixture.class);
        state.endExecution("execution");

        List<AffectedCollectorAgent.Dependency> dependencies = state.snapshot("fixture.ExampleTest");

        assertEquals(2, dependencies.size());
        assertEquals(DynamicProductionFixture.class.getName(), dependencies.get(0).getClassName());
        assertEquals(SecondObservedFixture.class.getName(), dependencies.get(1).getClassName());
    }

    @Test
    public void transformedArrayMergesRemainVerifierSafe() throws Exception {
        AffectedCollectorAgent.CollectorState state = new AffectedCollectorAgent.CollectorState();
        state.configure(Collections.singleton(codeSource(ArrayMergeFixture.class)));
        byte[] transformed = new AffectedCollectorAgent.CollectorTransformer(state).transform(
            ArrayMergeFixture.class.getClassLoader(),
            ArrayMergeFixture.class.getName().replace('.', '/'),
            null,
            ArrayMergeFixture.class.getProtectionDomain(),
            classBytes(ArrayMergeFixture.class)
        );

        assertNotNull(transformed);
        Class<?> type = new ByteArrayLoader(ArrayMergeFixture.class.getClassLoader()).define(
            ArrayMergeFixture.class.getName(),
            transformed
        );
        assertNull(type.getMethod("first", boolean.class).invoke(null, true));
        assertNull(type.getMethod("first", boolean.class).invoke(null, false));
    }

    @Test
    public void staticAndPrivateHotPathsDoNotReceiveRuntimeEntryProbes() throws Exception {
        AffectedCollectorAgent.CollectorState state = new AffectedCollectorAgent.CollectorState();
        state.configure(Collections.singleton(codeSource(HotLoopFixture.class)));

        byte[] transformed = new AffectedCollectorAgent.CollectorTransformer(state).transform(
            HotLoopFixture.class.getClassLoader(),
            HotLoopFixture.class.getName().replace('.', '/'),
            null,
            HotLoopFixture.class.getProtectionDomain(),
            classBytes(HotLoopFixture.class)
        );

        assertNotNull(transformed);
        AtomicInteger runtimeEntryProbes = new AtomicInteger();
        new ClassReader(transformed).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(
                int access,
                String name,
                String descriptor,
                String signature,
                String[] exceptions
            ) {
                if (!name.equals("run") && !name.equals("value")) return null;
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(
                        int opcode,
                        String owner,
                        String name,
                        String descriptor,
                        boolean isInterface
                    ) {
                        if (owner.equals(AffectedCollectorAgent.class.getName().replace('.', '/'))
                            && name.equals("hitProduction")) {
                            runtimeEntryProbes.incrementAndGet();
                        }
                    }
                };
            }
        }, 0);
        assertEquals(0, runtimeEntryProbes.get());
    }

    @Test
    public void classCatalogResolvesAProductionClassBeforeTransformerObservation() throws Exception {
        AffectedCollectorAgent.CollectorState state = new AffectedCollectorAgent.CollectorState();
        state.configure(Collections.singleton(codeSource(ObservedFixture.class)));
        state.beginExecution("execution", "fixture.ExampleTest");

        state.hit(ObservedFixture.class);
        state.endExecution("execution");

        assertEquals(1, state.snapshot("fixture.ExampleTest").size());
        assertTrue(state.isSupported());
    }

    @Test
    public void singleActiveClassStillFailsClosedForAsynchronousAccess() throws Exception {
        AffectedCollectorAgent.CollectorState state = configuredState(ObservedFixture.class);
        state.beginExecution("execution", "fixture.ExampleTest");
        Thread asynchronous = new Thread(() -> {
            try {
                state.hit(ObservedFixture.class);
            } catch (Throwable failure) {
                state.fail(failure);
            }
        });

        asynchronous.start();
        asynchronous.join(5_000L);
        state.endExecution("execution");

        assertFalse(asynchronous.isAlive());
        assertFalse(state.isSupported());
        assertTrue(state.snapshot("fixture.ExampleTest").isEmpty());
    }

    @Test
    public void concurrentAsynchronousAccessFailsClosed() throws Exception {
        AffectedCollectorAgent.CollectorState state = configuredState(ObservedFixture.class);
        state.beginExecution("first", "fixture.FirstTest");
        state.beginExecution("second", "fixture.SecondTest");
        Thread asynchronous = new Thread(() -> {
            try {
                state.hit(ObservedFixture.class);
            } catch (Throwable failure) {
                state.fail(failure);
            }
        });

        asynchronous.start();
        asynchronous.join(5_000L);
        state.endExecution("second");
        state.endExecution("first");

        assertFalse(asynchronous.isAlive());
        assertFalse(state.isSupported());
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
    public void isolatedMavenForksUseDeterministicTestClassWorkers() throws Exception {
        Path outputRoot = temporary.getRoot().toPath().resolve("isolated-maven-output");
        System.setProperty(CollectorOutput.OUTPUT_PROPERTY, outputRoot.toString());
        System.setProperty(CollectorOutput.WORKER_PROPERTY, "reused-fork-slot");
        System.setProperty("affected.collector.runner", "maven");
        System.setProperty("affected.collector.reuseForks", "false");

        CollectorOutput.fromSystemProperties(Collections.singleton("fixture.AlphaTest"));
        CollectorOutput.fromSystemProperties(Collections.singleton("fixture.BetaTest"));
        CollectorOutput.fromSystemProperties(Collections.singleton("fixture.AlphaTest"));
        System.setProperty(CollectorOutput.WORKER_PROPERTY, "second-fork-slot");
        CollectorOutput.fromSystemProperties(Collections.singleton("fixture.AlphaTest"));

        try (Stream<Path> files = Files.list(outputRoot)) {
            assertEquals(3, files.count());
        }
        assertRejectedExpectedClasses(Collections.<String>emptySet());
        assertRejectedExpectedClasses(new HashSet<String>(Arrays.asList("fixture.AlphaTest", "fixture.BetaTest")));
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
        PlatformCapabilities.createSymbolicLink(workerLink, outside);
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
    public void parallelJupiterClassesKeepIndependentDependencies() throws Exception {
        Path outputRoot = prepareListener(ObservedFixture.class, SecondObservedFixture.class);
        Launcher launcher = LauncherFactory.create(
            LauncherConfig.builder().enableTestExecutionListenerAutoRegistration(false).build()
        );
        launcher.registerTestExecutionListeners(new AffectedTestExecutionListener());

        launcher.execute(
            LauncherDiscoveryRequestBuilder.request()
                .configurationParameter("junit.jupiter.execution.parallel.enabled", "true")
                .configurationParameter("junit.jupiter.execution.parallel.mode.default", "concurrent")
                .configurationParameter("junit.jupiter.execution.parallel.mode.classes.default", "concurrent")
                .configurationParameter("junit.jupiter.execution.parallel.config.strategy", "fixed")
                .configurationParameter("junit.jupiter.execution.parallel.config.fixed.parallelism", "2")
                .selectors(
                    selectClass(ParallelFixtures.FirstFixture.class),
                    selectClass(ParallelFixtures.SecondFixture.class)
                )
                .build()
        );

        Map<String, String> maps = mapsByTest(onlyWorkerDirectory(outputRoot));
        String first = maps.get(ParallelFixtures.FirstFixture.class.getName());
        String second = maps.get(ParallelFixtures.SecondFixture.class.getName());
        assertNotNull(first);
        assertNotNull(second);
        assertTrue(first.contains("dependency=" + encode(ObservedFixture.class.getName()) + "|"));
        assertFalse(first.contains("dependency=" + encode(SecondObservedFixture.class.getName()) + "|"));
        assertTrue(second.contains("dependency=" + encode(SecondObservedFixture.class.getName()) + "|"));
        assertFalse(second.contains("dependency=" + encode(ObservedFixture.class.getName()) + "|"));
    }

    @Test
    public void listenerMarksSourcelessTestsUnsupported() throws Exception {
        Path outputRoot = prepareListener();
        System.setProperty("affected.collector.runner", "maven");
        System.setProperty("affected.collector.reuseForks", "false");
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
        assertTrue(read(workerDirectory.resolve("expected.manifest")).contains("supported=false"));
        assertTrue(read(workerDirectory.resolve("complete.manifest")).contains("supported=false"));
    }

    @Test
    public void missingOutputNeverFailsTheUserTestRun() throws Exception {
        configureAgentState(ObservedFixture.class);
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
            "-Daffected.collector.debug=true",
            "-D" + AffectedCollectorAgent.CODE_SOURCES_PROPERTY + "=" + requiredProperty("affected.smoke.codeSources"),
            "-D" + AffectedCollectorAgent.TEST_CODE_SOURCES_PROPERTY + "=" +
                requiredProperty("affected.smoke.instrumentationSources"),
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
        String completion = read(workerDirectory.resolve("complete.manifest"));
        assertTrue(log + "\n" + completion, completion.contains("supported=true"));
        try (Stream<Path> files = Files.list(workerDirectory)) {
            assertEquals(4, files.filter(path -> path.getFileName().toString().endsWith(".map")).count());
        }
    }

    private Path prepareListener() throws Exception {
        return prepareListener(ObservedFixture.class);
    }

    private Path prepareListener(Class<?>... observedTypes) throws Exception {
        Path outputRoot = temporary.getRoot().toPath().resolve("listener");
        System.setProperty(CollectorOutput.OUTPUT_PROPERTY, outputRoot.toString());
        System.setProperty(CollectorOutput.WORKER_PROPERTY, "worker-1");
        configureAgentState(observedTypes);
        return outputRoot;
    }

    private void configureAgentState(Class<?>... observedTypes) throws Exception {
        AffectedCollectorAgent.CollectorState state = AffectedCollectorAgent.state();
        state.configure(Collections.singleton(codeSource(ObservedFixture.class)));
        for (Class<?> observedType : observedTypes) {
            new AffectedCollectorAgent.CollectorTransformer(state).transform(
                observedType.getClassLoader(),
                observedType.getName().replace('.', '/'),
                null,
                observedType.getProtectionDomain(),
                classBytes(observedType)
            );
        }
    }

    private static AffectedCollectorAgent.CollectorState configuredState(Class<?>... observedTypes) throws Exception {
        AffectedCollectorAgent.CollectorState state = new AffectedCollectorAgent.CollectorState();
        state.configure(Collections.singleton(codeSource(ObservedFixture.class)));
        for (Class<?> observedType : observedTypes) {
            new AffectedCollectorAgent.CollectorTransformer(state).transform(
                observedType.getClassLoader(),
                observedType.getName().replace('.', '/'),
                null,
                observedType.getProtectionDomain(),
                classBytes(observedType)
            );
        }
        return state;
    }

    private static Map<String, String> mapsByTest(Path workerDirectory) throws Exception {
        Map<String, String> result = new HashMap<String, String>();
        try (Stream<Path> files = Files.list(workerDirectory)) {
            for (Path map : files.filter(path -> path.getFileName().toString().endsWith(".map"))
                .collect(Collectors.toList())) {
                String content = read(map);
                String encodedTest = Stream.of(content.split("\n"))
                    .filter(line -> line.startsWith("test="))
                    .findFirst()
                    .orElseThrow(AssertionError::new)
                    .substring("test=".length());
                result.put(new String(Base64.getUrlDecoder().decode(encodedTest), StandardCharsets.UTF_8), content);
            }
        }
        return result;
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

    private static void assertRejectedExpectedClasses(Set<String> testClasses) throws Exception {
        try {
            CollectorOutput.fromSystemProperties(testClasses);
            fail("isolated Maven fork requires exactly one test class");
        } catch (IllegalStateException expected) {
            assertEquals("affected.collector.expectedTests", expected.getMessage());
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

    private static void writeClass(Path root, Class<?> type) throws Exception {
        Path target = root.resolve(type.getName().replace('.', '/') + ".class");
        Files.createDirectories(target.getParent());
        Files.write(target, classBytes(type));
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

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
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

    public static final class SecondObservedFixture {
    }

    public static final class HotLoopFixture {
        public static int run(int iterations) {
            int result = 0;
            for (int index = 0; index < iterations; index++) result += value(index);
            return result;
        }

        private static int value(int input) {
            return input & 1;
        }
    }

    public static final class DynamicProductionFixture {
    }

    public static final class ArrayMergeFixture {
        public static Object first(boolean strings) {
            Object[][] values = strings ? new String[1][1] : new Integer[1][1];
            return values[0][0];
        }
    }

    private static final class ByteArrayLoader extends ClassLoader {
        private ByteArrayLoader(ClassLoader parent) {
            super(parent);
        }

        private Class<?> define(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }

    public static final class StableJupiterFixture {
        private static final AtomicInteger executions = new AtomicInteger();

        @org.junit.jupiter.api.Test
        void passes() {
            AffectedCollectorAgent.hit(ObservedFixture.class);
            executions.incrementAndGet();
        }
    }

    public static final class StableVintageFixture {
        private static final AtomicInteger executions = new AtomicInteger();

        @org.junit.Test
        public void passes() {
            AffectedCollectorAgent.hit(ObservedFixture.class);
            executions.incrementAndGet();
        }
    }

    public static final class ParallelFixtures {
        private static volatile CyclicBarrier barrier = new CyclicBarrier(2);

        public static final class FirstFixture {
            @org.junit.jupiter.api.Test
            void passes() throws Exception {
                barrier.await(5, TimeUnit.SECONDS);
                AffectedCollectorAgent.hit(ObservedFixture.class);
            }
        }

        public static final class SecondFixture {
            @org.junit.jupiter.api.Test
            void passes() throws Exception {
                barrier.await(5, TimeUnit.SECONDS);
                AffectedCollectorAgent.hit(SecondObservedFixture.class);
            }
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
