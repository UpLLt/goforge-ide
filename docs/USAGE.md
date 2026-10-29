# Workflows / 功能用法

Use action search for registered menu actions hidden by your layout; console-specific actions require the corresponding console. GoForge does not require its own login. Service credentials belong to the services you choose to connect.

布局隐藏的已注册菜单动作可用动作搜索查找；控制台专属动作需在对应控制台使用。GoForge 无自身登录门槛；外部服务使用各自的认证。

Verification differs by build and host; see [current acceptance and limitations](KNOWN-LIMITATIONS.md#verification-scope--验证范围). Instructions describe current source, not a complete native pass. / 构建与宿主验收分别记录，见[当前验收与差距](KNOWN-LIMITATIONS.md#verification-scope--验证范围)；以下说明当前源码用法，不代表完整原生通过。

Current Full 24 passed 915 macOS-host JUnit cases and independent build readback. It adds owned temporary-file cleanup with bounded Windows retries and visible persistent failures; its Mac archive and all 72 packaged protocol cases passed independent readback, including bundled JNA and cleanup checks. New GUI/Windows checks remain pending, with 32 shutdown warnings recorded. Full 22 Windows Run 15 passed product tests, plugin readback and nine native groups, but failed later packaged-method cleanup; no release archive was produced. / 当前 Full 24 的 915 项 macOS 宿主 JUnit 与独立构建读回通过；新增自有临时文件清理、Windows 有界重试及持续失败显示，Mac 归档及全部 72 个包内协议场景已通过独立读回，包括包内 JNA 与清理检查，32 条关闭阶段警告保留；新 GUI/Windows 验证仍待完成。Full 22 第 15 次 Windows CI 通过产品测试、插件读回和 9 组原生检查，但后续包内方法清理失败，未生成发行归档。

| Entry / 入口 | Steps / 操作 | Boundary / 边界 |
| --- | --- | --- |
| Go editor | Use local comments, brace pairing and Enter without a language server; open a module for gopls completion, Go to Declaration, Find Usages, Rename and Reformat. / 本地注释、括号和回车无需语言服务；模块内使用 gopls 语义功能。 | Formatting is scoped to supported Go documents; complete editing parity is unverified. / 格式化限受支持 Go 文档，尚未证明完整编辑对齐。 |
| Run / Debug | Select a Go configuration, choose package/arguments/environment, run or debug, and use the Debug pane for frames/variables/step/stop. / 配置包、参数、环境后运行或调试。 | Local debug needs Delve and the OS debugger prerequisites. Expression completion covers the implemented frame/local/field cases, not every Go expression. |
| Go Refactor… | Select source, choose an available action, review all file changes, then Apply. Use normal Undo/Redo. / 选择源码，预览全部文件改动，再应用或撤销。 | Unsupported resource operations/commands and stale/conflicting files are refused. Cross-package move/signature parity is incomplete. |
| Run → Run Go Coverage | Save the module, run coverage, select a row to navigate, use Clear to remove coverage marks. / 保存模块后运行覆盖率，点行跳转，Clear 清除标记。 | One module; saved-file snapshots; no combined go.work report. |
| Tools → Go Profiling… | Collect CPU/memory profiles for one package or open pprof data; use top/compare/viewer. / 单包采集或打开 pprof，查看 top、比较与交互视图。 | Viewer binds loopback; graph layout needs Graphviz. |
| Tools → Go Database | Add a connection; browse schema; execute SQL. Select Begin/Commit/Rollback then Apply. Review primary-key edits before applying; export CSV. / 连接数据库后执行 SQL，事务选动作再 Apply。 | Passwords are not persisted. Row editing requires a supported primary key. Explain estimates SELECT/WITH; no ANALYZE execution. |
| Tools → GoForge HTTP Client | Open `.http`/`.rest`, select environment files explicitly, run one/all requests, inspect assertions and results. / 打开请求文件、选择环境、运行并查看结果。 | Bounded redirects enabled by default with per-request opt-out; no script execution, WebSocket or gRPC. Raw response save is a separate explicit action. |
| Containers → Docker | Choose a context; refresh containers/images; inspect logs, start/stop or use reviewed build/Compose actions. / 选择 context 后管理。 | Requires Docker CLI/daemon. Cancel stops the local command, not necessarily an accepted daemon operation. |
| Containers → Kubernetes | Choose context/namespace, refresh, inspect logs/describe, preview diff/apply, use loopback forwarding and Cancel. / 选择集群与命名空间后操作。 | Requires kubectl and access to the selected cluster. Deletion checks resource UID. No global context switching. |
| Containers → Dev Containers | Load and review the supported configuration, Start, open Terminal; execute postCreate separately if desired; Stop or Remove owned resources. / 先预览，再启动；postCreate 单独触发。 | Basic image/build projection, not the complete specification. Unsupported metadata/configuration is rejected or explicitly omitted. |

## Local Go editing / Go 本地编辑

Use **Comment with Line Comment** or **Comment with Block Comment** on the current line/selection, then normal Undo/Redo. The block action refuses a selection containing a string's `*/` rather than inserting a broken comment. Braces `()`, `[]` and `{}` pair in Go code and stay ordinary text inside comments/literals; the existing closer can be skipped normally. Project indentation settings override the default four-column tabs.

Enter inside a Go block uses bounded local indentation. With smart indentation enabled, Enter inside a raw backtick string adds a newline without copying the surrounding Go indent, consuming only the spaces/tabs immediately to the right of the caret. It does not cross an existing newline, closing backtick or non-whitespace text. Undo restores the original line and whitespace. Smart indentation and **Insert pair } on Enter** are separate platform options; turning off one does not necessarily disable the other. This is not a replacement for gopls Reformat or general expression/switch-case formatting.

Inside an unfinished `(` or `[`, Enter uses the opening line’s leading indentation plus the project’s continuation-indent width. Nested calls use their own nearest opening; commas keep one continuation level, and a matching closing delimiter returns to its opening line’s indentation. Function-literal `{}` bodies retain block indentation. Comments and literals are opaque; malformed or oversized contexts use a conservative fallback. Normal platform Enter, multi-caret and Undo/Redo actions remain in use. The added platform cases passed on the macOS host. Full 19 native Enter after a call opening, an argument comma and a case label matched the same GoLand fixture; this does not cover every editing context. The corresponding Full 19 Windows product tests passed; interactive Windows GUI remains unverified.

使用 **Comment with Line Comment / Comment with Block Comment** 操作当前行或选区，普通 Undo/Redo 可撤销重做；包含字符串 `*/` 的块注释选区会拒绝包裹。代码中的括号可配对，注释和字面量中的括号作为普通文本。默认使用视觉宽度 4 的 tab，项目设置可覆盖。

Go 块内回车使用限定范围的本地缩进。开启智能缩进时，原始字符串内回车不复制外围 Go 缩进，只移除光标右侧紧邻的空格/tab 后插入换行，不跨已有换行、闭合反引号或非空白内容；Undo 恢复原文与空白。智能缩进与 **Insert pair } on Enter** 是独立设置。一般表达式续行、switch-case 和更广多光标行为仍需继续验证。

在未闭合的 `(` 或 `[` 内回车，使用起始行的前导缩进加项目续行宽度；嵌套调用选择最近起始括号，逗号后保持同一级续行，匹配闭合括号返回起始行缩进。函数值的 `{}` 保持块缩进，注释/字面量不参与括号计数；语法不完整或超出扫描范围时保守回退。仍使用平台 Enter、多光标和 Undo/Redo 动作。新增平台用例已在 macOS 宿主通过；Full 19 原生检查在调用起始括号、参数逗号及 case 标签后回车，与同一 GoLand 夹具结果一致，不代表全部编辑语境已对齐。对应 Full 19 的 Windows 产品测试已通过，Windows 交互界面仍待验收。

### Formatting on save / 保存格式化

In **Settings → Tools → Actions on Save → Reformat code**, review whether formatting is enabled and whether Go is selected. The initial default selects Go only; persisted explicit-off and type choices override it. With a ready connected gopls overlay, the Go save action awaits the response and applies a fully validated edit before the platform performs its final save. A later manual edit cancels that pending action; the newer input remains unsaved until you save it. Invalid raw syntax displays a formatting-refusal notification and preserves the input.

