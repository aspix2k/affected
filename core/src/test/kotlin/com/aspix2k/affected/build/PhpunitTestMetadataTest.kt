package com.aspix2k.affected.build

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PhpunitTestMetadataTest {

    @Test
    fun `parses nullable PHP runtime settings`() {
        val runtime = parsePhpunitRuntime(
            "8.4.24",
            "PHPUnit 13.3.0 by Sebastian Bergmann and contributors.",
            """{"extensions":[["Core","8.4.24"]],"configuration":["/etc/php.ini"],"settings":{""" +
                """"auto_prepend_file":null,"auto_append_file":null,"opcache.preload":""}}""",
        )

        assertNotNull(runtime)
        assertEquals("auto_append_file=null\nauto_prepend_file=null\nopcache.preload=\"\"", runtime.settings)
        assertEquals("", runtime.autoPrependFile)
    }

    @Test
    fun `reads a bounded Composer autoload and test inventory`() {
        val fixture = fixture()
        val state = readPhpunitProjectState(
            fixture.root,
            fixture.packageRoot,
            setOf(fixture.packageRoot, fixture.dependencyRoot),
            fixture.adapter,
            runtime(),
            mapOf("APP_ENV" to "test"),
        )

        assertNotNull(state)
        assertEquals(
            setOf("packages/alpha/src/Alpha.php", "packages/shared/src/Shared.php"),
            state.artifacts.keys,
        )
        assertTrue(state.identity.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun `identity changes with tests manifests runtime and environment`() {
        val fixture = fixture()
        fun state(environment: Map<String, String> = mapOf("APP_ENV" to "test")) = assertNotNull(
            readPhpunitProjectState(
                fixture.root,
                fixture.packageRoot,
                setOf(fixture.packageRoot, fixture.dependencyRoot),
                fixture.adapter,
                runtime(),
                environment,
            ),
        )
        val initial = state()

        fixture.packageRoot.resolve("tests/AlphaTest.php").writeText("<?php\nfinal class AlphaTest {}\n")
        assertTrue(initial.identity != state().identity)
        fixture.packageRoot.resolve("tests/AlphaTest.php").writeText(SAFE_PHPUNIT_TEST)
        fixture.packageRoot.resolve("tests/flag.txt").writeText("enabled\n")
        assertTrue(initial.identity != state().identity)
        fixture.packageRoot.resolve("tests/flag.txt").toFile().delete()
        fixture.root.resolve("schema.json").writeText("{}\n")
        assertTrue(initial.identity != state().identity)
        fixture.root.resolve("schema.json").toFile().delete()
        fixture.packageRoot.resolve("composer.json").writeText(composer("lib/"))
        fixture.packageRoot.resolve("lib/Alpha.php").also { it.parent.createDirectories() }
            .writeText("<?php\nnamespace Affected;\nfinal class Alpha {}\n")
        assertTrue(initial.identity != state().identity)
        fixture.packageRoot.resolve("composer.json").writeText(composer("src/"))
        assertTrue(initial.identity != assertNotNull(
            readPhpunitProjectState(
                fixture.root,
                fixture.packageRoot,
                setOf(fixture.packageRoot, fixture.dependencyRoot),
                fixture.adapter,
                runtime().copy(php = "8.5.10"),
                mapOf("APP_ENV" to "test"),
            ),
        ).identity)
        assertTrue(initial.identity != state(mapOf("APP_ENV" to "other")).identity)
    }

    @Test
    fun `rejects unsupported PHPUnit configuration dynamic includes generated code and symlinks`() {
        val configured = fixture()
        configured.root.resolve("phpunit.xml").writeText("<phpunit/>")
        assertNull(configured.state())

        val dynamic = fixture()
        dynamic.packageRoot.resolve("src/Alpha.php").writeText("<?php require \$path;\n")
        assertNull(dynamic.state())

        val externalIo = fixture()
        externalIo.packageRoot.resolve("src/Alpha.php").writeText("<?php file('/tmp/flag');\n")
        assertNull(externalIo.state())

        val externalStatic = fixture()
        externalStatic.packageRoot.resolve("src/Alpha.php")
            .writeText("<?php\nnamespace Affected;\n// class External\nExternal::value();\n")
        assertNull(externalStatic.state())

        val shell = fixture()
        shell.packageRoot.resolve("src/Alpha.php")
            .writeText("<?php\nnamespace Affected;\n`cat /tmp/flag`;\nfinal class Alpha {}\n")
        assertNull(shell.state())

        val callable = fixture()
        callable.packageRoot.resolve("src/Alpha.php").writeText(
            "<?php\nnamespace Affected;\n\$call = 'file';\n(\$call)('/tmp/flag');\nfinal class Alpha {}\n",
        )
        assertNull(callable.state())

        val fileAssertion = fixture()
        fileAssertion.packageRoot.resolve("tests/AlphaTest.php").writeText(
            SAFE_PHPUNIT_TEST.replace("self::assertSame(1, Alpha::value());", "self::assertFileExists('/tmp/flag');"),
        )
        assertNull(fileAssertion.state())

        val externalParent = fixture()
        externalParent.packageRoot.resolve("src/Alpha.php").writeText(
            "<?php\nnamespace Affected;\nclass Alpha EXTENDS \\Vendor\\External {}\nAlpha::value();\n",
        )
        assertNull(externalParent.state())

        val generated = fixture()
        generated.packageRoot.resolve("composer.json").writeText(composer("generated/"))
        generated.packageRoot.resolve("generated/Alpha.php").also { it.parent.createDirectories() }.writeText("<?php\n")
        assertNull(generated.state())

        val nestedGenerated = fixture()
        nestedGenerated.packageRoot.resolve("src/generated/Alpha.php")
            .also { it.parent.createDirectories() }
            .writeText("<?php\n")
        assertNull(nestedGenerated.state())

        val linked = fixture()
        val external = createTempDirectory("phpunit-external").resolve("Alpha.php").apply { writeText("<?php\n") }
        linked.packageRoot.resolve("src/Alpha.php").toFile().delete()
        runCatching { java.nio.file.Files.createSymbolicLink(linked.packageRoot.resolve("src/Alpha.php"), external) }
            .onSuccess { assertNull(linked.state()) }
    }

    @Test
    fun `rejects unsupported PHPUnit versions and incomplete package inputs`() {
        val fixture = fixture()

        assertNull(fixture.state(runtime().copy(phpunit = "13.1.5")))
        assertNull(fixture.state(runtime().copy(phpunit = "13.4.0")))
        assertNull(fixture.state(runtime().copy(autoPrependFile = "/tmp/bootstrap.php")))
        fixture.root.resolve("composer.lock").toFile().delete()
        assertNull(fixture.state())

        val pest = fixture()
        pest.root.resolve("composer.json").writeText(
            """{"name":"affected/root","require-dev":{"pestphp/pest":"^3.8"}}""",
        )
        assertNull(pest.state())
    }

    private fun fixture(): Fixture {
        val root = createTempDirectory("phpunit-metadata")
        val packageRoot = root.resolve("packages/alpha")
        val dependencyRoot = root.resolve("packages/shared")
        packageRoot.resolve("src/Alpha.php").also { it.parent.createDirectories() }
            .writeText("<?php\nnamespace Affected;\nfinal class Alpha {}\n")
        packageRoot.resolve("tests/AlphaTest.php").also { it.parent.createDirectories() }.writeText(SAFE_PHPUNIT_TEST)
        dependencyRoot.resolve("src/Shared.php").also { it.parent.createDirectories() }
            .writeText("<?php\nnamespace Affected;\nfinal class Shared {}\n")
        packageRoot.resolve("composer.json").writeText(composer("src/"))
        dependencyRoot.resolve("composer.json").writeText(composer("src/"))
        root.resolve("composer.json").writeText("{\"name\":\"affected/root\"}\n")
        root.resolve("composer.lock").writeText("{\"packages\":[],\"packages-dev\":[]}\n")
        root.resolve("vendor/composer").createDirectories()
        root.resolve("vendor/autoload.php").writeText("<?php\n")
        root.resolve("vendor/composer/installed.php").writeText("<?php return [];\n")
        root.resolve("vendor/composer/autoload_psr4.php").writeText("<?php return [];\n")
        root.resolve("vendor/bin").createDirectories()
        root.resolve("vendor/bin/phpunit").writeText("#!/usr/bin/env php\n")
        val adapter = createTempDirectory("phpunit-adapter")
            .resolve("affected-phpunit.php")
            .apply { writeText("<?php\n") }
        return Fixture(root, packageRoot, dependencyRoot, adapter)
    }

    private fun composer(source: String): String =
        "{\"name\":\"affected/package\",\"autoload\":{\"psr-4\":{\"Affected\\\\\":\"$source\"}}}\n"

    private fun runtime() = PhpunitTestMetadata(
        php = "8.5.9",
        phpunit = "13.3.0",
        extensions = listOf("Core", "json"),
        configuration = listOf("/etc/php.ini"),
    )

    private data class Fixture(
        val root: Path,
        val packageRoot: Path,
        val dependencyRoot: Path,
        val adapter: Path,
    ) {
        fun state(
            runtime: PhpunitTestMetadata = PhpunitTestMetadata("8.5.9", "13.3.0", listOf("Core"), emptyList()),
        ) =
            readPhpunitProjectState(
                root,
                packageRoot,
                setOf(packageRoot, dependencyRoot),
                adapter,
                runtime,
                emptyMap(),
            )
    }

    private companion object {
        const val SAFE_PHPUNIT_TEST = """<?php
namespace Affected\Tests;
use Affected\Alpha;
use PHPUnit\Framework\TestCase;
final class AlphaTest extends TestCase {
    public function testValue(): void { self::assertSame(1, Alpha::value()); }
}
"""
    }
}
