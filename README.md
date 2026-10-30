# GoForge

**An independent Go IDE with no account, registration, activation, subscription or feature paywall.**

[简体中文](README.zh-CN.md) · [Download v0.1.0](https://github.com/UpLLt/goforge-ide/releases/tag/v0.1.0) · [Install](docs/INSTALL.md) · [Usage](docs/USAGE.md) · [Known limitations](docs/KNOWN-LIMITATIONS.md)

GoForge combines original code with the public IntelliJ Community platform, LSP4IJ, gopls and Delve. It is independent of JetBrains and does not contain the proprietary GoLand plugin. v0.1.0 is an early usable release for ordinary local Go projects; **complete GoLand parity is not verified**. External databases, SSH, containers and Git services retain their normal connection requirements.

## Start here

1. Download the application ZIP for your OS from [Releases](https://github.com/UpLLt/goforge-ide/releases/tag/v0.1.0), compare its SHA-256 with the accompanying checksum file, and extract the whole archive.
2. On macOS Apple Silicon (13.5+), open `GoForge.app`. On Windows 10/11 x64, run `GoForge/bin/goforge64.exe`.
3. Select **New Go Project…**, or open a folder containing `go.mod`. Allow indexing and gopls initialization to finish.
4. Use the generated **Run / Test / Debug** configurations. Configure tools under **Settings → GoForge**, and build options under **Settings → Go Project**.

Packages include Java, Go SDK, gopls, Delve, Node, Dev Containers CLI and JDBC drivers. Git, Docker, kubectl, OpenSSH, Cgo compilers and macOS debugger prerequisites are separate system tools when needed. macOS is ad-hoc signed, without notarization; the Windows launcher is unsigned. Linux and other architectures have no verified standalone package. See the [installation guide](docs/INSTALL.md).

## Current workflows

Current source supports [save/manual import optimization](docs/USAGE.md#organize-imports-on-save--保存时整理导入), [selected top-level Benchmark Run and console-node Debug](docs/USAGE.md#run-one-benchmark--运行单个基准测试), [remote SDK and selected cached dependency source navigation](docs/USAGE.md#remote-standard-library-sources--远端标准库源码), and guarded cross-file refactoring. Source tests, packages and native acceptance have separate scopes; see verification below and the [workflow guide](docs/USAGE.md).

| Area | Implemented scope |
| --- | --- |
| Go editor | Local line/block comments, paired braces, bounded block/parenthesis/bracket continuation indentation and raw-string Enter; gopls completion, diagnostics, navigation, references, rename and available code actions; Go-only formatting on save with awaited completion, plus manual Reformat. Independent imports before save formatting, plus manual Optimize Imports and Go selection in the save type chooser; Earlier workflows have macOS/Windows platform and bounded macOS native evidence; new continuation behavior passed macOS-host tests, three bounded native Enter comparisons with GoLand and the corresponding Full 19 Windows product tests; Windows interactive GUI remains unverified. |
| Projects & execution | Application/library modules; run/build/test; top-level Test/Example selection, runtime subtest rerun, containing-test navigation and single-package failed top-level rerun; local termination preserves completed results and interrupts unfinished nodes. One top-level Benchmark Run, guarded completed-node rerun, Debug and source navigation, plus Debug Go Benchmark at Caret for one iteration from a saved editor declaration; direct subbenchmark selection remains unavailable. See the verification scope below for each build. |
| Refactoring | Project-wide previews and undoable apply; extraction to a new file; guarded cross-package parameter reorder/removal; a separate operation adds one scalar parameter with a literal default to verified project direct calls, including bounded generic free-function calls and typed direct calls of private concrete methods. Ordinary imports use a standalone-module offline compile check. Type parameters, constraints and argument order are preserved. Current source also provides a compile-checked scalar parameter/single-result type change for free functions, and one scalar parameter or named/unnamed single-result type change for bounded private concrete methods within one captured module. A separate private static-function scalar reorder preserves constant/static-call argument evaluation order using typed temporaries and whole-module type/compile checks. File-list changes invalidate previews; unsupported call shapes and resource operations are rejected. |
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

On 2026-10-30 native macOS QA completed an ordinary Go project's open, edit/cross-file completion, save, run, test/failure recovery, breakpoint debugging, Git diff/commit/branch and close/reopen workflows. A Git branch-dashboard dispatcher exception was fixed and the affected paths passed again. The unchanged Full 28 plugin previously passed 926 macOS product tests and 141 packaged refactoring cases.

Windows release assets require the same CI run's product, launcher, toolchain, Git repair and packaged-protocol checks. **Interactive Windows desktop, large-project and long-session acceptance remain incomplete.** See [release notes](docs/RELEASE-0.1.0.md) and [known limitations](docs/KNOWN-LIMITATIONS.md) for evidence and gaps. v0.1.0 retains the tested internal `0.1.0-dev` / `0.1 Development` version text; release checksums identify this distribution.

## Licenses

Original GoForge code and documentation are licensed under [Apache-2.0](LICENSE), with attribution in [NOTICE](NOTICE). Identified third-party material retains its own license: the LSP4IJ metadata patch is EPL-2.0, and bundled runtimes and drivers have additional terms. See [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md). The project license does not grant rights on behalf of those upstream owners or change their terms. Preserve all applicable notices and corresponding-source materials when distributing.
