import ast
import base64
import json
import os
import sys
from pathlib import Path, PurePosixPath

SCHEMA = 1
MAX_DEPTH = 7
MAX_DIRECTORIES = 4096
MAX_FILES = 4096
MAX_FILE_BYTES = 8 * 1024 * 1024
MAX_TOTAL_BYTES = 64 * 1024 * 1024
IGNORED_DIRECTORIES = {
    ".git",
    ".github",
    ".idea",
    ".mypy_cache",
    ".nox",
    ".pytest_cache",
    ".tox",
    ".venv",
    ".vscode",
    "__pycache__",
    "build",
    "dist",
    "venv",
}
CONFIG_FILES = {".pytest.ini", "pytest.ini", "setup.cfg", "tox.ini"}
RISKY_MODULES = {
    "ctypes",
    "importlib",
    "io",
    "marshal",
    "os",
    "pathlib",
    "pickle",
    "pkgutil",
    "runpy",
    "socket",
    "subprocess",
    "urllib",
    "zipimport",
}
SAFE_EXTERNAL_MODULES = {
    "collections",
    "dataclasses",
    "datetime",
    "decimal",
    "enum",
    "fractions",
    "functools",
    "hashlib",
    "itertools",
    "json",
    "math",
    "operator",
    "pytest",
    "re",
    "statistics",
    "time",
    "typing",
    "unittest",
    "uuid",
}
RISKY_CALLS = {
    "__import__",
    "compile",
    "eval",
    "exec",
    "getattr",
    "globals",
    "locals",
    "open",
    "patch",
    "setattr",
    "vars",
}
RISKY_ATTRIBUTES = {
    "exec_module",
    "import_module",
    "importorskip",
    "load_module",
    "module_from_spec",
    "open",
    "read_bytes",
    "read_text",
    "setattr",
    "spec_from_file_location",
}


class Unsupported(Exception):
    """Signal that the current project must keep the full pytest plan."""


class AffectedPytestPlugin:
    """Filter a fully collected default pytest plan to affected test files."""

    def __init__(self, context, root, pytest_module):
        """Keep validated host context and the active pytest module."""
        self.context = context
        self.root = root
        self.pytest = pytest_module
        self.reason = None

    def pytest_configure(self, config):
        """Reject runtime selectors and third-party collection plugins."""
        self.reason = runtime_fallback_reason(config, self.pytest)

    def pytest_collection_modifyitems(self, session, config, items):
        """Deselect test files only after the complete plan is available."""
        if self.reason is not None:
            report(config, "full fallback", self.reason)
            return
        if session.testsfailed:
            report(config, "full fallback", "collection")
            return
        try:
            selected = select_test_files(self.root, self.context, items)
        except Unsupported as error:
            report(config, "full fallback", str(error))
            return
        if not selected:
            report(config, "full fallback", "no-related-tests")
            return
        deselected = [
            item for item in items if canonical_item_path(item) not in selected
        ]
        if not deselected:
            report(config, "full fallback", "complete-plan")
            return
        items[:] = [item for item in items if canonical_item_path(item) in selected]
        config.hook.pytest_deselected(items=deselected)
        suffix = "" if len(selected) == 1 else "s"
        report(config, "exact", f"{len(selected)} test file{suffix}")


def main(argv):
    """Run pytest with the Affected collection filter or an unchanged full plan."""
    try:
        pytest_args, encoded = split_arguments(argv)
    except Unsupported:
        return 4
    try:
        root = Path.cwd().resolve(strict=True)
    except OSError:
        return 4
    sys.path.insert(0, str(root))
    try:
        import pytest
    except ImportError:
        return 4
    try:
        context = decode_context(encoded)
        validate_context(context, root)
    except (OSError, ValueError, Unsupported):
        print("Affected pytest: full fallback (invalid-context)")
        return pytest.main(pytest_args)
    plugin = AffectedPytestPlugin(context, root, pytest)
    return pytest.main(pytest_args, plugins=[plugin])


def split_arguments(argv):
    """Separate the opaque Affected context from native pytest arguments."""
    if len(argv) < 3 or argv[1] != "--" or not argv[0]:
        raise Unsupported("invalid-context")
    return argv[2:], argv[0]