No ready server or connected overlay causes a conservative skip. **Changed lines only** with an active VCS also skips this whole-document-only operation. An Editor closed while its overlay remains connected is covered; closing a tab that disconnects the overlay is not. Imports-on-save is described below. Automatic rearrangement and cleanup remain unimplemented. Manual **Reformat Code** remains available for open editors; the dependency's manual no-Editor formatting limitation remains.

A narrow gopls response filter preserves horizontal whitespace on the active caret's whitespace-only Go-code line when document, overlay, version and caret still match. It does not preserve raw-string/comment whitespace by guessing, and does not handle every merged-edit shape. It also applies to explicit whole-file gopls formatting; it is not a global whitespace policy or a setting change.

在 **Settings → Tools → Actions on Save → Reformat code** 检查开关与文件类型。初始默认只选 Go，已持久化的关闭或类型选择优先。服务器和文档 overlay 就绪时，Go 保存动作等待响应、完整校验后应用，由平台完成最终保存；期间用户继续输入会取消旧动作，新输入保持未保存。无效 raw 显示格式化拒绝通知并保留输入。

无就绪服务/overlay，或启用 VCS 且选择 **Changed lines only** 时保守跳过。只关闭 Editor 而保留连接 overlay 的路径已有测试，关闭标签导致 overlay 断开则不作保证。保存时导入整理见下节；自动重排和 cleanup 尚未实现；打开编辑器后的手动 **Reformat Code** 保留，无 Editor 的手动格式化依赖限制仍在。光标空白行保护只覆盖来源/版本/光标完全匹配的限定水平空白编辑，也作用于显式整文件 gopls 格式化，不是全局空白规则。

### Organize imports on save / 保存时整理导入

Enable **Settings → Tools → Actions on Save → Optimize imports**, then select **Go** in its file-type chooser. This preference is independent of **Reformat code**. Open a module Go file and wait for gopls before saving; the save action does not start a cold server.

When both actions are enabled, imports run first, the overlay is synchronized, then formatting uses the updated document. VCS **Changed lines only** skips full-file formatting but does not disable independently selected imports. Only exact-version direct edits to the current document are accepted; command-based, unresolved, multi-file, ambiguous or stale changes are refused. No ready overlay means no import edit. If later formatting fails or is cancelled, valid imports already applied may remain. Inspect the buffer before saving again. Combined imports/formatting use one platform Undo/Redo command.

在 **Settings → Tools → Actions on Save → Optimize imports** 开启，并在文件类型选择器选 **Go**。此开关独立于 **Reformat code**。先打开模块内 Go 文件并等待 gopls 就绪；保存动作不会临时启动服务。

两项均开启时先整理导入，刷新 overlay，再格式化更新后的文档。VCS 的 **Changed lines only** 跳过整文件格式化，但不关闭独立导入整理。只接受当前文档、版本精确匹配的直接修改；命令式、未解析、跨文件、歧义或过期修改会拒绝，无就绪 overlay 时不改导入。后续格式化失败或取消时，已完成的有效导入可能保留，请检查缓冲区后再保存。导入与格式化合并为一次平台 Undo/Redo。

### Optimize imports manually / 手动整理导入

Open a Go file, wait for gopls, then choose **Code → Optimize Imports**. This uses the current buffer independently of save preferences. Validated edits are applied in one undoable command; save explicitly when ready. Changed documents/connections, disabled features, unsupported edits, cancellation and timeouts do not apply stale edits. A cold or unopened server document is reported as unavailable.

打开 Go 文件并等待 gopls，选择 **Code → Optimize Imports**。它独立于保存开关，使用当前缓冲区，将有效修改作为一次可撤销命令应用，需要时再显式保存。文档/连接变化、功能关闭、不支持的编辑、取消或超时均不会应用过期修改；未就绪或未打开的服务端文档会提示不可用。

## Run named Go tests / 运行指定 Go 测试

Save the test file, place the caret inside a runnable top-level `Test…` or `Example…` function in a `_test.go` file, and use its context **Run** action. GoForge selects the containing function. An example needs an `Output:` or `Unordered output:` directive to be runnable. A caret inside a `t.Run` body selects its top-level test; select an individual runtime subtest from the completed test tree instead. Outside a recognized runnable declaration, the context action remains a package run. Current development source also recognizes the bounded Benchmark declarations described below. You can also use **Current File → Run** and choose **test at caret in …**; GoForge resolves the current editor's caret before starting. This does not retarget an already named or manual configuration. If the file or configuration changes while GoForge is resolving the target, save/review it and choose Run again; a stale request will not silently run the package.

In **Run → Edit Configurations…**, choose the Go configuration and set **Command** to `test`. **Test selection** offers the following modes; the Benchmark mode has separate native acceptance limits:

| Mode / 模式 | Configuration / 配置 |
| --- | --- |
| **Package / manual** | Set a package target such as `.` or `./...` and supply ordinary **Go arguments** as needed. Use this mode for manual filters, custom benchmark commands or fuzzing. / 填写 `.`、`./...` 等包目标，按需添加 **Go arguments**；手写筛选条件、自定义 benchmark 命令或 fuzzing 使用此模式。 |
| **Named Test / Example** | Set **Working directory** to the package directory and **Package / file / subcommand** to `.`. Enter one complete name per line in **Test names**, for example `TestOne`, `ExampleHello` or `TestCases/dup#01`. Names are literal, including punctuation; use the exact child name shown in a completed test tree. / 工作目录填写包所在目录，包目标填写 `.`；**Test names** 每行填写一个完整名称，标点按字面含义处理，子测试名称以已完成的测试树为准。 |
| **One top-level Benchmark** | Command `test`, package `.`, package working directory, and exactly one full top-level Benchmark name. See the next section. / command 设为 `test`、包为 `.`、工作目录为包目录，仅填写一个顶层 Benchmark 完整名称，见下节。 |

For a named run, select **Run selected names afresh (-count=1)** when you want a new execution instead of a cached result. A selected subtest also runs the necessary parent setup and any descendants of that subtest. Conflicting options such as `-run`, `-bench`, `-count`, fuzzing options or `-overlay` in **Go arguments** or `GOFLAGS` are refused in named mode; remove the conflicting option or choose **Package / manual**. A requested name that never appears in the run is reported as a selection failure, even if Go exits successfully.

先保存 `_test.go`，把光标放在可运行的顶层 `Test…` 或 `Example…` 函数内，再使用上下文 **Run**。示例函数须包含 `Output:` 或 `Unordered output:` 输出声明。光标位于 `t.Run` 函数体内时，运行的是所属顶层测试；单独运行子测试请从已完成的测试树选择。光标不在可识别的运行声明内时，上下文动作仍运行当前包；当前开发源码还识别下节所述限定 Benchmark 声明。也可以使用 **Current File → Run**，选择 **test at caret in …**；GoForge 会在启动前解析当前编辑器的光标位置，不会自动改变已有 Named 或手工配置的范围。解析期间源码或配置变化时，保存并检查后重新选择 Run；过期请求不会悄悄退回整包执行。

在 **Run → Edit Configurations…** 中把 **Command** 设为 `test`，按上表选择模式。需要绕过缓存重新执行指定名称时，勾选 **Run selected names afresh (-count=1)**。选中子测试仍会执行必要的父级准备及该子测试的后代。Named 模式不接受与名称选择冲突的 `-run`、`-bench`、`-count`、fuzzing 或 `-overlay` 等选项，包括 `GOFLAGS` 中的选项；请移除冲突或改用 **Package / manual**。指定名称未实际出现时会显示选择失败，即使 Go 本身以成功状态退出。

## Run one benchmark / 运行单个基准测试

Save a `_test.go` file, place the caret inside a top-level `Benchmark…` function, and choose its context **Run** or **Current File → Run**. Alternatively, choose **Command: test → Test selection: One top-level Benchmark** in the run configuration, set the package directory and target `.`, and enter one full name in **Test names**. The generated selection uses `-run=^$` and an exact literal `-bench` filter, so ordinary tests and other top-level benchmarks are excluded. The selected function may still create and execute its own child benchmarks.

Supported declarations have a body, one `*testing.B` parameter (named or unnamed, including a named alias for the `testing` import), and no results or type parameters. Type-alias chains, dot imports, binding conflicts and incorrect benchmark signatures are unavailable. Conflicting selectors in **Go arguments** or effective `GOFLAGS` are rejected; a stale file/configuration or an unobserved requested benchmark is a selection error, not a fallback to the entire package. Optional `-benchtime=1x -benchmem` can request one iteration with allocation metrics. The Test/Example fresh-run checkbox does not apply to Benchmark mode.

