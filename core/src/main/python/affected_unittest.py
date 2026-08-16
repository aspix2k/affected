import base64
import importlib
import inspect
import json
import secrets
import sys
import tempfile
import unittest
from pathlib import Path, PurePosixPath

SCHEMA = 1
MAX_CONTEXT_CHARS = 16_384
MAX_CONTEXT_BYTES = 12_288
MAX_PATHS = 256
MAX_DISCOVERY_ENTRIES = 16_384
MAX_DISCOVERY_DEPTH = 32


class Unsupported(Exception):
    """Signal that exact selection must widen to package discovery."""


def main(argv):
    """Run an exact owned unittest suite or discover every planned package."""
    try:
        root = Path.cwd().resolve(strict=True)
        context = decode_context(argv)
        packages = validated_paths(
            context.get("packages"), root, require_directory=True
        )
        validate_independent_package_roots(packages)
    except (OSError, UnicodeError, ValueError, Unsupported) as error:
        print(
            f"Affected unittest: invalid context ({bounded_reason(error)})",
            file=sys.stderr,
        )
        return 2

    isolate_bytecode_cache()
    sys.path.insert(0, str(root))
    collected = collect_suite(root, context, packages)
    if collected is None:
        return 2
    suite, count = collected
    if count == 0:
        print("Affected unittest: no tests collected", file=sys.stderr)
        return 2
    try:
        result = unittest.TextTestRunner(verbosity=2).run(suite)
    except KeyboardInterrupt:
        raise
    except SystemExit as error:
        print(
            f"Affected unittest: unsafe test execution ({bounded_reason(error)})",
            file=sys.stderr,
        )
        return 2
    if result.testsRun != count:
        print(
            f"Affected unittest: expected {count} tests but ran {result.testsRun}",
            file=sys.stderr,
        )
        return 2
    return 0 if result.wasSuccessful() else 1


def collect_suite(root, context, packages):
    """Collect one exact or full suite and its fail-closed test count."""
    try:
        suite = exact_suite(root, context, packages)
        count = suite_test_count(suite)
    except (OSError, Unsupported, unittest.SkipTest) as error:
        print(f"Affected unittest: full fallback ({bounded_reason(error)})")
    else:
        suffix = "" if len(context["selected"]) == 1 else "s"
        print(
            f"Affected unittest: exact ({len(context['selected'])} test file{suffix}, {count} tests)"
        )
        return suite, count

    try:
        suite = discover_packages(root, packages)
        return suite, suite_test_count(suite)
    except (OSError, Unsupported) as discovery_error:
        print(
            f"Affected unittest: unsafe discovery ({bounded_reason(discovery_error)})",
            file=sys.stderr,
        )
        return None


def suite_test_count(suite):
    """Count tests without allowing user suites to terminate the adapter successfully."""
    try:
        return suite.countTestCases()
    except KeyboardInterrupt:
        raise
    except SystemExit as error:
        raise Unsupported("test-count") from error
    except Exception as error:
        raise Unsupported("test-count") from error


def decode_context(argv):
    """Decode one bounded URL-safe JSON context argument."""
    if len(argv) != 1 or not argv[0] or len(argv[0]) > MAX_CONTEXT_CHARS:
        raise Unsupported("context")
    padding = "=" * (-len(argv[0]) % 4)
    payload = base64.urlsafe_b64decode((argv[0] + padding).encode("ascii"))
    if len(payload) > MAX_CONTEXT_BYTES:
        raise Unsupported("context-limit")
    context = json.loads(payload.decode("utf-8"))
    if not isinstance(context, dict) or context.get("schema") != SCHEMA:
        raise Unsupported("schema")
    return context


def isolate_bytecode_cache():
    """Prevent project imports from reading or writing repository bytecode caches."""
    temporary = Path(tempfile.gettempdir()).resolve(strict=True)
    sys.dont_write_bytecode = True
    sys.pycache_prefix = str(temporary / f"affected-unittest-{secrets.token_hex(16)}")
    importlib.invalidate_caches()


