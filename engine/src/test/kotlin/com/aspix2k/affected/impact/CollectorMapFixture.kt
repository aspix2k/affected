package com.aspix2k.affected.impact

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.util.Base64
import kotlin.io.path.createTempDirectory

abstract class CollectorMapFixture {

    protected fun task(root: Path): Path {
        val key = "root|:app|testDebugUnitTest"
        return Files.createDirectory(root.resolve("task-${sha256(key)}")).also { directory ->
            Files.writeString(
                directory.resolve("task.manifest"),
                "format=1\ntask=${encode(key)}\nruntime=${encode("runtime-1")}\ninput=${encode("input-1")}\nall=true\n",
            )
            Files.writeString(
                directory.resolve("expected.manifest"),
                "format=1\nsupported=true\ntest=${encode("AlphaTest")}\ntest=${encode("BetaTest")}\n",
            )
            Files.writeString(
                directory.resolve("catalog.manifest"),
                "format=1\n${artifactLine(dependency("Alpha", "alpha-1"))}" +
                    artifactLine(dependency("Beta", "beta-1")),
            )
        }
    }

    protected fun started(task: Path, worker: String): Path = workerDirectory(task, worker).also { directory ->
        Files.createDirectories(directory)
        Files.writeString(directory.resolve("started.manifest"), "format=1\nworker=${encode(worker)}\n")
    }

    protected fun worker(
        task: Path,
        worker: String,
        test: String,
        vararg dependencies: ClassDependency,
        supported: Boolean = true,
        expected: String? = null,
    ) {
        val directory = started(task, worker)
        if (expected != null) {
            Files.writeString(
                directory.resolve("expected.manifest"),
                "format=1\nsupported=$supported\ntest=${encode(expected)}\n",
            )
        }
        Files.writeString(
            directory.resolve("complete.manifest"),
            "format=1\nworker=${encode(worker)}\nsupported=$supported\ntest=${encode(test)}\n",
        )
        Files.writeString(
            directory.resolve("test-${sha256(test)}.map"),
            "format=1\ntest=${encode(test)}\n${dependencies.joinToString("") { dependencyLine(it) }}",
        )
    }

    protected fun workerDirectory(task: Path, worker: String): Path = task.resolve("worker-${sha256(worker)}")

    protected fun dependencyLine(dependency: ClassDependency): String =
        "dependency=${encode(dependency.id.className)}|${encode(dependency.id.codeSource)}|${dependency.sha256}\n"

    protected fun artifactLine(dependency: ClassDependency): String =
        "artifact=${encode(dependency.id.className)}|${encode(dependency.id.codeSource)}|${dependency.sha256}\n"

    protected fun dependency(name: String, hashSeed: String) = ClassDependency(
        DependencyId(name, "file:///classes/"),
        sha256(hashSeed),
    )

    protected fun testClass(name: String) = TestClassId(name)

    protected fun record(test: TestClassId, vararg dependencies: ClassDependency) =
        TestDependencyRecord(test, dependencies.toSet())

    protected fun complete(artifacts: List<ClassDependency>, records: List<TestDependencyRecord>) =
        CompleteDependencyMap(
            identity = DependencyMapIdentity(
                DEPENDENCY_MAP_SCHEMA_VERSION,
                "collector-1",
                "root|:app|testDebugUnitTest",
                "runtime-1",
                "input-1",
            ),
            artifacts = artifacts,
            records = records,
            completedRunId = "run-1",
        )

    protected fun encode(value: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    protected fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    protected fun withDirectory(block: (Path) -> Unit) {
        val directory = createTempDirectory("affected-map-test-")
        try {
            block(directory)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    protected fun dependencyValue(dependency: ClassDependency): String =
        "${encode(dependency.id.className)}|${encode(dependency.id.codeSource)}|${dependency.sha256}"

    protected fun workerPath(worker: String) = "worker-${sha256(worker)}"

    protected fun startedPath(worker: String) = "${workerPath(worker)}/started.manifest"

    protected fun completedPath(worker: String) = "${workerPath(worker)}/complete.manifest"

    protected fun mapPath(worker: String, test: String) = "${workerPath(worker)}/test-${sha256(test)}.map"

    protected fun edit(file: String, from: String, to: String): (Path) -> Unit = transform(file) { text ->
        check(text.contains(from)) { "$file does not contain $from" }
        text.replaceFirst(from, to)
    }

    protected fun transform(file: String, change: (String) -> String): (Path) -> Unit = { task ->
        val path = task.resolve(file)
        Files.writeString(path, change(Files.readString(path)))
    }

    protected fun overwrite(file: String, content: String): (Path) -> Unit = { task ->
        Files.writeString(task.resolve(file), content)
    }

    protected fun remove(file: String): (Path) -> Unit = { task -> Files.delete(task.resolve(file)) }

    protected fun together(vararg changes: (Path) -> Unit): (Path) -> Unit = { task ->
        changes.forEach { it(task) }
    }

    protected fun replaceWithDirectory(file: String): (Path) -> Unit = { task ->
        Files.deleteIfExists(task.resolve(file))
        Files.createDirectory(task.resolve(file))
    }

    protected fun replaceWithSymlink(file: String): (Path) -> Unit = { task ->
        val path = task.resolve(file)
        val copy = Files.copy(path, task.resolveSibling("${task.fileName}-${path.fileName}.copy"))
        Files.delete(path)
        Files.createSymbolicLink(path, copy)
    }

    protected fun withPermissions(path: Path, permissions: Set<PosixFilePermission>, block: () -> Unit) {
        val original = Files.getPosixFilePermissions(path)
        Files.setPosixFilePermissions(path, permissions)
        try {
            val enforced = if (PosixFilePermission.OWNER_WRITE in permissions) {
                !Files.isReadable(path)
            } else {
                !Files.isWritable(path)
            }
            if (enforced) block()
        } finally {
            Files.setPosixFilePermissions(path, original)
        }
    }
}
