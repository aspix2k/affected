import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    kotlin("jvm")
    id("org.jetbrains.intellij.platform.module")
}

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        intellijIdea(providers.gradleProperty("affected.idea.version").get())
        bundledPlugin("com.intellij.gradle")
        bundledPlugin("org.jetbrains.idea.maven")
        testFramework(TestFrameworkType.Platform)
    }

    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}

kotlin { jvmToolchain(21) }

tasks.test {
    useJUnit()
    systemProperty(
        "affected.test.pytestAdapter",
        layout.projectDirectory.file("src/main/python/affected_pytest.py").asFile.absolutePath,
    )
    systemProperty(
        "affected.cliConformance",
        providers.gradleProperty("affected.cliConformance").orElse("false").get(),
    )
    testLogging { events("passed", "failed", "skipped") }
}