After **Run**, current source can debug an explicitly observed top-level node or navigate to its declaration. A separate **Debug Go Benchmark at Caret** action now starts directly from the saved editor declaration; see below. Guarded per-node rerun is described below. Direct subbenchmark/fuzz selection and comparison charts remain unavailable. Existing Test/Example and package/manual workflows retain their separate behavior. The new editor/navigation actions have bounded Full 17 macOS evidence, outside the Full 13/Run 10 baseline; Windows IDE validation remains pending.

保存 `_test.go`，将光标放在顶层 `Benchmark…` 函数内，选择上下文 **Run** 或 **Current File → Run**。也可在运行配置中选择 **Command: test → Test selection: One top-level Benchmark**，工作目录填写包目录、目标填写 `.`，并在 **Test names** 只填一个完整名称。生成的选择使用 `-run=^$` 与精确的字面量 `-bench` 筛选，排除普通测试及其他顶层 Benchmark；被选函数自己创建的子基准仍可能执行。

支持有函数体、单个 `*testing.B` 参数（有名或无名，允许 `testing` 的具名导入别名）、无返回值或类型参数的声明。不支持类型别名链、点导入、绑定冲突或错误签名。**Go arguments** 和实际 `GOFLAGS` 中的冲突筛选参数会被拒绝；源码/配置过期或目标没有实际出现时，报告选择错误，不会退回整包执行。可选填 `-benchtime=1x -benchmem` 运行一次并显示分配指标；Test/Example 的强制重新运行勾选项不适用于 Benchmark 模式。

**Run** 后可调试明确出现的顶层节点，或跳转到其声明；新增独立 **Debug Go Benchmark at Caret** 动作可直接从已保存的编辑器声明启动，见下方。受保护的逐节点重跑见下方；直接子基准/fuzz 选择及对比图表仍不可用；原 Test/Example 和包级手工配置保留各自行为。新编辑器入口/跳转已有限定 Full 17 macOS 证据，不属于 Full 13/第 10 次基线；Windows IDE 验证仍待完成。

### Rerun one completed benchmark / 重跑一个已完成基准

Wait for the Run console to finish, select one explicitly observed top-level Benchmark result, then choose **Rerun selected Go benchmark** in that console. It runs only that benchmark in its mapped package with the exact `-bench` selector and `-run=^$`. Original `-benchtime`, CPU settings, arguments and environment remain in effect; it does not add the editor Debug action's `1x` timing. Cross-package selections use the existing [argument-preservation rules](#selecting-a-package-from-a-multi-package-run--从多包结果选择单包). The selected benchmark can still run its own children.

The source captured by that run must still have its cached document, remain saved, have its edits fully synchronized by the IDE and match the result snapshot. After editing it, run the package/benchmark afresh to refresh the result. Source edits or renames after selection are checked again before launch. Running, stopped, inferred, foreign/disposed, multiple and subbenchmark selections are refused. A terminal failed benchmark can be rerun explicitly, while **Rerun failed top-level tests in one package** retains its ordinary Test/Example rules. The workflow passed Full 22 macOS-host and Run 15 Windows product tests; native IDE acceptance remains pending.

