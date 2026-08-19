import com.github.spotbugs.snom.Confidence
import com.github.spotbugs.snom.Effort

plugins {
    `java-library`
    id("com.github.spotbugs") version "6.5.10"
}

val testJavaVersion = providers.gradleProperty("affected.test.javaVersion").orElse("21")
val runGradleEightTests = providers.gradleProperty("affected.test.gradle8").orElse("true")
val symlinkMode = providers.gradleProperty("affected.test.symlinkMode").orElse("optional")
val conformance = providers.gradleProperty("affected.conformance").map(String::toBoolean).orElse(false)
val junitVersion = "5.14.4"
val junitPlatformVersion = "1.14.4"
val mavenLatestVersion = "3.9.16"
val maven4Version = "4.0.0-rc-6"
val smoke = sourceSets.create("smoke")
val smokeProduction = sourceSets.create("smokeProduction")
val maven = sourceSets.create("maven")
val maven390Distribution = configurations.create("maven390Distribution") {
    isCanBeConsumed = false
    isCanBeResolved = true
}
val mavenLatestDistribution = configurations.create("mavenLatestDistribution") {
    isCanBeConsumed = false
    isCanBeResolved = true
}
val maven4Distribution = configurations.create("maven4Distribution") {
    isCanBeConsumed = false
    isCanBeResolved = true
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
    maven("https://cache-redirector.jetbrains.com/intellij-dependencies")
}

dependencies {
    implementation("org.jetbrains.intellij.deps:asm-all:9.10.1")
    compileOnly("org.junit.platform:junit-platform-launcher:$junitPlatformVersion")
    compileOnly("org.testng:testng:7.5.1")

    add(maven.compileOnlyConfigurationName, "org.apache.maven:maven-core:$mavenLatestVersion")
    add(maven.compileOnlyConfigurationName, "org.apache.maven:maven-model:$mavenLatestVersion")
    add(maven.compileOnlyConfigurationName, "org.codehaus.plexus:plexus-utils:3.6.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.testng:testng:7.5.1")
    testImplementation(gradleTestKit())
    testImplementation("org.apache.maven:maven-core:$mavenLatestVersion")
    testImplementation("org.apache.maven:maven-model:$mavenLatestVersion")
    testImplementation("org.codehaus.plexus:plexus-utils:3.6.1")
    testCompileOnly("org.junit.jupiter:junit-jupiter-api:$junitVersion")
    testImplementation("org.junit.platform:junit-platform-engine:$junitPlatformVersion")
    testImplementation("org.junit.platform:junit-platform-launcher:$junitPlatformVersion")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:$junitVersion")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:$junitVersion")

    add(smoke.compileOnlyConfigurationName, "junit:junit:4.13.2")
    add(smoke.compileOnlyConfigurationName, "org.junit.jupiter:junit-jupiter-api:$junitVersion")
    add(smoke.implementationConfigurationName, "org.junit.platform:junit-platform-engine:$junitPlatformVersion")
    add(smoke.implementationConfigurationName, "org.junit.platform:junit-platform-launcher:$junitPlatformVersion")
    add(smoke.runtimeOnlyConfigurationName, "org.junit.jupiter:junit-jupiter-engine:$junitVersion")
    add(smoke.runtimeOnlyConfigurationName, "org.junit.vintage:junit-vintage-engine:$junitVersion")
    add(maven390Distribution.name, "org.apache.maven:apache-maven:3.9.0:bin@zip")
    add(mavenLatestDistribution.name, "org.apache.maven:apache-maven:$mavenLatestVersion:bin@zip")
    add(maven4Distribution.name, "org.apache.maven:apache-maven:$maven4Version:bin@zip")
}

smoke.compileClasspath += smokeProduction.output
smoke.runtimeClasspath += smokeProduction.output

sourceSets.test {
    compileClasspath += maven.output
    runtimeClasspath += maven.output
}

