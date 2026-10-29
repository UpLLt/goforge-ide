# Workflows / 功能用法

Use the action search if a menu is hidden in your selected IDE layout. GoForge does not require its own login. Service credentials belong to the services you choose to connect.

如果当前布局隐藏菜单，可以用动作搜索查找下列名称。GoForge 无自身登录门槛；外部服务使用各自的认证。

| Entry / 入口 | Steps / 操作 | Boundary / 边界 |
| --- | --- | --- |
| Go editor | Use local comments, brace pairing and Enter without a language server; open a module for gopls completion, Go to Declaration, Find Usages, Rename and Reformat. / 本地注释、括号和回车无需语言服务；模块内使用 gopls 语义功能。 | Local editing and awaited Go save formatting have bounded platform tests; 921d passed five selected native target comparisons and three candidate-only save checks. Historical 9abc/9ab8 failures remain recorded. / 基础编辑与等待响应的 Go 保存格式化已有平台测试；921d 限定原生保存通过，旧失败保留。 |
| Run / Debug | Select a Go configuration, choose package/arguments/environment, run or debug, and use the Debug pane for frames/variables/step/stop. / 配置包、参数、环境后运行或调试。 | Local debug needs Delve and the OS debugger prerequisites. Expression completion covers the implemented frame/local/field cases, not every Go expression. |
| Go Refactor… | Select source, choose an available action, review all file changes, then Apply. Use normal Undo/Redo. / 选择源码，预览全部文件改动，再应用或撤销。 | Unsupported resource operations/commands and stale/conflicting files are refused. Cross-package move/signature parity is incomplete. |
| Run → Run Go Coverage | Save the module, run coverage, select a row to navigate, use Clear to remove coverage marks. / 保存模块后运行覆盖率，点行跳转，Clear 清除标记。 | One module; saved-file snapshots; no combined go.work report. |
| Tools → Go Profiling… | Collect CPU/memory profiles for one package or open pprof data; use top/compare/viewer. / 单包采集或打开 pprof，查看 top、比较与交互视图。 | Viewer binds loopback; graph layout needs Graphviz. |
| Tools → Go Database | Add a connection; browse schema; execute SQL. Select Begin/Commit/Rollback then Apply. Review primary-key edits before applying; export CSV. / 连接数据库后执行 SQL，事务选动作再 Apply。 | Passwords are not persisted. Row editing requires a supported primary key. Explain estimates SELECT/WITH; no ANALYZE execution. |
| Tools → GoForge HTTP Client | Open `.http`/`.rest`, select environment files explicitly, run one/all requests, inspect assertions and results. / 打开请求文件、选择环境、运行并查看结果。 | No script execution, redirects, WebSocket or gRPC. Raw response save is a separate explicit action. |
| Containers → Docker | Choose a context; refresh containers/images; inspect logs, start/stop or use reviewed build/Compose actions. / 选择 context 后管理。 | Requires Docker CLI/daemon. Cancel stops the local command, not necessarily an accepted daemon operation. |
| Containers → Kubernetes | Choose context/namespace, refresh, inspect logs/describe, preview diff/apply, use loopback forwarding and Cancel. / 选择集群与命名空间后操作。 | Requires kubectl and access to the selected cluster. Deletion checks resource UID. No global context switching. |
| Containers → Dev Containers | Load and review the supported configuration, Start, open Terminal; execute postCreate separately if desired; Stop or Remove owned resources. / 先预览，再启动；postCreate 单独触发。 | Basic image/build projection, not the complete specification. Unsupported metadata/configuration is rejected or explicitly omitted. |

## Local Go editing / Go 本地编辑

Use **Comment with Line Comment** or **Comment with Block Comment** on the current line/selection, then normal Undo/Redo. The block action refuses a selection containing a string's `*/` rather than inserting a broken comment. Braces `()`, `[]` and `{}` pair in Go code and stay ordinary text inside comments/literals; the existing closer can be skipped normally. Project indentation settings override the default four-column tabs.

Enter inside a Go block uses bounded local indentation. With smart indentation enabled, Enter inside a raw backtick string adds a newline without copying the surrounding Go indent, consuming only the spaces/tabs immediately to the right of the caret. It does not cross an existing newline, closing backtick or non-whitespace text. Undo restores the original line and whitespace. Smart indentation and **Insert pair } on Enter** are separate platform options; turning off one does not necessarily disable the other. This is not a replacement for gopls Reformat or complete continuation/switch-case formatting.

使用 **Comment with Line Comment / Comment with Block Comment** 操作当前行或选区，普通 Undo/Redo 可撤销重做；包含字符串 `*/` 的块注释选区会拒绝包裹。代码中的括号可配对，注释和字面量中的括号作为普通文本。默认使用视觉宽度 4 的 tab，项目设置可覆盖。

Go 块内回车使用限定范围的本地缩进。开启智能缩进时，原始字符串内回车不复制外围 Go 缩进，只移除光标右侧紧邻的空格/tab 后插入换行，不跨已有换行、闭合反引号或非空白内容；Undo 恢复原文与空白。智能缩进与 **Insert pair } on Enter** 是独立设置。完整续行、switch-case 和多光标行为仍需更广验证；历史保存对照分别归属 9abc 与 921d，不移作新版原生通过。

### Formatting on save / 保存格式化

In **Settings → Tools → Actions on Save → Reformat code**, review whether formatting is enabled and whether Go is selected. The initial default selects Go only; persisted explicit-off and type choices override it. With a ready connected gopls overlay, the Go save action awaits the response and applies a fully validated edit before the platform performs its final save. A later manual edit cancels that pending action; the newer input remains unsaved until you save it. The 921d delayed lifecycle has platform tests and a bounded native one-Save regression. Five selected target cases matched saved bytes on the first read, including 03/08 tab/caret preservation; ordinary Go, explicit OFF and non-Go behavior passed separately. OFF was checked by reopening Settings, then ON was restored; restart persistence was not tested in that native session. Invalid raw syntax displays a formatting-refusal notification and preserves the input.