等待 Run 控制台结束，选中一个明确出现的顶层 Benchmark 结果，使用该控制台的 **Rerun selected Go benchmark**。它在结果对应包内用精确 `-bench` 和 `-run=^$` 只运行此基准，保留原 `-benchtime`、CPU 设置、参数和环境，不增加编辑器 Debug 的 `1x` 时长。跨包选择沿用[参数保留规则](#selecting-a-package-from-a-multi-package-run--从多包结果选择单包)；被选基准自己创建的子基准仍可能运行。

本次运行捕获的源码须仍有缓存文档、已保存、IDE 已完成编辑同步且与结果快照一致；编辑后请重新运行包或基准以刷新结果。选中后源码编辑或重命名也会在启动前再次检查。运行中、已 Stop、仅推测、外来/已关闭、多选及子基准结果均拒绝。已终结的失败基准可明确重跑，但 **Rerun failed top-level tests in one package** 仍只遵守原 Test/Example 规则。该流程通过 Full 22 macOS 宿主与第 15 次 Windows 产品测试；原生 IDE 验收仍待完成。

### Debug one observed benchmark / 调试一个已运行的基准测试

First Run the benchmark and wait for its Run console to finish. Save the source, set a breakpoint inside the top-level Benchmark, select its explicitly observed node, then choose **Debug selected Go test/benchmark**. The debug-specific selection uses an exact `-test.bench` filter and `-test.run=^$`, excluding ordinary tests and other top-level benchmarks. Optional `-benchtime=1x` and `-benchmem` are preserved; without an explicit timing flag, Go keeps its normal benchmark timing. Use Debug for frames, variables, stepping and Stop.

The action refuses inferred/output-only nodes, subbenchmark nodes, foreign/disposed consoles and runs stopped locally. It preserves source/configuration guards and rejects unsupported flags. Current source adds completed-node declaration navigation and a separate direct editor Debug action. Selected-node Run uses the separate rerun action above; Debug keeps its own argument rules. Earlier observed-node platform tests passed on macOS and Windows; those results do not cover the new entries. A bounded bdf5 macOS native session passed breakpoint, evaluation, step, resume and exit; a separate Stop removed adapter/target processes but left a generated `__debug_bin…` file. Full 17 later passed normal-exit output cleanup but failed toolbar Stop; Full 18 top-toolbar Stop/normal-exit cleanup and archive checks passed; Windows and full GUI checks remain pending. Windows Run 10 checked direct Delve normal/cancel protocols, not this IDE toolbar or benchmark workflow. See the linked verification scope.

先 Run 基准并等待 Run 控制台结束，保存源码、在顶层 Benchmark 内设断点，选中实际出现的节点，再选择 **Debug selected Go test/benchmark**。调试使用精确 `-test.bench` 和 `-test.run=^$`，排除普通测试及其他顶层基准；可选 `-benchtime=1x`、`-benchmem` 会保留，没有显式时间参数时仍使用 Go 默认基准运行时长。通过 Debug 查看栈、变量、步进和 Stop。

仅从输出推测的节点、子基准、其他/已关闭控制台及本地 Stop 的运行不授权此动作；源码/配置守卫与不支持参数拒绝仍保留。当前源码新增完成节点声明跳转及独立编辑器直接 Debug，指定节点 Run 使用上方独立重跑动作，Debug 保留自己的参数规则。较早选中节点流程的 macOS/Windows 平台测试通过，不包含新入口；bdf5 macOS 限定原生会话通过断点、表达式、步进、继续与退出，但另一次 Stop 清除适配器/目标进程后仍留下 `__debug_bin…` 文件，后来 Full 17 正常退出输出清理通过，但工具栏 Stop 失败；Full 18 顶部 Stop/正常退出清理与归档检查通过，Windows 与完整界面仍待验收。Windows 第 10 次检查了直接 Delve 正常/取消协议，不是本 IDE 工具栏或 Benchmark 流程；详见验证范围。

Full 17 packaged macOS checks passed direct editor Benchmark Debug normal exit and exact completed-result navigation, but failed toolbar Stop cleanup. Full 18 repairs passed 805 macOS-host tests, independent archive audit and bounded top-toolbar Stop/normal Resume cleanup, followed by normal app exit and signature recheck. Native Windows IDE cleanup workflows and complete GUI acceptance remain pending. / Full 17 Mac 包已限定验证编辑器直接 Benchmark Debug 正常退出及结果精确跳转，工具栏 Stop 清理失败；Full 18 修复通过 805 项 macOS 宿主测试、独立归档审计和顶部 Stop/Resume 正常结束限定清理，随后应用正常退出及再次验签通过；Windows 原生 IDE 清理工作流与完整界面验收仍待完成。

### Debug a benchmark at the editor caret / 直接调试光标所在基准测试

Save the `_test.go` file and set a breakpoint inside a supported top-level `Benchmark…` function. Put the caret inside that function, then choose **Run → Debug Go Benchmark at Caret** or the same editor context-menu action. When the declaration is already known, the menu may show **Debug BenchmarkName at Caret**. No prior benchmark Run is needed. Preparation checks the current package build and creates a temporary local Delve configuration for the selected declaration.

This entry uses an exact literal `-test.bench` filter, `-test.run=^$`, `-test.count=1` and `-test.benchtime=1x`. Ordinary tests and other top-level benchmarks are excluded; the selected function may still execute its own child benchmarks. Current Go tool paths and project build settings apply. Save all package source changes first. A moved/renamed file, changed source or Go settings, build-excluded declaration, conflicting timing/CPU-list options or unsupported flags cause refusal rather than package-wide fallback. Preparation cancellation does not start a debug session. The same source/path/settings guard applies to later launches of this temporary configuration; select the action again after changes.

先保存 `_test.go`，在受支持的顶层 `Benchmark…` 函数内设断点，将光标放在该函数中，选择 **Run → Debug Go Benchmark at Caret** 或编辑器右键中的同名动作。已识别声明时，菜单可能显示 **Debug BenchmarkName at Caret**。无需先 Run 基准；准备阶段检查当前包的实际构建范围，并为所选声明创建临时本地 Delve 配置。

该入口使用精确字面量 `-test.bench`、`-test.run=^$`、`-test.count=1` 和 `-test.benchtime=1x`，排除普通测试及其他顶层 Benchmark；所选函数仍可能执行它自行创建的子基准。沿用当前 Go 工具路径和项目构建设置，先保存包内源码改动。文件移动/重命名、源码或 Go 设置变化、声明不在当前构建范围、时间/CPU 列表冲突或不支持的参数会拒绝，不退回整包调试；取消准备不会启动调试会话。临时配置后续启动仍校验原源码、路径和设置，变化后请重新选择此动作。

### Navigate from a completed benchmark / 从已完成基准结果跳转

Wait for a benchmark Run console to finish, select an explicitly observed top-level Benchmark node, then use **Go to benchmark declaration**. The editor opens at the function name's exact parsed source position. A live, locally stopped, inferred/output-only, subbenchmark, foreign or disposed result does not authorize this navigation. The captured file/text must still match; if it changed, Run again to refresh the result. Navigation does not run the benchmark; use the separate rerun action above.

等待 Benchmark Run 控制台结束，选中实际出现的顶层 Benchmark 节点，使用 **Go to benchmark declaration**，编辑器会定位到源码解析确认的函数名位置。运行中、本地 Stop、仅从输出推测、子基准、其他或已关闭控制台结果不允许此跳转；捕获文件/文本变化后需重新 Run 刷新。跳转本身不会运行基准；重跑需使用上方独立动作。

## Test results, navigation and rerun / 测试结果、跳转与重跑

After a run finishes, select one actual Test/Example node and use **Rerun selected Go test/subtree**. This starts a fresh run for that node's package and full name, including names generated at runtime and duplicate names such as `dup#01`. The run copies the original configuration's arguments and environment, subject to the directory-change rules below. Select a parent node to rerun its whole subtree.

Double-click a mapped node or use **Go to test declaration**. Runtime subtests use **Go to containing test declaration** and open the top-level test; this does not identify the exact dynamic `t.Run` call. If the source changed or cannot be located, run the test again to refresh navigation. When source mapping is unavailable, the console explains why navigation and selected/failed rerun are unavailable; ordinary package execution remains usable.

After a failure, fix and save the source, then use **Rerun failed top-level tests in one package**. It reruns the full subtree of each affected top-level test, including siblings that previously passed. The action is unavailable when failures span multiple packages or include an unmapped build, process or selection failure. Run the affected packages separately or resolve those errors first. A new result is created; the earlier failed result remains unchanged.

运行结束后，选中一个真实 Test/Example 节点，使用 **Rerun selected Go test/subtree**，即可按该节点所属包及完整名称重新执行；动态名称和 `dup#01` 这类重复名称也以测试树中实际出现的名称为准。重跑沿用原配置的参数和环境，并强制重新执行；切换目录时须满足下文的参数保留规则。选择父节点会运行其整个子树。

双击可定位的节点，或使用 **Go to test declaration** 跳转。运行时子测试对应 **Go to containing test declaration**，定位到所属顶层测试，不代表精确找到动态 `t.Run` 调用。源码变化或无法定位时，重新运行测试以刷新跳转信息。无法建立源码对应关系时，控制台会提示，节点跳转和指定/失败重跑不可用；普通包测试仍可运行。

测试失败后，修复并保存源码，再使用 **Rerun failed top-level tests in one package**。此动作重新运行受影响顶层测试的完整子树，也包括其中此前通过的兄弟子测试。失败涉及多个包，或包含无法定位的构建、进程、选择错误时，按钮不可用；请按包分别运行或先解决这些错误。重跑生成新结果，保留原失败结果。

### Stop a local test run / 停止本地测试

Use **Run → Stop** for the active test run. Results already reported as passed, failed or skipped remain unchanged; tests that have not reported a result become interrupted when the process finishes. A genuine test or build failure remains a failure. Stopping before a requested name appears records unfinished work, rather than a missing-name assertion failure. Exit codes or output such as `signal: interrupt` alone do not identify an IDE-requested stop. Raw Go `FAIL`/signal output remains visible. Some test output is attached when a node finishes or is interrupted, so a running tree node can appear before its buffered log text.

After termination, **Rerun failed top-level tests in one package** is disabled: a failed-only run would omit unfinished work. Select a mapped, observed Test/Example or runtime subtest and use **Rerun selected Go test/subtree** to explicitly rerun that target and its descendants. The normal source-mapping and argument restrictions still apply.

使用 **Run → Stop** 停止当前测试。已经报告的通过、失败和跳过结果保持不变；进程结束时，尚未报告结果的测试标为中断。真实测试失败或构建失败仍保留为失败。停止发生在指定名称出现之前时，记录为尚未完成，不制造“未找到指定名称”的断言失败。不能仅凭退出码或 `signal: interrupt` 输出认定是 IDE 主动停止；原始 Go `FAIL`/信号输出仍显示。部分测试日志在节点完成或中断时才附加到结果，树节点开始运行可能早于其缓冲日志显示。

终止后，**Rerun failed top-level tests in one package** 禁用，避免只重跑失败项却遗漏未完成工作。可明确选中一个已观察到且可定位的 Test/Example 或运行时子测试，使用 **Rerun selected Go test/subtree** 重跑该目标及其后代；仍须满足正常的源码对应和参数限制。

### Selecting a package from a multi-package run / 从多包结果选择单包

A selected or failed rerun changes the working directory to the mapped package. GoForge preserves the original command's effective `GOFLAGS`, including values obtained from `GOENV`. Relative `-modfile`, `-pkgdir` and `-pgo` paths in supported arguments/`GOFLAGS` are made absolute against the original command directory; `-pgo=auto` and `-pgo=off` keep their special meaning.

When the directory changes, flags with complex embedded paths, wrappers or output destinations—such as `-gcflags`, `-asmflags`, `-ldflags`, `-exec`, `-toolexec`, coverage/profile output flags and unknown options—are explicitly refused. Create a named-test configuration directly in the desired package directory and configure those options there. GoForge does not drop them or reinterpret their paths from the new directory. The selected-debug shortcut has its own narrower allowlist below; path preservation does not make `-pgo` or other unsupported debug options available there.

从多包结果重跑指定测试或失败测试时，工作目录会切到对应包。GoForge 保留原命令实际生效的 `GOFLAGS`，包括 `GOENV` 提供的值；受支持参数及 `GOFLAGS` 中的相对 `-modfile`、`-pkgdir`、`-pgo` 路径按原命令目录转为绝对路径，`-pgo=auto/off` 保留特殊含义。

切换目录时，包含复杂内部路径、包装命令或输出位置的参数会明确拒绝，例如 `-gcflags`、`-asmflags`、`-ldflags`、`-exec`、`-toolexec`、覆盖率/性能输出选项及未知选项。请直接在目标包目录新建 Named 配置并设置这些参数；不会丢弃参数或从新目录错误解释旧路径。下面的指定测试调试入口另有更窄的支持清单，路径重写并不代表该入口支持 `-pgo` 等未列选项。

## Debug a selected test / 调试选中的测试

Save the files and set a breakpoint. In a completed test tree, select one Test/Example or runtime subtest and choose **Debug selected Go test/benchmark**. Use the ordinary Debug window for breakpoints, stack frames, variables, watches, stepping and Stop. The selected test and its subtree run once, with the necessary parent setup. Local debugging requires Delve and the operating system's normal debugger permissions. If a source file changes during preparation, save and select Debug again.

The selected-test debug action preserves these supported options, subject to the chosen Go toolchain and host platform:

| Options / 选项 | Supported flags / 支持的参数 |
| --- | --- |
| Test execution / 测试运行 | `-short`, `-v`, `-failfast`, `-timeout`, `-parallel`, `-cpu`, `-shuffle` |
| Build / 构建 | `-tags`, `-mod`, `-modfile`, `-gcflags`, `-ldflags`, `-asmflags`, `-p`, `-compiler`, `-pkgdir`, `-race`, `-msan`, `-asan`, `-trimpath`, `-work`, `-x`, `-buildvcs` |

Unsupported options, including coverage/profile collection, `-vet`, custom test-binary flags and `-exec`, stop this shortcut with an explanation. Edit the run configuration or create an explicit Delve configuration for those needs. This action does not silently discard an unsupported option.

先保存文件、设置断点，再在已完成的测试树中选中一个 Test/Example 或运行时子测试，使用 **Debug selected Go test/benchmark**。在普通 Debug 窗口中查看断点、调用栈、变量与 Watch，进行步进或 Stop。选中测试及其子树执行一次，并执行必要的父级准备。本地调试需要 Delve 及操作系统正常的调试权限；准备期间源码变化时，保存后重新选择 Debug。

此入口保留上表所列选项，具体可用性仍受 Go 工具链和操作系统限制。覆盖率/性能数据采集、`-vet`、自定义测试程序参数、`-exec` 等未支持选项会明确报错并停止启动。请修改运行配置，或针对这些需求创建显式 Delve 配置；不会悄悄丢弃参数继续调试。

The current cleanup change gives default local Delve launches in `debug`/`test` mode a separate, session-owned temporary output directory. After the adapter and observed target processes exit, it removes only that session’s generated executable and empty directory. If exit or ownership cannot be established, it retains the output and reports a warning. Explicit `output` paths, `exec`, attach and remote sessions retain their separate behavior. Full 17 native normal-exit cleanup passed, but toolbar Stop failed and retained output. Full 18 fixes passed macOS-host tests, archive audit and bounded top-toolbar Stop/normal-exit cleanup. Native Windows IDE cleanup workflows and complete GUI acceptance remain pending.

当前清理改动为默认本地 Delve `debug`/`test` 启动分配独立、由本会话拥有的临时输出目录。适配器及已观察到的目标进程退出后，仅删除本会话生成的可执行文件和空目录；不能确认退出或目录归属时保留输出并警告。显式 `output` 路径、`exec`、attach 和远端会话保持各自行为。Full 17 原生正常退出清理通过，但工具栏 Stop 失败并保留输出。Full 18 修复通过 macOS 宿主测试、归档审计与顶部 Stop/正常退出限定清理，Windows 原生 IDE 清理工作流与完整界面验收仍待完成。

## Change a Go signature / 修改 Go 签名

Place the caret on a declaration's **`func`** keyword, invoke **Go Refactor…**, and select **Change signature across files…**. Edit the proposed function type to reorder existing parameters or remove unused parameters. Keep the original parameter names, parameter types and result types. Review every affected file before Apply; ordinary Undo/Redo covers the whole change.

This bounded operation checks direct callers in the captured project, including synchronized unsaved documents. Caller arguments must be identifiers or literals. Calls, indexing, dereferences, channel receives, indirect function references, variadic expansion, missing references and unsupported generic operations are refused before applying. This reorder/removal dialog does not add parameters; use the separate bounded operation below in current source. For a bounded existing scalar parameter/result type change, use the separate operation below. Arbitrary type changes and moving declarations between packages remain incomplete.

把光标放在声明的 **`func`** 关键字上，执行 **Go Refactor… → Change signature across files…**。在函数类型模板中重排现有参数，或删除未使用参数；保留参数名、类型与返回值类型。检查全部受影响文件后 Apply，普通 Undo/Redo 统一撤销或重做整组修改。

当前会检查项目内直接调用及已同步的未保存文档。实参只接受标识符或字面量；函数调用、下标、解引用、通道接收、间接函数引用、可变参数展开、缺失引用及不支持的泛型操作会在应用前拒绝。此重排/删除对话框不新增参数；当前源码使用下方独立的限定新增操作。现有标量参数/返回值类型可用下方独立的限定操作；任意类型迁移、跨包移动声明仍未完成。

### Add one parameter / 新增一个参数

Place the caret on a function declaration's **`func`** keyword, invoke **Go Refactor…**, and choose **Add parameter to verified project calls…**. Enter the new parameter name, a predeclared scalar type, the insertion position (`0` means first; the current parameter count means last), and the literal value to insert at every verified call. Select **Preview verified callers**, review the declaration and every affected caller, then **Apply**. Preview leaves files unchanged. After Apply, **save the affected files before running an external Go command**: editor changes may still be unsaved. Ordinary Undo/Redo covers the complete change; cancellation or a stale/conflicting file prevents application.

For example, add `count int` at position `1` with default `1`: `Join(left, right)` becomes `Join(left, 1, right)`, with the corresponding parameter inserted in the declaration. The existing caller arguments stay in their original order. This operation supplies the same constant at every verified call; it does not generate caller-specific expressions or update other projects.

Supported functions are free functions with bodies, including generic functions. Generic calls may infer type arguments, supply all of them, or supply a prefix and infer the rest. Parentheses may surround a fully instantiated callee, such as `(Join[string, string])(...)`; uninstantiated or partially instantiated generic values inside parentheses are refused. Existing type parameters, constraints, explicit type arguments and the order of the original value arguments are preserved. Existing value parameters must each have one non-blank name; the new name must not already occur in the function, its type parameters or constraints. Supported new types are `bool`, `string`, predeclared numeric types, `byte`, `rune` and `uintptr`. Defaults are representable numeric/string/rune literals, signed numeric literals, `true` or `false`; `int`, `uint` and `uintptr` defaults use a portable 32-bit range. Boolean defaults appear as constant comparisons in the preview so caller-local names cannot shadow them.

Exported/generic receiver methods, interface migration, method values/expressions/promotions, variadics, grouped/unnamed parameters, function-value references (including instantiated functions used as values), nested instantiations, tuple/variadic call shapes, runtime/test entry points and compiler/linkage directives are refused. Ordinary declaration imports require a standalone module, cached dependencies and offline before/after compile checks; project code is not executed. Explicit aliases/no-import cases keep their existing bounded planner. Dot imports and scalar-shadowing bindings are refused. The new scalar type must not be shadowed by a declaration, import or function type parameter; a new parameter of a custom type or type parameter is not supported. All references must fit the captured project and resolve to verified direct calls; an unrelated same-name identifier can also cause a conservative refusal. Arbitrary default expressions, new imported/custom types, existing type/result changes and cross-package declaration moves remain outside this operation.

把光标放在函数声明的 **`func`** 关键字上，执行 **Go Refactor… → Add parameter to verified project calls…**。填写新参数名、Go 预声明标量类型、插入位置（`0` 表示最前，现有参数数量表示末尾）和每个已验证调用都要插入的字面量值。点击 **Preview verified callers**，检查声明和全部调用方后 **Apply**。预览不修改文件；Apply 后编辑器内容可能尚未保存，**先保存受影响文件，再运行外部 Go 命令**。普通 Undo/Redo 撤销或重做整组修改。取消、文件变化或冲突会阻止应用。

例如，以位置 `1`、默认值 `1` 新增 `count int`，调用 `Join(left, right)` 变成 `Join(left, 1, right)`，声明中同步插入参数。现有实参顺序保持不变。所有已验证调用使用同一个常量，不为不同调用生成不同表达式，也不更新其他项目。

支持有函数体的普通函数，包括泛型函数。泛型调用可以推断类型实参、完整显式指定，或指定前缀后推断其余类型；括号可包住完整实例化的调用目标，例如 `(Join[string, string])(...)`，但括号内未实例化或仅部分实例化的泛型值会拒绝。原类型参数、约束、显式类型实参及原值实参顺序保持不变。现有值参数须逐个具名且不能是 `_`，新名字不能已出现在函数、类型参数或约束中。新类型限 `bool`、`string`、预声明数值类型、`byte`、`rune` 和 `uintptr`。默认值限类型可表示的数值/字符串/字符字面量、带正负号数值或 `true`/`false`；`int`、`uint`、`uintptr` 按可移植的 32 位范围校验。布尔默认值在预览中呈现为常量比较，避免调用方同名变量遮蔽。

导出/泛型接收者方法、接口迁移、方法值/表达式/提升调用、可变参数、合并/未命名参数、函数值引用（含实例化后作为值使用的函数）、嵌套实例化、多返回值/可变参数调用形态、运行时或测试入口，以及带编译/链接指令的函数会拒绝。声明文件的普通导入需要单独模块、已缓存依赖及离线前后编译检查，不执行项目代码；明确别名/无导入仍用原限定规划器，点导入和标量遮蔽绑定会拒绝。新增标量类型被声明、导入或函数类型参数遮蔽时也会拒绝，新增参数的类型不能是自定义类型或类型参数。引用必须位于捕获的项目内并可验证为直接调用；无关的同名标识符也可能触发保守拒绝。任意默认表达式、导入类型/自定义类型、修改现有类型或返回值、跨包移动声明仍不在此操作范围内。

For ordinary imports, the compile check requires default host settings, one root go.mod, no active/parent go.work, nested modules, local module replacements or vendor tree, and a complete pure-Go source graph. It uses go test -c including tests without executing init, TestMain or tests. Compile success confirms type compatibility, not identical behavior. Module/GOENV/SDK, project build settings, unsaved metadata, the active server and captured sources are rechecked before Apply; changed proof inputs require a new preview.

普通导入的编译检查要求默认宿主设置、根 go.mod、完整纯 Go 源码图，不支持活动/父级 go.work、嵌套模块、本地 module replace 或 vendor。通过 go test -c 编译含测试的源码，不执行 init、TestMain 或测试；编译成功只证明类型兼容，不证明行为完全相同。Apply 前再次校验模块、GOENV、SDK、项目构建设置、未保存元数据、活动服务及捕获源码；证明输入变化时须重新预览。

Bounded dd87 macOS native acceptance used one module with ordinary standard-library imports, adding `extra int` at index `1` with literal default `7`: preview/cancel preserved all sources; Apply changed the declaration and two callers across three files; Save, Undo/Redo, bundled Go tests after saved edits, final restoration and normal exit passed. Place the caret on `func`; a caret after the function name did not expose this signature operation. This does not establish methods, arbitrary defaults or Windows GUI acceptance. / dd87 macOS 限定原生检查使用单模块及普通标准库导入，在位置 `1` 新增 `extra int`、默认值 `7`：预览/取消保留源码，Apply 修改三文件中的声明和两个调用，保存、Undo/Redo、保存后包内 Go 测试、最终恢复及正常退出通过。光标须放在 `func`，函数名后的光标未显示该签名操作；不代表方法、任意默认值或 Windows 界面验收。

### Add a private method parameter / 为私有方法新增参数

The same **Add parameter to verified project calls…** action accepts a private method on a private, non-generic, non-alias struct or pointer receiver. Put the caret on **`func`**. Every method goes through offline compilation of the captured module before and after the proposed edits, plus a typed check of the original method and its exact direct-call reference set. Same-package test calls are included. The caller must use a concrete identifier receiver, such as `w.render(a, b)`; existing arguments keep their order.

The check refuses related interfaces and type constraints, including anonymous assertions, even when the proposed code could still compile. It also refuses exported methods/types, generic or alias receivers, method values, method expressions, promoted methods and incomplete reference sets. Existing scalar/literal-default, standalone-module, cached-dependency and preview freshness bounds still apply. This operation does not migrate an interface API or other projects. Preview does not run project init, TestMain or tests. Save after Apply before running external Go commands; use ordinary Undo/Redo for the complete edit.

同一 **Add parameter to verified project calls…** 入口也接受私有、非泛型、非别名结构体或指针接收者上的私有方法，光标仍放在 **`func`**。每次都对捕获模块进行离线修改前后编译，并用完整类型信息核对原方法及其全部直接调用；包含同包测试中的调用。接收者须是 `w.render(a, b)` 这样的具体变量，原实参顺序保留。

即使修改后仍能编译，涉及相关接口、类型约束或匿名类型断言也会拒绝；导出方法/类型、泛型或别名接收者、方法值、方法表达式、提升调用和不完整引用同样拒绝。仍限标量/字面量默认值、单独模块、已缓存依赖及有效预览，不迁移接口 API 或其他项目。预览不会运行项目 init、TestMain 或测试。Apply 后先保存再运行外部 Go 命令；普通 Undo/Redo 操作整组修改。

The Full 22 Mac package passed 23 real method-protocol cases with its bundled JBR, Go and gopls and no development overlay, including refusal and stale-preview cases. This is Mac protocol evidence. Actual Windows Run 15 subsequently failed the corresponding 23-case method suite during temporary executable cleanup after 15 completed cases; native IDE method Apply/Undo remains unverified. / Full 22 Mac 包使用自身 JBR、Go、gopls 且无开发覆盖，通过 23 个真实方法协议场景，含拒绝及预览失效检查；这是 Mac 协议证据；随后实际 Windows 第 15 次在对应 23 场景方法套件中完成 15 项后，于临时可执行文件清理失败；原生 IDE 方法 Apply/Undo 仍未验收。

### Change an existing scalar type / 修改现有标量类型

In current source, place the caret on a free-function or supported private-method declaration's **`func`** keyword and choose **Go Refactor… → Change scalar parameter/result type…**. Select **Existing parameter** and its zero-based position, or **Single result**; enter a Go predeclared scalar type and select **Compile and preview**. Inspect the change, Apply, and save. The operation edits one type token; it checks captured callers without inserting conversions or rewriting their expressions. Generic function constraints and type arguments are retained. Undo/Redo uses the ordinary refactoring workflow.

Both the captured module and changed module, including tests, must compile in a temporary copy. This check does not execute project initialization, `TestMain`, or tests. Compilation proves static compatibility within that snapshot; changing a type can still change runtime behavior. Missing cached dependencies or incompatible callers produce a refusal before edits.

The current scope requires one standalone module, default host build settings and already-cached dependencies. Unsupported methods, method result changes, function values, grouped parameters, multiple results, build conditions, cgo/assembly, vendor trees, embedded resources, local replacements and references outside the captured project are refused. Save or revert module/workspace metadata first; metadata changes invalidate this operation's preview. The preview also binds the selected Go SDK and effective settings, including cgo and CPU feature selection; changing the SDK or GOENV configuration requires a fresh preview. Pure-Go dependencies under the actual cgo tag remain supported; dependencies requiring C compilation are refused. Stop remote analysis before using this local check. This does not provide arbitrary type migration or full Change Signature parity.

在当前源码中，将光标放到普通函数或受支持私有方法声明的 **`func`** 上，选择 **Go Refactor… → Change scalar parameter/result type…**。选择现有参数及从 `0` 开始的位置，或选择单一返回值，填写 Go 预声明标量类型，点击 **Compile and preview**。检查后 Apply 并保存；普通 Undo/Redo 可撤销或重做。操作只修改一个类型标识符，检查调用方但不自动插入强制转换或改写表达式，保留泛型约束与类型实参。

检查会在临时副本内分别编译修改前后的模块及测试，不执行项目初始化、`TestMain` 或测试函数。编译通过只证明该快照的静态兼容性，类型变化仍可能改变运行行为。依赖未缓存或调用不兼容会在编辑前拒绝。当前限单独模块、默认本机构建设置；不支持的方法形态、方法返回值修改、函数值、合并参数、多返回值、条件构建、cgo/汇编、vendor、嵌入资源、本地 replace 和项目外引用仍不支持。先保存或还原模块元数据，元数据变化会使本操作预览失效；预览也绑定 Go SDK 及有效配置（含 cgo 和 CPU 特性），切换 SDK 或修改 GOENV 后需重新预览。实际 cgo 标签下的纯 Go 依赖仍可使用，需要 C 编译的依赖会拒绝；远端分析需先停止。这不等于任意类型迁移或完整签名重构。

Full 23 also accepts **Existing parameter** on a private method whose named receiver is a private, non-alias, non-generic local struct; value and pointer receivers are supported. Choose the parameter position and new predeclared scalar type, then use the same Compile and preview / Apply / Save / Undo flow. The preview changes one type token. Direct callers must use a verified identifier receiver and continue to compile; their expressions and any unchanged dirty caller are not rewritten. Related interfaces and constraints, exported/alias/generic/non-struct receivers, method values/expressions/promotions, incomplete references, grouped/variadic parameters and non-builtin or shadowed types are refused. **Single result remains unavailable for methods.** Snapshot, metadata, SDK/settings and later-caller-edit guards still apply. Full 23 platform transaction tests and 29 actual packaged method-scalar protocol cases passed; the 23 Add Parameter and 20 free-function scalar cases also passed. Native GUI/Windows evidence for this new method-scalar scope is pending.

Full 23 的 **Existing parameter** 还支持私有方法：具名接收者须为本地私有、非别名、非泛型结构体，可为值或指针接收者。选择参数位置与新的预声明标量类型后，沿用 Compile and preview / Apply / Save / Undo 流程。预览只改一个类型标识符；直接调用须使用可验证的标识符接收者并继续编译，不改写调用表达式，也不写入未发生变化的未保存调用文件。关联接口/约束、导出/别名/泛型/非结构体接收者、方法值/表达式/提升调用、不完整引用、合并/可变参数、非内置或被遮蔽类型均拒绝。**方法仍不支持 Single result。** 快照、元数据、SDK/配置及后续调用方编辑保护仍保留。Full 23 平台事务测试及 29 个真实包内方法标量协议场景通过；23 个新增参数和 20 个普通函数标量场景也通过。本次新增方法标量范围的原生 GUI/Windows 证据仍待完成。

### Preview safety and file changes / 预览期间的文件变化

Temporary compile/planner files belong to the current refactoring only. Full 24 checks those directory/file identities before cleanup and retries temporary Windows sharing failures for a bounded period. If cleanup remains blocked or is interrupted, the operation keeps the original error plus the cleanup failure and retains remaining files; an expected refusal or cancellation does not hide that failure. This does not delete user project files or make a failed preview applicable. The fix passed local tests and 72 actual Mac packaged protocol cases; Windows verification and native IDE workflows remain pending.

编译/规划临时文件仅属于本次重构。Full 24 清理前核对这些目录/文件身份，并对 Windows 临时共享占用进行有界重试；仍被占用或中断时，保留原始错误与清理失败，并留下剩余文件，预期拒绝或取消也不会掩盖清理问题。不会据此删除用户项目文件或允许应用失败预览。修复已通过本地测试及 72 个真实 Mac 包内协议场景；Windows 与原生 IDE 工作流仍待验收。

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

### Remote standard-library sources / 远端标准库源码

Use **Go to Declaration or Usages** and **LSP Type Definition(s)** to open sources from the exact active remote Go SDK's `GOROOT/src`. Start a reviewed Go analysis session first and wait for its remote-initialized message. For example, navigate from `fmt.Println` to `fmt/print.go`, or use Type Definition on an `io.Writer` variable to open `io/io.go`.

The target is a read-only Go editor snapshot identified as remote SDK source, not a local project file. Navigation/copy is available; edits and saves to the SDK are refused. **Stop remote analysis** expires those session URIs and closes its SDK tabs while keeping ordinary project tabs. Reads are bounded (2 MiB per file; 128 files/32 MiB per session cache); a changed SDK, stale request, unsafe path or limit violation is refused. Restart a reviewed session to obtain a new SDK snapshot. Bounded macOS native checks passed exact fmt/io navigation, refused editing/saving and Stop cleanup on an owned localhost SSH fixture. Windows IDE-host and broader remote-host acceptance remain pending.

通过 **Go to Declaration or Usages**、**LSP Type Definition(s)** 跳转到当前远端 Go SDK 的精确 `GOROOT/src`。先启动审阅后的 Go analysis 会话，等待远端分析初始化消息；例如从 `fmt.Println` 跳到 `fmt/print.go`，或在 `io.Writer` 类型变量上使用 Type Definition 打开 `io/io.go`。

目标是标识为远端 SDK 的只读 Go 编辑器快照，不是本地项目文件；允许查看/复制，拒绝修改和保存到 SDK。**Stop remote analysis** 会使本次 URI 失效并关闭所属 SDK 标签，普通项目标签保留。读取上限为每文件 2 MiB、每会话 128 文件/32 MiB；SDK 变化、过期请求、不安全路径或超限会被拒绝，需重新审阅会话获取新快照。macOS 限定原生检查通过自有 localhost SSH 夹具的精确 fmt/io 跳转、编辑/保存拒绝及 Stop 清理；Windows IDE 宿主和更广远端宿主待验收。

Analysis SDK and dependency sources use distinct session identities and do not permit cross-boundary workspace edits. Paused debug/test SDK viewing is a separate workflow below; dependency debugger mapping remains unsupported. / 分析用 SDK 与依赖源码身份各自独立，均不允许跨边界工作区修改；暂停的包/测试调试 SDK 查看属于下方独立流程，依赖调试映射仍不支持。

### Selected cached remote dependencies / 已选缓存远端依赖

Start Go analysis from one reviewed root `go.mod` and `go.sum`, with all dependencies already cached on the remote host. This source lookup does not support `go.work`, replacement directives or vendor mode. Wait for `Remote dependency source lookup ready for … selected module(s)`; discovery is optional and limited to five seconds, so unavailable dependency sources do not prevent project/SDK analysis. An early request reports that discovery is still loading.

Use the same **Go to Declaration or Usages** or **LSP Type Definition(s)** from a project file. The target opens as a read-only source identified by its exact module path and selected version. The selected Go module graph must match reviewed content and `/go.mod` h1 checksums; the requested cache file must equal its verified archive member. A historical version appearing only in `go.sum` is not permission to open it. Checksums bind reviewed bytes, not publisher trust. Missing metadata/archive, changed paths or bytes, unsafe ZIP entries and size limits cause refusal; no module is downloaded.

SDK and dependency files share the 2 MiB per-file, 128-file/32 MiB session cache. Module discovery/archives also have bounded limits; even an uncached historical metadata anchor may conservatively disable optional dependency lookup. Viewing/copying is supported; editing, saving and onward LSP requests from a fetched source are not. **Stop remote analysis** expires and closes both kinds of source tabs while preserving ordinary project tabs. Re-review a new session after changing module metadata. This does not add dependency paths to remote debugging. Protocol, macOS-host platform and bounded Full 19 native checks passed. In an owned localhost SSH fixture, declaration/type navigation selected `example.test/Upper/dep@v1.1.0` at `3:6`/`2:6`, despite a cached historical v1.0.0; SDK navigation opened `fmt/print.go:314:6`. Both source kinds refused typing/Save. UI Stop closed their tabs, preserved the local tab and stopped analysis children; the listener was stopped separately after normal app exit. Source/cache bytes remained unchanged. Windows IDE-host and broader remote-host acceptance remain pending.

从单个已审阅根 `go.mod` 与 `go.sum` 启动 Go analysis，依赖须已缓存于远端；此源码查找不支持 `go.work`、replace 或 vendor。等待 `Remote dependency source lookup ready for … selected module(s)` 日志。可选发现最多五秒，失败不妨碍项目/SDK 分析；过早请求会提示仍在加载。

在项目文件中使用相同的定义/类型定义动作，目标按精确模块路径和已选版本标识，以只读源码打开。Go 选定模块图须符合审核过的模块内容与 `/go.mod` h1，缓存源文件须与验证后的归档成员一致；`go.sum` 中仅有历史版本不授予读取权限。校验和绑定已审阅字节，不证明作者可信。元数据/归档缺失、路径/内容变化、不安全 ZIP 或超限均拒绝，不自动下载。

SDK 与依赖共享每文件 2 MiB、每会话 128 文件/32 MiB 上限，模块图与归档另有范围限制；历史元数据未缓存也可能保守关闭依赖查找。允许查看/复制，不允许编辑、保存或从只读源页继续请求 LSP。**Stop remote analysis** 同时使两类源码失效并关闭所属标签，普通项目标签保留；修改模块元数据后须重新审阅会话。该功能不增加远端调试器依赖映射。协议、macOS 宿主平台及 Full 19 限定原生检查通过：自有 localhost SSH 夹具中，虽缓存历史 v1.0.0，定义/类型跳转仍选择 `example.test/Upper/dep@v1.1.0` 的 `3:6`/`2:6`，SDK 跳转到 `fmt/print.go:314:6`。两类源码均拒绝输入/保存；UI Stop 关闭所属标签、保留本地标签并停止分析子进程，应用正常退出后另行停止监听器；源文件/缓存字节未变。Windows IDE 宿主及更广远端宿主仍待验收。

The review preview still contains the stale blanket text **“External dependency navigation is unavailable.”** This wording defect remains in Full 19; the supported selected-cache scope is described above. Actual discovery failures and unsupported-source refusals still apply. / 审阅预览仍显示过时的笼统文字 **“External dependency navigation is unavailable.”**，Full 19 保留此缺陷；已支持的缓存版本范围见上，实际发现失败和不支持源码的拒绝仍有效。

## Remote debugging / 远端调试

In **Go Remote → Go debug**, select **Package**, **Tests**, **Executable**, or **Attach**, review the configuration, then start. Set the remote Delve executable, package/binary path or PID, program arguments and environment as needed. Arguments are parsed as arguments rather than evaluated by a shell. Environment values belong to the session and are not stored as profile credentials.

Local saved Go sources must match the selected remote workspace. Save explicitly and transfer/prepare matching sources before starting; debug does not silently save or overwrite your files. The remote needs Python 3, Go, Delve, dependencies, and normal OS debugging permissions. Executables should be compiled with debugging information, for example `-gcflags="all=-N -l"`.

The adapter binds remote DAP to loopback and relays it through SSH. Local/remote source paths are mapped for the verified workspace. Use the ordinary Debug window for breakpoints, frames, variables, expressions, stepping and stop. **Stop terminates a target launched by the session; attach detaches and preserves the pre-existing target.** External dependency sources and arbitrary remapped roots are not exposed as local project files.

在 **Go debug** 选择包、测试、可执行文件或 PID attach，Review 后启动。使用已保存且与远端匹配的源码；不会自动保存、上传或覆盖。参数按普通参数解析，不通过 shell 执行；环境值仅属于当前会话。远端需要 Python 3、Go、Delve 和正常调试权限。DAP 仅绑定远端 loopback，通过 SSH 转发。普通 Debug 窗口支持断点、栈、变量、求值和步进。**停止启动型会话会结束其目标；停止 attach 会话会脱离并保留原进程。**


### View SDK source at a paused remote frame / 查看远端暂停栈帧的 SDK 源码

Start a reviewed remote **Package** or **Tests** debug session. When it pauses in a standard-library frame, select that frame to view a read-only snapshot from the selected remote Go SDK at the reported source line. The source must be within the verified remote `GOROOT/src`; arbitrary paths, module-cache files and SDK sources for **Executable/Attach** sessions are unavailable. Breakpoints are supported only in reviewed workspace files. These source views do not start local Go language services or write the remote SDK.

Resume, Step and Stop cancel outstanding source reads. Already loaded snapshots may stay visible until Stop, which closes and invalidates only this debug session's views and preserves unrelated editor tabs. A changed SDK/file, invalid or stale request, unavailable source or limit violation leaves the SDK source unavailable; workspace debugging stays usable. Limits are 2 MiB per file, 128 files/32 MiB per session and 16 queued reads. This is selected-SDK snapshot viewing, not proof that all compiler inputs or cached binaries were frozen. Mac-host SSH protocol, Full 22 platform tests and Run 15 Windows product tests passed; native IDE acceptance remains pending.

审阅后启动远端 **Package** 或 **Tests** 调试，会话在标准库栈帧暂停时，选中该帧即可按报告行号查看当前远端 Go SDK 的只读源码快照。仅允许已验证 `GOROOT/src` 内的路径；任意路径、模块缓存文件及 **Executable/Attach** 会话的 SDK 源码不支持。断点只允许审阅后的工作区文件；源码页不启动本地 Go 语言服务，也不写入远端 SDK。

Resume、Step 和 Stop 会取消尚未完成的源码读取；已加载快照可保留至 Stop，届时只关闭并失效本调试会话的视图，其他编辑器标签保留。SDK/文件变化、过期或无效请求、源码不可用或超限时不加载 SDK 源码，工作区调试仍可使用。上限为每文件 2 MiB、每会话 128 文件/32 MiB、16 个排队读取。这是当前选定 SDK 的快照查看，不证明全部编译输入或缓存二进制都被冻结；Mac 宿主 SSH 协议、Full 22 平台测试与第 15 次 Windows 产品测试通过，原生 IDE 验收仍待完成。

Full 23 corrects the remote debug preview to describe the paused Package/Tests SDK views. Dependency and Executable/Attach SDK restrictions remain. / Full 23 已修正远端调试预览文字，说明暂停的 Package/Tests SDK 查看；依赖及 Executable/Attach 的 SDK 限制仍保留。

### Multi-module workspaces / 多模块工作区

Open the common directory containing **`go.work`**, with all `use` modules and local `replace` directories beneath that root. Their paths must be relative and free of symlinks. Keep the same relative layout remotely. In **Go debug**, enter the package directory relative to the common root, such as `app`, instead of selecting a non-module workspace root. Review lists the bound modules before starting.

Debugging uses a private copy of `go.work` and `go.work.sum`; the original files remain unchanged. The current review supports 1–32 modules, up to 64 local replacement directories and at most 512 visible saved files within the startup-manifest limit. Dependencies must already be available remotely. Current source also supports explicitly reviewed **Go analysis** snapshots using the same contained module topology. Analysis uses a private workspace file, supports cross-module unsaved overlays, and stops on local or remote module/workspace metadata changes. Start a fresh reviewed session after metadata or file-set changes. The 512-file limit above is the debug startup limit; analysis retains its separate snapshot size limits. Windows drive and UNC path mapping has protocol checks, but Windows-native acceptance remains pending.

打开包含 **`go.work`** 的公共目录，所有 `use` 模块及本地 `replace` 目录都须在该根目录下，使用相对路径且不经过符号链接；远端保持相同相对布局。在 **Go debug** 输入相对公共根目录的包路径，例如 `app`，不要选择本身不是模块的工作区根目录。Review 会列出本次绑定的模块。

调试使用 `go.work` 与 `go.work.sum` 的私有副本，不改写原文件。目前支持 1–32 个模块、最多 64 个本地替换目录、最多 512 个可见已保存文件，并受启动清单大小限制；远端依赖须预先就绪。当前源码的 **Go analysis** 也支持按同样目录边界显式审阅的多模块快照，使用私有工作区文件，支持跨模块未保存 overlay；本地或远端模块/工作区元数据变化会停止会话，文件集合或元数据变化后需重新审阅启动。上述 512 文件限制属于调试启动，分析保留其独立快照大小限制。Windows 盘符及 UNC 映射已有协议检查，尚未完成 Windows 原生验收。


See [Known limitations / 已知差距](KNOWN-LIMITATIONS.md) for verification boundaries. / 验收边界见已知差距。

## Follow HTTP redirects / 跟随 HTTP 重定向

Open **Tools → GoForge HTTP Client**. **Follow redirects** is enabled by default and follows up to five HTTP(S) redirects. Clear it to inspect the first 3xx response; the single-request timeout covers all hops. Put `// @no-redirect` or `# @no-redirect` before a request’s METHOD line to keep that request’s first redirect response even when the checkbox is enabled. Run All applies each request’s own choice and checks assertions against its final response.

301/302 POST and 303 except HEAD become GET without the body. 307/308 retain method and body. Cross-origin redirects remove all original headers and refuse a preserved nonempty body; HTTPS-to-HTTP is refused. Headers shows hop status/method without redirect URLs. No automatic cookie store, response JavaScript, WebSocket or gRPC is provided. Cancel stops the active request and leaves later collection requests Not run. These are bounded GoForge redirect rules; matching the default does not establish full HTTP client parity.

打开 **Tools → GoForge HTTP Client**。**Follow redirects** 默认勾选，最多跟随五跳 HTTP(S) 重定向；取消勾选可查看第一次 3xx 响应，所有跳转共用单次请求超时。在某请求的 METHOD 行之前写 `// @no-redirect` 或 `# @no-redirect`，即使勾选仍只返回该请求的首个重定向响应。Run All 分别遵守各请求设置，并在最终响应上检查断言。

301/302 的 POST 和除 HEAD 外的 303 转为无请求体的 GET；307/308 保留方法和请求体。跨源跳转清除全部原始请求头，且保留非空请求体的跨源跳转会被拒绝；HTTPS 降级为 HTTP 也会拒绝。Headers 显示跳转状态和方法，不显示跳转 URL。不提供自动 Cookie 存储、响应 JavaScript、WebSocket 或 gRPC。Cancel 停止当前请求，集合后续请求保留 Not run。这是 GoForge 当前限定重定向规则；默认设置一致不代表完整 HTTP 客户端已对齐。
