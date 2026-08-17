import info.solidsoft.gradle.pitest.PitestTask
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    kotlin("jvm")
    id("org.jetbrains.intellij.platform.module")
    id("info.solidsoft.pitest")
}

repositories {
    val mavenCentralMirror = "https://cache-redirector.jetbrains.com/repo1.maven.org/maven2"
    if (System.getenv("AFFECTED_PREFER_MAVEN_CENTRAL") == "1") {
        mavenCentral()
        maven(mavenCentralMirror)
    } else {
        maven(mavenCentralMirror)
        mavenCentral()
    }
    intellijPlatform { defaultRepositories() }
}

dependencies {
    implementation("org.tomlj:tomlj:1.1.1")

    intellijPlatform {
        intellijIdea(providers.gradleProperty("affected.idea.version").get())
        bundledPlugin("com.intellij.gradle")
        bundledPlugin("org.jetbrains.idea.maven")
        testFramework(TestFrameworkType.Platform)
    }
    add("intellijPlatformTestDependencies", enforcedPlatform("com.fasterxml.jackson:jackson-bom:2.22.2"))

    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}

kotlin { jvmToolchain(21) }

tasks.test {
    useJUnit()
    systemProperty(
        "affected.test.repositoryRoot",
        rootProject.layout.projectDirectory.asFile.absolutePath,
    )
    systemProperty(
        "affected.test.pytestAdapter",
        layout.projectDirectory.file("src/main/python/affected_pytest.py").asFile.absolutePath,
    )
    systemProperty(
        "affected.test.unittestAdapter",
        layout.projectDirectory.file("src/main/python/affected_unittest.py").asFile.absolutePath,
    )
    systemProperty(
        "affected.test.dotnetAnalyzer",
        layout.projectDirectory.dir("src/main/dotnet/Affected.DotnetAnalyzer").asFile.absolutePath,
    )
    systemProperty(
        "affected.test.phpunitAdapter",
        layout.projectDirectory.file("src/main/php/affected_phpunit.php").asFile.absolutePath,
    )
    systemProperty(
        "affected.test.gradleFailureStrategy",
        project(":collector").layout.projectDirectory
            .file("src/main/gradle/affected-failure-strategy.init.gradle").asFile.absolutePath,
    )
    systemProperty(
        "affected.cliConformance",
        providers.gradleProperty("affected.cliConformance").orElse("false").get(),
    )
    System.getProperty("affected.phpunitVersion")?.let { systemProperty("affected.phpunitVersion", it) }
    testLogging { events("passed", "failed", "skipped") }
}

pitest {
    targetClasses.set(
        listOf(
            "com.aspix2k.affected.TestRootResolver*",
            "com.aspix2k.affected.AffectedMcpInputs*",
            "com.aspix2k.affected.build.ExecutablePathKt*",
        ),
    )
    targetTests.set(
        listOf(
            "com.aspix2k.affected.TestRootResolverTest*",
            "com.aspix2k.affected.AffectedMcpInputsTest*",
            "com.aspix2k.affected.AffectedMcpViewsTest*",
            "com.aspix2k.affected.build.ExecutablePathTest*",
        ),
    )
    mutators.set(listOf("STRONGER"))
    outputFormats.set(listOf("XML", "HTML"))
    threads.set(4)
    timestampedReports.set(false)
    timeoutConstInMillis.set(8_000)
}

tasks.named<PitestTask>("pitest") {
    additionalClasspath.from(configurations.named("intellijPlatformTestClasspath"))
}
