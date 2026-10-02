package com.aspix2k.affected.collector.maven;

import com.aspix2k.affected.collector.AffectedMavenConfig;
import com.aspix2k.affected.collector.AffectedDependencySelector;
import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeSet;
import java.util.stream.Stream;

public final class AffectedMavenLifecycleParticipant extends AbstractMavenLifecycleParticipant {
    private static final String MAVEN_AGENT = "affected.collector.mavenAgent";
    private static final String OUTPUT = "affected.collector.output";
    private static final String MAPS = "affected.collector.maps";
    private static final String VERSION = "affected.collector.version";
    private static final String WORKER = "affected.collector.worker";
    private static final String ADDITIONAL_CLASSPATH_PROPERTY = "maven.test.additionalClasspath";
    private static final String[] PATH_PARAMETERS = {
        "suiteXmlFiles", "additionalClasspathElements", "includesFile", "excludesFile",
        "systemPropertiesFile", "classesDirectory", "testClassesDirectory"
    };
    private static final int MAX_REFERENCED_FILES = 200_000;
    private static final int BUFFER_SIZE = 64 * 1024;
    private List<AffectedMavenConfig.ProjectConfig> diagnostics = Collections.emptyList();

    @Override
    public void afterProjectsRead(MavenSession session) {
        Package runtimePackage = MavenSession.class.getPackage();
        if (runtimePackage == null || !supportedRuntime(runtimePackage.getImplementationVersion())) {
            reportProjects(session.getProjects(), AffectedDependencySelector.Reason.UNSUPPORTED_RUNTIME);
            return;
        }
        Properties properties = new Properties();
        properties.putAll(session.getSystemProperties());
        properties.putAll(session.getUserProperties());
        try {
            Preparation preparation = prepare(
                session.getProjects(),
                properties,
                session.getUserProperties(),
                failsafeBaselineEligible(session.getGoals())
            );
            diagnostics = preparation.projects;
            for (String display : preparation.fallbacks) {
                report(display, AffectedDependencySelector.Decision.full(
                    AffectedDependencySelector.Reason.UNSUPPORTED_CONFIGURATION
                ).describe());
            }
            if (preparation.kind == Kind.INJECTED) {
                session.getUserProperties().setProperty(WORKER, "${surefire.forkNumber}");
                for (Map.Entry<String, String> argument : preparation.debugArguments.entrySet()) {
                    session.getUserProperties().setProperty(argument.getKey(), argument.getValue());
                }
            }
        } catch (Exception ignored) {
            diagnostics = Collections.emptyList();
            reportProjects(session.getProjects(), AffectedDependencySelector.Reason.COLLECTOR_ERROR);
        }
    }

    @Override
    public void afterSessionEnd(MavenSession session) {
        for (AffectedMavenConfig.ProjectConfig project : diagnostics) {
            report(project.getDisplay(), decision(project));
        }
        diagnostics = Collections.emptyList();
    }

    public static boolean supportedRuntime(String version) {
        return MavenRuntime.exactEligible(version);
    }

    public static boolean failsafeBaselineEligible(List<String> goals) {
        if (goals == null) return false;
        for (String goal : goals) {
            if ("verify".equals(goal) || "install".equals(goal) || "deploy".equals(goal)) return true;
        }
        return false;
    }

    public static Preparation prepare(List<MavenProject> projects, Properties properties) throws Exception {
        return prepare(projects, properties, properties, true);
    }

