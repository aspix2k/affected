package com.aspix2k.affected.collector.smoke;

import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.core.LauncherConfig;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

public final class ChildLauncher {
    private ChildLauncher() {
    }

    public static void run() {
        Launcher launcher = LauncherFactory.create(
            LauncherConfig.builder().enableTestExecutionListenerAutoRegistration(true).build()
        );
        launcher.execute(
            LauncherDiscoveryRequestBuilder.request()
                .selectors(selectClass(JupiterFixture.class), selectClass(VintageFixture.class))
                .build()
        );
        if (JupiterFixture.executions.get() != 1 || VintageFixture.executions.get() != 1) {
            throw new IllegalStateException("smoke tests did not execute");
        }
    }

    public static final class JupiterFixture {
        private static final AtomicInteger executions = new AtomicInteger();

        @org.junit.jupiter.api.Test
        void passes() {
            executions.incrementAndGet();
        }
    }

    public static final class VintageFixture {
        private static final AtomicInteger executions = new AtomicInteger();

        @org.junit.Test
        public void passes() {
            executions.incrementAndGet();
        }
    }
}