No ready server or connected overlay causes a conservative skip. **Changed lines only** with an active VCS also skips this whole-document-only operation. An Editor closed while its overlay remains connected is covered; closing a tab that disconnects the overlay is not. The current-source imports-on-save addition is described below and still awaits acceptance. Automatic rearrangement and cleanup remain unimplemented. Manual **Reformat Code** remains available for open editors; the dependency's manual no-Editor formatting limitation remains.

A narrow gopls response filter preserves horizontal whitespace on the active caret's whitespace-only Go-code line when document, overlay, version and caret still match. It does not preserve raw-string/comment whitespace by guessing, and does not handle every merged-edit shape. It also applies to explicit whole-file gopls formatting; it is not a global whitespace policy or a setting change.

在 **Settings → Tools → Actions on Save → Reformat code** 检查开关与文件类型。初始默认只选 Go，已持久化的关闭或类型选择优先。服务器和文档 overlay 就绪时，Go 保存动作等待响应、完整校验后应用，由平台完成最终保存；期间用户继续输入会取消旧动作，新输入保持未保存。921d 平台及限定原生均已验证这条管线：五个选定样例一次保存、首次磁盘读回即匹配，含 03/08 的 tab/光标保留；普通 Go、明确关闭和非 Go 另行通过。关闭状态由重开 Settings 读回，随后恢复 ON，本轮未用重启验证设置持久化；无效 raw 显示格式化拒绝通知并保留输入。

无就绪服务/overlay，或启用 VCS 且选择 **Changed lines only** 时保守跳过。只关闭 Editor 而保留连接 overlay 的路径已有测试，关闭标签导致 overlay 断开则不作保证。新源码的保存时导入整理见下节，仍待验收；自动重排和 cleanup 尚未实现；打开编辑器后的手动 **Reformat Code** 保留，无 Editor 的手动格式化依赖限制仍在。光标空白行保护只覆盖来源/版本/光标完全匹配的限定水平空白编辑，也作用于显式整文件 gopls 格式化，不是全局空白规则。

### Organize imports on save / 保存时整理导入

**Current-source feature; Full 10 integrated regression passed. Native UI and Windows acceptance pending.** In **Settings → Tools → Actions on Save → Optimize imports**, enable the action. In the current 2406 package, Go is missing from the file-type picker; use **All file types** as the current workaround. That choice also enables the preference for other supported file types. This preference is independent of **Reformat code**; neither option enables the other. Open a Go file in a module and wait for its gopls connection before saving. This action does not start a cold language server during Save.

When both actions are selected, imports are organized first, the document overlay is refreshed, then formatting runs on that updated text. **Changed lines only** with an active VCS skips whole-document formatting but does not disable independently selected import organization. An import action is accepted only when it directly edits the same document at the captured version; ambiguous, command-based, unresolved, other-file or stale edits are refused. No ready overlay means no import mutation. If formatting later fails or is canceled, valid import edits may already be present; inspect the result and save again after any further typing. Full 10 platform tests passed one-step Undo/Redo for imports-only, imports+format and no-op-imports+format. These are controlled platform tests. One bounded 2406 native observation using All file types also passed Save, Undo/Redo and bundled Go tests on stable saved bytes; full native acceptance and the missing-Go picker repair remain pending. Historical format-only results do not establish broader coverage.

**当前源码功能，Full 10 整合回归已通过；原生界面及 Windows 验收待完成。** 在 **Settings → Tools → Actions on Save → Optimize imports** 开启该项。当前 2406 包的文件类型列表缺少 Go，可暂选 **All file types**；此选择也会对其他受支持文件类型开启该偏好。它与 **Reformat code** 是独立开关，不会互相开启。先打开模块中的 Go 文件并等待 gopls 连接就绪，再保存；保存动作不会临时启动尚未运行的语言服务。

两项均开启时，先整理导入、刷新文档 overlay，再格式化更新后的内容。启用 VCS 并选择 **Changed lines only** 会跳过整文件格式化，但不会关闭独立选择的导入整理。仅接受针对当前文档、版本完全匹配的直接编辑；需要执行命令、继续解析、跨文件或存在歧义/过期的编辑会被拒绝。无就绪 overlay 时不修改导入。后续格式化失败或取消时，已经完成的有效导入编辑可能保留；继续输入后请检查并再次保存。Full 10 平台测试已通过仅导入、导入+格式化、导入无变化+格式化的一步 Undo/Redo。这些是受控平台测试；2406 使用 All file types 的一项限定原生观察另已通过 Save、Undo/Redo 及稳定保存字节上的包内 Go 测试。完整原生验收与缺少 Go 的选择器修复仍待完成，上述历史“仅格式化”结果不代表更广覆盖。

## Run named Go tests / 运行指定 Go 测试

Save the test file, place the caret inside a runnable top-level `Test…` or `Example…` function in a `_test.go` file, and use its context **Run** action. GoForge selects the containing function. An example needs an `Output:` or `Unordered output:` directive to be runnable. A caret inside a `t.Run` body selects its top-level test; select an individual runtime subtest from the completed test tree instead. Outside a recognized runnable declaration, the context action remains a package run. Current development source also recognizes the bounded Benchmark declarations described below. You can also use **Current File → Run** and choose **test at caret in …**; GoForge resolves the current editor's caret before starting. This does not retarget an already named or manual configuration. If the file or configuration changes while GoForge is resolving the target, save/review it and choose Run again; a stale request will not silently run the package.

In **Run → Edit Configurations…**, choose the Go configuration and set **Command** to `test`. **Test selection** offers the following modes; the Benchmark mode is a current-source addition awaiting acceptance:

| Mode / 模式 | Configuration / 配置 |
| --- | --- |
| **Package / manual** | Set a package target such as `.` or `./...` and supply ordinary **Go arguments** as needed. Use this mode for manual filters, custom benchmark commands or fuzzing. / 填写 `.`、`./...` 等包目标，按需添加 **Go arguments**；手写筛选条件、自定义 benchmark 命令或 fuzzing 使用此模式。 |
| **Named Test / Example** | Set **Working directory** to the package directory and **Package / file / subcommand** to `.`. Enter one complete name per line in **Test names**, for example `TestOne`, `ExampleHello` or `TestCases/dup#01`. Names are literal, including punctuation; use the exact child name shown in a completed test tree. / 工作目录填写包所在目录，包目标填写 `.`；**Test names** 每行填写一个完整名称，标点按字面含义处理，子测试名称以已完成的测试树为准。 |
| **One top-level Benchmark** | Current source only: command `test`, package `.`, package working directory, and exactly one full top-level Benchmark name. See the next section; acceptance pending. / 仅当前源码：command 设为 `test`、包为 `.`、工作目录为包目录，仅填写一个顶层 Benchmark 完整名称，见下节；验收待完成。 |

For a named run, select **Run selected names afresh (-count=1)** when you want a new execution instead of a cached result. A selected subtest also runs the necessary parent setup and any descendants of that subtest. Conflicting options such as `-run`, `-bench`, `-count`, fuzzing options or `-overlay` in **Go arguments** or `GOFLAGS` are refused in named mode; remove the conflicting option or choose **Package / manual**. A requested name that never appears in the run is reported as a selection failure, even if Go exits successfully.

先保存 `_test.go`，把光标放在可运行的顶层 `Test…` 或 `Example…` 函数内，再使用上下文 **Run**。示例函数须包含 `Output:` 或 `Unordered output:` 输出声明。光标位于 `t.Run` 函数体内时，运行的是所属顶层测试；单独运行子测试请从已完成的测试树选择。光标不在可识别的运行声明内时，上下文动作仍运行当前包；当前开发源码还识别下节所述限定 Benchmark 声明。也可以使用 **Current File → Run**，选择 **test at caret in …**；GoForge 会在启动前解析当前编辑器的光标位置，不会自动改变已有 Named 或手工配置的范围。解析期间源码或配置变化时，保存并检查后重新选择 Run；过期请求不会悄悄退回整包执行。

在 **Run → Edit Configurations…** 中把 **Command** 设为 `test`，按上表选择模式。需要绕过缓存重新执行指定名称时，勾选 **Run selected names afresh (-count=1)**。选中子测试仍会执行必要的父级准备及该子测试的后代。Named 模式不接受与名称选择冲突的 `-run`、`-bench`、`-count`、fuzzing 或 `-overlay` 等选项，包括 `GOFLAGS` 中的选项；请移除冲突或改用 **Package / manual**。指定名称未实际出现时会显示选择失败，即使 Go 本身以成功状态退出。

## Run one benchmark / 运行单个基准测试

**Current-source feature; Full 10 integrated regression passed. Native UI and Windows acceptance pending.** Save a `_test.go` file, place the caret inside a top-level `Benchmark…` function, and choose its context **Run** or **Current File → Run**. Alternatively, choose **Command: test → Test selection: One top-level Benchmark** in the run configuration, set the package directory and target `.`, and enter one full name in **Test names**. The generated selection uses `-run=^$` and an exact literal `-bench` filter, so ordinary tests and other top-level benchmarks are excluded. The selected function may still create and execute its own child benchmarks.

Supported declarations have a body, one `*testing.B` parameter (named or unnamed, including a named alias for the `testing` import), and no results or type parameters. Type-alias chains, dot imports, binding conflicts and incorrect benchmark signatures are unavailable. Conflicting selectors in **Go arguments** or effective `GOFLAGS` are rejected; a stale file/configuration or an unobserved requested benchmark is a selection error, not a fallback to the entire package. Optional `-benchtime=1x -benchmem` can request one iteration with allocation metrics. The Test/Example fresh-run checkbox does not apply to Benchmark mode.

Use **Run** for this selection. **Selected Benchmark Debug**, direct subbenchmark selection, benchmark-result source navigation/per-node rerun, fuzz selection and comparison charts are not included. Existing Test/Example selection and package/manual configurations retain their separate workflows. These instructions describe the integrated source now included in the 2406 Mac package; native GUI and Windows acceptance are pending. The historical 318d package does not contain this addition. A bounded 2406 native Current File caret Run selected only BenchmarkOne, exited 0 and preserved source bytes; this is not complete GUI acceptance.

**当前源码功能，Full 10 整合回归已通过；原生界面及 Windows 验收待完成。** 保存 `_test.go`，将光标放在顶层 `Benchmark…` 函数内，选择上下文 **Run** 或 **Current File → Run**。也可在运行配置中选择 **Command: test → Test selection: One top-level Benchmark**，工作目录填写包目录、目标填写 `.`，并在 **Test names** 只填一个完整名称。生成的选择使用 `-run=^$` 与精确的字面量 `-bench` 筛选，排除普通测试及其他顶层 Benchmark；被选函数自己创建的子基准仍可能执行。

支持有函数体、单个 `*testing.B` 参数（有名或无名，允许 `testing` 的具名导入别名）、无返回值或类型参数的声明。不支持类型别名链、点导入、绑定冲突或错误签名。**Go arguments** 和实际 `GOFLAGS` 中的冲突筛选参数会被拒绝；源码/配置过期或目标没有实际出现时，报告选择错误，不会退回整包执行。可选填 `-benchtime=1x -benchmem` 运行一次并显示分配指标；Test/Example 的强制重新运行勾选项不适用于 Benchmark 模式。

