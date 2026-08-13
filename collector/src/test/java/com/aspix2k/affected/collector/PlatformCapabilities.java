package com.aspix2k.affected.collector;

import org.junit.AssumptionViolatedException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class PlatformCapabilities {
    private PlatformCapabilities() {
    }

    static void createSymbolicLink(Path link, Path target) {
        String mode = System.getProperty("affected.test.symlinkMode", "optional");
        if (!"required".equals(mode) && !"optional".equals(mode)) {
            throw new AssertionError("unknown affected.test.symlinkMode: " + mode);
        }
        try {
            Files.createSymbolicLink(link, target);
            System.out.println("[Affected conformance] symlink=available");
        } catch (UnsupportedOperationException | IOException | SecurityException failure) {
            System.out.println("[Affected conformance] symlink=unavailable");
            if ("required".equals(mode)) {
                throw new AssertionError("symbolic links are required by this conformance job", failure);
            }
            throw new AssumptionViolatedException("symbolic links are unavailable on this runner", failure);
        }
    }
}
