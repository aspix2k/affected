package com.aspix2k.affected.collector;

import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.TestSource;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class AffectedTestExecutionListener implements TestExecutionListener {
    private final Set<String> completedClasses = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private final Set<String> expectedClasses = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private final Map<String, String> classesByIdentifier = new ConcurrentHashMap<String, String>();
    private final Map<String, Set<String>> enclosingClasses = new ConcurrentHashMap<String, Set<String>>();
    private final AtomicBoolean unsupported = new AtomicBoolean();
    private volatile CollectorOutput output;

    @Override
    public void testPlanExecutionStarted(TestPlan testPlan) {
        Set<String> discovered = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
        for (TestIdentifier root : testPlan.getRoots()) {
            rememberClass(testPlan, root);
            for (TestIdentifier identifier : testPlan.getDescendants(root)) {
                rememberClass(testPlan, identifier);
                if (!identifier.isTest()) continue;
                String testClass = classesByIdentifier.get(identifier.getUniqueId());
                if (testClass == null) unsupported.set(true);
                else discovered.add(testClass);
            }
        }
        if (!discovered.isEmpty()
            && "maven".equals(System.getProperty("affected.collector.runner"))) {
            prepareOutput(discovered);
        }
    }

    @Override
    public void dynamicTestRegistered(TestIdentifier testIdentifier) {
        String testClass = directClass(testIdentifier);
        if (testClass == null) unsupported.set(true);
        else classesByIdentifier.put(testIdentifier.getUniqueId(), testClass);
    }

    @Override
    public void executionStarted(TestIdentifier testIdentifier) {
        String testClass = classesByIdentifier.get(testIdentifier.getUniqueId());
        if (testClass == null) testClass = directClass(testIdentifier);
        if (testClass != null) {
            AffectedCollectorAgent.beginExecution(testIdentifier.getUniqueId(), testClass);
            boolean maven = "maven".equals(System.getProperty("affected.collector.runner"));
            if (output == null || (maven && !expectedClasses.contains(testClass))) {
                prepareOutput(Collections.singleton(testClass));
            }
        } else if (testIdentifier.isTest()) {
            unsupported.set(true);
            prepareUnsupportedOutput(testIdentifier.getUniqueId());
        }
    }

    @Override
    public void executionFinished(
        TestIdentifier testIdentifier,
        TestExecutionResult testExecutionResult
    ) {
        if (testExecutionResult != null
            && testExecutionResult.getStatus() == TestExecutionResult.Status.ABORTED) {
            unsupported.set(true);
        }
        String stableClass = classesByIdentifier.get(testIdentifier.getUniqueId());
        if (stableClass == null) stableClass = directClass(testIdentifier);
        if (stableClass != null) AffectedCollectorAgent.endExecution(testIdentifier.getUniqueId());
        Optional<TestSource> source = testIdentifier.getSource();
        if (!source.isPresent() || !(source.get() instanceof ClassSource)) return;
        String testClass = ((ClassSource) source.get()).getClassName();
        if (output == null) return;
        completedClasses.add(testClass);
        writeClassMap(testClass);
    }

    @Override
    public void testPlanExecutionFinished(TestPlan testPlan) {
        CollectorOutput current = output;
        if (current == null) return;
        for (String testClass : completedClasses) writeClassMap(testClass);
        boolean supported = !unsupported.get()
            && AffectedCollectorAgent.isSupported()
            && !completedClasses.isEmpty();
        try {
            if ("maven".equals(System.getProperty("affected.collector.runner"))) {
                current.writeExpected(supported, expectedClasses);
            }
            current.writeCompletion(supported, completedClasses);
        } catch (Exception failure) {
            AffectedCollectorAgent.markUnsupported();
        }
    }

    private static Set<String> enclosingClasses(TestPlan testPlan, TestIdentifier identifier, String testClass) {
        Set<String> enclosing = new LinkedHashSet<String>();
        if (testPlan == null) return enclosing;
        Optional<TestIdentifier> parent = testPlan.getParent(identifier);
        while (parent.isPresent()) {
            String parentClass = directClass(parent.get());
            if (parentClass != null && !parentClass.equals(testClass)) enclosing.add(parentClass);
            parent = testPlan.getParent(parent.get());
        }
        return enclosing;
    }

    private void writeClassMap(String testClass) {
        CollectorOutput current = output;
        if (current == null) return;
        try {
            Map<String, AffectedCollectorAgent.Dependency> merged =
                new LinkedHashMap<String, AffectedCollectorAgent.Dependency>();
            addDependencies(merged, testClass);
            Set<String> enclosing = enclosingClasses.get(testClass);
            if (enclosing != null) {
                for (String enclosingClass : enclosing) addDependencies(merged, enclosingClass);
            }
            current.writeMap(testClass, new ArrayList<AffectedCollectorAgent.Dependency>(merged.values()));
        } catch (Exception failure) {
            AffectedCollectorAgent.markUnsupported();
            unsupported.set(true);
        }
    }

    private static void addDependencies(Map<String, AffectedCollectorAgent.Dependency> merged, String testClass) {
        for (AffectedCollectorAgent.Dependency dependency : AffectedCollectorAgent.dependencies(testClass)) {
            merged.put(dependency.getClassName() + "\n" + dependency.getCodeSource(), dependency);
        }
    }

    private static String stableClass(TestPlan testPlan, TestIdentifier identifier) {
        TestIdentifier current = identifier;
        while (current != null) {
            String testClass = directClass(current);
            if (testClass != null) return testClass;
            Optional<TestIdentifier> parent = testPlan.getParent(current);
            current = parent.isPresent() ? parent.get() : null;
        }
        return null;
    }

    private void rememberClass(TestPlan testPlan, TestIdentifier identifier) {
        Optional<TestSource> source = identifier.getSource();
        if (source.isPresent() && source.get() instanceof ClassSource) {
            String ownClass = ((ClassSource) source.get()).getClassName();
            enclosingClasses.put(ownClass, enclosingClasses(testPlan, identifier, ownClass));
        }
        String testClass = stableClass(testPlan, identifier);
        if (testClass != null) classesByIdentifier.put(identifier.getUniqueId(), testClass);
    }

    private static String directClass(TestIdentifier identifier) {
        Optional<TestSource> source = identifier.getSource();
        if (!source.isPresent()) return null;
        if (source.get() instanceof ClassSource) return ((ClassSource) source.get()).getClassName();
        if (source.get() instanceof MethodSource) return ((MethodSource) source.get()).getClassName();
        return null;
    }

    private synchronized void prepareOutput(Set<String> discovered) {
        try {
            expectedClasses.addAll(discovered);
            boolean invalidIsolatedPlan = "maven".equals(System.getProperty("affected.collector.runner"))
                && "false".equals(System.getProperty("affected.collector.reuseForks"))
                && expectedClasses.size() != 1;
            if (invalidIsolatedPlan) unsupported.set(true);
            if (output == null) {
                output = invalidIsolatedPlan
                    ? CollectorOutput.fromSystemProperties(
                        expectedClasses,
                        "unsupported-plan:" + new java.util.TreeSet<String>(expectedClasses)
                    )
                    : CollectorOutput.fromSystemProperties(expectedClasses);
            }
            if ("maven".equals(System.getProperty("affected.collector.runner"))) {
                output.writeExpected(!unsupported.get() && AffectedCollectorAgent.isSupported(), expectedClasses);
            }
        } catch (Exception failure) {
            AffectedCollectorAgent.markUnsupported();
            unsupported.set(true);
        }
    }

    private synchronized void prepareUnsupportedOutput(String uniqueId) {
        try {
            if (output == null) {
                output = CollectorOutput.fromSystemProperties(
                    expectedClasses,
                    "unsupported:" + uniqueId
                );
            }
            if ("maven".equals(System.getProperty("affected.collector.runner"))) {
                output.writeExpected(false, expectedClasses);
            }
        } catch (Exception failure) {
            AffectedCollectorAgent.markUnsupported();
            unsupported.set(true);
        }
    }
}
