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
| macOS, Apple Silicon | macOS 13.5+. Full 18 archive audited; bounded native top-toolbar Stop and normal Resume/exit cleanup passed. Full GUI acceptance remains pending. Ad-hoc signing; no Developer ID notarization. |
| Windows x64 | Run 11 failed three product tests. Run 10 package/native checks and independent archive audit passed; new repairs and interactive GUI acceptance are pending. Unsigned launcher. See [installation status](docs/INSTALL.md#windows-x64). |
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

**Current source — Full 18 (2de4).** Passed **805/805 macOS-host JUnit tests** (466 component + 339 platform; 105 XML reports, no failures/errors/skips), plugin build and structure verification, with 358 frozen inputs. It includes fixes for stopping a debug session and protecting its generated-output directory on Windows. The Full 18 Mac archive is independently audited; bounded native top-toolbar Stop and normal Resume/exit cleanup, final app exit and signature recheck passed. Real Windows validation and full GUI acceptance remain pending. Full 14–16 integration failures, Full 17 native Stop failure and Windows Run 11 failures remain recorded; older passes do not validate these repairs.

The current cleanup change gives default local Delve launches in `debug`/`test` mode a separate, session-owned temporary output directory. After the adapter and observed target processes exit, it removes only that session’s generated executable and empty directory. If exit or ownership cannot be established, it retains the output and reports a warning. Explicit `output` paths, `exec`, attach and remote sessions retain their separate behavior. Full 17 native normal-exit cleanup passed, but toolbar Stop failed and retained output. Full 18 fixes passed macOS-host tests, archive audit and bounded top-toolbar Stop/normal-exit cleanup. Windows and full GUI acceptance remain pending.

**Packaged baseline — Full 13 (dd87)** passed **756/756 macOS-host JUnit tests** (433 component + 323 platform; 99 XML reports, no failures/errors/skips), plugin build and structure verification, with 345 frozen build inputs. The same reviewed source passed Windows Run 10 with 755 product tests and one existing POSIX-shell skip. Its Mac archive is independently audited; ordinary-import Add Parameter has bounded macOS native evidence. Full GUI and complete GoLand parity remain unverified.

**Latest audited Mac package — Full 18 (2de4).** Two bounded Benchmark debug workflows passed: actual top-toolbar Stop and normal Resume/exit. Both reached the breakpoint, exited the adapter with code 0, removed their exact generated-output directories and left none of their eight tracked processes. All four fixture files remained unchanged; normal app exit and signature recheck passed. Seven log warnings remain recorded. Full 17 direct-editor evaluation and exact declaration navigation remain separate evidence; its failed Stop and retained zombie/output are preserved, not counted as current-session success. Windows and full GUI acceptance remain pending.

**Latest Windows attempt — [Run 11](https://github.com/UpLLt/goforge-ide/actions/runs/40834616140) failed** on Full 17: 800 product tests = 796 passed, three failed and one existing POSIX-shell skip (105 XML, no errors). Failures covered directory move protection, a process-handler timeout and a navigation path comparison. Full 18 contains repairs; their real Windows rerun is pending. Run 10 below remains the separately verified Full 13 package, including its now-completed independent archive audit.

Windows [Run 10](https://github.com/UpLLt/goforge-ide/actions/runs/40823363745) passed on the reviewed Full 13 source: **43/43 preflight checks**, **756 product tests = 755 passed + one existing POSIX-shell skip** (99 XML, no failures/errors), packaging and **nine native checks**. The relocated launcher/bundled JBR, Unicode and space paths, exact isolated configuration paths, tool versions, Go run/test, gopls and Delve normal/cancel cleanup passed. Windows interactive IDE GUI remains unverified; the launcher is unsigned, and publication was skipped with `publish=false`.

See [package identities and installation](docs/INSTALL.md#prepared-package-identities) and [concrete limitations](docs/KNOWN-LIMITATIONS.md#verification-scope--验证范围). Historical results retain their own build and scope; detailed records are retained by the maintainers.

Complete acceptance remains **0/101**: this counts complete acceptance items, not implemented features or an overall percentage. The repository remains private, no public Release exists, and `publish=false` with the full-alignment publication gate remains in force.

## Licenses

Original GoForge code and documentation are licensed under [Apache-2.0](LICENSE), with attribution in [NOTICE](NOTICE). Identified third-party material retains its own license: the LSP4IJ metadata patch is EPL-2.0, and bundled runtimes and drivers have additional terms. See [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md). The project license does not grant rights on behalf of those upstream owners or change their terms. Preserve all applicable notices and corresponding-source materials when distributing.

The full-alignment requirement is the maintainer's publication gate, not an additional restriction added to the Apache license.
