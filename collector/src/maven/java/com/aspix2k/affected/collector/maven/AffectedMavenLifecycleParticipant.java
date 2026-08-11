package com.aspix2k.affected.collector.maven;

import com.aspix2k.affected.collector.AffectedMavenConfig;
import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;

public final class AffectedMavenLifecycleParticipant extends AbstractMavenLifecycleParticipant {
    private static final String SUREFIRE_KEY = "org.apache.maven.plugins:maven-surefire-plugin";
    private static final String MAVEN_AGENT = "affected.collector.mavenAgent";
    private static final String OUTPUT = "affected.collector.output";
    private static final String MAPS = "affected.collector.maps";
    private static final String VERSION = "affected.collector.version";
    private static final String DEBUG = "maven.surefire.debug";

    @Override
    public void afterProjectsRead(MavenSession session) {
        Package runtimePackage = MavenSession.class.getPackage();
        if (runtimePackage == null || !supportedRuntime(runtimePackage.getImplementationVersion())) return;
        Properties properties = new Properties();
        properties.putAll(session.getSystemProperties());
        properties.putAll(session.getUserProperties());
        try {
            Preparation preparation = prepare(session.getProjects(), properties, session.getUserProperties());
            if (preparation.kind == Kind.INJECTED) {
                session.getUserProperties().setProperty(DEBUG, preparation.debugArgument);
            }
        } catch (Exception ignored) {
        }
    }

    public static boolean supportedRuntime(String version) {
        return version != null && version.startsWith("3.9.");
    }

    public static Preparation prepare(List<MavenProject> projects, Properties properties) throws Exception {
        return prepare(projects, properties, properties);
    }

    private static Preparation prepare(
        List<MavenProject> projects,
        Properties properties,
        Properties runtimeProperties
    ) throws Exception {
        String existingDebug = properties.getProperty(DEBUG);
        if ("true".equalsIgnoreCase(trim(existingDebug))) return Preparation.unchanged();
        Path agent = regularFile(properties, MAVEN_AGENT);
        Path output = directory(properties, OUTPUT);
        Path maps = directory(properties, MAPS);
        String version = required(properties, VERSION);
        List<AffectedMavenConfig.ProjectConfig> records = new ArrayList<AffectedMavenConfig.ProjectConfig>();
        for (MavenProject project : projects) {
            AffectedMavenConfig.ProjectConfig record = project(
                project,
                properties,
                runtimeProperties,
                output,
                maps,
                version
            );
            if (record != null) records.add(record);
        }
        if (records.isEmpty()) return Preparation.unchanged();
        Path manifest = output.resolve("maven-projects.manifest");
        AffectedMavenConfig.write(manifest, records);
        String agentArgument = quote("-javaagent:" + agent + "=" + manifest.toRealPath(LinkOption.NOFOLLOW_LINKS));
        String debug = trim(existingDebug);
        return new Preparation(
            Kind.INJECTED,
            manifest.toRealPath(LinkOption.NOFOLLOW_LINKS),
            debug.isEmpty() ? agentArgument : debug + " " + agentArgument
        );
    }

    private static AffectedMavenConfig.ProjectConfig project(
        MavenProject project,
        Properties properties,
        Properties runtimeProperties,
        Path output,
        Path maps,
        String version
    ) throws Exception {
        Plugin plugin = project.getPlugin(SUREFIRE_KEY);
        if (plugin == null || plugin.getVersion() == null || !plugin.getVersion().startsWith("3.")) return null;
        List<Xpp3Dom> configurations = configurations(plugin);
        if (!singleReusableFork(configurations, properties)) return null;
        Path basedir = project.getBasedir().toPath().toRealPath();
        String codeSources = buildPath(project.getBuild().getOutputDirectory());
        String testClasses = buildPath(project.getBuild().getTestOutputDirectory());
        List<String> elements = project.getTestClasspathElements();
        if (elements.isEmpty()) return null;
        List<String> classpath = new ArrayList<String>();
        for (String element : elements) classpath.add(buildPath(element));
        return new AffectedMavenConfig.ProjectConfig(
            basedir.toString(),
            output.toString(),
            maps.toString(),
            version,
            basedir.toUri() + "|test",
            runtime(project, plugin, configurations, runtimeProperties),
            allTests(properties),
            codeSources,
            testClasses,
            join(classpath)
        );
    }

    private static boolean singleReusableFork(List<Xpp3Dom> configurations, Properties properties) {
        for (Xpp3Dom configuration : configurations) {
            String forkCount = effective(configuration, properties, "forkCount", "1");
            String reuseForks = effective(configuration, properties, "reuseForks", "true");
            if (!("1".equals(forkCount) && "true".equalsIgnoreCase(reuseForks))) return false;
        }
        return true;
    }

