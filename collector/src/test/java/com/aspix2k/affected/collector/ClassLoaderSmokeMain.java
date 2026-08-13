package com.aspix2k.affected.collector;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;

public final class ClassLoaderSmokeMain {
    private ClassLoaderSmokeMain() {
    }

    public static void main(String[] arguments) throws Exception {
        String classpath = System.getProperty("affected.smoke.childClasspath");
        if (classpath == null || classpath.trim().isEmpty()) throw new IllegalStateException("child classpath");
        List<URL> urls = new ArrayList<URL>();
        for (String entry : classpath.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            urls.add(new File(entry).toURI().toURL());
        }
        try (URLClassLoader child = new URLClassLoader(urls.toArray(new URL[urls.size()]))) {
            Thread.currentThread().setContextClassLoader(child);
            Class<?> launcher = Class.forName("com.aspix2k.affected.collector.smoke.ChildLauncher", true, child);
            launcher.getMethod("run").invoke(null);
        }
    }
}
