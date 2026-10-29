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
| macOS, Apple Silicon | macOS 13.5+. Current 318d archive readback and bound packaging checks passed; its GUI acceptance is pending. Latest actual GUI checks are two bounded 5259 scalar-type workflows. Local ad-hoc signing; no Apple Developer ID notarization. |
| Windows x64 | Actual Windows Run 5 product/platform tests: 677 passed, one explicit POSIX-shell skip, zero failures/errors. Windows assembly passed, but native-verifier preflight failed; no final package export or native launch/UI acceptance. See the [installation guide](docs/INSTALL.md#windows-x64). |
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

The current frozen **318d repair source build** passed **678/678 macOS-host JUnit tests** (397 component + 281 platform; 95 XML reports; zero failures/errors/skips), plugin build and structure checks. Its 326 build inputs are frozen. Plugin ZIP: **14,941,918 bytes**, SHA-256 `318d7205541d0fd00b51f74c13ec7ecc22297a3dd19d6da0e1da7f342918fb93`. Windows Run 5 separately passed its product/platform tests; automated checks do not establish application GUI acceptance. The current Mac package is 318d, while the latest actual GUI record remains 5259.

The frozen **2026-10-29 baseline 5259** passed **659/659 macOS-host JUnit tests** (389 component + 270 platform; 94 reports; zero failures/errors/skips), plugin build and structure checks. Plugin ZIP: **14,931,387 bytes**, SHA-256 `52598058b70ba2329bb1447be52d6874596fabf02eeb3b76ecaf30fe6c7fb354`; 324 build inputs were frozen. Separate checks passed 20 scalar-refactoring protocol cases, 31 helper subcases, 3 compiler-context regressions and 18 multi-module SSH/gopls checks; these are not additions to the JUnit count.

The current **318d macOS archive** is frozen: **751,553,996 bytes**, SHA-256 `7a53be4464eb41afe752b069201f08ec7f5c4a6987b0c9903b8b1c70af8dbb02`. Independent archive/plugin/source/license readback passed; the bound packager receipts report ad-hoc signature, headless, relocation and extracted-archive checks passed. Audit SHA-256: `cffe0e152a10d2bd2a498ea5ad6e1ea45708ca8ba1447b5f7a531bdb13e832da`. **318d GUI acceptance is pending; the latest actual GUI checks remain the two bounded 5259 groups below.**

The historical **5259 macOS archive** is frozen: **751,543,468 bytes**, SHA-256 `c2c27d6b42cc98deaa95f8138b06cddd2cb7beac1042ccf5a3d85d08c4d87ecb`. Two bounded native groups passed: scalar parameter type change and generic single-result type change, each with preview, Apply, explicit Save, Undo/Redo and stable saved-byte/bundled-Go test checks. All four fixture files were restored; normal exit left no owned processes, 0 errors and 2 retained warnings, and post-exit signature verification passed. The parameter result is validated by the stable redo/test receipt. These are candidate-only checks, not a GoLand comparison; remote-analysis native acceptance and Windows desktop acceptance remain incomplete.

**Windows Run 5 product/platform tests passed:** [private CI run `40808662484`](https://github.com/UpLLt/goforge-ide/actions/runs/40808662484), source commit `cf208a581836640331c24d9ec5eb8c71d1a6e7d1`. The 95 XML reports contain **678 tests: 677 passed, 0 failures/errors, and 1 explicit POSIX-shell skip** (`GoRemoteProfileTest.shellQuotedDataRemainsExactlyOneLiteralArgument`). All 14 tests that failed in Run 4 passed in Run 5. The reviewed source ZIP SHA-256 is `7a5fd28aa330c816e33912edde4dcd65055f8e3a077d989780699e8bdab42f05` (**878,196 bytes**). Windows assembly and both platform-patch checks passed. **Run 5 ultimately failed at native-verifier preflight:** `Get-AuthenticodeSignature` could not load `Microsoft.PowerShell.Security`. The native report has `checks: {}`: launcher, Go, gopls and Delve probes were not reached. Final release-file export was skipped; native launch and GUI acceptance remain unverified. The older a252 Windows archive retains its separate cross-assembly identity. `publish=false`; this is private validation.

Earlier a252 generic-refactor, 319d Stop/output and 311e selected-debug/add-parameter results retain their original scope in [Known limitations](docs/KNOWN-LIMITATIONS.md#verification-scope--验证范围); archive hashes remain in the [installation guide](docs/INSTALL.md#prepared-package-identities).

Complete acceptance remains **0/101**; this counts fully completed acceptance items, not implemented features or an overall percentage. The repository remains private, there is no public Release, and `publish=false` with the full-alignment publication gate remains in force.

## Licenses

Original GoForge code and documentation are licensed under [Apache-2.0](LICENSE), with attribution in [NOTICE](NOTICE). Identified third-party material retains its own license: the LSP4IJ metadata patch is EPL-2.0, and bundled runtimes and drivers have additional terms. See [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md). The project license does not grant rights on behalf of those upstream owners or change their terms. Preserve all applicable notices and corresponding-source materials when distributing.

The full-alignment requirement is the maintainer's publication gate, not an additional restriction added to the Apache license.