    private static boolean allTests(Properties properties) {
        String[] propertyNames = {
            "test", "groups", "excludedGroups", "surefire.includes", "surefire.excludes",
            "surefire.includesFile", "surefire.excludesFile", "surefire.suiteXmlFiles", "dependenciesToScan",
            "surefire.includeJUnit5Engines", "surefire.excludeJUnit5Engines"
        };
        for (String name : propertyNames) {
            if (hasText(properties.getProperty(name))) return false;
        }
        return true;
    }

    private static String runtime(
        MavenProject project,
        Plugin plugin,
        List<Xpp3Dom> configurations,
        Properties properties
    ) throws Exception {
        List<String> configurationValues = new ArrayList<String>();
        for (Xpp3Dom configuration : configurations) configurationValues.add(configuration.toString());
        Collections.sort(configurationValues);
        StringBuilder configurationValue = new StringBuilder(plugin.getVersion()).append('\n');
        for (String value : configurationValues) configurationValue.append(value).append('\n');
        StringBuilder propertyValue = new StringBuilder();
        appendProperties(propertyValue, "project", project.getProperties(), false);
        appendProperties(propertyValue, "user", properties, true);
        return sha256(configurationValue.toString() + propertyValue);
    }

    private static void appendProperties(
        StringBuilder target,
        String kind,
        Properties properties,
        boolean excludeCollector
    ) {
        List<String> propertyNames = new ArrayList<String>();
        for (Map.Entry<Object, Object> entry : properties.entrySet()) {
            String name = String.valueOf(entry.getKey());
            if (!excludeCollector
                || (!name.startsWith("affected.collector.")
                    && !DEBUG.equals(name)
                    && !"maven.ext.class.path".equals(name))) {
                propertyNames.add(name);
            }
        }
        Collections.sort(propertyNames);
        for (String name : propertyNames) {
            target.append(kind).append(':').append(name).append('=')
                .append(properties.getProperty(name, "")).append('\n');
        }
    }

    private static List<Xpp3Dom> configurations(Plugin plugin) {
        List<Xpp3Dom> configurations = new ArrayList<Xpp3Dom>();
        Object value = plugin.getConfiguration();
        configurations.add(value instanceof Xpp3Dom ? (Xpp3Dom) value : new Xpp3Dom("configuration"));
        for (PluginExecution execution : plugin.getExecutions()) {
            Object executionValue = execution.getConfiguration();
            if (executionValue instanceof Xpp3Dom) configurations.add((Xpp3Dom) executionValue);
        }
        return configurations;
    }

    private static String effective(
        Xpp3Dom configuration,
        Properties properties,
        String name,
        String defaultValue
    ) {
        String property = properties.getProperty(name);
        if (hasText(property)) return property.trim();
        Xpp3Dom child = configuration.getChild(name);
        return child != null && hasText(child.getValue()) ? child.getValue().trim() : defaultValue;
    }

    private static String buildPath(String value) {
        if (!hasText(value)) throw new IllegalStateException("build path");
        return Paths.get(value).toAbsolutePath().normalize().toString();
    }

    private static String join(List<String> values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            if (result.length() > 0) result.append(File.pathSeparatorChar);
            result.append(value);
        }
        return result.toString();
    }

    private static String quote(String value) {
        if (value.indexOf('"') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IllegalStateException("agent argument");
        }
        return '"' + value + '"';
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static String sha256(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder(digest.length * 2);
        for (byte item : digest) {
            int unsigned = item & 0xff;
            if (unsigned < 16) result.append('0');
            result.append(Integer.toHexString(unsigned));
        }
        return result.toString();
    }

    private static Path regularFile(Properties properties, String name) throws Exception {
        Path path = Paths.get(required(properties, name)).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
            || !Files.isReadable(path)
            || Files.isSymbolicLink(path)) {
            throw new IllegalStateException(name);
        }
        return path.toRealPath(LinkOption.NOFOLLOW_LINKS);
    }

    private static Path directory(Properties properties, String name) throws Exception {
        Path path = Paths.get(required(properties, name)).toAbsolutePath().normalize();
        if (Files.isSymbolicLink(path)) throw new IllegalStateException(name);
        Path real = path.toRealPath(LinkOption.NOFOLLOW_LINKS);
        if (!Files.isDirectory(real) || !Files.isReadable(real) || !Files.isWritable(real)) {
            throw new IllegalStateException(name);
        }
        return real;
    }

    private static String required(Properties properties, String name) {
        String value = properties.getProperty(name);
        if (!hasText(value)) throw new IllegalStateException(name);
        return value;
    }

    public enum Kind {
        INJECTED,
        UNCHANGED
    }

    public static final class Preparation {
        private final Kind kind;
        private final Path manifest;
        private final String debugArgument;

        private Preparation(Kind kind, Path manifest, String debugArgument) {
            this.kind = kind;
            this.manifest = manifest;
            this.debugArgument = debugArgument;
        }

        private static Preparation unchanged() {
            return new Preparation(Kind.UNCHANGED, null, null);
        }

        public Kind getKind() {
            return kind;
        }

        public Path getManifest() {
            return manifest;
        }

        public String getDebugArgument() {
            return debugArgument;
        }
    }
}
