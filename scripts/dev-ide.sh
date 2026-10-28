#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
jdk_home="${JAVA_HOME:-$repo_root/.tools/jdk}"
if [[ ! -x "$jdk_home/bin/javac" ]]; then
  echo 'Run python3 scripts/setup-jdk.py, or set JAVA_HOME to JDK 25.' >&2
  exit 1
fi
if [[ ! -x "$repo_root/.tools/bin/gopls" ]]; then
  echo 'Run scripts/setup-tools.sh first.' >&2
  exit 1
fi
exec env JAVA_HOME="$jdk_home" \
  GRADLE_USER_HOME="${GRADLE_USER_HOME:-$repo_root/.gradle-user-home}" \
  GOFORGE_TOOLS_DIR="$repo_root/.tools/bin" \
  PATH="$jdk_home/bin:$repo_root/.tools/bin:$PATH" \
  "$repo_root/gradlew" --project-dir "$repo_root" runIde "$@"
