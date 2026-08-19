# Support

This page is generated from `config/support-matrix.json`. Every supported entry
is tied to public repository evidence and an executable CI gate.

## JetBrains products

| Product | Evidence level | Minimum platform | Home ecosystems | Evidence |
|---|---|---:|---|---|
| IntelliJ IDEA | Product-verified | 2025.3 | Gradle JVM and Android, Maven, sbt, Ant, Kotlin Toolchain | [build.gradle.kts](../build.gradle.kts) · [ci.yml](../.github/workflows/ci.yml) |
| Android Studio | Product-verified | 2025.3 | Gradle JVM and Android | [build.gradle.kts](../build.gradle.kts) · [ci.yml](../.github/workflows/ci.yml) |
| Rider | Platform-compatible | 2025.3 | .NET | [build.gradle.kts](../build.gradle.kts) · [ci.yml](../.github/workflows/ci.yml) |
| GoLand | Platform-compatible | 2025.3 | Go modules | [build.gradle.kts](../build.gradle.kts) · [ci.yml](../.github/workflows/ci.yml) |
| CLion | Platform-compatible | 2025.3 | CMake, Meson, Make, Ninja | [plugin.xml](../src/main/resources/META-INF/plugin.xml) · [ci.yml](../.github/workflows/ci.yml) |
| PyCharm | Platform-compatible | 2025.3 | Python projects | [plugin.xml](../src/main/resources/META-INF/plugin.xml) · [ci.yml](../.github/workflows/ci.yml) |
| WebStorm | Platform-compatible | 2025.3 | npm, Yarn and pnpm, Dart, Flutter | [plugin.xml](../src/main/resources/META-INF/plugin.xml) · [ci.yml](../.github/workflows/ci.yml) |
| PhpStorm | Platform-compatible | 2025.3 | Composer | [plugin.xml](../src/main/resources/META-INF/plugin.xml) · [ci.yml](../.github/workflows/ci.yml) |
| RubyMine | Platform-compatible | 2025.3 | Bundler | [plugin.xml](../src/main/resources/META-INF/plugin.xml) · [ci.yml](../.github/workflows/ci.yml) |
| RustRover | Platform-compatible | 2025.3 | Cargo | [plugin.xml](../src/main/resources/META-INF/plugin.xml) · [ci.yml](../.github/workflows/ci.yml) |
| DataSpell | Platform-compatible | 2025.3 | R, Python projects | [plugin.xml](../src/main/resources/META-INF/plugin.xml) · [ci.yml](../.github/workflows/ci.yml) |

Product-verified entries run a dedicated product gate. Every Platform-compatible
entry runs a static product-specific Plugin Verifier at its exact minimum and
current endpoints, including the declared optional Gradle and Maven descriptors.
Home ecosystems are the native adapters that the product must keep proven at runtime;
Plugin Verifier still does not claim the installed IDE lifecycle.

## Mixed build systems

| Proof | Adapters | Evidence |
|---|---|---|
| cmake-dotnet | CMake, .NET | [mixed-cmake-dotnet](../conformance/cli-fixtures/mixed-cmake-dotnet) · [CliMixedPolyglotConformanceTest.kt](../core/src/test/kotlin/com/aspix2k/affected/build/CliMixedPolyglotConformanceTest.kt) |
| gradle-xcode | Gradle JVM and Android, Xcode | [mixed-gradle-xcode](../conformance/cli-fixtures/mixed-gradle-xcode) · [xcode](../conformance/cli-fixtures/xcode) · [AffectedMixedRunNativeTest.kt](../core/src/test/kotlin/com/aspix2k/affected/build/AffectedMixedRunNativeTest.kt) |
| gradle-node | Gradle JVM and Android, npm, Yarn and pnpm | [mixed-gradle-node](../conformance/cli-fixtures/mixed-gradle-node) · [CliMixedGradleNodeConformanceTest.kt](../core/src/test/kotlin/com/aspix2k/affected/build/CliMixedGradleNodeConformanceTest.kt) |

## Planned coverage