def decode_context(encoded):
    """Decode a bounded URL-safe JSON context."""
    if len(encoded) > 16384:
        raise Unsupported("context-limit")
    padding = "=" * (-len(encoded) % 4)
    payload = base64.urlsafe_b64decode((encoded + padding).encode("ascii"))
    if len(payload) > 12288:
        raise Unsupported("context-limit")
    value = json.loads(payload.decode("utf-8"))
    if not isinstance(value, dict):
        raise Unsupported("invalid-context")
    return value


def validate_context(context, root):
    """Validate schema, relative paths, ownership roots, and modified-file proof."""
    if context.get("schema") != SCHEMA:
        raise Unsupported("schema")
    roots = validated_paths(context.get("roots"), root, require_directory=True)
    packages = validated_paths(context.get("packages"), root, require_directory=True)
    changes = validated_paths(context.get("changes"), root, require_directory=False)
    eligible = validated_paths(context.get("eligible"), root, require_directory=False)
    if not roots or not packages or not changes or set(changes) != set(eligible):
        raise Unsupported("change-kind")
    if not set(packages).issubset(set(roots)):
        raise Unsupported("ownership")
    if any(
        path.suffix != ".py" or not path.is_file() or path.is_symlink()
        for path in changes
    ):
        raise Unsupported("changed-file")
    if any(owner_of(path, roots) is None for path in changes):
        raise Unsupported("ownership")
    context["roots"] = roots
    context["packages"] = packages
    context["changes"] = changes


def validated_paths(values, root, require_directory):
    """Resolve unique relative paths without permitting traversal or symlinks."""
    if not isinstance(values, list) or len(values) > 256:
        raise Unsupported("context-limit")
    result = []
    for value in values:
        if not isinstance(value, str) or not value or "\\" in value:
            raise Unsupported("invalid-path")
        relative = PurePosixPath(value)
        if relative.is_absolute() or ".." in relative.parts:
            raise Unsupported("invalid-path")
        path = root.joinpath(*relative.parts)
        if has_symlink_between(root, path):
            raise Unsupported("symlink")
        resolved = path.resolve(strict=True)
        if root != resolved and root not in resolved.parents:
            raise Unsupported("invalid-path")
        if require_directory and not resolved.is_dir():
            raise Unsupported("invalid-path")
        if not require_directory and not resolved.is_file():
            raise Unsupported("changed-file")
        result.append(resolved)
    if len(set(result)) != len(result):
        raise Unsupported("duplicate-path")
    return result


def has_symlink_between(root, path):
    """Detect a symlink in an existing path prefix without following it."""
    current = root
    try:
        relative = path.relative_to(root)
    except ValueError:
        return True
    for part in relative.parts:
        current = current / part
        if current.is_symlink():
            return True
        if not current.exists():
            return False
    return False


def runtime_fallback_reason(config, pytest_module):
    """Return a bounded reason when runtime collection semantics are not default."""
    version = pytest_module.__version__
    major = version.split(".", 1)[0] if version else ""
    if major not in {"8", "9"} or any(
        mark in version for mark in ("a", "b", "rc", "dev")
    ):
        return "pytest-version"
    if os.environ.get("PYTEST_ADDOPTS") or os.environ.get("PYTEST_PLUGINS"):
        return "runtime-options"
    if config.pluginmanager.list_plugin_distinfo():
        return "external-plugin"
    return None


def select_test_files(root, context, items):
    """Build the complete current import graph and return affected collected files."""
    ensure_default_configuration(root, context["roots"])
    files = scan_python_files(root)
    changes = set(context["changes"])
    if not changes.issubset(files):
        raise Unsupported("changed-file")
    test_files = collected_test_files(items, context["packages"])
    if not test_files:
        raise Unsupported("collection")
    module_map, names_by_file = module_index(root, context["roots"], files)
    edges = import_edges(files, module_map, names_by_file)
    selected = {test for test in test_files if dependencies_of(test, edges) & changes}
    return selected


def ensure_default_configuration(root, roots):
    """Reject conftest and pytest configuration that can alter collection."""
    for directory in {root, *roots}:
        for name in CONFIG_FILES:
            if (directory / name).exists():
                raise Unsupported("pytest-config")
        manifest = directory / "pyproject.toml"
        if manifest.is_file():
            text = read_bounded_text(manifest)
            if "[tool.pytest" in text:
                raise Unsupported("pytest-config")


