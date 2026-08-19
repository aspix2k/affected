package com.aspix2k.affected.build

import com.google.gson.JsonParser
import java.io.File
import java.nio.file.Path
import java.util.Base64
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals

internal data class DeferredUnittestFixture(
    val root: File,
    val selected: File,
    val adapter: Path,
)

internal fun unittestChanges(vararg files: File) = BuildChanges(
    files = files.map(File::getPath),
    exactSelectionEligible = files.mapTo(LinkedHashSet(), File::getPath),
    comparedToBase = true,
)

internal fun unittestModules(root: File, vararg entries: String): List<BuildModule> {
    require(entries.size % 2 == 0)
    return entries.toList().chunked(2).map { (name, path) ->
        BuildModule(
            name,
            root.path,
            listOf(File(root, path).path),
            PythonProjects.TEST,
            PythonProjects.TYPECHECK,
            true,
            executionId = name,
        )
    }
}

internal fun deferredUnittestFixture(prefix: String): DeferredUnittestFixture {
    val root = createTempDirectory(prefix).toFile()
    File(root, "pyproject.toml").writeText("[project]\nname = \"app\"\n")
    val selected = File(root, "packages/a/test_alpha.py").apply {
        parentFile.mkdirs()
        writeText("import unittest\nclass AlphaTest(unittest.TestCase):\n    pass\n")
    }
    val adapter = File(root, "affected_unittest.py").apply { writeText("# adapter\n") }.toPath()
    return DeferredUnittestFixture(root, selected, adapter)
}

internal fun assertUnittestAdapterFullCommand(arguments: List<String>, adapter: Path) {
    val context = unittestAdapterContext(arguments, adapter)
    assertEquals(emptyList(), context.getAsJsonArray("selected").map { it.asString })
}

internal fun assertExactUnittestSelection(
    arguments: List<String>,
    adapter: Path,
    packageName: String,
    selected: String,
) {
    val context = unittestAdapterContext(arguments, adapter)
    assertEquals(1, context.get("schema").asInt)
    assertEquals(listOf(packageName), context.getAsJsonArray("packages").map { it.asString })
    assertEquals(listOf(selected), context.getAsJsonArray("selected").map { it.asString })
}

private fun unittestAdapterContext(arguments: List<String>, adapter: Path) = run {
    assertEquals(listOf("python", adapter.toString()), arguments.take(2))
    assertEquals(3, arguments.size)
    val padding = "=".repeat((4 - arguments[2].length % 4) % 4)
    JsonParser.parseString(
        Base64.getUrlDecoder().decode(arguments[2] + padding).toString(Charsets.UTF_8),
    ).asJsonObject
}

internal const val PYTHON_RUNNER_DISCOVERY_FAILURE =
    "import sys; sys.stderr.write(\"Affected could not safely determine whether this project uses " +
        "pytest or unittest; remove test-tree symlinks or declare pytest.\\n\"); raise SystemExit(2)"
internal const val UNITTEST_CONTEXT_FAILURE =
    "import sys; sys.stderr.write(\"Affected could not safely encode the unittest package set; " +
        "reduce the number or depth of Python package roots.\\n\"); raise SystemExit(2)"
internal const val PYTHON_RUNNER_DRIFT_FAILURE =
    "import sys; sys.stderr.write(\"Affected detected a Python test-runner change after planning; " +
        "refresh the project model and run again.\\n\"); raise SystemExit(2)"
internal const val UNITTEST_ADAPTER_DRIFT_FAILURE =
    "import sys; sys.stderr.write(\"Affected could not revalidate the packaged unittest adapter; " +
        "reinstall or rebuild the plugin and run again.\\n\"); raise SystemExit(2)"
