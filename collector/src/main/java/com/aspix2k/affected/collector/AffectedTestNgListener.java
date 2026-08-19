package com.aspix2k.affected.collector;

import org.testng.IClass;
import org.testng.IClassListener;
import org.testng.IExecutionListener;
import org.testng.IInvokedMethod;
import org.testng.IInvokedMethodListener;
import org.testng.ITestClass;
import org.testng.ITestNGMethod;
import org.testng.ITestResult;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class AffectedTestNgListener implements IInvokedMethodListener, IClassListener, IExecutionListener {
    static final String ENABLED_PROPERTY = "affected.collector.testng";

    private final Set<String> seen = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private final Set<String> written = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private final AtomicBoolean unsupported = new AtomicBoolean();
    private final AtomicBoolean enabled = new AtomicBoolean();
    private volatile CollectorOutput output;

    @Override
    public void beforeInvocation(IInvokedMethod method, ITestResult result) {
        if (!enabled()) return;
        String testClass = className(method, result);
        if (testClass == null) {
            unsupported.set(true);
            return;
        }
        try {
            AffectedCollectorAgent.beginExecution(token(method, result, testClass), testClass);
            if (output == null) {
                output = CollectorOutput.fromSystemProperties(Collections.singleton(testClass));
            }
        } catch (Throwable failure) {
            AffectedCollectorAgent.markUnsupported();
            unsupported.set(true);
        }
    }

    @Override
    public void afterInvocation(IInvokedMethod method, ITestResult result) {
        if (!enabled()) return;
        String testClass = className(method, result);
        if (testClass == null) return;
        seen.add(testClass);
        try {
            AffectedCollectorAgent.endExecution(token(method, result, testClass));
        } catch (Throwable failure) {
            AffectedCollectorAgent.markUnsupported();
            unsupported.set(true);
        }
    }

    @Override
    public void onBeforeClass(ITestClass testClass) {
    }

    @Override
    public void onAfterClass(ITestClass testClass) {
        if (!enabled()) return;
        writeClassMap(className(testClass));
    }

    @Override
    public void onExecutionStart() {
    }

    @Override
    public void onExecutionFinish() {
        if (!enabled()) return;
        for (String testClass : seen) writeClassMap(testClass);
        CollectorOutput current = output;
        if (current == null) return;
        boolean supported = !unsupported.get()
            && AffectedCollectorAgent.isSupported()
            && !seen.isEmpty();
        try {
            current.writeCompletion(supported, seen);
        } catch (Throwable failure) {
            AffectedCollectorAgent.markUnsupported();
        }
    }

    private boolean enabled() {
        if (enabled.get()) return true;
        boolean value = "true".equals(System.getProperty(ENABLED_PROPERTY));
        if (value) enabled.set(true);
        return value;
    }

    private void writeClassMap(String testClass) {
        if (testClass == null || output == null || !written.add(testClass)) return;
        try {
            output.writeMap(testClass, AffectedCollectorAgent.dependencies(testClass));
        } catch (Throwable failure) {
            AffectedCollectorAgent.markUnsupported();
            unsupported.set(true);
        }
    }

    private static String className(IInvokedMethod method, ITestResult result) {
        if (result != null) {
            String fromResult = className(result.getTestClass());
            if (fromResult != null) return fromResult;
            ITestNGMethod testMethod = result.getMethod();
            if (testMethod != null) {
                String fromMethod = className(testMethod.getTestClass());
                if (fromMethod != null) return fromMethod;
            }
        }
        if (method != null && method.getTestMethod() != null) {
            return className(method.getTestMethod().getTestClass());
        }
        return null;
    }

    private static String className(IClass testClass) {
        if (testClass == null) return null;
        Class<?> type = testClass.getRealClass();
        if (type != null && type.getName() != null && !type.getName().trim().isEmpty()) {
            return type.getName();
        }
        String name = testClass.getName();
        if (name == null || name.trim().isEmpty()) return null;
        return name;
    }

    private static String token(IInvokedMethod method, ITestResult result, String testClass) {
        String methodName = "";
        if (result != null && result.getMethod() != null) {
            methodName = String.valueOf(result.getMethod().getMethodName());
        } else if (method != null && method.getTestMethod() != null) {
            methodName = String.valueOf(method.getTestMethod().getMethodName());
        }
        return testClass + "#" + methodName + "#" + System.identityHashCode(result == null ? method : result);
    }
}
