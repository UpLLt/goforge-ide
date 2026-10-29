# GoForge

**An independent Go IDE with no GoForge account, activation, subscription, or feature paywall.**

[简体中文](README.zh-CN.md) · [Install](docs/INSTALL.md) · [Workflows](docs/USAGE.md) · [Known limitations](docs/KNOWN-LIMITATIONS.md) · [Third-party notices](THIRD-PARTY-NOTICES.md)

GoForge combines original GoForge code with the public IntelliJ Community platform, LSP4IJ, gopls, and Delve. It is independent of JetBrains and does not contain the proprietary GoLand plugin.

**Internal development build:** practical workflows are implemented, but complete GoLand feature parity is not established. **Public source and binary publication must wait until full alignment is verified.** Preparing archives or choosing a license does not satisfy that release gate. Optional services such as an SSH server, database, Docker daemon, or Git host still require their own normal connection settings and authorization.

## Start here

1. For development, build locally or use the prepared internal platform archive. A public **Releases** download is not available under the current release gate. Follow the [installation guide](docs/INSTALL.md), including the platform's verification status.
2. Select **New Go Project…**, or open a directory containing `go.mod`.
3. Choose a generated **Run**, **Test**, or **Debug** configuration. For a single test, save the file and use **Run** inside a top-level Test/Example; select runtime subtests from the completed test tree. See the [test workflow](docs/USAGE.md#run-named-go-tests--运行指定-go-测试).
4. Use **Settings → GoForge** for tool paths and **Settings → Go Project** for build tags, GOOS, and GOARCH.

| Platform | Distribution and verification |
| --- | --- |
| macOS, Apple Silicon | macOS 13.5+. dd87 archive audited; bounded Add Parameter native workflow passed. Earlier bdf5 Benchmark Debug passed with a generated-binary Stop cleanup gap; full GUI acceptance is pending. Ad-hoc signing; no Developer ID notarization. |
| Windows x64 | Run 10 product tests, packaging and nine native checks passed; interactive GUI acceptance is pending. Unsigned launcher. See [installation status](docs/INSTALL.md#windows-x64). |
| Linux / other architectures | No verified standalone distribution currently provided. |

Prepared internal platform packages bundle a runtime, Go SDK, gopls, Delve, Node, Dev Containers CLI, and JDBC drivers. Docker, kubectl, Git, OpenSSH, Cgo's C compiler, and macOS debugger prerequisites are separate system tools. Package checksums and the distinction between preparation and publication are listed in the [installation guide](docs/INSTALL.md#prepared-package-identities).

## Current workflows

Current source supports [save/manual import optimization](docs/USAGE.md#organize-imports-on-save--保存时整理导入), [selected top-level Benchmark Run and console-node Debug](docs/USAGE.md#run-one-benchmark--运行单个基准测试), [exact remote SDK source navigation](docs/USAGE.md#remote-standard-library-sources--远端标准库源码), and guarded cross-file refactoring. Source tests, packages and native acceptance have separate scopes; see verification below and the [workflow guide](docs/USAGE.md).

| Area | Implemented scope |
| --- | --- |
| Go editor | Local line/block comments, paired braces, bounded indentation and raw-string Enter; gopls completion, diagnostics, navigation, references, rename and available code actions; Go-only formatting on save with awaited completion, plus manual Reformat. Independent imports before save formatting, plus manual Optimize Imports and Go selection in the save type chooser; Platform tests passed on macOS and Windows; bounded macOS native checks passed, Windows editor GUI acceptance pending. |
| Projects & execution | Application/library modules; run/build/test; top-level Test/Example selection, runtime subtest rerun, containing-test navigation and single-package failed top-level rerun; local termination preserves completed results and interrupts unfinished nodes. One top-level Benchmark Run, completed-node Debug and source navigation, plus Debug Go Benchmark at Caret for one iteration from a saved editor declaration; direct subbenchmark selection remains unavailable. The new entry/navigation are outside the verified Full 13/Run 10 baseline. |
| Refactoring | Project-wide previews and undoable apply; extraction to a new file; guarded cross-package parameter reorder/removal; a separate operation adds one scalar parameter with a literal default to verified project direct calls, including bounded generic free-function calls. Ordinary imports use a standalone-module offline compile check. Type parameters, constraints and argument order are preserved. Current source also provides a compile-checked scalar parameter/single-result type change within one captured module. File-list changes invalidate previews; unsupported call shapes and resource operations are rejected. |
| Debugging | Local Delve, including selected Test/Example/subtree debugging with supported flags; SSH remote package/test/executable launch and PID attach, including reviewed project-root `go.work` modules; breakpoints, stacks, variables, evaluation, stepping, and stop. |
| Coverage & profiling | Single-module coverage and source coloring; CPU/memory collection and pprof views. |
| Database | SQLite, PostgreSQL, MySQL/MariaDB; SQL, transactions, primary-key row edits, CSV, basic schema completion, estimated Explain. |
| HTTP | `.http`/`.rest` collections, environments, sequential runs, assertions, cancellation, and redacted summaries. |
| Containers | Docker/Compose, Kubernetes resource tools and loopback forwarding, limited Dev Container workflows. |
| Remote workspace | Strict-host-key SSH tasks and reviewed transfers; opt-in gopls snapshot analysis with unsaved-document overlays, including reviewed contained `go.work` modules; exact remote `GOROOT/src` Definition/Type Definition opens read-only session sources. Arbitrary dependency sources remain unsupported. |

See [local test Stop behavior](docs/USAGE.md#stop-a-local-test-run--停止本地测试) and [adding one literal-default parameter to verified direct calls](docs/USAGE.md#add-one-parameter--新增一个参数) for the supported workflow and boundaries.

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

**Current source — Full 17 (6425).** Passed **800/800 macOS-host JUnit tests** (463 component + 337 platform; 105 XML reports, no failures/errors/skips), plugin build and structure verification, with 358 frozen inputs. This source adds default local debug-output cleanup, navigation from completed top-level Benchmark results, and **Debug Go Benchmark at Caret**. Full 14–16 remain recorded failed integration runs. The existing dd87 Mac package and Windows Run 10 below belong to Full 13; new packaged macOS GUI and Windows acceptance remain pending.

The current cleanup change gives default local Delve launches in `debug`/`test` mode a separate, session-owned temporary output directory. After the adapter and observed target processes exit, it removes only that session’s generated executable and empty directory. If exit or ownership cannot be established, it retains the output and reports a warning. Explicit `output` paths, `exec`, attach and remote sessions retain their separate behavior. The change is not included in the verified Full 13/dd87 packages or Run 10 evidence; new macOS GUI and Windows validation remain pending.

**Packaged baseline — Full 13 (dd87)** passed **756/756 macOS-host JUnit tests** (433 component + 323 platform; 99 XML reports, no failures/errors/skips), plugin build and structure verification, with 345 frozen build inputs. The same reviewed source passed Windows Run 10 with 755 product tests and one existing POSIX-shell skip. Its Mac archive is independently audited; ordinary-import Add Parameter has bounded macOS native evidence. Full GUI and complete GoLand parity remain unverified.

The audited dd87 Mac package passed a bounded ordinary-import Add Parameter workflow: preview/cancel, three-file Apply and Save, Undo/Redo, bundled Go tests after saved edits, restored fixture bytes, normal exit and signature recheck. The fixture uses one standalone module, standard-library imports and an `int` literal default; four platform warnings remain recorded. Earlier bdf5 native evidence covers manual imports, the visible Go type entry, remote SDK read-only navigation/Stop, and selected Benchmark Run/Debug. Benchmark Debug reached breakpoint/evaluation/step/exit; a separate Stop removed adapter/target processes but left a generated `__debug_bin…` file. The cleanup repair has Full 17 macOS-host tests; packaged native and full GUI acceptance remain pending.

Windows [Run 10](https://github.com/UpLLt/goforge-ide/actions/runs/40823363745) passed on the reviewed Full 13 source: **43/43 preflight checks**, **756 product tests = 755 passed + one existing POSIX-shell skip** (99 XML, no failures/errors), packaging and **nine native checks**. The relocated launcher/bundled JBR, Unicode and space paths, exact isolated configuration paths, tool versions, Go run/test, gopls and Delve normal/cancel cleanup passed. Windows interactive IDE GUI remains unverified; the launcher is unsigned, and publication was skipped with `publish=false`.

See [package identities and installation](docs/INSTALL.md#prepared-package-identities) and [concrete limitations](docs/KNOWN-LIMITATIONS.md#verification-scope--验证范围). Historical results retain their own build and scope; detailed records are retained by the maintainers.

Complete acceptance remains **0/101**: this counts complete acceptance items, not implemented features or an overall percentage. The repository remains private, no public Release exists, and `publish=false` with the full-alignment publication gate remains in force.

## Licenses

Original GoForge code and documentation are licensed under [Apache-2.0](LICENSE), with attribution in [NOTICE](NOTICE). Identified third-party material retains its own license: the LSP4IJ metadata patch is EPL-2.0, and bundled runtimes and drivers have additional terms. See [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md). The project license does not grant rights on behalf of those upstream owners or change their terms. Preserve all applicable notices and corresponding-source materials when distributing.

The full-alignment requirement is the maintainer's publication gate, not an additional restriction added to the Apache license.
