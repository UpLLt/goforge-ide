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
| Windows x64 | The a252 portable package is prepared and frozen after cross-assembly on macOS; full archive extraction and file readback passed. Native Windows execution and CI rerun remain unverified. See the [installation guide](docs/INSTALL.md#windows-x64). |
| Linux / other architectures | No verified standalone distribution currently provided. |

Prepared internal platform packages bundle a runtime, Go SDK, gopls, Delve, Node, Dev Containers CLI, and JDBC drivers. Docker, kubectl, Git, OpenSSH, Cgo's C compiler, and macOS debugger prerequisites are separate system tools. Package checksums and the distinction between preparation and publication are listed in the [installation guide](docs/INSTALL.md#prepared-package-identities).

## Current workflows

| Area | Implemented scope |
| --- | --- |
| Go editor | Local line/block comments, paired braces, bounded indentation and raw-string Enter; gopls completion, diagnostics, navigation, references, rename and available code actions; Go-only formatting on save with awaited completion, plus manual Reformat. See the scoped save limitations below. |
| Projects & execution | Application/library modules; run/build/test; top-level Test/Example selection, runtime subtest rerun, containing-test navigation and single-package failed top-level rerun; local termination preserves completed results and interrupts unfinished nodes. |
| Refactoring | Project-wide previews and undoable apply; extraction to a new file; guarded cross-package parameter reorder/removal; a separate operation adds one scalar parameter with a literal default to verified project direct calls, including bounded generic free-function calls. Type parameters, constraints and argument order are preserved. File-list changes invalidate previews; unsupported call shapes and resource operations are rejected. |
| Debugging | Local Delve, including selected Test/Example/subtree debugging with supported flags; SSH remote package/test/executable launch and PID attach, including reviewed project-root `go.work` modules; breakpoints, stacks, variables, evaluation, stepping, and stop. |
| Coverage & profiling | Single-module coverage and source coloring; CPU/memory collection and pprof views. |
| Database | SQLite, PostgreSQL, MySQL/MariaDB; SQL, transactions, primary-key row edits, CSV, basic schema completion, estimated Explain. |
| HTTP | `.http`/`.rest` collections, environments, sequential runs, assertions, cancellation, and redacted summaries. |
| Containers | Docker/Compose, Kubernetes resource tools and loopback forwarding, limited Dev Container workflows. |
| Remote workspace | Strict-host-key SSH tasks and reviewed transfers; opt-in gopls snapshot analysis with unsaved-document overlays. |

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

The latest frozen **a252** plugin passed **633/633 macOS-host JUnit tests** (366 component + 267 platform; 91 XML reports), with zero failures/errors/skips and 311 frozen inputs. The 88 focused tests are included in that total. Plugin SHA-256: `a252ce31c51d9b427745256245bb1f852cbdf8f1a8bebe4c68d30a4098360aac`; size: **14,884,065 bytes**. Both a252 application packages are prepared and frozen, with static archive readback passed. Checksums are in the [installation guide](docs/INSTALL.md#prepared-package-identities). The bounded generic-refactoring native checks below passed; Windows native execution and CI rerun remain unverified. Separately, 12 real-gopls/Go generic-parameter protocol cases, 44 standalone helper test nodes (including two parent tests), and seven ordinary-parameter protocol regression cases passed; these are not added to the JUnit total.

The **a252 bounded macOS native check passed** generic three-file preview, Apply, explicit Save, and one global Undo/Redo. Inferred, explicit and partially explicit type arguments were preserved. Adding a new caller after preview was refused before any writes, leaving all five baseline files and the new file unchanged. After a fresh preview and Apply, bundled `go test -count=1 ./...` passed following explicit Save. Undo and Redo matched all five fixture files exactly; the final Undo/Save restored the baseline. Normal exit left no owned processes, with 0 new errors / 6 warnings retained, and strict/deep signature verification passed. This is a candidate-only bounded check, not a direct GoLand comparison or full parity; automatic-save timing is unproven, and selected-debug and Stop were not retested on a252.

Historical **319d** passed 626/626 JUnit tests; both application packages were prepared and frozen. Its bounded native Stop/output regression preserved completed results, marked unfinished nodes Terminated, retained buffered output with correct diagnostic newlines, and left no tracked Go/test processes. The fixture stayed unchanged; normal exit and post-exit signing passed, with 0 errors and 5 warnings recorded. This remains 319d evidence, not a new a252 native pass.

**311e** passed bounded macOS native Stop, selected-subtest debugging, and three-file parameter insertion through preview, Apply, Save, Undo and Redo. Both fixtures were restored; normal exit left no owned processes, post-exit signing passed, and 0 errors / 7 warnings were retained. Its seven add-parameter protocol cases remain separate historical evidence. These native checks cover 311e’s non-generic refactoring and selected-debug scope; they do not establish a252 generic native acceptance.

Historical **f9e0** passed **596/596** JUnit tests (352 component + 244 platform; 87 reports, 300 frozen inputs). Its plugin SHA-256 is `f9e0ebf8734e088a7d017a7a661b14bf8ab1e13bc590f09ef26ddb66f347bad2`, size **14,849,715 bytes**; both application packages were frozen. Its native Current File `TestCases` (five passed nodes), `TestCases/dup#01` rerun with `-count=1` (parent and selected child only), and navigation to 18:6 passed. Clicking the enabled selected-test debug button produced no new configuration, Debug pane or dialog, with no DAP startup/error logged. That historical no-start observation retains an unknown cause; it is neither a proven Delve defect nor a current-build failure.

Separate historical evidence includes seven actual-platform/real-Go workflow cases from e6b3, two real Delve protocol sessions, 21 real-Go selector checks and nine relative-argument checks. These are not additions to 633 or new a252 runs. See [usage and flag boundaries](docs/USAGE.md) before selecting tests from a multi-package run. Windows native execution and complete feature acceptance remain unverified; the complete acceptance ledger remains **0/101**, with no overall completion percentage. That ledger counts fully completed acceptance items, not implemented features.

Historical **e6b3** passed 593 automated tests. Native checks passed Current File selection of `TestOne`, `TestTwo` and `TestCases` with dynamic children, plus fresh failed-test rerun using `-count=1`. Stop removed the tracked Go process and test child, but the UI showed failed/incomplete instead of Interrupted. Its selected-subtree rerun, navigation and debug toolbar buttons stayed disabled. Later f9e0 rerun/navigation passes repaired those two paths; its separate selected-debug no-start observation remains historical, as described above.

The earlier **4764** macOS/Windows packages are assembled and frozen. Its native Current File run failed: a caret inside `TestOne` launched `go test -json .`, running the whole package (8 passed, 1 ignored). Only two navigation observations passed: a top-level declaration and a runtime child's containing declaration. That failed result remains recorded separately from the later e6b3 selection passes.

Earlier **921d** packages passed their own packaging checks, and that artifact's native Save regression passed five selected target comparisons and three candidate-only checks, with clean exit and post-GUI signing. Those results do not establish complete native acceptance of a252. Go save formatting waits for a connected gopls response; absent overlays, active-VCS changed-lines-only, automatic import/rearrange/cleanup and manual closed-editor formatting retain the [documented limitations](docs/KNOWN-LIMITATIONS.md).

Historical failures remain recorded: **9ab8** failed first-save persistence and blank-line/caret behavior despite passing automated tests; **9abc** matched 8/10 editor observations, with two final-LF differences. Earlier plugin-settings, essential-toggle, LSP-tree and raw remote-console defects retain their original evidence; **1caa** has separate bounded native repair results. The ten original editor observations and these repair checks are partial baselines, not full product acceptance.

`UpLLt/goforge-ide` remains private with older imported sources. Its first Windows CI run failed during JDK setup before tests; the exact-version fix is committed, but the rerun has not started. Current local changes have not been uploaded because direct browser control still reports disabled. There is no public repository or Release; `publish=false` and the full-alignment publication gate remain in force. The [installation guide](docs/INSTALL.md#prepared-package-identities) retains the identities of previously prepared packages; machine-private evidence is excluded from source exports.

## Licenses

Original GoForge code and documentation are licensed under [Apache-2.0](LICENSE), with attribution in [NOTICE](NOTICE). Identified third-party material retains its own license: the LSP4IJ metadata patch is EPL-2.0, and bundled runtimes and drivers have additional terms. See [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md). The project license does not grant rights on behalf of those upstream owners or change their terms. Preserve all applicable notices and corresponding-source materials when distributing.

The full-alignment requirement is the maintainer's publication gate, not an additional restriction added to the Apache license.