    private static Preparation prepare(
        List<MavenProject> projects,
        Properties properties,
        Properties runtimeProperties,
        boolean failsafeBaselineEligible
    ) throws Exception {
        Path agent = regularFile(properties, MAVEN_AGENT);
        Path output = directory(properties, OUTPUT);
        Path maps = directory(properties, MAPS);
        String version = required(properties, VERSION);
        List<AffectedMavenConfig.ProjectConfig> records = new ArrayList<AffectedMavenConfig.ProjectConfig>();
        List<String> fallbacks = new ArrayList<String>();
        Map<String, Path> manifests = new LinkedHashMap<String, Path>();
        Map<String, String> debugArguments = new LinkedHashMap<String, String>();
        for (Adapter adapter : Adapter.values()) {
            String existingDebug = properties.getProperty(adapter.debugProperty);
            List<AffectedMavenConfig.ProjectConfig> adapterRecords = new ArrayList<AffectedMavenConfig.ProjectConfig>();
            for (MavenProject project : projects) {
                if ("pom".equals(project.getPackaging()) || !adapter.applies(project)) continue;
                if ("true".equalsIgnoreCase(trim(existingDebug))) {
                    fallbacks.add(display(project, adapter.task));
                    continue;
                }
                AffectedMavenConfig.ProjectConfig record = project(
                    project,
                    adapter,
                    properties,
                    runtimeProperties,
                    output,
                    maps,
                    version,
                    adapter != Adapter.FAILSAFE || failsafeBaselineEligible
                );
                if (record == null) fallbacks.add(display(project, adapter.task));
                else adapterRecords.add(record);
            }
            if (adapterRecords.isEmpty()) continue;
            Path manifest = output.resolve("maven-" + adapter.task + "-projects.manifest");
            AffectedMavenConfig.write(manifest, adapterRecords);
            Path realManifest = manifest.toRealPath(LinkOption.NOFOLLOW_LINKS);
            String agentArgument = quote("-javaagent:" + agent + "=" + realManifest);
            String debug = trim(existingDebug);
            manifests.put(adapter.debugProperty, realManifest);
            debugArguments.put(
                adapter.debugProperty,
                debug.isEmpty() ? agentArgument : debug + " " + agentArgument
            );
            records.addAll(adapterRecords);
        }
        if (records.isEmpty()) return Preparation.unchanged(fallbacks);
        return new Preparation(
            Kind.INJECTED,
            manifests,
            debugArguments,
            records,
            fallbacks
        );
    }

