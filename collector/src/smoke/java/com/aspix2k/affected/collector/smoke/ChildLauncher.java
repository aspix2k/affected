package com.aspix2k.affected.collector.smoke;

import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.core.LauncherConfig;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.ServiceLoader;

import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

public final class ChildLauncher {
    private ChildLauncher() {
    }

    public static void run() {
        Launcher launcher = LauncherFactory.create(
            LauncherConfig.builder().enableTestExecutionListenerAutoRegistration(true).build()
        );
        SummaryGeneratingListener summary = new SummaryGeneratingListener();
        launcher.registerTestExecutionListeners(summary);
        launcher.execute(
            LauncherDiscoveryRequestBuilder.request()
                .selectors(
                    selectClass(JupiterFixture.class),
                    selectClass(ReflectiveFixture.class),
                    selectClass(ServiceLoaderFixture.class),
                    selectClass(VintageFixture.class)
                )
                .build()
        );
        if (JupiterFixture.executions.get() != 1
            || ReflectiveFixture.executions.get() != 1
            || ServiceLoaderFixture.executions.get() != 1
            || VintageFixture.executions.get() != 1) {
            throw new IllegalStateException(
                "smoke executions: jupiter=" + JupiterFixture.executions.get() +
                    ", reflection=" + ReflectiveFixture.executions.get() +
                    ", service=" + ServiceLoaderFixture.executions.get() +
                    ", vintage=" + VintageFixture.executions.get() +
                    ", found=" + summary.getSummary().getTestsFoundCount() +
                    ", started=" + summary.getSummary().getTestsStartedCount() +
                    ", failed=" + summary.getSummary().getTestsFailedCount() +
                    ", firstFailure=" + (summary.getSummary().getFailures().isEmpty()
                        ? "none"
                        : summary.getSummary().getFailures().get(0).getException())
            );
        }
    }

    public static final class JupiterFixture {
        private static final AtomicInteger executions = new AtomicInteger();

        @org.junit.jupiter.api.Test
        void passes() {
            if (ProductionFixture.value() != 1) throw new AssertionError();
            executions.incrementAndGet();
        }
    }

    public static final class VintageFixture {
        private static final AtomicInteger executions = new AtomicInteger();

        @org.junit.Test
        public void passes() {
            if (ProductionFixture.value() != 1) throw new AssertionError();
            executions.incrementAndGet();
        }
    }

    public static final class ReflectiveFixture {
        private static final AtomicInteger executions = new AtomicInteger();

        @org.junit.jupiter.api.Test
        void passes() throws Exception {
            Class<?> type = Class.forName(
                "com.aspix2k.affected.collector.smoke.ProductionFixture",
                false,
                Thread.currentThread().getContextClassLoader()
            );
            Object value = type.getDeclaredMethod("value").invoke(null);
            if (!Integer.valueOf(1).equals(value)) throw new AssertionError();
            executions.incrementAndGet();
        }
    }

    public static final class ServiceLoaderFixture {
        private static final AtomicInteger executions = new AtomicInteger();

        @org.junit.jupiter.api.Test
        void passes() {
            ProductionService service = ServiceLoader.load(ProductionService.class)
                .iterator()
                .next();
            if (service.value() != 2) throw new AssertionError();
            executions.incrementAndGet();
        }
    }
}
