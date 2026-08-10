package com.aspix2k.affected.collector;

import org.junit.platform.engine.TestSource;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class AffectedTestExecutionListener implements TestExecutionListener {
    private final Set<String> expectedClasses = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private final Set<String> completedClasses = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private final AtomicBoolean unsupported = new AtomicBoolean();
    private volatile CollectorOutput output;
    private volatile TestPlan plan;

    @Override
    public void testPlanExecutionStarted(TestPlan testPlan) {
        plan = testPlan;
        try {
            output = CollectorOutput.fromSystemProperties();
        } catch (Exception failure) {
            AffectedCollectorAgent.markUnsupported();
            unsupported.set(true);
        }
        for (TestIdentifier root : testPlan.getRoots()) {
            inspect(testPlan, root);
            for (TestIdentifier identifier : testPlan.getDescendants(root)) inspect(testPlan, identifier);
        }
        if (expectedClasses.isEmpty()) unsupported.set(true);
    }

    @Override
    public void dynamicTestRegistered(TestIdentifier testIdentifier) {
        TestPlan current = plan;
        if (current != null) inspect(current, testIdentifier);
        else unsupported.set(true);
    }

    @Override
    public void executionFinished(
        TestIdentifier testIdentifier,
        org.junit.platform.engine.TestExecutionResult testExecutionResult
    ) {
        Optional<TestSource> source = testIdentifier.getSource();
        if (!source.isPresent() || !(source.get() instanceof ClassSource)) return;
        String testClass = ((ClassSource) source.get()).getClassName();
        if (!completedClasses.add(testClass)) return;
        try {
            java.util.List<AffectedCollectorAgent.Dependency> dependencies = AffectedCollectorAgent.dependencies();
            if (dependencies.isEmpty() || output == null) {
                unsupported.set(true);
                return;
            }
            output.writeMap(testClass, dependencies);
        } catch (Exception failure) {
            AffectedCollectorAgent.markUnsupported();
            unsupported.set(true);
        }
    }

    @Override
    public void testPlanExecutionFinished(TestPlan testPlan) {
        CollectorOutput current = output;
        if (current == null) return;
        boolean supported = !unsupported.get()
            && AffectedCollectorAgent.isSupported()
            && !expectedClasses.isEmpty()
            && completedClasses.containsAll(expectedClasses);
        try {
            current.writeCompletion(supported, expectedClasses);
        } catch (Exception failure) {
            AffectedCollectorAgent.markUnsupported();
        }
    }

    private void inspect(TestPlan testPlan, TestIdentifier identifier) {
        if (!identifier.isTest()) return;
        String testClass = stableClass(testPlan, identifier);
        if (testClass == null) unsupported.set(true);
        else expectedClasses.add(testClass);
    }

    private static String stableClass(TestPlan testPlan, TestIdentifier identifier) {
        TestIdentifier current = identifier;
        while (current != null) {
            Optional<TestSource> source = current.getSource();
            if (source.isPresent()) {
                if (source.get() instanceof ClassSource) return ((ClassSource) source.get()).getClassName();
                if (source.get() instanceof MethodSource) return ((MethodSource) source.get()).getClassName();
            }
            Optional<TestIdentifier> parent = testPlan.getParent(current);
            current = parent.isPresent() ? parent.get() : null;
        }
        return null;
    }
}
