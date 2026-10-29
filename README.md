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
| macOS, Apple Silicon | macOS 13.5+. Full 27 archive audited; all 103 actual packaged refactoring cases passed independent readback, with 44 recorded gopls shutdown warnings. No Full 27 IDE GUI group ran while the Mac was locked. The earlier Full 24 archive retains its separate 72 passed packaged cases. Earlier build-specific native checks retain their separate identities. Ad-hoc signing; no Developer ID notarization. |
| Windows x64 | Full 24 Run 16 passed 914 product tests plus the existing POSIX skip and independent plugin readback, then its package job hit the one-hour limit and was cancelled. No native/method reports or release archive were uploaded; the blocking phase is unknown. Full 22 Run 15 failed later packaged-method cleanup and produced no release archive. Previous Full 19 Run 13 has audited tests, nine bounded native groups and complete application archive. Interactive IDE GUI remains unverified; launcher unsigned. See [Windows installation status](docs/INSTALL.md#windows-x64). |
| Linux / other architectures | No verified standalone distribution currently provided. |

Prepared internal platform packages bundle a runtime, Go SDK, gopls, Delve, Node, Dev Containers CLI, and JDBC drivers. Docker, kubectl, Git, OpenSSH, Cgo's C compiler, and macOS debugger prerequisites are separate system tools. Package checksums and the distinction between preparation and publication are listed in the [installation guide](docs/INSTALL.md#prepared-package-identities).

## Current workflows

Current source supports [save/manual import optimization](docs/USAGE.md#organize-imports-on-save--保存时整理导入), [selected top-level Benchmark Run and console-node Debug](docs/USAGE.md#run-one-benchmark--运行单个基准测试), [remote SDK and selected cached dependency source navigation](docs/USAGE.md#remote-standard-library-sources--远端标准库源码), and guarded cross-file refactoring. Source tests, packages and native acceptance have separate scopes; see verification below and the [workflow guide](docs/USAGE.md).

| Area | Implemented scope |
| --- | --- |
| Go editor | Local line/block comments, paired braces, bounded block/parenthesis/bracket continuation indentation and raw-string Enter; gopls completion, diagnostics, navigation, references, rename and available code actions; Go-only formatting on save with awaited completion, plus manual Reformat. Independent imports before save formatting, plus manual Optimize Imports and Go selection in the save type chooser; Earlier workflows have macOS/Windows platform and bounded macOS native evidence; new continuation behavior passed macOS-host tests, three bounded native Enter comparisons with GoLand and the corresponding Full 19 Windows product tests; Windows interactive GUI remains unverified. |
| Projects & execution | Application/library modules; run/build/test; top-level Test/Example selection, runtime subtest rerun, containing-test navigation and single-package failed top-level rerun; local termination preserves completed results and interrupts unfinished nodes. One top-level Benchmark Run, guarded completed-node rerun, Debug and source navigation, plus Debug Go Benchmark at Caret for one iteration from a saved editor declaration; direct subbenchmark selection remains unavailable. See the verification scope below for each build. |
| Refactoring | Project-wide previews and undoable apply; extraction to a new file; guarded cross-package parameter reorder/removal; a separate operation adds one scalar parameter with a literal default to verified project direct calls, including bounded generic free-function calls and typed direct calls of private concrete methods. Ordinary imports use a standalone-module offline compile check. Type parameters, constraints and argument order are preserved. Current source also provides a compile-checked scalar parameter/single-result type change for free functions, and one scalar parameter type change for bounded private concrete methods within one captured module. A separate private static-function scalar reorder preserves constant/static-call argument evaluation order using typed temporaries and whole-module type/compile checks. File-list changes invalidate previews; unsupported call shapes and resource operations are rejected. |
| Debugging | Local Delve, including selected Test/Example/subtree debugging with supported flags; SSH remote package/test/executable launch and PID attach, including reviewed project-root `go.work` modules; breakpoints, stacks, variables, evaluation, stepping, and stop. Paused remote package/test sessions can show bounded read-only SDK and selected cached dependency snapshots. Dependency viewing requires one reviewed root module and matching checksum/cache proofs; SDK and dependency snapshots remain unavailable for executable/attach sessions. |
| Coverage & profiling | Single-module coverage and source coloring; CPU/memory collection and pprof views. |
| Database | SQLite, PostgreSQL, MySQL/MariaDB; SQL, transactions, primary-key row edits, CSV, basic schema completion, estimated Explain. |
| HTTP | `.http`/`.rest` collections, environments, sequential runs, assertions, cancellation and redacted summaries; default-on five-hop redirects, per-request `@no-redirect` and cross-origin header isolation. |
| Containers | Docker/Compose, Kubernetes resource tools and loopback forwarding, limited Dev Container workflows. |
| Remote workspace | Strict-host-key SSH tasks and reviewed transfers; opt-in gopls snapshot analysis with unsaved-document overlays, including reviewed contained `go.work` modules; exact remote `GOROOT/src` Definition/Type Definition opens read-only session sources. Selected cached module versions also open read-only with one reviewed root go.mod/go.sum and verified checksums; replacements, vendor and go.work dependency-source lookup remain unsupported. Paused package/test debugger dependency viewing has a separate guarded workflow. |

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

**Current verified source — Full 27.** All 924 macOS-host JUnit tests passed (549 component, 375 platform; 120 XML, 395 frozen inputs), with independent build and Mac archive readback. All 103 actual packaged refactoring cases passed using the bundled implementation and tools, with no development overlay; 44 gopls shutdown warnings are recorded. New scope includes selected cached dependency views in paused remote Package/Tests sessions and private static-function scalar reorder with preserved supported argument-call order. No Full 27 IDE GUI group ran while the Mac was locked, and this source has not undergone Windows acceptance. Windows [Run 16](https://github.com/UpLLt/goforge-ide/actions/runs/40869160021) tests frozen Full 24: 914 product cases passed plus the existing POSIX-only skip, with independent test/plugin readback. Its package job was cancelled at the one-hour limit; no native/method reports or release archive were uploaded, and the blocking phase within the verification is unknown. Run 15's cleanup failure remains preserved; Full 19 Run 13 remains the latest complete independently audited Windows archive. Interactive Windows GUI is unverified.

**Previous Windows baseline:** [Run 13](https://github.com/UpLLt/goforge-ide/actions/runs/40844074829) tested Full 19: 834 passed and one existing POSIX-only skip, packaging and nine bounded native check groups passed. Downloaded reports and plugin were independently checked; complete application-archive readback has passed. Windows interactive GUI remains unverified.

Complete GoLand alignment is **not verified**. Complete acceptance remains **0/101**, a count of whole acceptance items rather than a feature-completion percentage. The repository is private and no public Release is available. See [package identities and installation](docs/INSTALL.md#prepared-package-identities) and [concrete limitations and verification history](docs/KNOWN-LIMITATIONS.md#verification-scope--验证范围).

## Licenses

Original GoForge code and documentation are licensed under [Apache-2.0](LICENSE), with attribution in [NOTICE](NOTICE). Identified third-party material retains its own license: the LSP4IJ metadata patch is EPL-2.0, and bundled runtimes and drivers have additional terms. See [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md). The project license does not grant rights on behalf of those upstream owners or change their terms. Preserve all applicable notices and corresponding-source materials when distributing.

The full-alignment requirement is the maintainer's publication gate, not an additional restriction added to the Apache license.