def validated_paths(values, root, require_directory):
    """Resolve unique relative paths without following any symlink component."""
    if not isinstance(values, list) or not values or len(values) > MAX_PATHS:
        raise Unsupported("path-count")
    result = []
    for value in values:
        if not isinstance(value, str) or not value or "\\" in value:
            raise Unsupported("path")
        relative = PurePosixPath(value)
        if relative.is_absolute() or ".." in relative.parts:
            raise Unsupported("path")
        requested = root.joinpath(*relative.parts)
        if has_symlink_between(root, requested):
            raise Unsupported("symlink")
        resolved = requested.resolve(strict=True)
        if root != resolved and root not in resolved.parents:
            raise Unsupported("path")
        if require_directory and not resolved.is_dir():
            raise Unsupported("package")
        if not require_directory and not resolved.is_file():
            raise Unsupported("selected")
        result.append(resolved)
    if len(set(result)) != len(result):
        raise Unsupported("duplicate-path")
    return result


def has_symlink_between(root, path):
    """Return whether an existing path prefix contains a symbolic link."""
    try:
        relative = path.relative_to(root)
    except ValueError:
        return True
    current = root
    for part in relative.parts:
        current = current / part
        if is_link_like(current):
            return True
        if not current.exists():
            return False
    return False


def validate_independent_package_roots(packages):
    """Reject package roots whose discovery trees overlap and could run tests twice."""
    for index, package in enumerate(packages):
        for other in packages[index + 1 :]:
            if package in other.parents or other in package.parents:
                raise Unsupported("overlapping-packages")


def is_link_like(path):
    """Reject symbolic links and Windows directory junctions."""
    try:
        junction = getattr(path, "is_junction", None)
        return path.is_symlink() or (junction is not None and junction())
    except OSError:
        return True


def exact_suite(root, context, packages):
    """Collect standard suites whose tests are owned by every selected file."""
    selected = validated_paths(context.get("selected"), root, require_directory=False)
    if any(path.suffix != ".py" or not owned_by(path, packages) for path in selected):
        raise Unsupported("ownership")
    before = {path: file_identity(path) for path in selected}
    loader = unittest.TestLoader()
    suites = []
    for path in selected:
        module_name = module_name_for(root, path)
        validate_package_initializers(root, path.parent)
        reject_ancestor_hooks(module_name)
        try:
            module = importlib.import_module(module_name)
        except KeyboardInterrupt:
            raise
        except SystemExit as error:
            raise Unsupported("import") from error
        except Exception as error:
            raise Unsupported("import") from error
        try:
            if canonical_module_file(module) != path or callable(
                getattr(module, "load_tests", None)
            ):
                raise Unsupported("module")
            if before[path] != file_identity(path):
                raise Unsupported("drift")
            suite = loader.loadTestsFromModule(module)
            tests = flatten_standard_suite(suite)
            if not tests or any(test_source(test) != path for test in tests):
                raise Unsupported("zero-or-imported-tests")
        except KeyboardInterrupt:
            raise
        except SystemExit as error:
            raise Unsupported("module") from error
        except Unsupported:
            raise
        except Exception as error:
            raise Unsupported("module") from error
        suites.append(suite)
    if any(before[path] != file_identity(path) for path in selected):
        raise Unsupported("drift")
    return unittest.TestSuite(suites)


def reject_ancestor_hooks(module_name):
    """Reject package-level load_tests hooks that can replace discovery."""
    parts = module_name.split(".")
    for end in range(1, len(parts)):
        try:
            package = importlib.import_module(".".join(parts[:end]))
        except KeyboardInterrupt:
            raise
        except SystemExit as error:
            raise Unsupported("package-import") from error
        except Exception as error:
            raise Unsupported("package-import") from error
        try:
            if callable(getattr(package, "load_tests", None)):
                raise Unsupported("package-load-tests")
        except KeyboardInterrupt:
            raise
        except SystemExit as error:
            raise Unsupported("package-load-tests") from error
        except Unsupported:
            raise
        except Exception as error:
            raise Unsupported("package-load-tests") from error


def module_name_for(root, path):
    """Convert one selected root-relative Python path to its dotted module name."""
    relative = path.relative_to(root).with_suffix("")
    if not relative.parts or any(not part.isidentifier() for part in relative.parts):
        raise Unsupported("module-name")
    return ".".join(relative.parts)


def canonical_module_file(module):
    """Resolve the imported module source without accepting missing metadata."""
    value = getattr(module, "__file__", None)
    if not isinstance(value, str):
        raise Unsupported("module-file")
    return Path(value).resolve(strict=True)


