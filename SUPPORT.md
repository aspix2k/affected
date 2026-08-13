# Support

This page is generated from `config/support-matrix.json`. Every supported entry
is tied to public repository evidence and an executable CI gate.

## JetBrains products

| Product | Evidence level | Minimum platform | Evidence |
|---|---|---:|---|
| IntelliJ IDEA | Product-verified | 2025.3 | [build.gradle.kts](build.gradle.kts) · [ci.yml](.github/workflows/ci.yml) |
| Android Studio | Product-verified | 2025.3 | [build.gradle.kts](build.gradle.kts) · [ci.yml](.github/workflows/ci.yml) |
| Rider | Platform-compatible | 2025.3 | [plugin.xml](src/main/resources/META-INF/plugin.xml) · [ci.yml](.github/workflows/ci.yml) |
| GoLand | Platform-compatible | 2025.3 | [plugin.xml](src/main/resources/META-INF/plugin.xml) · [ci.yml](.github/workflows/ci.yml) |
| CLion | Platform-compatible | 2025.3 | [plugin.xml](src/main/resources/META-INF/plugin.xml) · [ci.yml](.github/workflows/ci.yml) |
| PyCharm | Platform-compatible | 2025.3 | [plugin.xml](src/main/resources/META-INF/plugin.xml) · [ci.yml](.github/workflows/ci.yml) |
| WebStorm | Platform-compatible | 2025.3 | [plugin.xml](src/main/resources/META-INF/plugin.xml) · [ci.yml](.github/workflows/ci.yml) |
| PhpStorm | Platform-compatible | 2025.3 | [plugin.xml](src/main/resources/META-INF/plugin.xml) · [ci.yml](.github/workflows/ci.yml) |
| RubyMine | Platform-compatible | 2025.3 | [plugin.xml](src/main/resources/META-INF/plugin.xml) · [ci.yml](.github/workflows/ci.yml) |
| RustRover | Platform-compatible | 2025.3 | [plugin.xml](src/main/resources/META-INF/plugin.xml) · [ci.yml](.github/workflows/ci.yml) |
| DataSpell | Platform-compatible | 2025.3 | [plugin.xml](src/main/resources/META-INF/plugin.xml) · [ci.yml](.github/workflows/ci.yml) |

Product-verified entries run a product-specific verifier. Platform-compatible
entries share the supported IntelliJ Platform contract but do not yet have a
dedicated product lifecycle fixture.

## Planned coverage