maven.compileClasspath += sourceSets.main.get().output

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(testJavaVersion.get().toInt()))
}

spotbugs {
    toolVersion = "4.10.3"
    ignoreFailures = false
    effort = Effort.MAX
    reportLevel = Confidence.DEFAULT
    runOnCheck = false
}

tasks.named("check") {
    dependsOn("spotbugsMain", "spotbugsMaven")
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(8)
}

tasks.jar {
    archiveFileName.set("affected-collector-agent.jar")
    include("com/aspix2k/affected/collector/AffectedCollectorAgent*.class")
    include("com/aspix2k/affected/collector/AffectedClassInstrumenter*.class")
    include("com/aspix2k/affected/collector/AffectedJUnit4Bridge*.class")
    include("com/aspix2k/affected/collector/AffectedDependencySelector*.class")
    include("com/aspix2k/affected/collector/CollectorOutput*.class")
    include("META-INF/LICENSE-ASM.txt")
    from(configurations.runtimeClasspath.map { classpath -> classpath.map(::zipTree) }) {
        include("org/jetbrains/org/objectweb/asm/**")
    }
    manifest {
        attributes("Premain-Class" to "com.aspix2k.affected.collector.AffectedCollectorAgent")
    }
}

val listenerJar = tasks.register<Jar>("listenerJar") {
    archiveFileName.set("affected-collector-listener.jar")
    from(sourceSets.main.get().output)
    include("com/aspix2k/affected/collector/AffectedTestExecutionListener*.class")
    include("com/aspix2k/affected/collector/AffectedTestNgListener*.class")
    include("META-INF/services/org.junit.platform.launcher.TestExecutionListener")
}

val mavenExtensionJar = tasks.register<Jar>("mavenExtensionJar") {
    archiveFileName.set("affected-maven-extension.jar")
    from(maven.output)
    from(sourceSets.main.get().output) {
        include("com/aspix2k/affected/collector/AffectedMavenConfig*.class")
        include("com/aspix2k/affected/collector/AffectedDependencySelector*.class")
    }
}

val mavenAgentJar = tasks.register<Jar>("mavenAgentJar") {
    archiveFileName.set("affected-maven-agent.jar")
    from(sourceSets.main.get().output)
    include("com/aspix2k/affected/collector/AffectedCollectorAgent*.class")
    include("com/aspix2k/affected/collector/AffectedClassInstrumenter*.class")
    include("com/aspix2k/affected/collector/AffectedJUnit4Bridge*.class")
    include("com/aspix2k/affected/collector/AffectedDependencySelector*.class")
    include("com/aspix2k/affected/collector/AffectedMavenFilter*.class")
    include("com/aspix2k/affected/collector/AffectedMavenConfig*.class")
    include("com/aspix2k/affected/collector/AffectedTestExecutionListener*.class")
    include("com/aspix2k/affected/collector/CollectorOutput*.class")
    include("META-INF/services/org.junit.platform.launcher.PostDiscoveryFilter")
    include("META-INF/services/org.junit.platform.launcher.TestExecutionListener")
    include("META-INF/LICENSE-ASM.txt")
    from(configurations.runtimeClasspath.map { classpath -> classpath.map(::zipTree) }) {
        include("org/jetbrains/org/objectweb/asm/**")
    }
    manifest {
        attributes("Premain-Class" to "com.aspix2k.affected.collector.AffectedCollectorAgent")
    }
}

val extractMaven = tasks.register<Sync>("extractMaven") {
    from(maven390Distribution.map(::zipTree))
    from(mavenLatestDistribution.map(::zipTree))
    from(maven4Distribution.map(::zipTree))
    into(layout.buildDirectory.dir("maven"))
}

