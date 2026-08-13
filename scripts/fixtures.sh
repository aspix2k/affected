#!/usr/bin/env bash
# Clones real-world projects to run the analysers against code nobody wrote
# for this plugin. Fixtures live outside git; tests that use them skip
# themselves when a fixture is missing, so CI never depends on the network.
set -uo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
fixtures="$root/fixtures"
mkdir -p "$fixtures"

only=${1:-}

clone() {
  local name=$1 url=$2
  local target="$fixtures/$name"

  if [ -n "$only" ] && [[ "$name" != *"$only"* ]]; then
    return
  fi

  if [ -d "$target/.git" ]; then
    printf 'have     %-22s %s\n' "$name" "$(du -sh "$target" 2>/dev/null | cut -f1)"
    return
  fi

  printf 'cloning  %-22s' "$name"
  if git clone --quiet --depth 1 --filter=blob:none "$url" "$target" 2>/dev/null; then
    printf '%s\n' "$(du -sh "$target" 2>/dev/null | cut -f1)"
  else
    printf 'FAILED\n'
    rm -rf "$target"
  fi
}

# Gradle, Kotlin build scripts, many modules
clone gradle-okhttp        https://github.com/square/okhttp.git
clone gradle-detekt        https://github.com/detekt/detekt.git
clone gradle-junit5        https://github.com/junit-team/junit-framework.git

# Gradle, Android, version catalogs, convention plugins
clone android-nowinandroid https://github.com/android/nowinandroid.git

# Gradle, Groovy build scripts
clone gradle-groovy-retrofit https://github.com/square/retrofit.git

# Gradle, composite build
clone gradle-composite     https://github.com/gradle/native-platform.git

# Maven, multi-module
clone maven-dubbo          https://github.com/apache/dubbo.git
clone maven-commons-lang   https://github.com/apache/commons-lang.git

# Cargo workspaces
clone cargo-serde          https://github.com/serde-rs/serde.git
clone cargo-ripgrep        https://github.com/BurntSushi/ripgrep.git
clone cargo-tokio          https://github.com/tokio-rs/tokio.git

# Go modules
clone go-cobra             https://github.com/spf13/cobra.git
clone go-gin               https://github.com/gin-gonic/gin.git

# npm and pnpm workspaces
clone npm-vite             https://github.com/vitejs/vite.git
clone npm-babel            https://github.com/babel/babel.git

# Python, no module graph at all
clone python-httpx         https://github.com/encode/httpx.git

# .NET, project references
clone dotnet-serilog       https://github.com/serilog/serilog.git

# composer monorepo — packages under a single repository
clone php-symfony          https://github.com/symfony/symfony.git

# Ruby monorepo — a gemspec per component
clone ruby-rails           https://github.com/rails/rails.git

# CMake targets
clone cmake-fmt            https://github.com/fmtlib/fmt.git
clone cmake-spdlog         https://github.com/gabime/spdlog.git

echo
du -sh "$fixtures" 2>/dev/null | awk '{print "total    " $1}'
