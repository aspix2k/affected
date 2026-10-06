import info.solidsoft.gradle.pitest.PitestTask
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

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
    api(project(":engine"))

    intellijPlatform {
        intellijIdea(providers.gradleProperty("affected.idea.version").get())
        bundledPlugin("com.intellij.gradle")
        bundledPlugin("org.jetbrains.idea.maven")
        testFramework(TestFrameworkType.Platform)
    }
    add("intellijPlatformTestDependencies", enforcedPlatform("com.fasterxml.jackson:jackson-bom:2.22.3"))

    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}

kotlin { jvmToolchain(21) }

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.freeCompilerArgs.add(
        project(":engine").tasks.named<Jar>("jar").flatMap { it.archiveFile }
            .map { "-Xfriend-paths=${it.asFile.absolutePath}" },
    )
}

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
    val realRepositories = providers.gradleProperty("affected.realRepositories").orElse("false").get()
    systemProperty("affected.realRepositories", realRepositories)
    systemProperty(
        "affected.realRepositories.skipMissingTools",
        providers.gradleProperty("affected.realRepositories.skipMissingTools").orElse("false").get(),
    )
    if (realRepositories == "true") outputs.upToDateWhen { false }
    System.getProperty("affected.phpunitVersion")?.let { systemProperty("affected.phpunitVersion", it) }
    doFirst {
        environment("KOTLIN_CLI_JAVA_HOME", javaLauncher.get().metadata.installationPath.asFile.absolutePath)
    }
    testLogging { events("passed", "failed", "skipped") }
}

pitest {
    targetClasses.set(listOf("com.aspix2k.affected.AffectedMcpInputs*"))
    targetTests.set(
        listOf(
            "com.aspix2k.affected.AffectedMcpInputsTest*",
            "com.aspix2k.affected.AffectedMcpViewsTest*",
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