def scan_python_files(root):
    """Scan a bounded symlink-free project tree for Python sources and conftest."""
    queue = [(root, 0)]
    files = set()
    directories = 0
    total_bytes = 0
    while queue:
        current, depth = queue.pop(0)
        directories += 1
        if directories > MAX_DIRECTORIES:
            raise Unsupported("scan-limit")
        try:
            children = list(current.iterdir())
        except OSError:
            raise Unsupported("unreadable")
        if len(children) > MAX_DIRECTORIES:
            raise Unsupported("scan-limit")
        for child in children:
            if child.is_symlink():
                if child.name not in IGNORED_DIRECTORIES:
                    raise Unsupported("symlink")
                continue
            if child.is_dir():
                if child.name in IGNORED_DIRECTORIES or child.name.endswith(
                    ".egg-info"
                ):
                    continue
                if child.name.startswith(".") or depth >= MAX_DEPTH:
                    raise Unsupported("scan-limit")
                queue.append((child, depth + 1))
                continue
            if not child.is_file():
                raise Unsupported("unreadable")
            if child.name == "conftest.py":
                raise Unsupported("conftest")
            if child.suffix != ".py":
                continue
            size = child.stat().st_size
            if size > MAX_FILE_BYTES:
                raise Unsupported("scan-limit")
            total_bytes += size
            if total_bytes > MAX_TOTAL_BYTES or len(files) >= MAX_FILES:
                raise Unsupported("scan-limit")
            files.add(child.resolve(strict=True))
    return files


def collected_test_files(items, packages):
    """Validate that every collected item belongs to exactly one planned package."""
    result = set()
    for item in items:
        path = canonical_item_path(item)
        if path is None or owner_of(path, packages) is None:
            raise Unsupported("collection")
        result.add(path)
    return result


def canonical_item_path(item):
    """Return a canonical path for a public pytest item path."""
    value = getattr(item, "path", None)
    if value is None:
        return None
    try:
        path = Path(value)
        if path.is_symlink() or not path.is_file():
            return None
        return path.resolve(strict=True)
    except OSError:
        return None


def owner_of(path, roots):
    """Return the single deepest ownership root for a path."""
    candidates = [root for root in roots if root == path or root in path.parents]
    if not candidates:
        return None
    depth = max(len(root.parts) for root in candidates)
    deepest = [root for root in candidates if len(root.parts) == depth]
    return deepest[0] if len(deepest) == 1 else None


def module_index(root, roots, files):
    """Index unique import names for root, module, and conventional src layouts."""
    source_roots = {root, *roots}
    source_roots.update(
        directory / "src" for directory in roots if (directory / "src").is_dir()
    )
    module_map = {}
    names_by_file = {}
    for path in files:
        names = set()
        for source_root in source_roots:
            name = module_name(source_root, path)
            if name:
                previous = module_map.get(name)
                if previous is not None and previous != path:
                    raise Unsupported("module-identity")
                module_map[name] = path
                names.add(name)
        names_by_file[path] = names
    return module_map, names_by_file


def module_name(source_root, path):
    """Derive an import name when a source is below a candidate source root."""
    try:
        relative = path.relative_to(source_root)
    except ValueError:
        return None
    if relative.suffix != ".py":
        return None
    parts = list(relative.with_suffix("").parts)
    if parts[-1] == "__init__":
        parts.pop()
    if not parts or any(not part.isidentifier() for part in parts):
        return None
    return ".".join(parts)


def import_edges(files, module_map, names_by_file):
    """Parse every project source and resolve its complete static import edges."""
    edges = {}
    for path in files:
        source = read_bounded_text(path)
        try:
            tree = ast.parse(source, filename=path.name)
        except (SyntaxError, ValueError):
            raise Unsupported("syntax")
        reject_dynamic_syntax(tree)
        dependencies = set()
        for node in ast.walk(tree):
            if isinstance(node, ast.Import):
                for alias in node.names:
                    dependencies.update(resolve_absolute_import(alias.name, module_map))
            elif isinstance(node, ast.ImportFrom):
                dependencies.update(
                    resolve_from_import(node, names_by_file[path], module_map)
                )
        edges[path] = dependencies
    return edges


