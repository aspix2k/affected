plugins {
    kotlin("jvm")
    id("info.solidsoft.pitest")
}

base { archivesName = "affected-engine" }

repositories {
    val mavenCentralMirror = "https://cache-redirector.jetbrains.com/repo1.maven.org/maven2"
    if (System.getenv("AFFECTED_PREFER_MAVEN_CENTRAL") == "1") {
        mavenCentral()
        maven(mavenCentralMirror)
    } else {
        maven(mavenCentralMirror)
        mavenCentral()
    }
}

dependencies {
    compileOnly(kotlin("stdlib"))
    compileOnly("com.google.code.gson:gson:2.13.2")
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    compileOnly("net.java.dev.jna:jna:5.17.0")
    compileOnly("net.java.dev.jna:jna-platform:5.17.0")

    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    testRuntimeOnly("com.google.code.gson:gson:2.13.2")
    testRuntimeOnly("net.java.dev.jna:jna:5.17.0")
    testRuntimeOnly("net.java.dev.jna:jna-platform:5.17.0")
}

kotlin { jvmToolchain(21) }

tasks.test {
    useJUnit()
    testLogging { events("passed", "failed", "skipped") }
}

pitest {
    targetClasses.set(
        listOf(
            "com.aspix2k.affected.TestRootResolver*",
            "com.aspix2k.affected.build.ExecutablePathKt*",
            "com.aspix2k.affected.impact.*",
        ),
    )
    targetTests.set(
        listOf(
            "com.aspix2k.affected.TestRootResolverTest*",
            "com.aspix2k.affected.build.ExecutablePathTest*",
            "com.aspix2k.affected.impact.*Test*",
        ),
    )
    mutators.set(listOf("STRONGER"))
    outputFormats.set(listOf("XML", "HTML"))
    threads.set(4)
    timestampedReports.set(false)
    timeoutConstInMillis.set(8_000)
}
