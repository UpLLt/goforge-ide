# GoForge

**An independent Go IDE with no GoForge account, activation, subscription, or feature paywall.**

[简体中文](README.zh-CN.md) · [Install](docs/INSTALL.md) · [Workflows](docs/USAGE.md) · [Known limitations](docs/KNOWN-LIMITATIONS.md) · [Third-party notices](THIRD-PARTY-NOTICES.md)

GoForge combines original GoForge code with the public IntelliJ Community platform, LSP4IJ, gopls, and Delve. It is independent of JetBrains and does not contain the proprietary GoLand plugin.

**Internal development build:** practical workflows are implemented, but complete GoLand feature parity is not established. **Public source and binary publication must wait until full alignment is verified.** Preparing archives or choosing a license does not satisfy that release gate. Optional services such as an SSH server, database, Docker daemon, or Git host still require their own normal connection settings and authorization.

## Start here

1. For development, build locally or use the prepared internal platform archive. A public **Releases** download is not available under the current release gate. Follow the [installation guide](docs/INSTALL.md), including the platform's verification status.
2. Select **New Go Project…**, or open a directory containing `go.mod`.
3. Choose a generated **Run**, **Test**, or **Debug** configuration. Set breakpoints in the gutter and inspect variables in the Debug tool window.
4. Use **Settings → GoForge** for tool paths and **Settings → Go Project** for build tags, GOOS, and GOARCH.

| Platform | Distribution and verification |
| --- | --- |
| macOS, Apple Silicon | Development `.app`/ZIP assembled; macOS 13.5+. The latest 1caa build has passed automated checks; its package and native acceptance are pending. Historical 7bff results and defects remain separate. Historical scoped passes remain separate. Ad-hoc signature, without Apple Developer ID notarization. |
| Windows x64 | Portable ZIP assembled and structural checks passed. Native Windows execution has not been verified. See [Windows installation](docs/INSTALL.md#windows-x64). |
| Linux / other architectures | No verified standalone distribution currently provided. |

Both prepared platform packages bundle a runtime, Go SDK, gopls, Delve, Node, Dev Containers CLI, and JDBC drivers. Docker, kubectl, Git, OpenSSH, Cgo's C compiler, and macOS debugger prerequisites are separate system tools. Package checksums and the distinction between preparation and publication are listed in the [installation guide](docs/INSTALL.md#prepared-package-identities).

## Current workflows

| Area | Implemented scope |
| --- | --- |
| Go editor | gopls completion, diagnostics, navigation, references, rename, formatting, and available code actions. |
| Projects & execution | Application/library modules; run, build, and test; test tree; editable Go command configurations. |
| Refactoring | Project-wide previews and undoable apply; extraction to a new file; guarded cross-package parameter reorder/removal. Unsafe or unproven call transformations and unsupported commands/resource operations are rejected. |
| Debugging | Local Delve; SSH remote package/test/executable launch and PID attach, including reviewed project-root `go.work` modules; breakpoints, stacks, variables, evaluation, stepping, and stop. |
| Coverage & profiling | Single-module coverage and source coloring; CPU/memory collection and pprof views. |
| Database | SQLite, PostgreSQL, MySQL/MariaDB; SQL, transactions, primary-key row edits, CSV, basic schema completion, estimated Explain. |
| HTTP | `.http`/`.rest` collections, environments, sequential runs, assertions, cancellation, and redacted summaries. |
| Containers | Docker/Compose, Kubernetes resource tools and loopback forwarding, limited Dev Container workflows. |
| Remote workspace | Strict-host-key SSH tasks and reviewed transfers; opt-in gopls snapshot analysis with unsaved-document overlays. |

See [workflows](docs/USAGE.md) and [concrete gaps](docs/KNOWN-LIMITATIONS.md). Integrating a tool does not establish complete compatibility with another IDE's features or plugins.

## Build from source

Required: **JDK 25**, **Python 3**, network access for pinned dependencies, and **Go 1.26+** for backend tools. Java output targets 21. The platform is pinned to Community **2025.2.6.3 / 252.28539.97**, with LSP4IJ **0.21.0**.

```sh
# macOS Apple Silicon only: isolated development JDK.
python3 scripts/setup-jdk.py
export JAVA_HOME="$PWD/.tools/jdk"

# Uses an existing Go SDK; installs only checkout-local tools.
./scripts/setup-tools.sh
GRADLE_USER_HOME="$PWD/.gradle-user-home" ./gradlew \
  -PgoforgeSandboxDirectory=build/verification-sandbox \
  test buildPlugin verifyPluginStructure
./scripts/dev-ide.sh
```

On other systems, supply JDK 25 through `JAVA_HOME`. Windows uses `gradlew.bat`; see the [development instructions](docs/INSTALL.md#building-on-windows). The development sandbox is distinct from a standalone application. [macOS packaging](packaging/macos/README.md) documents native assembly.

## Verification status

The frozen **1caa** unified build on the macOS development host passed **449 tests** (313 component + 136 platform) across 72 JUnit reports, with no failures, errors, or skips. Plugin packaging and structure checks passed. The plugin SHA-256 is `1caad6d85ea78419e17fbb0abcd15b16fba8667d803f34d3518da59441ee1038`. These are macOS-host results, not counts of completed features or proof of Windows compatibility. New package and native UI acceptance remain pending.

Guarded cross-package parameter reorder/removal passed 21 real gopls/AST/planner cases. Reviewed root-`go.work` debugging passed generated Linux SSH/Go/Delve protocol checks. Historical **7bff** native acceptance covered bounded signature workflows and remote package/test debugging with cross-module breakpoints, values, stepping and exit. It also exposed an essential-plugin toggle bypass, an LSP explorer tree lifecycle error and raw DAP transport text in the remote console. The new build includes lifecycle protection and the transport-aware console repair, with separate pinned platform/dependency patch regressions. Their new native acceptance remains pending; the older failures are retained.

Historical `d3d7` native checks passed scoped extraction/Undo/Redo and local run/test/debug, and exposed a Plugins Settings search initialization error. These earlier passes are not reassigned to the new artifact. Windows archives have passed cross-assembly/static checks; Windows native execution is pending. The repository `UpLLt/goforge-ide` is private and contains the Windows workflow for internal validation. No public repository or Release has been published; `publish` defaults to false and the full-alignment release gate remains closed. See the [verification records](docs/INSTALL.md#prepared-package-identities); private machine logs are excluded from source exports.

## Licenses

Original GoForge code and documentation are licensed under [Apache-2.0](LICENSE), with attribution in [NOTICE](NOTICE). Identified third-party material retains its own license: the LSP4IJ metadata patch is EPL-2.0, and bundled runtimes and drivers have additional terms. See [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md). The project license does not grant rights on behalf of those upstream owners or change their terms. Preserve all applicable notices and corresponding-source materials when distributing.

The full-alignment requirement is the maintainer's publication gate, not an additional restriction added to the Apache license.