def reject_dynamic_syntax(tree):
    """Reject code paths that can load project sources outside static imports."""
    for node in ast.walk(tree):
        if isinstance(node, (ast.Import, ast.ImportFrom)):
            module = imported_root(node)
            if module in RISKY_MODULES:
                raise Unsupported("dynamic-dependency")
        if isinstance(node, ast.Call):
            if isinstance(node.func, ast.Name) and node.func.id in RISKY_CALLS:
                raise Unsupported("dynamic-dependency")
            if (
                isinstance(node.func, ast.Attribute)
                and node.func.attr in RISKY_ATTRIBUTES
            ):
                raise Unsupported("dynamic-dependency")
        if isinstance(node, ast.Name) and node.id in RISKY_CALLS:
            raise Unsupported("dynamic-dependency")
        if isinstance(node, ast.Attribute):
            if node.attr in RISKY_ATTRIBUTES:
                raise Unsupported("dynamic-dependency")
            if (
                isinstance(node.value, ast.Name)
                and node.value.id == "sys"
                and node.attr == "path"
            ):
                raise Unsupported("dynamic-dependency")


def imported_root(node):
    """Return the absolute top-level module imported by an AST node."""
    if isinstance(node, ast.Import):
        roots = {alias.name.split(".", 1)[0] for alias in node.names}
        return next(iter(roots)) if len(roots) == 1 else None
    if node.level == 0 and node.module:
        return node.module.split(".", 1)[0]
    return None


def resolve_absolute_import(name, module_map):
    """Resolve a local absolute import or validate its external module family."""
    result = module_prefixes(name, module_map)
    if result:
        return result
    if name.split(".", 1)[0] not in SAFE_EXTERNAL_MODULES:
        raise Unsupported("external-dependency")
    return set()


def resolve_from_import(node, caller_names, module_map):
    """Resolve absolute or relative from-imports conservatively."""
    bases = set()
    if node.level == 0:
        if node.module:
            bases.add(node.module)
    else:
        for caller in caller_names:
            package = (
                caller
                if caller in module_map and module_map[caller].name == "__init__.py"
                else caller.rpartition(".")[0]
            )
            parts = package.split(".") if package else []
            remove = node.level - 1
            if remove > len(parts):
                raise Unsupported("relative-import")
            prefix = parts[: len(parts) - remove]
            if node.module:
                prefix.extend(node.module.split("."))
            if prefix:
                bases.add(".".join(prefix))
    if not bases:
        raise Unsupported("relative-import")
    result = set()
    for base in bases:
        base_result = module_prefixes(base, module_map)
        if (
            not base_result
            and node.level == 0
            and base.split(".", 1)[0] not in SAFE_EXTERNAL_MODULES
        ):
            raise Unsupported("external-dependency")
        result.update(base_result)
        for alias in node.names:
            if alias.name != "*":
                result.update(module_prefixes(base + "." + alias.name, module_map))
    return result


def module_prefixes(name, module_map):
    """Return imported modules and parent package initializers for a name."""
    result = set()
    parts = name.split(".")
    for index in range(1, len(parts) + 1):
        path = module_map.get(".".join(parts[:index]))
        if path is not None:
            result.add(path)
    return result


def dependencies_of(path, edges):
    """Return the transitive local source closure for one collected test file."""
    result = {path}
    queue = [path]
    while queue:
        current = queue.pop()
        for dependency in edges.get(current, set()):
            if dependency not in result:
                result.add(dependency)
                queue.append(dependency)
    return result


def read_bounded_text(path):
    """Read one UTF-8 source or manifest within the per-file limit."""
    try:
        if (
            path.is_symlink()
            or not path.is_file()
            or path.stat().st_size > MAX_FILE_BYTES
        ):
            raise Unsupported("unreadable")
        return path.read_text(encoding="utf-8")
    except (OSError, UnicodeError):
        raise Unsupported("unreadable")


def report(config, mode, detail):
    """Write one bounded selection status line to the current Run session."""
    line = f"Affected pytest: {mode} ({detail})"
    terminal = config.pluginmanager.get_plugin("terminalreporter")
    if terminal is not None:
        terminal.write_line(line)
    else:
        print(line)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