此选择使用 **Run**。**指定 Benchmark Debug**、直接选择子基准、基准结果源码跳转/逐节点重跑、fuzz 选择及对比图表尚未包含。原有 Test/Example 和包级手工配置保留各自流程。这些操作描述已纳入 2406 Mac 包的整合源码，原生界面与 Windows 验收仍待完成；历史 318d 包不包含这项新增功能。2406 的一项限定原生 Current File 光标位置 Run 检查只运行 BenchmarkOne，退出 0，源码字节未变；这不代表完整界面验收。

## Test results, navigation and rerun / 测试结果、跳转与重跑

After a run finishes, select one actual Test/Example node and use **Rerun selected Go test/subtree**. This starts a fresh run for that node's package and full name, including names generated at runtime and duplicate names such as `dup#01`. The run copies the original configuration's arguments and environment, subject to the directory-change rules below. Select a parent node to rerun its whole subtree.

Double-click a mapped node or use **Go to test declaration**. Runtime subtests use **Go to containing test declaration** and open the top-level test; this does not identify the exact dynamic `t.Run` call. If the source changed or cannot be located, run the test again to refresh navigation. When source mapping is unavailable, the console explains why navigation and selected/failed rerun are unavailable; ordinary package execution remains usable.

After a failure, fix and save the source, then use **Rerun failed top-level tests in one package**. It reruns the full subtree of each affected top-level test, including siblings that previously passed. The action is unavailable when failures span multiple packages or include an unmapped build, process or selection failure. Run the affected packages separately or resolve those errors first. A new result is created; the earlier failed result remains unchanged.

运行结束后，选中一个真实 Test/Example 节点，使用 **Rerun selected Go test/subtree**，即可按该节点所属包及完整名称重新执行；动态名称和 `dup#01` 这类重复名称也以测试树中实际出现的名称为准。重跑沿用原配置的参数和环境，并强制重新执行；切换目录时须满足下文的参数保留规则。选择父节点会运行其整个子树。

双击可定位的节点，或使用 **Go to test declaration** 跳转。运行时子测试对应 **Go to containing test declaration**，定位到所属顶层测试，不代表精确找到动态 `t.Run` 调用。源码变化或无法定位时，重新运行测试以刷新跳转信息。无法建立源码对应关系时，控制台会提示，节点跳转和指定/失败重跑不可用；普通包测试仍可运行。

测试失败后，修复并保存源码，再使用 **Rerun failed top-level tests in one package**。此动作重新运行受影响顶层测试的完整子树，也包括其中此前通过的兄弟子测试。失败涉及多个包，或包含无法定位的构建、进程、选择错误时，按钮不可用；请按包分别运行或先解决这些错误。重跑生成新结果，保留原失败结果。

### Stop a local test run / 停止本地测试

**319d bounded native check passed:** stopping the generated `TestCancellation` fixture retained the completed child as Passed, marked the root/package/unfinished tests Terminated, and removed both tracked Go/test processes. The root showed `Stopped. 1 test passed`, without a synthetic failure test case. This does not cover every cancellation path or Windows.

Use **Run → Stop** for the active test run. Results already reported as passed, failed or skipped remain unchanged; tests that have not reported a result become interrupted when the process finishes. A genuine test or build failure remains a failure. Stopping before a requested name appears records unfinished work, rather than a missing-name assertion failure. Exit codes or output such as `signal: interrupt` alone do not identify an IDE-requested stop. Raw Go `FAIL`/signal output remains visible. The 311e native check exposed a missing newline before subsequent output. The 319d fix passed decoded-output regression tests and a native output recheck. Some test output is attached when a node finishes or is interrupted, so a running tree node can appear before its buffered log text.

After termination, **Rerun failed top-level tests in one package** is disabled: a failed-only run would omit unfinished work. Select a mapped, observed Test/Example or runtime subtest and use **Rerun selected Go test/subtree** to explicitly rerun that target and its descendants. The normal source-mapping and argument restrictions still apply.

**319d 限定原生检查通过：**停止生成的 `TestCancellation` 夹具后，已完成子项保持 Passed，根/包/未完成测试显示 Terminated，两个已跟踪的 Go/测试进程均退出。根显示 `Stopped. 1 test passed`，没有合成失败测试项；这不覆盖所有取消路径或 Windows。

使用 **Run → Stop** 停止当前测试。已经报告的通过、失败和跳过结果保持不变；进程结束时，尚未报告结果的测试标为中断。真实测试失败或构建失败仍保留为失败。停止发生在指定名称出现之前时，记录为尚未完成，不制造“未找到指定名称”的断言失败。不能仅凭退出码或 `signal: interrupt` 输出认定是 IDE 主动停止；原始 Go `FAIL`/信号输出仍显示。311e 原生检查发现一处取消诊断与后续输出间缺换行；319d 修复已通过解码输出回归测试及原生输出复验。部分测试日志在节点完成或中断时才附加到结果，树节点开始运行可能早于其缓冲日志显示。

终止后，**Rerun failed top-level tests in one package** 禁用，避免只重跑失败项却遗漏未完成工作。可明确选中一个已观察到且可定位的 Test/Example 或运行时子测试，使用 **Rerun selected Go test/subtree** 重跑该目标及其后代；仍须满足正常的源码对应和参数限制。

### Selecting a package from a multi-package run / 从多包结果选择单包

A selected or failed rerun changes the working directory to the mapped package. GoForge preserves the original command's effective `GOFLAGS`, including values obtained from `GOENV`. Relative `-modfile`, `-pkgdir` and `-pgo` paths in supported arguments/`GOFLAGS` are made absolute against the original command directory; `-pgo=auto` and `-pgo=off` keep their special meaning.

When the directory changes, flags with complex embedded paths, wrappers or output destinations—such as `-gcflags`, `-asmflags`, `-ldflags`, `-exec`, `-toolexec`, coverage/profile output flags and unknown options—are explicitly refused. Create a named-test configuration directly in the desired package directory and configure those options there. GoForge does not drop them or reinterpret their paths from the new directory. The selected-debug shortcut has its own narrower allowlist below; path preservation does not make `-pgo` or other unsupported debug options available there.

