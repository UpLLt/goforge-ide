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
| macOS, Apple Silicon | macOS 13.5+. The a252 package is prepared and frozen; archive readback, ad-hoc signature, headless probe and relocation checks passed. Its bounded generic preview/Apply/Save/Undo/Redo and stale-preview refusal native checks passed. Earlier 319d Stop/output and 311e selected-debug/add-parameter checks retain their historical scope. Local ad-hoc signing; no Apple Developer ID notarization. |
| Windows x64 | The a252 portable package is prepared and frozen after cross-assembly on macOS; full archive extraction and file readback passed. Windows CI has resumed, but Runs 2 and 3 failed before package acceptance; native launch and UI remain unverified. See the [installation guide](docs/INSTALL.md#windows-x64). |
| Linux / other architectures | No verified standalone distribution currently provided. |

Prepared internal platform packages bundle a runtime, Go SDK, gopls, Delve, Node, Dev Containers CLI, and JDBC drivers. Docker, kubectl, Git, OpenSSH, Cgo's C compiler, and macOS debugger prerequisites are separate system tools. Package checksums and the distinction between preparation and publication are listed in the [installation guide](docs/INSTALL.md#prepared-package-identities).

## Current workflows

| Area | Implemented scope |
| --- | --- |
| Go editor | Local line/block comments, paired braces, bounded indentation and raw-string Enter; gopls completion, diagnostics, navigation, references, rename and available code actions; Go-only formatting on save with awaited completion, plus manual Reformat. See the scoped save limitations below. |
| Projects & execution | Application/library modules; run/build/test; top-level Test/Example selection, runtime subtest rerun, containing-test navigation and single-package failed top-level rerun; local termination preserves completed results and interrupts unfinished nodes. |
| Refactoring | Project-wide previews and undoable apply; extraction to a new file; guarded cross-package parameter reorder/removal; a separate operation adds one scalar parameter with a literal default to verified project direct calls, including bounded generic free-function calls. Type parameters, constraints and argument order are preserved. Current source also provides a compile-checked scalar parameter/single-result type change within one captured module. File-list changes invalidate previews; unsupported call shapes and resource operations are rejected. |
| Debugging | Local Delve, including selected Test/Example/subtree debugging with supported flags; SSH remote package/test/executable launch and PID attach, including reviewed project-root `go.work` modules; breakpoints, stacks, variables, evaluation, stepping, and stop. |
| Coverage & profiling | Single-module coverage and source coloring; CPU/memory collection and pprof views. |
| Database | SQLite, PostgreSQL, MySQL/MariaDB; SQL, transactions, primary-key row edits, CSV, basic schema completion, estimated Explain. |
| HTTP | `.http`/`.rest` collections, environments, sequential runs, assertions, cancellation, and redacted summaries. |
| Containers | Docker/Compose, Kubernetes resource tools and loopback forwarding, limited Dev Container workflows. |
| Remote workspace | Strict-host-key SSH tasks and reviewed transfers; opt-in gopls snapshot analysis with unsaved-document overlays, including reviewed contained `go.work` modules in current source. |

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

Current source build **5259** passed **659/659 macOS-host JUnit tests** (389 component + 270 platform; 94 reports; zero failures/errors/skips), plus plugin build and structure checks. Plugin ZIP: **14,931,387 bytes**, SHA-256 `52598058b70ba2329bb1447be52d6874596fabf02eeb3b76ecaf30fe6c7fb354`; 324 build inputs are frozen. It adds bounded scalar parameter/result type changes and reviewed multi-module remote analysis, and repairs the Windows CI preparation issues. Separate real-tool checks passed 20 scalar-refactoring cases, 31 helper subcases, 3 independent compiler-context regressions, and 18 multi-module SSH/gopls checks. These are not additions to the JUnit count. New application/native acceptance and Windows CI are pending.


The latest frozen package milestone remains **a252**, which passed **633/633 macOS-host JUnit tests** (366 component + 267 platform; zero failures/errors/skips). Plugin SHA-256: `a252ce31c51d9b427745256245bb1f852cbdf8f1a8bebe4c68d30a4098360aac`, **14,884,065 bytes**. Both application packages passed static archive readback; their unchanged hashes are in the [installation guide](docs/INSTALL.md#prepared-package-identities). New package acceptance is pending; the current source build is recorded above.

The **a252 bounded macOS native check** passed generic three-file preview, Apply, explicit Save, global Undo/Redo and refusal of a stale preview after adding a caller. Bundled Go tests passed after Save; all five fixture files were restored exactly. Normal exit left no owned processes, with 0 new errors / 6 warnings retained, and strict/deep signature verification passed. This is a candidate-only check: automatic-save timing, selected-debug and Stop were not retested on a252. Earlier successes and failures retain their original build identities in [Known limitations](docs/KNOWN-LIMITATIONS.md#verification-scope--验证范围).

Private Windows CI has resumed. The a252 source snapshot was imported into `UpLLt/goforge-ide` at `57fd69c145cbbe3780d256b7a03fec793af17691`. Run 2 used the older 449-test snapshot and failed during sandbox preparation; Run 3 used the a252 snapshot and failed in a preparation-test path fixture. The ZIP metadata and path-fixture fixes passed their local regressions, but **Windows product tests, native launch and UI acceptance have not passed**. See the [CI record](docs/KNOWN-LIMITATIONS.md#private-validation-and-publication--私有验证与发布).

Complete acceptance remains **0/101**; this counts fully completed acceptance items, not implemented features or an overall percentage. The repository remains private, there is no public Release, and `publish=false` with the full-alignment publication gate remains in force.

## Licenses

Original GoForge code and documentation are licensed under [Apache-2.0](LICENSE), with attribution in [NOTICE](NOTICE). Identified third-party material retains its own license: the LSP4IJ metadata patch is EPL-2.0, and bundled runtimes and drivers have additional terms. See [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md). The project license does not grant rights on behalf of those upstream owners or change their terms. Preserve all applicable notices and corresponding-source materials when distributing.

The full-alignment requirement is the maintainer's publication gate, not an additional restriction added to the Apache license.
