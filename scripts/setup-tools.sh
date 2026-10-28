#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
tool_root="$repo_root/.tools/bin"
go_command="${GOFORGE_GO:-$(command -v go || true)}"
if [[ -z "$go_command" || ! -x "$go_command" ]]; then
  echo 'Go 1.26 or newer is required. Set GOFORGE_GO to an existing go executable.' >&2
  exit 1
fi
mkdir -p "$tool_root"
if [[ ! -e "$tool_root/go" ]]; then
  ln -s "$go_command" "$tool_root/go"
fi

# Pinned and compatible with the installed Go 1.26 toolchain.
# Go's normal module checksum verification remains enabled.
env GOBIN="$tool_root" GOTOOLCHAIN=local "$go_command" install -trimpath golang.org/x/tools/gopls@v0.23.0
env GOBIN="$tool_root" GOTOOLCHAIN=local "$go_command" install -trimpath github.com/go-delve/delve/cmd/dlv@v1.27.2
"$tool_root/gopls" version
"$tool_root/dlv" version
echo "Installed project-local tools in $tool_root"