def flatten_standard_suite(suite):
    """Flatten only standard unittest suites into concrete test cases."""
    if type(suite) is not unittest.TestSuite:
        raise Unsupported("custom-suite")
    result = []
    for child in suite:
        if type(child) is unittest.TestSuite:
            result.extend(flatten_standard_suite(child))
        elif isinstance(child, unittest.TestCase):
            result.append(child)
        else:
            raise Unsupported("custom-test")
    return result


def test_source(test):
    """Resolve the source file that owns a collected TestCase class."""
    try:
        value = inspect.getsourcefile(test.__class__) or inspect.getfile(test.__class__)
        return Path(value).resolve(strict=True)
    except (OSError, TypeError, ValueError) as error:
        raise Unsupported("test-source") from error


def discover_packages(root, packages):
    """Aggregate standard unittest discovery for every planned package."""
    for package in packages:
        validate_package_initializers(root, package)
    validate_discovery_trees(root, packages)
    loader = unittest.TestLoader()
    suites = []
    for package in packages:
        try:
            suites.append(loader.discover(str(package), top_level_dir=str(root)))
        except KeyboardInterrupt:
            raise
        except SystemExit as error:
            suites.append(discovery_failure_suite(error))
        except Exception as error:  # noqa: BLE001
            suites.append(discovery_failure_suite(error))
    return unittest.TestSuite(suites)


def validate_package_initializers(root, directory):
    """Require every importable directory below the root to be a local package."""
    try:
        relative = directory.relative_to(root)
    except ValueError as error:
        raise Unsupported("package-path") from error
    current = root
    for part in relative.parts:
        current = current / part
        initial = current / "__init__.py"
        if is_link_like(initial) or not initial.is_file():
            raise Unsupported("package-init")


def discovery_failure_suite(error):
    """Represent an escaped discovery exception as one failing unittest case."""
    reason = bounded_reason(error)

    def fail():
        """Fail the aggregate suite without re-running discovery."""
        raise RuntimeError(f"unittest discovery failed: {reason}")

    return unittest.TestSuite([unittest.FunctionTestCase(fail)])


def validate_discovery_trees(root, packages):
    """Reject bounded package layouts that unittest discovery could follow unsafely."""
    queue = [(package, 0) for package in packages]
    entries = 0
    while queue:
        directory, depth = queue.pop(0)
        if is_link_like(directory):
            raise Unsupported("discovery-link")
        resolved = directory.resolve(strict=True)
        if root != resolved and root not in resolved.parents:
            raise Unsupported("discovery-path")
        if depth > MAX_DISCOVERY_DEPTH:
            raise Unsupported("discovery-depth")
        initial = directory / "__init__.py"
        if is_link_like(initial) or initial.exists() and not initial.is_file():
            raise Unsupported("package-init")
        for entry in directory.iterdir():
            entries += 1
            if entries > MAX_DISCOVERY_ENTRIES:
                raise Unsupported("discovery-limit")
            if is_link_like(entry):
                if entry.is_dir() or is_discoverable_test(entry.name):
                    raise Unsupported("discovery-symlink")
                continue
            if entry.is_dir():
                resolved = entry.resolve(strict=True)
                if root != resolved and root not in resolved.parents:
                    raise Unsupported("discovery-path")
                package_init = entry / "__init__.py"
                if is_link_like(package_init):
                    raise Unsupported("package-init")
                if package_init.exists():
                    if not package_init.is_file():
                        raise Unsupported("package-init")
                    queue.append((entry, depth + 1))


def is_discoverable_test(name):
    """Match unittest's default pattern and valid Python module names."""
    return name.startswith("test") and name.endswith(".py") and name[:-3].isidentifier()


def owned_by(path, packages):
    """Return whether one selected file belongs to a planned package root."""
    return any(path == package or package in path.parents for package in packages)


def file_identity(path):
    """Capture stable file metadata around runtime import and collection."""
    stat = path.stat()
    return stat.st_dev, stat.st_ino, stat.st_size, stat.st_mtime_ns


def bounded_reason(error):
    """Return one short diagnostic token without exposing arbitrary content."""
    try:
        text = str(error).strip().replace("\n", " ")
    except KeyboardInterrupt:
        raise
    except BaseException:  # noqa: BLE001
        return "unprintable-error"
    return text[:80] if text else "unspecified-error"


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
