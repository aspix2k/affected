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
    private final Set<String> completedClasses = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private final AtomicBoolean unsupported = new AtomicBoolean();
    private volatile CollectorOutput output;
    private volatile TestPlan plan;

    @Override
    public void testPlanExecutionStarted(TestPlan testPlan) {
        plan = testPlan;
    }

    @Override
    public void dynamicTestRegistered(TestIdentifier testIdentifier) {
    }

    @Override
    public void executionStarted(TestIdentifier testIdentifier) {
        TestPlan current = plan;
        String testClass = current == null ? null : stableClass(current, testIdentifier);
        if (testClass != null) {
            AffectedCollectorAgent.beginExecution(testIdentifier.getUniqueId(), testClass);
        } else if (testIdentifier.isTest()) {
            unsupported.set(true);
        }
        if (testIdentifier.isTest()) openOutput();
    }

    @Override
    public void executionFinished(
        TestIdentifier testIdentifier,
        org.junit.platform.engine.TestExecutionResult testExecutionResult
    ) {
        TestPlan current = plan;
        String stableClass = current == null ? null : stableClass(current, testIdentifier);
        if (stableClass != null) AffectedCollectorAgent.endExecution(testIdentifier.getUniqueId());
        Optional<TestSource> source = testIdentifier.getSource();
        if (!source.isPresent() || !(source.get() instanceof ClassSource)) return;
        String testClass = ((ClassSource) source.get()).getClassName();
        if (output == null) return;
        if (!completedClasses.add(testClass)) return;
        try {
            java.util.List<AffectedCollectorAgent.Dependency> dependencies =
                AffectedCollectorAgent.dependencies(testClass);
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
            && !completedClasses.isEmpty();
        try {
            if ("maven".equals(System.getProperty("affected.collector.runner"))) {
                current.writeExpected(supported, completedClasses);
            }
            current.writeCompletion(supported, completedClasses);
        } catch (Exception failure) {
            AffectedCollectorAgent.markUnsupported();
        }
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

    private synchronized void openOutput() {
        if (output != null) return;
        try {
            output = CollectorOutput.fromSystemProperties();
        } catch (Exception failure) {
            AffectedCollectorAgent.markUnsupported();
            unsupported.set(true);
        }
    }
}
