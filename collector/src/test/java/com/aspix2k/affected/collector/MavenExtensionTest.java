package com.aspix2k.affected.collector;

import com.aspix2k.affected.collector.maven.AffectedMavenLifecycleParticipant;
import org.apache.maven.model.Build;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Properties;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class MavenExtensionTest {
    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void preparesAnAgentManifestWithoutMutatingSurefireConfiguration() throws Exception {
        Path root = temporary.newFolder("project with spaces").toPath();
        Path agent = Files.write(root.resolve("agent.jar"), new byte[] {1});
        Path output = Files.createDirectory(root.resolve("output"));
        Path maps = Files.createDirectory(root.resolve("maps"));
        MavenProject project = project(root);
        String original = project.getPlugin("org.apache.maven.plugins:maven-surefire-plugin")
            .getConfiguration().toString();

        AffectedMavenLifecycleParticipant.Preparation preparation =
            AffectedMavenLifecycleParticipant.prepare(
                Collections.singletonList(project),
                properties(agent, output, maps)
            );

        assertEquals(AffectedMavenLifecycleParticipant.Kind.INJECTED, preparation.getKind());
        assertEquals(original, project.getPlugin("org.apache.maven.plugins:maven-surefire-plugin")
            .getConfiguration().toString());
        assertTrue(Files.isRegularFile(preparation.getManifest()));
        assertTrue(preparation.getDebugArgument().startsWith("\"-javaagent:"));
        assertTrue(preparation.getDebugArgument().endsWith("\""));
        assertEquals(1, preparation.getProjects().size());
        assertEquals(Collections.emptyList(), preparation.getFallbacks());
        AffectedMavenConfig.ProjectConfig config = AffectedMavenConfig.read(
            preparation.getManifest(),
            root
        );
        assertEquals(root.toRealPath().toUri() + "|test", config.getTask());
        assertEquals("collector-version", config.getVersion());
        assertTrue(Files.isSameFile(output, Paths.get(config.getOutput())));
        assertTrue(Files.isSameFile(maps, Paths.get(config.getMaps())));
        assertTrue(config.isAllTests());
        assertTrue(config.getRuntime().matches("[0-9a-f]{64}"));
        assertTrue(config.getCodeSources().contains(root.resolve("target/classes").toString()));
        assertTrue(config.getTestClasses().contains(root.resolve("target/test-classes").toString()));
    }

    @Test
    public void leavesForklessSurefireUnchanged() throws Exception {
        Path root = temporary.newFolder("forkless-project").toPath();
        Path agent = Files.write(root.resolve("agent.jar"), new byte[] {1});
        Path output = Files.createDirectory(root.resolve("output"));
        Path maps = Files.createDirectory(root.resolve("maps"));
        MavenProject project = project(root);
        Xpp3Dom configuration = (Xpp3Dom) project.getPlugin("org.apache.maven.plugins:maven-surefire-plugin")
            .getConfiguration();
        Xpp3Dom forkCount = new Xpp3Dom("forkCount");
        forkCount.setValue("0");
        configuration.addChild(forkCount);

        AffectedMavenLifecycleParticipant.Preparation preparation =
            AffectedMavenLifecycleParticipant.prepare(
                Collections.singletonList(project),
                properties(agent, output, maps)
            );

        assertEquals(AffectedMavenLifecycleParticipant.Kind.UNCHANGED, preparation.getKind());
        assertNull(preparation.getManifest());
        assertNull(preparation.getDebugArgument());
        assertEquals(Collections.singletonList("fixture:app:test"), preparation.getFallbacks());
    }

    @Test
    public void collectorRunPathsDoNotChangeRuntimeIdentity() throws Exception {
        Path root = temporary.newFolder("stable-runtime-project").toPath();
        Path agent = Files.write(root.resolve("agent.jar"), new byte[] {1});
        MavenProject project = project(root);
        Path firstOutput = Files.createDirectory(root.resolve("first-output"));
        Path firstMaps = Files.createDirectory(root.resolve("first-maps"));
        AffectedMavenLifecycleParticipant.Preparation first =
            AffectedMavenLifecycleParticipant.prepare(
                Collections.singletonList(project),
                properties(agent, firstOutput, firstMaps)
            );
        String firstRuntime = AffectedMavenConfig.read(first.getManifest(), root).getRuntime();
        Path secondOutput = Files.createDirectory(root.resolve("second-output"));
        Path secondMaps = Files.createDirectory(root.resolve("second-maps"));

        AffectedMavenLifecycleParticipant.Preparation second =
            AffectedMavenLifecycleParticipant.prepare(
                Collections.singletonList(project),
                properties(agent, secondOutput, secondMaps)
            );

        assertEquals(firstRuntime, AffectedMavenConfig.read(second.getManifest(), root).getRuntime());
    }

    @Test
    public void commandLineEngineSelectionCannotProduceAFullBaseline() throws Exception {
        Path root = temporary.newFolder("selected-engine-project").toPath();
        Path agent = Files.write(root.resolve("agent.jar"), new byte[] {1});
        Path output = Files.createDirectory(root.resolve("output"));
        Path maps = Files.createDirectory(root.resolve("maps"));
        Properties properties = properties(agent, output, maps);
        properties.setProperty("surefire.includeJUnit5Engines", "junit-jupiter");

        AffectedMavenLifecycleParticipant.Preparation preparation =
            AffectedMavenLifecycleParticipant.prepare(
                Collections.singletonList(project(root)),
                properties
            );

        assertTrue(!AffectedMavenConfig.read(preparation.getManifest(), root).isAllTests());
    }

    @Test
    public void configuredExcludesRemainAFullRuntimeFingerprint() throws Exception {
        Path root = temporary.newFolder("configured-excludes-project").toPath();
        Path agent = Files.write(root.resolve("agent.jar"), new byte[] {1});
        Path output = Files.createDirectory(root.resolve("output"));
        Path maps = Files.createDirectory(root.resolve("maps"));
        MavenProject project = project(root);
        Xpp3Dom configuration = (Xpp3Dom) project.getPlugin("org.apache.maven.plugins:maven-surefire-plugin")
            .getConfiguration();
        Xpp3Dom excludes = new Xpp3Dom("excludes");
        Xpp3Dom exclude = new Xpp3Dom("exclude");
        exclude.setValue("**/*PerformanceTest.java");
        excludes.addChild(exclude);
        configuration.addChild(excludes);

        AffectedMavenLifecycleParticipant.Preparation preparation =
            AffectedMavenLifecycleParticipant.prepare(
                Collections.singletonList(project),
                properties(agent, output, maps)
            );

        AffectedMavenConfig.ProjectConfig config = AffectedMavenConfig.read(preparation.getManifest(), root);
        assertTrue(config.isAllTests());
        assertTrue(config.getRuntime().matches("[0-9a-f]{64}"));
    }

    @Test
    public void effectiveProjectPropertyChangesRuntimeIdentity() throws Exception {
        Path root = temporary.newFolder("project-property-runtime").toPath();
        Path agent = Files.write(root.resolve("agent.jar"), new byte[] {1});
        Path output = Files.createDirectory(root.resolve("output"));
        Path maps = Files.createDirectory(root.resolve("maps"));
        MavenProject project = project(root);
        project.getProperties().setProperty("fixture.mode", "first");
        AffectedMavenLifecycleParticipant.Preparation first =
            AffectedMavenLifecycleParticipant.prepare(
                Collections.singletonList(project),
                properties(agent, output, maps)
            );
        String firstRuntime = AffectedMavenConfig.read(first.getManifest(), root).getRuntime();
        project.getProperties().setProperty("fixture.mode", "second");

        AffectedMavenLifecycleParticipant.Preparation second =
            AffectedMavenLifecycleParticipant.prepare(
                Collections.singletonList(project),
                properties(agent, output, maps)
            );

        assertTrue(!firstRuntime.equals(AffectedMavenConfig.read(second.getManifest(), root).getRuntime()));
    }

    @Test
    public void supportsOnlyMaven39Runtime() {
        assertTrue(AffectedMavenLifecycleParticipant.supportedRuntime("3.9.0"));
        assertTrue(AffectedMavenLifecycleParticipant.supportedRuntime("3.9.16"));
        assertTrue(!AffectedMavenLifecycleParticipant.supportedRuntime("3.8.9"));
        assertTrue(!AffectedMavenLifecycleParticipant.supportedRuntime("4.0.0-rc-5"));
        assertTrue(!AffectedMavenLifecycleParticipant.supportedRuntime(null));
    }

    private static MavenProject project(Path root) {
        Model model = new Model();
        model.setModelVersion("4.0.0");
        model.setGroupId("fixture");
        model.setArtifactId("app");
        model.setVersion("1");
        Build build = new Build();
        build.setDirectory(root.resolve("target").toString());
        build.setOutputDirectory(root.resolve("target/classes").toString());
        build.setTestOutputDirectory(root.resolve("target/test-classes").toString());
        Plugin plugin = new Plugin();
        plugin.setGroupId("org.apache.maven.plugins");
        plugin.setArtifactId("maven-surefire-plugin");
        plugin.setVersion("3.5.4");
        Xpp3Dom configuration = new Xpp3Dom("configuration");
        Xpp3Dom argLine = new Xpp3Dom("argLine");
        argLine.setValue("@{argLine}");
        configuration.addChild(argLine);
        plugin.setConfiguration(configuration);
        build.addPlugin(plugin);
        model.setBuild(build);
        MavenProject project = new MavenProject(model);
        project.setFile(root.resolve("pom.xml").toFile());
        return project;
    }

    private static Properties properties(Path agent, Path output, Path maps) {
        Properties properties = new Properties();
        properties.setProperty("affected.collector.mavenAgent", agent.toString());
        properties.setProperty("affected.collector.output", output.toString());
        properties.setProperty("affected.collector.maps", maps.toString());
        properties.setProperty("affected.collector.version", "collector-version");
        return properties;
    }
}