| Product | Goal | Tracking issue | Last reviewed |
|---|---|---|---:|
| DataGrip | Local contracts are in-repo dbt + DuckDB, sqlc compile and Atlas migrate validate; DataGrip UI lifecycle and warehouse SQL stay unclaimed. | [Issue #120](https://github.com/aspix2k/affected/issues/120) | 2026-08-14 |
| JetBrains Gateway | Analysis and build stay on the IDE backend; a proven JetBrains Client frontend skips VFS refresh. Gateway install and update stay unclaimed. | [Issue #127](https://github.com/aspix2k/affected/issues/127) | 2026-08-14 |

## Build systems and test runners

| Ecosystem | Languages and files | Tests and checks | Selection unit | Tested versions | Evidence |
|---|---|---|---|---|---|
| Gradle JVM and Android | Kotlin, Java, Scala, Groovy | JUnit, TestNG, Spock, consumer test compilation | test class or module | Gradle 8–9; JDK 17, 21 and 26; in change-aware Affected test runs, exact KMP target narrowing is retained for proven platform source sets; common sources keep every target, Kotlin/Native stays task-level, and missing/stale JVM baselines or class-set drift keep the whole task; fallbacks report fixed reason codes without paths | [GradleInjectionTest.java](../collector/src/test/java/com/aspix2k/affected/collector/GradleInjectionTest.java) · [gradle-cancellation](../conformance/cli-fixtures/gradle-cancellation) · [gradle-kmp-fallback](../conformance/cli-fixtures/gradle-kmp-fallback) · [mixed-gradle-node](../conformance/cli-fixtures/mixed-gradle-node) · [CliGradleCancellationConformanceTest.kt](../core/src/test/kotlin/com/aspix2k/affected/build/CliGradleCancellationConformanceTest.kt) · [CliGradleSelectionDiagnosticsConformanceTest.kt](../core/src/test/kotlin/com/aspix2k/affected/build/CliGradleSelectionDiagnosticsConformanceTest.kt) · [CliMixedGradleNodeConformanceTest.kt](../core/src/test/kotlin/com/aspix2k/affected/build/CliMixedGradleNodeConformanceTest.kt) · [GradleKmpSourceSetTest.kt](../core/src/test/kotlin/com/aspix2k/affected/build/GradleKmpSourceSetTest.kt) · [TaskPlannerTest.kt](../core/src/test/kotlin/com/aspix2k/affected/TaskPlannerTest.kt) · [conformance.yml](../.github/workflows/conformance.yml) · [ci.yml](../.github/workflows/ci.yml) |
| Maven | Kotlin, Java | Surefire, Failsafe, consumer test compilation | test class or module | Maven 3.9.x; Maven 4 RC keeps the full goal | [MavenInjectionTest.java](../collector/src/test/java/com/aspix2k/affected/collector/MavenInjectionTest.java) · [maven-cancellation](../conformance/cli-fixtures/maven-cancellation) · [CliMavenCancellationConformanceTest.kt](../core/src/test/kotlin/com/aspix2k/affected/build/CliMavenCancellationConformanceTest.kt) · [conformance.yml](../.github/workflows/conformance.yml) |
| Cargo | Rust, TOML | cargo test, cargo nextest run, cargo check --tests | package | Current stable Rust; cargo-nextest 0.9.143–0.9.x | [cargo](../conformance/cli-fixtures/cargo) · [CargoNextestCliAdapterConformanceTest.kt](../core/src/test/kotlin/com/aspix2k/affected/build/CargoNextestCliAdapterConformanceTest.kt) · [conformance.yml](../.github/workflows/conformance.yml) |
| Go modules | Go | go test, go build | package | Current stable Go toolchain | [go](../conformance/cli-fixtures/go) · [CliGoConformanceTest.kt](../core/src/test/kotlin/com/aspix2k/affected/build/CliGoConformanceTest.kt) · [conformance.yml](../.github/workflows/conformance.yml) |
| npm, Yarn and pnpm | JavaScript, TypeScript, JSX, TSX, Vue, Svelte | Jest, Vitest, package test, tsc --noEmit | test file or package | Current stable Node.js; Jest 29–30; Vitest 2–4 | [node](../conformance/cli-fixtures/node) · [mixed-gradle-node](../conformance/cli-fixtures/mixed-gradle-node) · [CliMixedGradleNodeConformanceTest.kt](../core/src/test/kotlin/com/aspix2k/affected/build/CliMixedGradleNodeConformanceTest.kt) · [CliAdapterConformanceTest.kt](../core/src/test/kotlin/com/aspix2k/affected/build/CliAdapterConformanceTest.kt) · [conformance.yml](../.github/workflows/conformance.yml) |
| .NET | C#, F#, Visual Basic, Razor | VSTest with xUnit, NUnit, MSTest, Microsoft Testing Platform with xUnit v3 4, dotnet build | test or test class or project | .NET SDK 8–10 for VSTest; SDK 10.0.400 with xunit.v3 4.0.0 and MTP v2 for standalone projects without ProjectReference or custom extensions, and one public sealed Test/Tests class with an explicit global::Xunit.Fact method in a file-scoped namespace per changed C# file; other metadata or syntax runs the full project | [dotnet](../conformance/cli-fixtures/dotnet) · [dotnet-mtp-xunit4](../conformance/cli-fixtures/dotnet-mtp-xunit4) · [mixed-cmake-dotnet](../conformance/cli-fixtures/mixed-cmake-dotnet) · [CliMixedPolyglotConformanceTest.kt](../core/src/test/kotlin/com/aspix2k/affected/build/CliMixedPolyglotConformanceTest.kt) · [DotnetCliConformanceTest.kt](../core/src/test/kotlin/com/aspix2k/affected/build/DotnetCliConformanceTest.kt) · [DotnetMtpCliConformanceTest.kt](../core/src/test/kotlin/com/aspix2k/affected/build/DotnetMtpCliConformanceTest.kt) · [conformance.yml](../.github/workflows/conformance.yml) |
| Python projects | Python | pytest, unittest, mypy | test file or package | Current stable Python; pytest 8–9; standard-library unittest exact files only when every selected module contributes owned tests; zero-test, import, load_tests, or content drift widens to bounded package discovery in the same process, while unsafe layouts or incomplete runner discovery fail visibly | [python](../conformance/cli-fixtures/python) · [unittest](../conformance/cli-fixtures/unittest) · [CliAdapterConformanceTest.kt](../core/src/test/kotlin/com/aspix2k/affected/build/CliAdapterConformanceTest.kt) · [CliUnittestConformanceTest.kt](../core/src/test/kotlin/com/aspix2k/affected/build/CliUnittestConformanceTest.kt) · [conformance.yml](../.github/workflows/conformance.yml) |
| Composer | PHP | PHPUnit, Pest, PHPStan | test class or test file or package | PHP 8.2–8.5; PHPUnit 11.5, 12.5, 13.2 and 13.3; Pest 5.1.1 with PHPUnit 13.3 on PHP 8.4–8.5 | [composer](../conformance/cli-fixtures/composer) · [pest](../conformance/cli-fixtures/pest) · [CliAdapterConformanceTest.kt](../core/src/test/kotlin/com/aspix2k/affected/build/CliAdapterConformanceTest.kt) · [CliPestConformanceTest.kt](../core/src/test/kotlin/com/aspix2k/affected/build/CliPestConformanceTest.kt) · [conformance.yml](../.github/workflows/conformance.yml) |
| Bundler | Ruby | RSpec, Minitest, Test::Unit | gem | Ruby 4; lockless RSpec or rubygems.org direct locks tested at RSpec 3.13.2, Minitest 6.0.6 and Test::Unit 3.7.8 | [ruby](../conformance/cli-fixtures/ruby) · [CliAdapterConformanceTest.kt](../core/src/test/kotlin/com/aspix2k/affected/build/CliAdapterConformanceTest.kt) · [conformance.yml](../.github/workflows/conformance.yml) |
| CMake | C, C++ | CMake build, CTest | test or target | CMake 4.1+ and CTest 3.29+; tested with CMake 4.4.2 | [cmake](../conformance/cli-fixtures/cmake) · [mixed-cmake-dotnet](../conformance/cli-fixtures/mixed-cmake-dotnet) · [CliMixedPolyglotConformanceTest.kt](../core/src/test/kotlin/com/aspix2k/affected/build/CliMixedPolyglotConformanceTest.kt) · [CliAdapterConformanceTest.kt](../core/src/test/kotlin/com/aspix2k/affected/build/CliAdapterConformanceTest.kt) · [conformance.yml](../.github/workflows/conformance.yml) |
| sbt | Scala, Java, Kotlin, Groovy | sbt test, sbt compile | project | sbt 1.12.x; Scala 3.3 LTS | [sbt](../conformance/cli-fixtures/sbt) · [sbt-multi](../conformance/cli-fixtures/sbt-multi) · [CliAdapterConformanceTest.kt](../core/src/test/kotlin/com/aspix2k/affected/build/CliAdapterConformanceTest.kt) · [conformance.yml](../.github/workflows/conformance.yml) |
| Ant | Java, Kotlin | ant test, ant compile | project | Apache Ant 1.10.x; tested with 1.10.17; JUnit/TestNG task targets run when test/junit names are absent; generate/codegen runs before test unless depends proves it; static properties expand imports, unresolved properties keep ant test; dynamic antcall/ant/if/unless keep ant test | [ant](../conformance/cli-fixtures/ant) · [ant-imports](../conformance/cli-fixtures/ant-imports) · [ant-junit-task](../conformance/cli-fixtures/ant-junit-task) · [ant-generated](../conformance/cli-fixtures/ant-generated) · [ant-properties](../conformance/cli-fixtures/ant-properties) · [CliAntConformanceTest.kt](../core/src/test/kotlin/com/aspix2k/affected/build/CliAntConformanceTest.kt) · [conformance.yml](../.github/workflows/conformance.yml) |
| Kotlin Toolchain | Kotlin, Java | kotlin test, kotlin build | module or project | Kotlin Toolchain 0.11.x Alpha; named modules use -m and proven @platform dirs use -p when the wrapper pins 0.11.x; common or unproved keep the broader command | [kotlin-toolchain](../conformance/cli-fixtures/kotlin-toolchain) · [kotlin-toolchain-multi](../conformance/cli-fixtures/kotlin-toolchain-multi) · [CliKotlinToolchainModuleConformanceTest.kt](../core/src/test/kotlin/com/aspix2k/affected/build/CliKotlinToolchainModuleConformanceTest.kt) · [CliKotlinToolchainConformanceTest.kt](../core/src/test/kotlin/com/aspix2k/affected/build/CliKotlinToolchainConformanceTest.kt) · [conformance.yml](../.github/workflows/conformance.yml) |
| Bazel | Java, Kotlin, C, C++, Python, Go, Rust, Starlark | bazel test, bazel build | package or project | Bazel 8.x via Bazelisk 1.29.0; proven packages use //pkg:all, BUILD/MODULE changes keep //... | [bazel](../conformance/cli-fixtures/bazel) · [bazel-packages](../conformance/cli-fixtures/bazel-packages) · [CliBazelPackageConformanceTest.kt](../core/src/test/kotlin/com/aspix2k/affected/build/CliBazelPackageConformanceTest.kt) · [CliBazelConformanceTest.kt](../core/src/test/kotlin/com/aspix2k/affected/build/CliBazelConformanceTest.kt) · [conformance.yml](../.github/workflows/conformance.yml) |
| Dart | Dart | dart test, dart analyze | package or project | Dart 3.13.x; proven workspace packages use dart test <pkg>/test; pubspec, .dart_tool and generated files outside a member keep the workspace command; build.yaml or build_runner prepends dart run build_runner build | [dart](../conformance/cli-fixtures/dart) · [dart-workspace](../conformance/cli-fixtures/dart-workspace) · [CliDartConformanceTest.kt](../core/src/test/kotlin/com/aspix2k/affected/build/CliDartConformanceTest.kt) · [CliDartWorkspaceConformanceTest.kt](../core/src/test/kotlin/com/aspix2k/affected/build/CliDartWorkspaceConformanceTest.kt) · [conformance.yml](../.github/workflows/conformance.yml) |
| Flutter | Dart | flutter test, flutter analyze | project | Flutter 3.47.x; build.yaml or build_runner prepends dart run build_runner build; generated-file ownership stays unclaimed | [flutter](../conformance/cli-fixtures/flutter) · [CliFlutterConformanceTest.kt](../core/src/test/kotlin/com/aspix2k/affected/build/CliFlutterConformanceTest.kt) · [conformance.yml](../.github/workflows/conformance.yml) |
| Meson | C, C++ | meson test, meson compile | project | Meson 1.x with Ninja; in-tree subprojects and meson-info tests merge, unreadable introspection keeps meson test | [meson](../conformance/cli-fixtures/meson) · [meson-subprojects](../conformance/cli-fixtures/meson-subprojects) · [CliMesonConformanceTest.kt](../core/src/test/kotlin/com/aspix2k/affected/build/CliMesonConformanceTest.kt) · [conformance.yml](../.github/workflows/conformance.yml) |
| Make | C, C++ | make test, make check, make | project | GNU Make; static include files merge targets, unproved includes keep make test | [make](../conformance/cli-fixtures/make) · [make-includes](../conformance/cli-fixtures/make-includes) · [CliMakeConformanceTest.kt](../core/src/test/kotlin/com/aspix2k/affected/build/CliMakeConformanceTest.kt) · [conformance.yml](../.github/workflows/conformance.yml) |
| Ninja | C, C++ | ninja test, ninja check, ninja | project | Ninja as an execution backend; project-level only, no inferred source graph | [ninja](../conformance/cli-fixtures/ninja) · [CliNinjaConformanceTest.kt](../core/src/test/kotlin/com/aspix2k/affected/build/CliNinjaConformanceTest.kt) · [conformance.yml](../.github/workflows/conformance.yml) |
| R | R | testthat::test_local, testthat::test_dir, R CMD check | test file or project | R 4.x with testthat 3.x; source packages use exact or full test_local when testthat is present and an isolated R CMD check without manuals or built vignettes otherwise; renv and R Markdown execution stay unproven | [r](../conformance/cli-fixtures/r) · [r-check](../conformance/cli-fixtures/r-check) · [CliRConformanceTest.kt](../core/src/test/kotlin/com/aspix2k/affected/build/CliRConformanceTest.kt) · [conformance.yml](../.github/workflows/conformance.yml) |
| Pants | Python, Rust, Go, Java, Kotlin | pants test, pants check | project | Pants 2.x; project-level until target selection and native pants execution are proven | [pants](../conformance/cli-fixtures/pants) · [PantsCommandTest.kt](../core/src/test/kotlin/com/aspix2k/affected/build/PantsCommandTest.kt) · [ci.yml](../.github/workflows/ci.yml) |
| Buck2 | Starlark, Python, Rust, Go, Java, Kotlin | buck2 test, buck2 build | project | Buck2; static cells merge content roots, unproved or missing keep the project root | [buck2](../conformance/cli-fixtures/buck2) · [Buck2CommandTest.kt](../core/src/test/kotlin/com/aspix2k/affected/build/Buck2CommandTest.kt) · [ci.yml](../.github/workflows/ci.yml) |
| Swift | Swift, Objective-C | swift test, swift build | project | SwiftPM; project-level until Xcode schemes, target selection and native Swift execution are proven | [swift](../conformance/cli-fixtures/swift) · [SwiftCommandTest.kt](../core/src/test/kotlin/com/aspix2k/affected/build/SwiftCommandTest.kt) · [ci.yml](../.github/workflows/ci.yml) |
| Xcode | Swift, Objective-C | xcodebuild test, xcodebuild build | project | the default Xcode selected by macos-latest; one proven testable scheme runs xcodebuild test, build-only and empty-TestAction schemes run a signing-independent xcodebuild build, and incomplete or conflicting metadata stays fail-closed | [xcode](../conformance/cli-fixtures/xcode) · [XcodeNativeTest.kt](../core/src/test/kotlin/com/aspix2k/affected/build/XcodeNativeTest.kt) · [conformance.yml](../.github/workflows/conformance.yml) |
| dbt | SQL, YAML | dbt test, dbt compile | project | dbt; in-repo DuckDB profiles only, warehouse and MotherDuck stay off, native dbt execution stays unclaimed | [dbt](../conformance/cli-fixtures/dbt) · [DbtCommandTest.kt](../core/src/test/kotlin/com/aspix2k/affected/build/DbtCommandTest.kt) · [ci.yml](../.github/workflows/ci.yml) |
| sqlc | SQL, YAML, JSON | sqlc compile | project | sqlc 1.31.1; local schema and query files only, database URIs and cloud stay off | [sqlc](../conformance/cli-fixtures/sqlc) · [CliSqlcConformanceTest.kt](../core/src/test/kotlin/com/aspix2k/affected/build/CliSqlcConformanceTest.kt) · [conformance.yml](../.github/workflows/conformance.yml) |
| Atlas | SQL, HCL | atlas migrate validate | project | Atlas; local atlas.hcl without database URLs, native atlas execution stays unclaimed | [atlas](../conformance/cli-fixtures/atlas) · [AtlasCommandTest.kt](../core/src/test/kotlin/com/aspix2k/affected/build/AtlasCommandTest.kt) · [ci.yml](../.github/workflows/ci.yml) |

The smaller selection unit is used only when the adapter proves a complete
relationship. Otherwise **Affected** keeps the larger unit shown in the same row.

## Operating systems

| Operating system | Evidence level | Evidence |
|---|---|---|
| Linux | Native fixtures | [cli-fixtures](../conformance/cli-fixtures) · [SequentialProcessHandlerTest.kt](../core/src/test/kotlin/com/aspix2k/affected/build/SequentialProcessHandlerTest.kt) · [SequentialProcessCancellationTest.kt](../core/src/test/kotlin/com/aspix2k/affected/build/SequentialProcessCancellationTest.kt) · [ContainedProcessTest.kt](../core/src/test/kotlin/com/aspix2k/affected/build/ContainedProcessTest.kt) · [CliGradleCancellationConformanceTest.kt](../core/src/test/kotlin/com/aspix2k/affected/build/CliGradleCancellationConformanceTest.kt) · [CliGradleSelectionDiagnosticsConformanceTest.kt](../core/src/test/kotlin/com/aspix2k/affected/build/CliGradleSelectionDiagnosticsConformanceTest.kt) · [GradleInjectionTest.java](../collector/src/test/java/com/aspix2k/affected/collector/GradleInjectionTest.java) · [conformance.yml](../.github/workflows/conformance.yml) · [ci.yml](../.github/workflows/ci.yml) |
| macOS | Cross-platform contracts | [CrossPlatformPathTest.kt](../core/src/test/kotlin/com/aspix2k/affected/CrossPlatformPathTest.kt) · [SequentialProcessCancellationTest.kt](../core/src/test/kotlin/com/aspix2k/affected/build/SequentialProcessCancellationTest.kt) · [ContainedProcessTest.kt](../core/src/test/kotlin/com/aspix2k/affected/build/ContainedProcessTest.kt) · [GradleInjectionTest.java](../collector/src/test/java/com/aspix2k/affected/collector/GradleInjectionTest.java) · [conformance.yml](../.github/workflows/conformance.yml) |
| Windows | Cross-platform contracts | [CrossPlatformPathTest.kt](../core/src/test/kotlin/com/aspix2k/affected/CrossPlatformPathTest.kt) · [CliUnittestWindowsJunctionConformanceTest.kt](../core/src/test/kotlin/com/aspix2k/affected/build/CliUnittestWindowsJunctionConformanceTest.kt) · [SequentialProcessCancellationTest.kt](../core/src/test/kotlin/com/aspix2k/affected/build/SequentialProcessCancellationTest.kt) · [ContainedProcessTest.kt](../core/src/test/kotlin/com/aspix2k/affected/build/ContainedProcessTest.kt) · [GradleInjectionTest.java](../collector/src/test/java/com/aspix2k/affected/collector/GradleInjectionTest.java) · [conformance.yml](../.github/workflows/conformance.yml) |

## Explicit exclusions

| Product | Reason | Last reviewed |
|---|---|---:|
| Aqua | JetBrains discontinued the standalone product before the supported platform range. | 2026-08-12 |
| AppCode | JetBrains discontinued the product before the supported platform range. | 2026-08-12 |
| MPS | No zero-config public MPS test CLI; generated Ant build.xml stays off this adapter. | 2026-08-14 |

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