从多包结果重跑指定测试或失败测试时，工作目录会切到对应包。GoForge 保留原命令实际生效的 `GOFLAGS`，包括 `GOENV` 提供的值；受支持参数及 `GOFLAGS` 中的相对 `-modfile`、`-pkgdir`、`-pgo` 路径按原命令目录转为绝对路径，`-pgo=auto/off` 保留特殊含义。

切换目录时，包含复杂内部路径、包装命令或输出位置的参数会明确拒绝，例如 `-gcflags`、`-asmflags`、`-ldflags`、`-exec`、`-toolexec`、覆盖率/性能输出选项及未知选项。请直接在目标包目录新建 Named 配置并设置这些参数；不会丢弃参数或从新目录错误解释旧路径。下面的指定测试调试入口另有更窄的支持清单，路径重写并不代表该入口支持 `-pgo` 等未列选项。

## Debug a selected test / 调试选中的测试

**311e bounded native check passed:** `TestCases/dup#01` launched with `-test.run=^TestCases$/^dup#01$ -test.count=1`, stopped at the intended breakpoint, exposed locals and evaluated an expression; adapter and target exited cleanly. That result belongs to 311e; it is not a new a252 native run. Historical f9e0's no-start remains recorded with an unknown cause in [known limitations](KNOWN-LIMITATIONS.md).

**311e 限定原生检查通过：**`TestCases/dup#01` 以 `-test.run=^TestCases$/^dup#01$ -test.count=1` 启动，在预期断点暂停，可查看变量与求值，适配器和目标进程均正常退出。此结果归属 311e，不是 a252 新原生运行。历史 f9e0 原因不明的未启动观察仍保留在[已知差距](KNOWN-LIMITATIONS.md)。

Save the files and set a breakpoint. In a completed test tree, select one Test/Example or runtime subtest and choose **Debug selected Go test/subtree**. Use the ordinary Debug window for breakpoints, stack frames, variables, watches, stepping and Stop. The selected test and its subtree run once, with the necessary parent setup. Local debugging requires Delve and the operating system's normal debugger permissions. If a source file changes during preparation, save and select Debug again.

The selected-test debug action preserves these supported options, subject to the chosen Go toolchain and host platform:

| Options / 选项 | Supported flags / 支持的参数 |
| --- | --- |
| Test execution / 测试运行 | `-short`, `-v`, `-failfast`, `-timeout`, `-parallel`, `-cpu`, `-shuffle` |
| Build / 构建 | `-tags`, `-mod`, `-modfile`, `-gcflags`, `-ldflags`, `-asmflags`, `-p`, `-compiler`, `-pkgdir`, `-race`, `-msan`, `-asan`, `-trimpath`, `-work`, `-x`, `-buildvcs` |

Unsupported options, including coverage/profile collection, `-vet`, custom test-binary flags and `-exec`, stop this shortcut with an explanation. Edit the run configuration or create an explicit Delve configuration for those needs. This action does not silently discard an unsupported option.

先保存文件、设置断点，再在已完成的测试树中选中一个 Test/Example 或运行时子测试，使用 **Debug selected Go test/subtree**。在普通 Debug 窗口中查看断点、调用栈、变量与 Watch，进行步进或 Stop。选中测试及其子树执行一次，并执行必要的父级准备。本地调试需要 Delve 及操作系统正常的调试权限；准备期间源码变化时，保存后重新选择 Debug。

此入口保留上表所列选项，具体可用性仍受 Go 工具链和操作系统限制。覆盖率/性能数据采集、`-vet`、自定义测试程序参数、`-exec` 等未支持选项会明确报错并停止启动。请修改运行配置，或针对这些需求创建显式 Delve 配置；不会悄悄丢弃参数继续调试。

These workflows require no GoForge account, registration, activation or subscription. Full 10 (2406) passed 723 macOS-host JUnit tests, including platform import/format Undo/Redo and selected Benchmark Run regressions. New-feature native UI and Windows acceptance are pending. The standalone Mac package is 2406 with packager checks and independent archive/source audit passed; its native GUI acceptance is pending. Earlier scalar-type GUI checks remain bound to 5259. Earlier a252, 319d and 311e checks retain their own scope in [known limitations](KNOWN-LIMITATIONS.md).

