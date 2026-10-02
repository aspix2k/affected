import ast
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
MAX_SCAN_DEPTH = 7
MAX_SCAN_DIRECTORIES = 4096
MAX_SCAN_FILES = 4096
MAX_SCAN_FILE_BYTES = 8 * 1024 * 1024
MAX_SCAN_TOTAL_BYTES = 64 * 1024 * 1024
IGNORED_DIRECTORIES = {
    ".git",
    ".idea",
    ".mypy_cache",
    ".nox",
    ".pytest_cache",
    ".tox",
    ".venv",
    ".vscode",
    "__pycache__",
    "venv",
}
DYNAMIC_MODULES = {"imp", "importlib", "pkgutil", "runpy", "zipimport"}
DYNAMIC_CALLS = {"__import__", "compile", "eval", "exec"}
DYNAMIC_ATTRIBUTES = {
    "discover",
    "exec_module",
    "import_module",
    "load_module",
    "loadTestsFromName",
    "loadTestsFromNames",
    "module_from_spec",
    "spec_from_file_location",
}


class Unsupported(Exception):
    """Signal that exact selection must widen to package discovery."""


class AffectedTestLoader(unittest.TestLoader):
    """Discover both supported unittest module naming conventions."""

    def _match_path(self, path, full_path, pattern):
        """Match prefix or suffix unittest modules in one traversal."""
        return super()._match_path(path, full_path, pattern) or is_suffix_test(path)


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
    reject_selected_importers(root, packages, selected)
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


def reject_selected_importers(root, packages, selected):
    """Widen when any unselected package file may import a selected module."""
    names = {module_name_for(root, path) for path in selected}
    for path in scan_python_files(packages):
        if path in selected:
            continue
        try:
            tree = ast.parse(path.read_bytes(), filename=path.name)
        except (SyntaxError, ValueError, OSError) as error:
            raise Unsupported("syntax") from error
        reject_dynamic_syntax(tree)
        relative = path.relative_to(root)
        if any(not part.isidentifier() for part in relative.with_suffix("").parts):
            continue
        for node in ast.walk(tree):
            if isinstance(node, ast.Import):
                imported = [alias.name for alias in node.names]
            elif isinstance(node, ast.ImportFrom):
                imported = from_import_names(node, relative.parent.parts, names)
            else:
                continue
            if any(
                name == target or name.startswith(target + ".")
                for name in imported
                for target in names
            ):
                raise Unsupported("imported-by-other-tests")


def from_import_names(node, package_parts, selected_names):
    """Return every module name a from-import may bind, rejecting star imports of siblings."""
    remove = max(node.level - 1, 0)
    if remove > len(package_parts):
        raise Unsupported("relative-import")
    base = list(package_parts[: len(package_parts) - remove]) if node.level else []
    if node.module:
        base.extend(node.module.split("."))
    prefix = ".".join(base)
    result = [prefix] if prefix else []
    for alias in node.names:
        if alias.name != "*":
            result.append(".".join([*base, alias.name]))
        elif any(name.rpartition(".")[0] == prefix for name in selected_names):
            raise Unsupported("star-import")
    return result


def reject_dynamic_syntax(tree):
    """Reject code that can import project modules by name at runtime."""
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            roots = {alias.name.split(".", 1)[0] for alias in node.names}
        elif isinstance(node, ast.ImportFrom) and node.level == 0 and node.module:
            roots = {node.module.split(".", 1)[0]}
        else:
            roots = set()
        if roots & DYNAMIC_MODULES:
            raise Unsupported("dynamic-dependency")
        if isinstance(node, ast.Name) and node.id in DYNAMIC_CALLS:
            raise Unsupported("dynamic-dependency")
        if isinstance(node, ast.Attribute) and node.attr in DYNAMIC_ATTRIBUTES:
            raise Unsupported("dynamic-dependency")


def scan_python_files(packages):
    """List Python sources below planned packages within bounded symlink-free limits."""
    queue = [(package, 0) for package in packages]
    files = []
    directories = 0
    total_bytes = 0
    while queue:
        current, depth = queue.pop(0)
        directories += 1
        if directories > MAX_SCAN_DIRECTORIES or depth > MAX_SCAN_DEPTH:
            raise Unsupported("scan-limit")
        try:
            children = list(current.iterdir())
            for child in children:
                if is_link_like(child):
                    if child.name not in IGNORED_DIRECTORIES:
                        raise Unsupported("symlink")
                elif child.is_dir():
                    if child.name not in IGNORED_DIRECTORIES:
                        queue.append((child, depth + 1))
                elif child.suffix == ".py":
                    size = child.stat().st_size
                    total_bytes += size
                    if (
                        size > MAX_SCAN_FILE_BYTES
                        or total_bytes > MAX_SCAN_TOTAL_BYTES
                        or len(files) >= MAX_SCAN_FILES
                    ):
                        raise Unsupported("scan-limit")
                    files.append(child)
        except OSError as error:
            raise Unsupported("unreadable") from error
    return files


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
    loader = AffectedTestLoader()
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
    """Match supported unittest patterns and valid Python module names."""
    return (
        name.startswith("test") and name.endswith(".py") and name[:-3].isidentifier()
    ) or is_suffix_test(name)


def is_suffix_test(name):
    """Match valid Python modules using unittest's suffix convention."""
    return name.endswith("_test.py") and name[:-3].isidentifier()


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