tasks.test {
    val agentJar = tasks.jar.flatMap { it.archiveFile }
    val listenerArchive = listenerJar.flatMap { it.archiveFile }
    val initScript = layout.projectDirectory.file("src/main/gradle/affected-collector.init.gradle")
    val failureStrategyScript = layout.projectDirectory.file("src/main/gradle/affected-failure-strategy.init.gradle")
    val kmpFallbackFixture = rootProject.layout.projectDirectory.dir("conformance/cli-fixtures/gradle-kmp-fallback")
    val mavenExtensionArchive = mavenExtensionJar.flatMap { it.archiveFile }
    val mavenAgentArchive = mavenAgentJar.flatMap { it.archiveFile }
    dependsOn(
        tasks.jar,
        listenerJar,
        mavenExtensionJar,
        mavenAgentJar,
        extractMaven,
        tasks.named(smoke.classesTaskName),
        tasks.named(smokeProduction.classesTaskName),
    )
    inputs.files(
        agentJar,
        listenerArchive,
        mavenExtensionArchive,
        mavenAgentArchive,
        initScript,
        failureStrategyScript,
        kmpFallbackFixture,
        smoke.runtimeClasspath,
        smokeProduction.output,
    )
    useJUnit()
    isScanForTestClasses = false
    include("**/*Test.class")
    systemProperty("affected.smoke.agent", agentJar.get().asFile.absolutePath)
    systemProperty("affected.smoke.childClasspath", files(smoke.runtimeClasspath, listenerArchive).asPath)
    systemProperty("affected.smoke.codeSources", smokeProduction.output.classesDirs.asPath)
    systemProperty("affected.smoke.instrumentationSources", smoke.output.classesDirs.asPath)
    systemProperty("affected.smoke.testClasses", sourceSets.test.get().output.classesDirs.asPath)
    systemProperty("affected.test.initScript", initScript.asFile.absolutePath)
    systemProperty("affected.test.failureStrategyScript", failureStrategyScript.asFile.absolutePath)
    systemProperty("affected.test.repositoryRoot", rootProject.layout.projectDirectory.asFile.absolutePath)
    systemProperty("affected.test.listener", listenerArchive.get().asFile.absolutePath)
    systemProperty("affected.test.mavenExtension", mavenExtensionArchive.get().asFile.absolutePath)
    systemProperty("affected.test.mavenAgent", mavenAgentArchive.get().asFile.absolutePath)
    systemProperty(
        "affected.test.mavenLocalRepo",
        layout.buildDirectory.dir("maven-local-repo").get().asFile.absolutePath,
    )
    systemProperty("affected.test.gradle8", runGradleEightTests.get())
    systemProperty("affected.test.symlinkMode", symlinkMode.get())
    if (conformance.get()) {
        val report = layout.buildDirectory.file("reports/exact-impact-conformance/selector.properties")
        systemProperty("affected.test.conformanceReport", report.get().asFile.absolutePath)
        outputs.file(report)
        outputs.upToDateWhen { false }
    }
    systemProperty(
        "affected.test.mavenHomes",
        listOf("3.9.0", mavenLatestVersion).joinToString(File.pathSeparator) {
            layout.buildDirectory.dir("maven/apache-maven-$it").get().asFile.absolutePath
        },
    )
    systemProperty(
        "affected.test.unsupportedMavenHome",
        layout.buildDirectory.dir("maven/apache-maven-$maven4Version").get().asFile.absolutePath,
    )
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = conformance.get()
    }
}

val listenerElements = configurations.create("listenerElements") {
    isCanBeConsumed = true
    isCanBeResolved = false
}

val mavenExtensionElements = configurations.create("mavenExtensionElements") {
    isCanBeConsumed = true
    isCanBeResolved = false
}

val mavenAgentElements = configurations.create("mavenAgentElements") {
    isCanBeConsumed = true
    isCanBeResolved = false
}

artifacts {
    add(listenerElements.name, listenerJar)
    add(mavenExtensionElements.name, mavenExtensionJar)
    add(mavenAgentElements.name, mavenAgentJar)
}