Windows Run 8 validated older 318d: 31/31 preflight checks and 677 product tests passed with one POSIX-shell skip. Assembly passed; native verification failed at temporary configuration isolation after launcher exit 0 and correct relocated home/JBR observations. Bundled Go/gopls/Delve probes were not reached. It does not validate these 2406 features; see [installation status](INSTALL.md#windows-x64).

In e6b3 native checks, Current File selected `TestOne`, `TestTwo` and the `TestCases` parent correctly, and failed-test rerun executed afresh with `-count=1`. However, selected-test rerun/navigation/debug toolbar buttons stayed disabled; f9e0 repairs this integration. Stop cleaned up the tracked Go process and test child, but the tree showed failed/incomplete instead of Interrupted. That display difference remains recorded for e6b3; the newer local-termination behavior passed the bounded 311e native check described above. The earlier 4764 whole-package selection failure remains recorded in [known limitations](KNOWN-LIMITATIONS.md).

上述功能无需 GoForge 账号、注册、激活或订阅。Full 10（2406）已通过 723 项 macOS 宿主 JUnit，包含导入/格式化 Undo/Redo 平台测试及指定 Benchmark Run 回归；新功能原生界面与 Windows 验收待完成。独立 Mac 包现为 2406，打包检查及独立归档/源码审计通过，2406 界面验收待完成；此前标量类型界面检查仍归属 5259；较早 a252、319d 和 311e 检查保留各自范围，见[已知差距](KNOWN-LIMITATIONS.md)。

Windows 第 8 次验证的是旧 318d：31/31 项预检及 677 项产品测试通过，1 项 POSIX shell 跳过。组装通过；启动器退出 0 且迁移 home/JBR 正确后，在临时配置隔离检查失败，包内 Go/gopls/Delve 探针尚未执行；不能将本轮视作这些 2406 功能的验证。见[安装状态](INSTALL.md#windows-x64)。

e6b3 原生检查正确选择 `TestOne`、`TestTwo` 和 `TestCases` 父测试，失败重跑带 `-count=1` 重新执行；但选中测试的重跑/跳转/调试工具栏按钮仍禁用，f9e0 修复这项接线。Stop 清理了已跟踪的 Go 进程与测试子进程，但测试树显示 failed/incomplete 而非 Interrupted，该显示差异仍归属 e6b3；上文的新终止行为已通过 311e 的限定原生检查。此前 4764 误跑整包的失败仍在[已知差距](KNOWN-LIMITATIONS.md)中保留。

## Change a Go signature / 修改 Go 签名

Place the caret on a declaration's **`func`** keyword, invoke **Go Refactor…**, and select **Change signature across files…**. Edit the proposed function type to reorder existing parameters or remove unused parameters. Keep the original parameter names, parameter types and result types. Review every affected file before Apply; ordinary Undo/Redo covers the whole change.

This bounded operation checks direct callers in the captured project, including synchronized unsaved documents. Caller arguments must be identifiers or literals. Calls, indexing, dereferences, channel receives, indirect function references, variadic expansion, missing references and unsupported generic operations are refused before applying. This reorder/removal dialog does not add parameters; use the separate bounded operation below in current source. For a bounded existing scalar parameter/result type change, use the separate operation below. Arbitrary type changes and moving declarations between packages remain incomplete.

把光标放在声明的 **`func`** 关键字上，执行 **Go Refactor… → Change signature across files…**。在函数类型模板中重排现有参数，或删除未使用参数；保留参数名、类型与返回值类型。检查全部受影响文件后 Apply，普通 Undo/Redo 统一撤销或重做整组修改。

当前会检查项目内直接调用及已同步的未保存文档。实参只接受标识符或字面量；函数调用、下标、解引用、通道接收、间接函数引用、可变参数展开、缺失引用及不支持的泛型操作会在应用前拒绝。此重排/删除对话框不新增参数；当前源码使用下方独立的限定新增操作。现有标量参数/返回值类型可用下方独立的限定操作；任意类型迁移、跨包移动声明仍未完成。

### Add one parameter / 新增一个参数

**Generic-call validation:** a252 passed 12 real-gopls/Go protocol cases, 44 standalone helper test nodes (including two parent tests), and seven ordinary-function protocol regression cases, separately from its 633 product JUnit tests. Both a252 packages are prepared and statically verified. Its bounded macOS native check passed three-file preview/Apply, explicit Save, real bundled Go tests and one global Undo/Redo, with all five fixture files restored exactly at the end. A new caller added after preview was rejected before any edits. This is candidate-only acceptance; automatic-save timing and full GoLand parity are not proven. Historical 311e retains its separate non-generic native result.

Place the caret on a function declaration's **`func`** keyword, invoke **Go Refactor…**, and choose **Add parameter to verified project calls…**. Enter the new parameter name, a predeclared scalar type, the insertion position (`0` means first; the current parameter count means last), and the literal value to insert at every verified call. Select **Preview verified callers**, review the declaration and every affected caller, then **Apply**. Preview leaves files unchanged. After Apply, **save the affected files before running an external Go command**: editor changes may still be unsaved. Ordinary Undo/Redo covers the complete change; cancellation or a stale/conflicting file prevents application.

For example, add `count int` at position `1` with default `1`: `Join(left, right)` becomes `Join(left, 1, right)`, with the corresponding parameter inserted in the declaration. The existing caller arguments stay in their original order. This operation supplies the same constant at every verified call; it does not generate caller-specific expressions or update other projects.

Supported functions are free functions with bodies, including generic functions. Generic calls may infer type arguments, supply all of them, or supply a prefix and infer the rest. Parentheses may surround a fully instantiated callee, such as `(Join[string, string])(...)`; uninstantiated or partially instantiated generic values inside parentheses are refused. Existing type parameters, constraints, explicit type arguments and the order of the original value arguments are preserved. Existing value parameters must each have one non-blank name; the new name must not already occur in the function, its type parameters or constraints. Supported new types are `bool`, `string`, predeclared numeric types, `byte`, `rune` and `uintptr`. Defaults are representable numeric/string/rune literals, signed numeric literals, `true` or `false`; `int`, `uint` and `uintptr` defaults use a portable 32-bit range. Boolean defaults appear as constant comparisons in the preview so caller-local names cannot shadow them.

Methods, variadics, grouped/unnamed parameters, function-value references (including instantiated functions used as values), nested instantiations, tuple/variadic call shapes, runtime/test entry points and compiler/linkage directives are refused. Declaration-file imports need explicit aliases. The new scalar type must not be shadowed by a declaration, import or function type parameter; a new parameter of a custom type or type parameter is not supported. All references must fit the captured project and resolve to verified direct calls; an unrelated same-name identifier can also cause a conservative refusal. Arbitrary default expressions, new imported/custom types, existing type/result changes and cross-package declaration moves remain outside this operation.

**泛型调用验证：** a252 已通过 12 项真实 gopls/Go 协议检查、44 个独立 helper 测试节点（含两个父测试）及 7 项普通函数协议回归，均与 633 项产品 JUnit 分开计数。a252 两平台包已准备并通过静态校验。macOS 限定原生检查通过三文件预览/Apply、显式保存、包内真实 Go 测试及一次全局 Undo/Redo，最终 5 个夹具文件逐字节恢复；预览后新增调用文件时，在任何编辑前拒绝旧预览。这是候选应用的限定验收，未证明自动保存时序或完整 GoLand 对齐；历史 311e 保留独立非泛型原生结果。

把光标放在函数声明的 **`func`** 关键字上，执行 **Go Refactor… → Add parameter to verified project calls…**。填写新参数名、Go 预声明标量类型、插入位置（`0` 表示最前，现有参数数量表示末尾）和每个已验证调用都要插入的字面量值。点击 **Preview verified callers**，检查声明和全部调用方后 **Apply**。预览不修改文件；Apply 后编辑器内容可能尚未保存，**先保存受影响文件，再运行外部 Go 命令**。普通 Undo/Redo 撤销或重做整组修改。取消、文件变化或冲突会阻止应用。

例如，以位置 `1`、默认值 `1` 新增 `count int`，调用 `Join(left, right)` 变成 `Join(left, 1, right)`，声明中同步插入参数。现有实参顺序保持不变。所有已验证调用使用同一个常量，不为不同调用生成不同表达式，也不更新其他项目。

支持有函数体的普通函数，包括泛型函数。泛型调用可以推断类型实参、完整显式指定，或指定前缀后推断其余类型；括号可包住完整实例化的调用目标，例如 `(Join[string, string])(...)`，但括号内未实例化或仅部分实例化的泛型值会拒绝。原类型参数、约束、显式类型实参及原值实参顺序保持不变。现有值参数须逐个具名且不能是 `_`，新名字不能已出现在函数、类型参数或约束中。新类型限 `bool`、`string`、预声明数值类型、`byte`、`rune` 和 `uintptr`。默认值限类型可表示的数值/字符串/字符字面量、带正负号数值或 `true`/`false`；`int`、`uint`、`uintptr` 按可移植的 32 位范围校验。布尔默认值在预览中呈现为常量比较，避免调用方同名变量遮蔽。

方法、可变参数、合并/未命名参数、函数值引用（含实例化后作为值使用的函数）、嵌套实例化、多返回值/可变参数调用形态、运行时或测试入口，以及带编译/链接指令的函数会拒绝。声明文件的导入必须使用明确别名；新增标量类型被声明、导入或函数类型参数遮蔽时也会拒绝，新增参数的类型不能是自定义类型或类型参数。引用必须位于捕获的项目内并可验证为直接调用；无关的同名标识符也可能触发保守拒绝。任意默认表达式、导入类型/自定义类型、修改现有类型或返回值、跨包移动声明仍不在此操作范围内。

### Change an existing scalar type / 修改现有标量类型

In current source, place the caret on a free-function declaration's **`func`** keyword and choose **Go Refactor… → Change scalar parameter/result type…**. Select **Existing parameter** and its zero-based position, or **Single result**; enter a Go predeclared scalar type and select **Compile and preview**. Inspect the change, Apply, and save. The operation edits one type token; it checks captured callers without inserting conversions or rewriting their expressions. Generic function constraints and type arguments are retained. Undo/Redo uses the ordinary refactoring workflow.

Both the captured module and changed module, including tests, must compile in a temporary copy. This check does not execute project initialization, `TestMain`, or tests. Compilation proves static compatibility within that snapshot; changing a type can still change runtime behavior. Missing cached dependencies or incompatible callers produce a refusal before edits.

The current scope requires one standalone module, default host build settings and already-cached dependencies. Methods, function values, grouped parameters, multiple results, build conditions, cgo/assembly, vendor trees, embedded resources, local replacements and references outside the captured project are refused. Save or revert module/workspace metadata first; metadata changes invalidate this operation's preview. The preview also binds the selected Go SDK and effective settings, including cgo and CPU feature selection; changing the SDK or GOENV configuration requires a fresh preview. Pure-Go dependencies under the actual cgo tag remain supported; dependencies requiring C compilation are refused. Stop remote analysis before using this local check. This does not provide arbitrary type migration or full Change Signature parity.

在当前源码中，将光标放到普通函数声明的 **`func`** 上，选择 **Go Refactor… → Change scalar parameter/result type…**。选择现有参数及从 `0` 开始的位置，或选择单一返回值，填写 Go 预声明标量类型，点击 **Compile and preview**。检查后 Apply 并保存；普通 Undo/Redo 可撤销或重做。操作只修改一个类型标识符，检查调用方但不自动插入强制转换或改写表达式，保留泛型约束与类型实参。

检查会在临时副本内分别编译修改前后的模块及测试，不执行项目初始化、`TestMain` 或测试函数。编译通过只证明该快照的静态兼容性，类型变化仍可能改变运行行为。依赖未缓存或调用不兼容会在编辑前拒绝。当前限单独模块、默认本机构建设置；方法、函数值、合并参数、多返回值、条件构建、cgo/汇编、vendor、嵌入资源、本地 replace 和项目外引用仍不支持。先保存或还原模块元数据，元数据变化会使本操作预览失效；预览也绑定 Go SDK 及有效配置（含 cgo 和 CPU 特性），切换 SDK 或修改 GOENV 后需重新预览。实际 cgo 标签下的纯 Go 依赖仍可使用，需要 C 编译的依赖会拒绝；远端分析需先停止。这不等于任意类型迁移或完整签名重构。

### Preview safety and file changes / 预览期间的文件变化

Keep the project file set stable from the refactoring request through Apply. Creating, deleting or renaming a child entry, or changing its file/directory kind, in a captured directory invalidates the preview—even when the name is not a Go file. Refresh the project and request a fresh preview. The guard covers at most **1,024 captured directories and 16,384 direct child entries in total**; larger inventories are refused, so open a smaller project scope.

This file-list check does not watch the contents of existing non-Go metadata files or scan inside excluded directories. Existing Go document/disk guards still apply. Changes to `go.mod`, `go.work`, build settings or analysis configuration are not newly proven by this guard: discard the preview and request it again after such changes rather than relying on automatic invalidation.

从请求重构到 Apply，应保持项目文件集合稳定。捕获目录中的子项新增、删除、改名或文件/目录种类变化会使预览失效，即使变化的是非 Go 文件名；请刷新项目后重新请求预览。检查上限为 **1,024 个捕获目录及总共 16,384 个直接子项**，超限会拒绝，请缩小打开的项目范围。

这项文件清单检查不监听已有非 Go 元数据文件的内容，也不扫描排除目录内部；原有 Go 文档与磁盘变化检查仍然保留。它没有新增证明 `go.mod`、`go.work`、构建设置或分析配置变更的完整失效机制；修改这些内容后应放弃当前预览、重新请求，不要依赖自动失效。

## SSH tasks and remote analysis / SSH 任务与远端分析

1. Open **Go Remote → Connection**. Enter a host/config alias, port, optional user/key/known-hosts path, absolute remote workspace, and Go executable. Verify the host through ordinary OpenSSH before connecting. **Save profile** saves coordinates without connecting.
2. Use **Test connection**, **Workspace** browsing, or **SSH Terminal**. Background tasks use the existing key/agent; the terminal supports normal interactive authentication.
3. Review upload/download scope before applying. Upload creates an owned snapshot; it does not overwrite arbitrary existing files. Download creates a new local file and refuses replacement.
4. In **Go analysis**, provide a remote gopls executable and explicitly start a snapshot session. The remote requires a POSIX environment, Python 3, Go, gopls, and its dependencies.
5. Open local Go files normally. Completion, diagnostics and supported semantic operations run through SSH; unsaved documents use overlays. A conflicting remote file stops synchronization. Stop the analysis session to return to the local backend.

在 **Go Remote → Connection** 填写连接与远端目录，先按 OpenSSH 正常流程核实主机密钥。Save profile 不会自动连接。任务使用已有密钥/agent，交互认证可使用 SSH Terminal。传输须先预览范围；分析需要显式启动独立快照，不覆盖现有远端目录。未保存内容通过 overlay 传递，冲突会停止同步。源码集合固定，新增/删除文件后需建立新会话；这不是完整远端 IDE 后端，外部依赖路径不会伪装成本地文件。

## Remote debugging / 远端调试

In **Go Remote → Go debug**, select **Package**, **Tests**, **Executable**, or **Attach**, review the configuration, then start. Set the remote Delve executable, package/binary path or PID, program arguments and environment as needed. Arguments are parsed as arguments rather than evaluated by a shell. Environment values belong to the session and are not stored as profile credentials.

Local saved Go sources must match the selected remote workspace. Save explicitly and transfer/prepare matching sources before starting; debug does not silently save or overwrite your files. The remote needs Python 3, Go, Delve, dependencies, and normal OS debugging permissions. Executables should be compiled with debugging information, for example `-gcflags="all=-N -l"`.

The adapter binds remote DAP to loopback and relays it through SSH. Local/remote source paths are mapped for the verified workspace. Use the ordinary Debug window for breakpoints, frames, variables, expressions, stepping and stop. **Stop terminates a target launched by the session; attach detaches and preserves the pre-existing target.** External dependency sources and arbitrary remapped roots are not exposed as local project files.

在 **Go debug** 选择包、测试、可执行文件或 PID attach，Review 后启动。使用已保存且与远端匹配的源码；不会自动保存、上传或覆盖。参数按普通参数解析，不通过 shell 执行；环境值仅属于当前会话。远端需要 Python 3、Go、Delve 和正常调试权限。DAP 仅绑定远端 loopback，通过 SSH 转发。普通 Debug 窗口支持断点、栈、变量、求值和步进。**停止启动型会话会结束其目标；停止 attach 会话会脱离并保留原进程。**


### Multi-module workspaces / 多模块工作区

Open the common directory containing **`go.work`**, with all `use` modules and local `replace` directories beneath that root. Their paths must be relative and free of symlinks. Keep the same relative layout remotely. In **Go debug**, enter the package directory relative to the common root, such as `app`, instead of selecting a non-module workspace root. Review lists the bound modules before starting.

Debugging uses a private copy of `go.work` and `go.work.sum`; the original files remain unchanged. The current review supports 1–32 modules, up to 64 local replacement directories and at most 512 visible saved files within the startup-manifest limit. Dependencies must already be available remotely. Current source also supports explicitly reviewed **Go analysis** snapshots using the same contained module topology. Analysis uses a private workspace file, supports cross-module unsaved overlays, and stops on local or remote module/workspace metadata changes. Start a fresh reviewed session after metadata or file-set changes. The 512-file limit above is the debug startup limit; analysis retains its separate snapshot size limits. Windows drive and UNC path mapping has protocol checks, but Windows-native acceptance remains pending.

打开包含 **`go.work`** 的公共目录，所有 `use` 模块及本地 `replace` 目录都须在该根目录下，使用相对路径且不经过符号链接；远端保持相同相对布局。在 **Go debug** 输入相对公共根目录的包路径，例如 `app`，不要选择本身不是模块的工作区根目录。Review 会列出本次绑定的模块。

调试使用 `go.work` 与 `go.work.sum` 的私有副本，不改写原文件。目前支持 1–32 个模块、最多 64 个本地替换目录、最多 512 个可见已保存文件，并受启动清单大小限制；远端依赖须预先就绪。当前源码的 **Go analysis** 也支持按同样目录边界显式审阅的多模块快照，使用私有工作区文件，支持跨模块未保存 overlay；本地或远端模块/工作区元数据变化会停止会话，文件集合或元数据变化后需重新审阅启动。上述 512 文件限制属于调试启动，分析保留其独立快照大小限制。Windows 盘符及 UNC 映射已有协议检查，尚未完成 Windows 原生验收。
