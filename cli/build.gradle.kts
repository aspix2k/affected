plugins {
    kotlin("jvm")
    application
}

base { archivesName = "affected-cli" }

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
    implementation(project(":engine"))
    implementation(kotlin("stdlib"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    runtimeOnly("com.google.code.gson:gson:2.13.2")
    runtimeOnly("net.java.dev.jna:jna:5.17.0")
    runtimeOnly("net.java.dev.jna:jna-platform:5.17.0")

    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}

kotlin { jvmToolchain(21) }

application {
    mainClass = "com.aspix2k.affected.cli.MainKt"
    applicationName = "affected"
}

distributions {
    main {
        contents {
            val helpers = project(":core").layout.projectDirectory
            into("agent") {
                from(helpers.file("src/main/python/affected_pytest.py")) { rename { "affected-pytest.py" } }
                from(helpers.file("src/main/python/affected_unittest.py")) { rename { "affected-unittest.py" } }
                from(helpers.file("src/main/php/affected_phpunit.php")) { rename { "affected-phpunit.php" } }
            }
            into("agent/dotnet/Affected.DotnetAnalyzer") {
                from(helpers.dir("src/main/dotnet/Affected.DotnetAnalyzer"))
            }
        }
    }
}

tasks.test {
    useJUnit()
    testLogging { events("passed", "failed", "skipped") }
}
