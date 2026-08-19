package com.aspix2k.affected.collector;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

final class AffectedJUnit4Bridge {
    static final String ENABLED_PROPERTY = "affected.collector.junit4";

    private static final Set<String> COMPLETED = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private static final AtomicBoolean UNSUPPORTED = new AtomicBoolean();
    private static final AtomicBoolean ENABLED = new AtomicBoolean();
    private static volatile CollectorOutput output;

    private AffectedJUnit4Bridge() {
    }

    static boolean enabled() {
        if (ENABLED.get()) return true;
        boolean enabled = "true".equals(System.getProperty(ENABLED_PROPERTY));
        if (enabled) ENABLED.set(true);
        return enabled;
    }

    static void started(Object description) {
        if (!enabled()) return;
        String testClass = className(description);
        if (testClass == null) {
            UNSUPPORTED.set(true);
            return;
        }
        try {
            AffectedCollectorAgent.beginExecution(token(description, testClass), testClass);
            prepareOutput(testClass);
        } catch (Throwable failure) {
            AffectedCollectorAgent.markUnsupported();
            UNSUPPORTED.set(true);
        }
    }

    static void finished(Object description) {
        if (!enabled()) return;
        String testClass = className(description);
        if (testClass != null) {
            try {
                AffectedCollectorAgent.endExecution(token(description, testClass));
            } catch (Throwable failure) {
                AffectedCollectorAgent.markUnsupported();
                UNSUPPORTED.set(true);
            }
        }
        if (testClass == null || output == null || !COMPLETED.add(testClass)) return;
        try {
            output.writeMap(testClass, AffectedCollectorAgent.dependencies(testClass));
        } catch (Throwable failure) {
            AffectedCollectorAgent.markUnsupported();
            UNSUPPORTED.set(true);
        }
    }

    static void runFinished() {
        if (!enabled()) return;
        CollectorOutput current = output;
        if (current == null) return;
        boolean supported = !UNSUPPORTED.get()
            && AffectedCollectorAgent.isSupported()
            && !COMPLETED.isEmpty();
        try {
            current.writeCompletion(supported, COMPLETED);
        } catch (Throwable failure) {
            AffectedCollectorAgent.markUnsupported();
        }
    }

    private static void prepareOutput(String testClass) throws Exception {
        if (output == null) {
            output = CollectorOutput.fromSystemProperties(Collections.singleton(testClass));
        }
    }

    private static String className(Object description) {
        String value = invoke(description, "getClassName");
        if (value == null || value.trim().isEmpty()) return null;
        return value;
    }

    private static String token(Object description, String testClass) {
        return testClass + "#" + String.valueOf(invoke(description, "getMethodName"))
            + "#" + String.valueOf(invoke(description, "getDisplayName"));
    }

    private static String invoke(Object description, String methodName) {
        if (description == null) return null;
        try {
            Method method = description.getClass().getMethod(methodName);
            Object value = method.invoke(description);
            return value == null ? null : String.valueOf(value);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }
}