| Product | Goal | Tracking issue | Last reviewed |
|---|---|---|---:|
| DataGrip | SQL and database-project impact selection is tracked but not implemented yet. | [Issue #120](https://github.com/aspix2k/affected/issues/120) | 2026-08-12 |

## Build systems and test runners

| Ecosystem | Languages and files | Tests and checks | Selection unit | Tested versions | Evidence |
|---|---|---|---|---|---|
| Gradle JVM and Android | Kotlin, Java | JUnit, consumer test compilation | test class or module | Gradle 8–9; JDK 17, 21 and 26 | [GradleInjectionTest.java](collector/src/test/java/com/aspix2k/affected/collector/GradleInjectionTest.java) · [TaskPlannerTest.kt](core/src/test/kotlin/com/aspix2k/affected/TaskPlannerTest.kt) · [conformance.yml](.github/workflows/conformance.yml) · [ci.yml](.github/workflows/ci.yml) |
| Maven | Kotlin, Java | Surefire, Failsafe, consumer test compilation | test class or module | Maven 3.9.x; Maven 4 RC keeps the full goal | [MavenInjectionTest.java](collector/src/test/java/com/aspix2k/affected/collector/MavenInjectionTest.java) · [conformance.yml](.github/workflows/conformance.yml) |
| Cargo | Rust, TOML | cargo test, cargo nextest run, cargo check --tests | package | Current stable Rust; cargo-nextest 0.9.143–0.9.x | [cargo](conformance/cli-fixtures/cargo) · [CargoNextestCliAdapterConformanceTest.kt](core/src/test/kotlin/com/aspix2k/affected/build/CargoNextestCliAdapterConformanceTest.kt) · [conformance.yml](.github/workflows/conformance.yml) |
| Go modules | Go | go test, go build | package | Current stable Go toolchain | [go](conformance/cli-fixtures/go) · [CliAdapterConformanceTest.kt](core/src/test/kotlin/com/aspix2k/affected/build/CliAdapterConformanceTest.kt) · [conformance.yml](.github/workflows/conformance.yml) |
| npm, Yarn and pnpm | JavaScript, TypeScript, JSX, TSX, Vue, Svelte | Jest, Vitest, package test, tsc --noEmit | test file or package | Current stable Node.js; Jest 29–30; Vitest 2–4 | [node](conformance/cli-fixtures/node) · [CliAdapterConformanceTest.kt](core/src/test/kotlin/com/aspix2k/affected/build/CliAdapterConformanceTest.kt) · [conformance.yml](.github/workflows/conformance.yml) |
| .NET | C#, F#, Visual Basic, Razor | VSTest with xUnit, NUnit, MSTest, dotnet build | test or project | .NET SDK 8–10 | [dotnet](conformance/cli-fixtures/dotnet) · [DotnetCliConformanceTest.kt](core/src/test/kotlin/com/aspix2k/affected/build/DotnetCliConformanceTest.kt) · [conformance.yml](.github/workflows/conformance.yml) |
| Python projects | Python | pytest, mypy | test file or package | Current stable Python; pytest 8–9 | [python](conformance/cli-fixtures/python) · [CliAdapterConformanceTest.kt](core/src/test/kotlin/com/aspix2k/affected/build/CliAdapterConformanceTest.kt) · [conformance.yml](.github/workflows/conformance.yml) |
| Composer | PHP | PHPUnit, Pest, PHPStan | test class or test file or package | PHP 8.2–8.5; PHPUnit 11.5, 12.5, 13.2 and 13.3; Pest 5.1.1 with PHPUnit 13.3 on PHP 8.4–8.5 | [composer](conformance/cli-fixtures/composer) · [pest](conformance/cli-fixtures/pest) · [CliAdapterConformanceTest.kt](core/src/test/kotlin/com/aspix2k/affected/build/CliAdapterConformanceTest.kt) · [CliPestConformanceTest.kt](core/src/test/kotlin/com/aspix2k/affected/build/CliPestConformanceTest.kt) · [conformance.yml](.github/workflows/conformance.yml) |
| Bundler | Ruby | RSpec, Minitest, Test::Unit | gem | Ruby 4; lockless RSpec or rubygems.org direct locks tested at RSpec 3.13.2, Minitest 6.0.6 and Test::Unit 3.7.8 | [ruby](conformance/cli-fixtures/ruby) · [CliAdapterConformanceTest.kt](core/src/test/kotlin/com/aspix2k/affected/build/CliAdapterConformanceTest.kt) · [conformance.yml](.github/workflows/conformance.yml) |
| CMake | C, C++ | CMake build, CTest | test or target | CMake 4.1+ and CTest 3.29+; tested with CMake 4.4.2 | [cmake](conformance/cli-fixtures/cmake) · [CliAdapterConformanceTest.kt](core/src/test/kotlin/com/aspix2k/affected/build/CliAdapterConformanceTest.kt) · [conformance.yml](.github/workflows/conformance.yml) |
| sbt | Scala, Java, Kotlin, Groovy | sbt test, sbt compile | project | sbt 1.12.x; Scala 3.3 LTS | [sbt](conformance/cli-fixtures/sbt) · [sbt-multi](conformance/cli-fixtures/sbt-multi) · [CliAdapterConformanceTest.kt](core/src/test/kotlin/com/aspix2k/affected/build/CliAdapterConformanceTest.kt) · [conformance.yml](.github/workflows/conformance.yml) |
| Ant | Java, Kotlin | ant test, ant compile | project | Apache Ant 1.10.x; tested with 1.10.17; project-level targets until imported graphs are proven | [ant](conformance/cli-fixtures/ant) · [CliAntConformanceTest.kt](core/src/test/kotlin/com/aspix2k/affected/build/CliAntConformanceTest.kt) · [conformance.yml](.github/workflows/conformance.yml) |
| Kotlin Toolchain | Kotlin, Java | kotlin test, kotlin build | project | Kotlin Toolchain 0.11.x Alpha; project-level until the CLI contract is stable | [kotlin-toolchain](conformance/cli-fixtures/kotlin-toolchain) · [CliKotlinToolchainConformanceTest.kt](core/src/test/kotlin/com/aspix2k/affected/build/CliKotlinToolchainConformanceTest.kt) · [conformance.yml](.github/workflows/conformance.yml) |
| Bazel | Java, Kotlin, C, C++, Python, Go, Rust, Starlark | bazel test, bazel build | project | Bazel 8.x via Bazelisk 1.29.0; workspace-level //... until target ownership is proven | [bazel](conformance/cli-fixtures/bazel) · [CliBazelConformanceTest.kt](core/src/test/kotlin/com/aspix2k/affected/build/CliBazelConformanceTest.kt) · [conformance.yml](.github/workflows/conformance.yml) |

The smaller selection unit is used only when the adapter proves a complete
relationship. Otherwise **Affected** keeps the larger unit shown in the same row.

## Operating systems

| Operating system | Evidence level | Evidence |
|---|---|---|
| Linux | Native fixtures | [cli-fixtures](conformance/cli-fixtures) · [GradleInjectionTest.java](collector/src/test/java/com/aspix2k/affected/collector/GradleInjectionTest.java) · [conformance.yml](.github/workflows/conformance.yml) |
| macOS | Cross-platform contracts | [CrossPlatformPathTest.kt](core/src/test/kotlin/com/aspix2k/affected/CrossPlatformPathTest.kt) · [GradleInjectionTest.java](collector/src/test/java/com/aspix2k/affected/collector/GradleInjectionTest.java) · [conformance.yml](.github/workflows/conformance.yml) |
| Windows | Cross-platform contracts | [CrossPlatformPathTest.kt](core/src/test/kotlin/com/aspix2k/affected/CrossPlatformPathTest.kt) · [GradleInjectionTest.java](collector/src/test/java/com/aspix2k/affected/collector/GradleInjectionTest.java) · [conformance.yml](.github/workflows/conformance.yml) |

## Explicit exclusions

| Product | Reason | Last reviewed |
|---|---|---:|
| Aqua | JetBrains discontinued the standalone product before the supported platform range. | 2026-08-12 |
| AppCode | JetBrains discontinued the product before the supported platform range. | 2026-08-12 |

## JetBrains MCP Server

The optional JetBrains MCP Server plugin exposes the same analysis snapshot and
exclusive run lease as the toolbar. Read tools never write project state.

| Operation | MCP tool | Kind | UI counterpart |
|---|---|---|---|
| inspect-modules | `affected_modules` | read | `com.aspix2k.affected.Modules` |
| verification-plan | `affected_verification_plan` | read | MCP-only |
| changed-files | `affected_changed_files` | read | MCP-only |
| run-verification | `affected_run_verification` | mutating | `com.aspix2k.affected.Run`, `com.aspix2k.affected.RunBeforeCommit`, `com.aspix2k.affected.RunBeforePush` |
| run-named-task | `affected_run_task` | mutating | `com.aspix2k.affected.Detekt`, `com.aspix2k.affected.Lint`, `com.aspix2k.affected.Coverage` |
| stop-owned | `affected_stop` | mutating | MCP-only |
| status | `affected_status` | read | MCP-only |
| available-tasks | `affected_available_tasks` | read | MCP-only |
| configure | `affected_configure` | mutating | `com.aspix2k.affected.CheckConsumers`, `com.aspix2k.affected.RunBeforeCommit`, `com.aspix2k.affected.RunBeforePush`, `com.aspix2k.affected.AnimateWhileRunning`; MCP-only fields: `baseBranch` |


## Keep the matrix current

Run `python3 scripts/support_matrix.py --check`. Use `--write` after an
intentional matrix change, then add or update the public fixture and CI gate in
the same pull request.