    private static AffectedMavenConfig.ProjectConfig project(
        MavenProject project,
        Adapter adapter,
        Properties properties,
        Properties runtimeProperties,
        Path output,
        Path maps,
        String version,
        boolean baselineEligible
    ) throws Exception {
        Plugin plugin = project.getPlugin(adapter.pluginKey);
        if (plugin == null
            || plugin.getVersion() == null
            || !plugin.getVersion().startsWith("3.")
            || !adapter.supports(plugin)) {
            return null;
        }
        List<Xpp3Dom> configurations = configurations(plugin);
        ForkTopology topology = forkTopology(plugin, properties);
        if (topology == null) return null;
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
            basedir.toUri() + "|" + adapter.task,
            display(project, adapter.task),
            runtime(project, plugin, configurations, runtimeProperties, adapter, basedir),
            allTests(properties, adapter),
            baselineEligible,
            topology.reuseForks,
            codeSources,
            testClasses,
            join(classpath)
        );
    }

    private static ForkTopology forkTopology(Plugin plugin, Properties properties) {
        Xpp3Dom configuration = dom(plugin.getConfiguration());
        String forkCount = effective(configuration, properties, "forkCount", "1");
        String reuseForks = effective(configuration, properties, "reuseForks", "true");
        if (!supportedForkCount(forkCount)
            || !("true".equalsIgnoreCase(reuseForks) || "false".equalsIgnoreCase(reuseForks))) {
            return null;
        }
        for (PluginExecution execution : plugin.getExecutions()) {
            Xpp3Dom executionConfiguration = dom(execution.getConfiguration());
            if (!forkCount.equals(effective(executionConfiguration, properties, "forkCount", forkCount))
                || !reuseForks.equalsIgnoreCase(
                    effective(executionConfiguration, properties, "reuseForks", reuseForks)
                )) {
                return null;
            }
        }
        return new ForkTopology(Boolean.parseBoolean(reuseForks));
    }

    private static boolean supportedForkCount(String value) {
        if (!value.matches("[1-9][0-9]{0,2}")) return false;
        return Integer.parseInt(value) <= 256;
    }

    private static Xpp3Dom dom(Object value) {
        return value instanceof Xpp3Dom ? (Xpp3Dom) value : new Xpp3Dom("configuration");
    }

    private static boolean allTests(Properties properties, Adapter adapter) {
        for (String name : adapter.selectionProperties) {
            if (hasText(properties.getProperty(name))) return false;
        }
        return true;
    }

    private static String runtime(
        MavenProject project,
        Plugin plugin,
        List<Xpp3Dom> configurations,
        Properties properties,
        Adapter adapter,
        Path basedir
    ) throws Exception {
        List<String> configurationValues = new ArrayList<String>();
        for (Xpp3Dom configuration : configurations) configurationValues.add(configuration.toString());
        Collections.sort(configurationValues);
        StringBuilder configurationValue = new StringBuilder(adapter.task).append('\n')
            .append(plugin.getVersion()).append('\n')
            .append("debug=").append(trim(properties.getProperty(adapter.debugProperty))).append('\n');
        for (String value : configurationValues) configurationValue.append(value).append('\n');
        StringBuilder propertyValue = new StringBuilder();
        appendProperties(propertyValue, "project", project.getProperties(), false);
        appendProperties(propertyValue, "user", properties, true);
        String referenced = referencedFiles(configurations, properties, basedir);
        return sha256(configurationValue.toString() + propertyValue + referenced);
    }

    private static String referencedFiles(
        List<Xpp3Dom> configurations,
        Properties properties,
        Path basedir
    ) throws Exception {
        TreeSet<String> references = new TreeSet<String>();
        for (Xpp3Dom configuration : configurations) {
            for (String name : PATH_PARAMETERS) {
                Xpp3Dom child = configuration.getChild(name);
                if (child != null) references.addAll(pathValues(child));
            }
        }
        String additional = properties.getProperty(ADDITIONAL_CLASSPATH_PROPERTY);
        if (hasText(additional)) references.addAll(split(additional));
        StringBuilder result = new StringBuilder();
        for (String reference : references) {
            if (reference.contains("${")) throw new IllegalStateException(reference);
            Path path = basedir.resolve(reference).normalize();
            result.append("path:").append(path.toUri()).append('=').append(pathHash(path)).append('\n');
        }
        return result.toString();
    }

    private static List<String> pathValues(Xpp3Dom parameter) {
        List<String> values = new ArrayList<String>();
        if (parameter.getChildCount() == 0) return split(parameter.getValue());
        for (Xpp3Dom item : parameter.getChildren()) values.addAll(split(item.getValue()));
        return values;
    }

    private static List<String> split(String value) {
        List<String> values = new ArrayList<String>();
        if (value == null) return values;
        for (String item : value.split(",")) {
            if (hasText(item)) values.add(item.trim());
        }
        return values;
    }

    private static String pathHash(Path path) throws Exception {
        if (Files.isSymbolicLink(path)) throw new IllegalStateException(path.toString());
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            update(digest, "file");
            update(digest, fileHash(path));
        } else if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            update(digest, "directory");
            hashTree(digest, path);
        } else if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            update(digest, "missing");
        } else {
            throw new IllegalStateException(path.toString());
        }
        return hex(digest.digest());
    }

    private static void hashTree(MessageDigest digest, Path root) throws Exception {
        List<Path> files = new ArrayList<Path>();
        int count = 0;
        try (Stream<Path> entries = Files.walk(root)) {
            for (Path entry : (Iterable<Path>) entries::iterator) {
                if (++count > MAX_REFERENCED_FILES || Files.isSymbolicLink(entry)) {
                    throw new IllegalStateException(entry.toString());
                }
                if (Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) files.add(entry);
            }
        }
        Collections.sort(files);
        for (Path file : files) {
            update(digest, root.relativize(file).toString().replace(File.separatorChar, '/'));
            update(digest, fileHash(file));
        }
    }

    private static byte[] fileHash(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) digest.update(buffer, 0, read);
            }
        }
        return digest.digest();
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        update(digest, bytes);
    }

    private static void update(MessageDigest digest, byte[] value) {
        digest.update(Integer.toString(value.length).getBytes(StandardCharsets.UTF_8));
        digest.update((byte) ':');
        digest.update(value);
    }

    private static String hex(byte[] digest) {
        StringBuilder result = new StringBuilder(digest.length * 2);
        for (byte item : digest) {
            int unsigned = item & 0xff;
            if (unsigned < 16) result.append('0');
            result.append(Integer.toHexString(unsigned));
        }
        return result.toString();
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
                    && !Adapter.isDebugProperty(name)
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

    private static List<String> displays(List<MavenProject> projects) {
        List<String> result = new ArrayList<String>();
        for (Adapter adapter : Adapter.values()) {
            for (MavenProject project : projects) {
                if (!"pom".equals(project.getPackaging()) && adapter.applies(project)) {
                    result.add(display(project, adapter.task));
                }
            }
        }
        return result;
    }

    private static String display(MavenProject project, String task) {
        String group = project.getGroupId();
        String artifact = project.getArtifactId();
        return group == null || artifact == null ? "maven:" + task : group + ":" + artifact + ":" + task;
    }

    private static void reportProjects(
        List<MavenProject> projects,
        AffectedDependencySelector.Reason reason
    ) {
        String decision = AffectedDependencySelector.Decision.full(reason).describe();
        for (String display : displays(projects)) report(display, decision);
    }

    private static void report(String display, String decision) {
        try {
            String safeDisplay = display.matches("[A-Za-z0-9_.:-]{1,200}") ? display : "maven:test";
            if (System.out != null) System.out.println("[Affected] " + safeDisplay + " - " + decision);
        } catch (RuntimeException ignored) {
        }
    }

    private static String decision(AffectedMavenConfig.ProjectConfig project) {
        try {
            Path output = Paths.get(project.getOutput()).toAbsolutePath().normalize();
            if (Files.isSymbolicLink(output) || !Files.isDirectory(output, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("output");
            }
            Path root = output.toRealPath(LinkOption.NOFOLLOW_LINKS);
            Path task = root.resolve("task-" + sha256(project.getTask()));
            if (Files.isSymbolicLink(task) || !Files.isDirectory(task, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("task output");
            }
            Path realTask = task.toRealPath(LinkOption.NOFOLLOW_LINKS);
            if (!root.equals(realTask.getParent())) throw new IllegalStateException("task output");
            Path manifest = realTask.resolve("decision.manifest");
            if (Files.isSymbolicLink(manifest)
                || !Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)
                || !Files.isReadable(manifest)
                || Files.size(manifest) > 1024L) {
                throw new IllegalStateException("decision manifest");
            }
            String content = new String(Files.readAllBytes(manifest), StandardCharsets.UTF_8);
            String prefix = "format=1\ndecision=";
            if (!content.startsWith(prefix) || !content.endsWith("\n") || content.indexOf('\r') >= 0) {
                throw new IllegalStateException("decision manifest");
            }
            String encoded = content.substring(prefix.length(), content.length() - 1);
            byte[] bytes = Base64.getUrlDecoder().decode(encoded);
            if (!Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(encoded)) {
                throw new IllegalStateException("decision manifest");
            }
            String value = new String(bytes, StandardCharsets.UTF_8);
            if (!validDecision(value)) throw new IllegalStateException("decision manifest");
            return value;
        } catch (Exception ignored) {
            return AffectedDependencySelector.Decision.full(
                AffectedDependencySelector.Reason.COLLECTOR_ERROR
            ).describe();
        }
    }

    private static boolean validDecision(String value) {
        if ("proven-empty".equals(value)
            || "exact (1 test class)".equals(value)
            || value.matches("exact \\((?:[2-9]|[1-9][0-9]{1,5}) test classes\\)")) {
            return true;
        }
        for (AffectedDependencySelector.Reason reason : AffectedDependencySelector.Reason.values()) {
            if (reason != AffectedDependencySelector.Reason.NONE
                && AffectedDependencySelector.Decision.full(reason).describe().equals(value)) {
                return true;
            }
        }
        return false;
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
        return hex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
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

    private static final class ForkTopology {
        private final boolean reuseForks;

        private ForkTopology(boolean reuseForks) {
            this.reuseForks = reuseForks;
        }
    }

    private enum Adapter {
        SUREFIRE(
            "org.apache.maven.plugins:maven-surefire-plugin",
            "maven.surefire.debug",
            "test",
            new String[] {
                "test", "groups", "excludedGroups", "surefire.includes", "surefire.excludes",
                "surefire.includesFile", "surefire.excludesFile", "surefire.suiteXmlFiles", "dependenciesToScan",
                "surefire.includeJUnit5Engines", "surefire.excludeJUnit5Engines"
            }
        ),
        FAILSAFE(
            "org.apache.maven.plugins:maven-failsafe-plugin",
            "maven.failsafe.debug",
            "integration-test",
            new String[] {
                "it.test", "test", "groups", "excludedGroups", "failsafe.includes", "failsafe.excludes",
                "failsafe.includesFile", "failsafe.excludesFile", "failsafe.suiteXmlFiles", "dependenciesToScan",
                "failsafe.includeJUnit5Engines", "failsafe.excludeJUnit5Engines"
            }
        );

        private final String pluginKey;
        private final String debugProperty;
        private final String task;
        private final String[] selectionProperties;

        Adapter(String pluginKey, String debugProperty, String task, String[] selectionProperties) {
            this.pluginKey = pluginKey;
            this.debugProperty = debugProperty;
            this.task = task;
            this.selectionProperties = selectionProperties;
        }

        private boolean applies(MavenProject project) {
            if (this == SUREFIRE) return true;
            Plugin plugin = project.getPlugin(pluginKey);
            if (plugin == null) return false;
            for (PluginExecution execution : plugin.getExecutions()) {
                if (execution.getGoals().contains(task)) return true;
            }
            return false;
        }

        private boolean supports(Plugin plugin) {
            int executions = 0;
            boolean defaultSurefireExecution = false;
            for (PluginExecution execution : plugin.getExecutions()) {
                if (execution.getGoals().contains(task)) {
                    executions++;
                    defaultSurefireExecution = "default-test".equals(execution.getId());
                }
            }
            if (this == SUREFIRE) return executions == 0 || executions == 1 && defaultSurefireExecution;
            return executions == 1;
        }

        private static boolean isDebugProperty(String name) {
            for (Adapter adapter : values()) {
                if (adapter.debugProperty.equals(name)) return true;
            }
            return false;
        }
    }

    public static final class Preparation {
        private final Kind kind;
        private final Map<String, Path> manifests;
        private final Map<String, String> debugArguments;
        private final List<AffectedMavenConfig.ProjectConfig> projects;
        private final List<String> fallbacks;

        private Preparation(
            Kind kind,
            Map<String, Path> manifests,
            Map<String, String> debugArguments,
            List<AffectedMavenConfig.ProjectConfig> projects,
            List<String> fallbacks
        ) {
            this.kind = kind;
            this.manifests = Collections.unmodifiableMap(new LinkedHashMap<String, Path>(manifests));
            this.debugArguments = Collections.unmodifiableMap(new LinkedHashMap<String, String>(debugArguments));
            this.projects = Collections.unmodifiableList(
                new ArrayList<AffectedMavenConfig.ProjectConfig>(projects)
            );
            this.fallbacks = Collections.unmodifiableList(new ArrayList<String>(fallbacks));
        }

        private static Preparation unchanged(List<String> fallbacks) {
            return new Preparation(
                Kind.UNCHANGED,
                Collections.<String, Path>emptyMap(),
                Collections.<String, String>emptyMap(),
                Collections.<AffectedMavenConfig.ProjectConfig>emptyList(),
                fallbacks
            );
        }

        public Kind getKind() {
            return kind;
        }

        public Path getManifest() {
            return manifests.get(Adapter.SUREFIRE.debugProperty);
        }

        public String getDebugArgument() {
            return debugArguments.get(Adapter.SUREFIRE.debugProperty);
        }

        public Map<String, Path> getManifests() {
            return manifests;
        }

        public Map<String, String> getDebugArguments() {
            return debugArguments;
        }

        public List<AffectedMavenConfig.ProjectConfig> getProjects() {
            return projects;
        }

        public List<String> getFallbacks() {
            return fallbacks;
        }
    }
}
