plugins {
    java
}

val smoke = sourceSets.create("smoke")

repositories {
    mavenCentral()
}

dependencies {
    compileOnly("org.junit.platform:junit-platform-launcher:1.11.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.11.0")
    testImplementation("org.junit.platform:junit-platform-launcher:1.11.0")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.11.0")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.11.0")

    add(smoke.implementationConfigurationName, "junit:junit:4.13.2")
    add(smoke.implementationConfigurationName, "org.junit.jupiter:junit-jupiter-api:5.11.0")
    add(smoke.implementationConfigurationName, "org.junit.platform:junit-platform-launcher:1.11.0")
    add(smoke.runtimeOnlyConfigurationName, "org.junit.jupiter:junit-jupiter-engine:5.11.0")
    add(smoke.runtimeOnlyConfigurationName, "org.junit.vintage:junit-vintage-engine:5.11.0")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(8)
}

tasks.jar {
    archiveFileName.set("affected-collector-agent.jar")
    include("com/aspix2k/affected/collector/AffectedCollectorAgent*.class")
    manifest {
        attributes("Premain-Class" to "com.aspix2k.affected.collector.AffectedCollectorAgent")
    }
}

val listenerJar = tasks.register<Jar>("listenerJar") {
    archiveFileName.set("affected-collector-listener.jar")
    from(sourceSets.main.get().output)
    include("com/aspix2k/affected/collector/AffectedTestExecutionListener*.class")
    include("com/aspix2k/affected/collector/CollectorOutput*.class")
    include("META-INF/services/org.junit.platform.launcher.TestExecutionListener")
}

tasks.test {
    val agentJar = tasks.jar.flatMap { it.archiveFile }
    val listenerArchive = listenerJar.flatMap { it.archiveFile }
    dependsOn(tasks.jar, listenerJar, tasks.named(smoke.classesTaskName))
    inputs.files(agentJar, listenerArchive, smoke.runtimeClasspath)
    useJUnit()
    isScanForTestClasses = false
    include("**/*Test.class")
    systemProperty("affected.smoke.agent", agentJar.get().asFile.absolutePath)
    systemProperty("affected.smoke.childClasspath", files(smoke.runtimeClasspath, listenerArchive).asPath)
    systemProperty("affected.smoke.codeSources", smoke.output.classesDirs.asPath)
    systemProperty("affected.smoke.testClasses", sourceSets.test.get().output.classesDirs.asPath)
    testLogging { events("passed", "failed", "skipped") }
}

val listenerElements = configurations.create("listenerElements") {
    isCanBeConsumed = true
    isCanBeResolved = false
}

artifacts {
    add(listenerElements.name, listenerJar)
}
