package com.aspix2k.affected

import com.aspix2k.affected.build.GradleBuildSystem
import com.aspix2k.affected.build.MavenBuildSystem
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChangeAnalyzerTest {

    private fun repo(block: (File) -> Unit) {
        val dir = createTempDirectory("affected-test").toFile()
        run(dir, "git", "init", "-q", "-b", "main")
        run(dir, "git", "config", "user.email", "test@example.com")
        run(dir, "git", "config", "user.name", "test")
        File(dir, "settings.gradle.kts").writeText("rootProject.name = \"probe\"")
        File(dir, "lib/src/main/kotlin").mkdirs()
        File(dir, "lib/build.gradle.kts").writeText("")
        File(dir, "lib/src/main/kotlin/Sample.kt").writeText(
            """
            package probe

            class Sample {
                fun visible(): Int {
                    val internalValue = 1
                    return internalValue
                }
            }
            """.trimIndent()
        )
        run(dir, "git", "add", "-A")
        run(dir, "git", "commit", "-qm", "init")
        block(dir)
    }

    private fun run(dir: File, vararg args: String) {
        ProcessBuilder(*args).directory(dir).redirectErrorStream(true).start().waitFor()
    }

    private fun analyze(dir: File) = ChangeAnalyzer(dir, "main").collect()

    private fun analyze(dir: File, extensions: Set<String>) = ChangeAnalyzer(dir, "main", extensions).collect()

    @Test
    fun `the list is empty without changes`() = repo { dir ->
        assertTrue(analyze(dir).files.isEmpty(), "a clean tree must not have changes")
    }

    @Test
    fun `an edit inside a body does not affect the public API`() = repo { dir ->
        val file = File(dir, "lib/src/main/kotlin/Sample.kt")
        file.writeText(file.readText().replace("val internalValue = 1", "val internalValue = 2"))

        val changes = analyze(dir)
        assertEquals(1, changes.files.size, "the file must be listed as changed")
        assertTrue(changes.apiTouched.isEmpty(), "a body edit does not change the API")
    }

    @Test
    fun `a new public function changes the API`() = repo { dir ->
        val file = File(dir, "lib/src/main/kotlin/Sample.kt")
        file.appendText("\nfun added(): Int = 5\n")

        assertEquals(1, analyze(dir).apiTouched.size, "a new public declaration changes the API")
    }

    @Test
    fun `a private function does not change the API`() = repo { dir ->
        val file = File(dir, "lib/src/main/kotlin/Sample.kt")
        file.appendText("\nprivate fun hidden(): Int = 5\n")

        assertTrue(analyze(dir).apiTouched.isEmpty(), "a private declaration is not externally visible")
    }

    @Test
    fun `a signature change changes the API`() = repo { dir ->
        val file = File(dir, "lib/src/main/kotlin/Sample.kt")
        file.writeText(file.readText().replace("fun visible(): Int", "fun visible(flag: Boolean): Int"))

        assertEquals(1, analyze(dir).apiTouched.size, "a signature change breaks consumers")
    }

    @Test
    fun `test sources do not change the API`() = repo { dir ->
        File(dir, "lib/src/test/kotlin").mkdirs()
        File(dir, "lib/src/test/kotlin/SampleTest.kt").writeText("class SampleTest { fun check() {} }")

        val changes = analyze(dir)
        assertTrue(changes.files.isNotEmpty(), "a test file is still listed as changed")
        assertTrue(changes.apiTouched.isEmpty(), "tests are not part of the module artifact")
    }

    @Test
    fun `an XML resource does not change the API`() = repo { dir ->
        File(dir, "lib/src/main/res/values").mkdirs()
        File(dir, "lib/src/main/res/values/colors.xml")
            .writeText("<resources><color name=\"c\">#fff</color></resources>")

        val changes = analyze(dir)
        assertTrue(changes.files.isNotEmpty(), "the resource is listed as changed")
        assertTrue(changes.apiTouched.isEmpty(), "an icon color does not break consumers")
    }

    @Test
    fun `a new file with a public declaration changes the API`() = repo { dir ->
        File(dir, "lib/src/main/kotlin/Added.kt").writeText("package probe\n\nclass Added\n")

        assertEquals(1, analyze(dir).apiTouched.size, "a new public class extends the API")
    }

    @Test
    fun `a new file with only private content does not change the API`() = repo { dir ->
        File(dir, "lib/src/main/kotlin/Hidden.kt").writeText("package probe\n\nprivate fun x() = 1\n")

        assertTrue(analyze(dir).apiTouched.isEmpty(), "private content is not externally visible")
    }

    @Test
    fun `unrelated files are ignored`() = repo { dir ->
        File(dir, "README.md").writeText("# doc")
        File(dir, "notes.txt").writeText("hello")

        assertTrue(analyze(dir).files.isEmpty(), "documentation does not affect the build")
    }

    @Test
    fun `all-file collection still ignores project documentation`() = repo { dir ->
        File(dir, "README.md").writeText("# doc")
        File(dir, "LICENSE").writeText("license")
        File(dir, "docs/CHANGELOG.md").apply {
            parentFile.mkdirs()
            writeText("## Unreleased")
        }

        val changes = ChangeAnalyzer(dir, "main", includeAllFiles = true).collect().files

        assertTrue(changes.none { it.name.equals("README.md", ignoreCase = true) }, changes.toString())
        assertTrue(changes.none { it.name.equals("LICENSE", ignoreCase = true) }, changes.toString())
        assertTrue(changes.none { it.name.equals("CHANGELOG.md", ignoreCase = true) }, changes.toString())
    }

    @Test
    fun `windows separators still drop project documentation from all-file collection`() {
        assertTrue(isProjectDocumentation("C:\\repo\\README.md"))
        assertTrue(isProjectDocumentation("C:\\repo\\docs\\CHANGELOG.md"))
        assertFalse(
            isCollectedSource(
                "C:\\repo\\README.md",
                includeAllFiles = true,
                extensions = setOf("kt"),
                names = emptySet(),
            ),
        )
        assertTrue(
            isCollectedSource(
                "C:\\repo\\NOTICE",
                includeAllFiles = true,
                extensions = emptySet(),
                names = emptySet(),
            ),
        )
    }

    @Test
    fun `complete change collection includes extensionless and resource files`() = repo { dir ->
        val extensionless = File(dir, "lib/src/main/resources/NOTICE").apply {
            parentFile.mkdirs()
            writeText("notice")
        }
        val resource = File(dir, "lib/src/main/resources/schema.graphql").apply { writeText("type Query") }

        val changes = ChangeAnalyzer(dir, "main", includeAllFiles = true).collect().files

        assertEquals(setOf(extensionless, resource), changes.toSet())
    }

    @Test
    fun `a deleted public source remains affected`() = repo { dir ->
        val deleted = File(dir, "lib/src/main/kotlin/Sample.kt")
        deleted.delete()

        val changes = analyze(dir)
        assertTrue(deleted in changes.files, "module ownership still depends on the deleted path")
        assertTrue(deleted in changes.apiTouched, "removing a public declaration affects consumers")
    }

    @Test
    fun `a deleted private-only source does not affect consumers`() = repo { dir ->
        val deleted = File(dir, "lib/src/main/kotlin/Hidden.kt")
        deleted.writeText("package probe\n\nprivate fun hidden() = 1\n")
        run(dir, "git", "add", deleted.relativeTo(dir).path)
        run(dir, "git", "commit", "-qm", "add private source")
        deleted.delete()

        val changes = analyze(dir)
        assertTrue(deleted in changes.files, "the owning module remains affected")
        assertFalse(deleted in changes.apiTouched, "private declarations have no consumers")
    }

    @Test
    fun `a renamed source affects its old and new owners`() = repo { dir ->
        val old = File(dir, "lib/src/main/kotlin/Sample.kt")
        val renamed = File(dir, "other/src/main/kotlin/Renamed.kt")
        renamed.parentFile.mkdirs()
        run(dir, "git", "mv", old.relativeTo(dir).path, renamed.relativeTo(dir).path)

        val changes = analyze(dir)
        assertTrue(old in changes.files, "the old module must remain affected")
        assertTrue(renamed in changes.files, "the new module must be affected")
        assertTrue(old in changes.apiTouched, "removing public declarations affects old consumers")
        assertTrue(renamed in changes.apiTouched, "adding public declarations affects new consumers")
    }

    @Test
    fun `a branch commit remains visible`() = repo { dir ->
        run(dir, "git", "checkout", "-qb", "feature")
        File(dir, "lib/src/main/kotlin/Sample.kt").appendText("\nfun afterCommit(): Int = 7\n")
        run(dir, "git", "add", "-A")
        run(dir, "git", "commit", "-qm", "work")

        val changes = analyze(dir)
        assertTrue(changes.files.isNotEmpty(), "committed branch work still requires tests")
        assertEquals(1, changes.apiTouched.size, "its API change remains visible too")
    }

    @Test
    fun `a missing base branch does not crash analysis`() = repo { dir ->
        File(dir, "lib/src/main/kotlin/Sample.kt").appendText("\nfun another(): Int = 1\n")

        val changes = ChangeAnalyzer(dir, "no-such-branch").collect()
        assertTrue(changes.files.isNotEmpty(), "the working tree is read without a base branch")
    }

    @Test
    fun `androidTest sources do not change the API`() = repo { dir ->
        File(dir, "lib/src/androidTest/kotlin").mkdirs()
        File(dir, "lib/src/androidTest/kotlin/UiTest.kt").writeText("class UiTest { fun check() {} }")

        val changes = analyze(dir)
        assertTrue(changes.files.isNotEmpty())
        assertTrue(changes.apiTouched.isEmpty(), "instrumented tests are not part of the module artifact")
    }

    @Test
    fun `Gradle and Maven adapters keep Scala and Groovy sources`() = repo { dir ->
        val scala = File(dir, "lib/src/main/scala/probe/Alpha.scala").apply {
            parentFile.mkdirs()
            writeText("package probe\n\nclass Alpha")
        }
        val groovy = File(dir, "lib/src/main/groovy/probe/Beta.groovy").apply {
            parentFile.mkdirs()
            writeText("package probe\n\nclass Beta {}")
        }
        File(dir, "notes.txt").writeText("ignored")

        val gradle = analyze(dir, GradleBuildSystem().sourceExtensions)
        assertTrue(scala in gradle.files, "a Scala production file belongs to the Gradle module")
        assertTrue(groovy in gradle.files, "a Groovy production file belongs to the Gradle module")
        assertTrue(gradle.files.none { it.name == "notes.txt" })
        assertEquals(setOf(scala, groovy), gradle.apiTouched, "a public Scala or Groovy type breaks consumers")

        val maven = analyze(dir, MavenBuildSystem().sourceExtensions)
        assertTrue(scala in maven.files, "a Scala production file belongs to the Maven module")
        assertTrue(groovy in maven.files, "a Groovy production file belongs to the Maven module")
        assertEquals(setOf(scala, groovy), maven.apiTouched)
    }

    @Test
    fun `a private Scala member does not change the API`() = repo { dir ->
        val file = File(dir, "lib/src/main/scala/probe/Hidden.scala")
        file.parentFile.mkdirs()
        file.writeText("package probe\n\nclass Hidden {\n  def visible: Int = 1\n}\n")
        run(dir, "git", "add", "-A")
        run(dir, "git", "commit", "-qm", "scala")
        file.writeText("package probe\n\nclass Hidden {\n  def visible: Int = 1\n  private def hidden: Int = 2\n}\n")

        val changes = analyze(dir, GradleBuildSystem().sourceExtensions)
        assertTrue(file in changes.files)
        assertTrue(changes.apiTouched.isEmpty(), "a private Scala member is not externally visible")
    }

    @Test
    fun `a Java file participates in API analysis`() = repo { dir ->
        File(dir, "lib/src/main/java/probe").mkdirs()
        File(dir, "lib/src/main/java/probe/Legacy.java").writeText(
            "package probe;\n\npublic class Legacy {\n    public int value() { return 1; }\n}\n"
        )

        assertEquals(1, analyze(dir).apiTouched.size, "a public Java class also extends the API")
    }

    @Test
    fun `editing a Java method body does not change the API`() = repo { dir ->
        val file = File(dir, "lib/src/main/java/probe/Legacy.java")
        file.parentFile.mkdirs()
        file.writeText("package probe;\n\npublic class Legacy {\n    public int value() { return 1; }\n}\n")
        run(dir, "git", "add", "-A")
        run(dir, "git", "commit", "-qm", "legacy")

        file.writeText("package probe;\n\npublic class Legacy {\n    public int value() { return 2; }\n}\n")

        assertTrue(analyze(dir).apiTouched.isEmpty(), "only the method body changed")
    }

    @Test
    fun `a KTS file is changed but excluded from API analysis`() = repo { dir ->
        File(dir, "lib/build.gradle.kts").writeText("// changed\n")

        val changes = analyze(dir)
        assertTrue(changes.files.any { it.name == "build.gradle.kts" })
        assertTrue(changes.apiTouched.isEmpty(), "a build script is not the module public API")
    }

    @Test
    fun `non JVM production changes are classified by their owning build system`() = repo { dir ->
        val extensions = setOf("rs", "go", "ts", "cs", "py", "php", "cpp", "h")
        extensions.forEach { extension ->
            File(dir, "other/src/main/code/sample.$extension").apply {
                parentFile.mkdirs()
                writeText("changed\n")
            }
        }

        val changes = analyze(dir, extensions)

        assertTrue(changes.apiTouched.isEmpty())
        assertTrue(changes.files.all { affectsConsumers("NODE", it.path, signatureTouched = false) })
    }

    @Test
    fun `non JVM test files do not affect consumers`() = repo { dir ->
        val files = listOf(
            "rust/tests/sample.rs",
            "go/pkg/sample_test.go",
            "node/src/sample.test.ts",
            "python/src/test_sample.py",
            "ruby/spec/sample_spec.rb",
        )
        files.forEach { path ->
            File(dir, path).apply {
                parentFile.mkdirs()
                writeText("changed\n")
            }
        }

        val changes = analyze(dir, setOf("rs", "go", "ts", "py", "rb"))

        assertEquals(files.size, changes.files.size)
        assertTrue(changes.apiTouched.isEmpty())
        val systems = mapOf(
            "rust" to "CARGO",
            "go" to "GO",
            "node" to "NODE",
            "python" to "PYTHON",
            "ruby" to "RUBY",
        )
        assertTrue(changes.files.none { file ->
            val system = systems.getValue(file.relativeTo(dir).invariantSeparatorsPath.substringBefore('/'))
            affectsConsumers(system, file.path, signatureTouched = false)
        })
        assertTrue(
            affectsConsumers("GO", "/repo/test/production.go", signatureTouched = false),
            "a conventional test directory from another ecosystem must not hide Go production code",
        )
    }

    @Test
    fun `a Gradle JSON resource does not affect consumers`() {
        assertFalse(affectsConsumers("GRADLE", "/repo/app/src/main/assets/sample.json", signatureTouched = false))
    }

    @Test
    fun `an extensionless manifest can be tracked without matching every extensionless file`() = repo { dir ->
        File(dir, "Gemfile").writeText("source 'https://rubygems.org'\n")
        File(dir, "README").writeText("not a build input\n")

        val changes = ChangeAnalyzer(dir, "main", setOf("rb"), setOf("Gemfile")).collect()

        assertEquals(listOf("Gemfile"), changes.files.map { it.name })
    }

    @Test
    fun `the fast path returns the same files as the full path`() = repo { dir ->
        File(dir, "lib/src/main/kotlin/Sample.kt").appendText("\nfun quick(): Int = 1\n")

        val quick = ChangeAnalyzer(dir, "main").collectPaths()
        val full = analyze(dir).files
        assertEquals(full.map { it.path }.sorted(), quick.map { it.path }.sorted())
    }

    @Test
    fun `the fast path does not fail outside a repository`() {
        val dir = createTempDirectory("affected-quick").toFile()
        assertTrue(ChangeAnalyzer(dir, "main").collectPaths().isEmpty())
    }

    @Test
    fun `the fast path filters unrelated extensions`() = repo { dir ->
        File(dir, "notes.txt").writeText("x")
        File(dir, "lib/src/main/kotlin/Sample.kt").appendText("\nfun q(): Int = 1\n")

        val paths = ChangeAnalyzer(dir, "main").collectPaths()
        assertTrue(paths.none { it.name == "notes.txt" })
        assertTrue(paths.any { it.name == "Sample.kt" })
    }

    @Test
    fun `the base is detected when the configured branch is absent`() = repo { dir ->
        run(dir, "git", "checkout", "-qb", "feature")
        File(dir, "lib/src/main/kotlin/Sample.kt").appendText("\nfun auto(): Int = 1\n")
        run(dir, "git", "add", "-A")
        run(dir, "git", "commit", "-qm", "work")

        val changes = ChangeAnalyzer(dir, "no-such-branch").collect()
        assertTrue(changes.files.isNotEmpty(), "main must be found as a fallback base")
        assertEquals(1, changes.apiTouched.size)
    }

    @Test
    fun `the configured branch takes priority over fallbacks`() = repo { dir ->
        run(dir, "git", "checkout", "-qb", "release")
        File(dir, "lib/src/main/kotlin/Sample.kt").appendText("\nfun onRelease(): Int = 1\n")
        run(dir, "git", "add", "-A")
        run(dir, "git", "commit", "-qm", "release work")
        run(dir, "git", "checkout", "-qb", "feature")
        File(dir, "lib/src/main/kotlin/Other.kt").writeText("package probe\n\nclass Other\n")
        run(dir, "git", "add", "-A")
        run(dir, "git", "commit", "-qm", "feature work")

        val fromRelease = ChangeAnalyzer(dir, "release").collect().files.map { it.name }
        assertTrue(fromRelease.contains("Other.kt"), "only branch work is visible relative to release")
        assertFalse(fromRelease.contains("Sample.kt"), "work already in release is not tested again")
    }

    @Test
    fun `a test path is recognized with any OS separator`() = repo { dir ->
        File(dir, "lib/src/test/kotlin").mkdirs()
        File(dir, "lib/src/test/kotlin/PlatformTest.kt").writeText("class PlatformTest { fun check() {} }")

        val changes = analyze(dir)
        val relative = changes.files.single().relativeTo(dir).invariantSeparatorsPath
        assertTrue(relative.contains("/src/test"), "separators are normalized to forward slashes")
        assertTrue(changes.apiTouched.isEmpty(), "a test source does not change the API on any OS")
    }

    @Test
    fun `a non Git directory does not crash analysis`() {
        val dir = createTempDirectory("affected-nogit").toFile()
        val changes = ChangeAnalyzer(dir, "main").collect()
        assertTrue(changes.files.isEmpty(), "the analyzer stays silent outside a repository")
        assertFalse(dir.resolve(".git").exists())
    }
}
