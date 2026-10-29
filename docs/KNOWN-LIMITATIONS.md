# Known limitations / 已知差距

GoForge is an internal development build. Complete GoLand feature parity and a complete original-product comparison baseline have **not** been established. Test counts are not a completion percentage. GoForge has no account, activation or paid-feature gate; external services still use their own authorization.

GoForge 当前为内部开发构建，**尚未证明完整对齐 GoLand**，也未建立完整原产品对照基线。测试数量不是完成百分比。GoForge 不设注册、激活或付费功能门槛；外部服务仍使用各自权限。

| Area / 领域 | Remaining boundary / 当前边界 |
| --- | --- |
| Distribution / 发行 | macOS has bounded native evidence, not full GUI acceptance. Windows native verification failed at gopls; newer source and Windows GUI are pending. No notarized installer/updater or verified Linux/other-architecture distribution. / macOS 只有限定原生证据；Windows 原生检查在 gopls 失败，新源码及界面待验收，无已验证的公证安装器/更新器或 Linux/其他架构发行。 |
| Go language / Go 语义 | Uses gopls; full inspections, completion, build tags, generics, workspace and large-project comparison coverage is incomplete. / 基于 gopls，检查规则、补全、构建标签、泛型、工作区与大工程完整差分覆盖未完成。 |
| Editing / 编辑 | Basic comments/braces/Enter, save formatting and manual/save imports are implemented. Continuation/switch indentation, full EditorConfig precedence, multi-caret whitespace, rearrangement/cleanup and closed-editor manual formatting remain limited. / 基础编辑、保存格式化及导入整理已实现；完整续行/switch 缩进、EditorConfig 优先级、多光标空白、重排/cleanup 与无 Editor 手动格式化仍有限制。 |
| Test selection / 测试选择 | Runtime subtests navigate to their containing top-level declaration; no static subtest-call discovery. Failed rerun is restricted to one mapped package and refuses build/process/selection failures or locally stopped runs. / 运行时子测试跳到所属顶层声明，不静态发现子测试调用；仅失败项重跑限同包可定位结果，不忽略其他失败或本地终止。 |
| Benchmarks/fuzzing / 基准与 fuzzing | One top-level Benchmark Run and completed-node Debug are implemented; Benchmark Debug native UI is unresolved. No direct editor Benchmark Debug, subbenchmark/fuzz selection, benchmark result navigation/per-node rerun or comparison charts. / 顶层 Benchmark Run 与完成节点 Debug 已实现，但 Debug 原生界面未确认；直接编辑器调试、子基准/fuzz 选择、结果跳转/逐节点重跑及对比图表未包含。 |
| Refactoring / 重构 | Reorder/remove parameters accepts only proven direct-call shapes. Add Parameter inserts one scalar/literal default, including bounded generics; ordinary imports require offline before/after compilation of one standalone module. Arbitrary defaults, methods, function values, variadics, broad type migrations and cross-package moves remain unsupported. / 参数重排/删除只接受受限直接调用；新增参数限一个标量/字面量默认值及限定泛型，普通导入须单独模块离线前后编译。任意默认表达式、方法、函数值、可变参数、任意类型迁移及跨包移动未支持。 |
| Preview guards / 预览保护 | File inventory: 1,024 directories / 16,384 direct entries. Generic providers without file keys allow at most 256 pairwise same-file checks per pass, then refuse. Windows handle identities are supported. General previews do not establish full metadata/build invalidation; compile-checked parameter/type operations additionally bind module/GOENV/SDK proofs. See [preview safety](USAGE.md#preview-safety-and-file-changes--预览期间的文件变化). / 文件清单上限为 1,024 目录、16,384 子项；缺少身份 key 的通用回退每轮最多 256 次文件对比较，超限拒绝。Windows 支持句柄身份。一般预览未证明完整元数据/构建失效机制；编译检查操作另绑定模块、GOENV 与 SDK。 |
| Debugging / 调试 | Delve/OS limits apply. Selected debugging supports a bounded flag list. SSH multi-module package/test/exec/attach requires reviewed contained go.work paths; no all-platform/client acceptance. / 受 Delve/系统限制；指定调试参数有边界，SSH 多模块需审阅根目录内 go.work 路径，未完成全平台/客户端验收。 |
| Remote development / 远程开发 | Fixed reviewed snapshot sets; file/module-set changes require a new session. Exact remote GOROOT/src navigation is read-only. Arbitrary module-cache/dependency sources, debugger SDK mapping and a complete remote filesystem/backend remain absent. / 快照集合固定，增删文件/模块需新会话；精确远端 GOROOT/src 只读跳转已实现，任意依赖/module-cache 源码、调试器 SDK 映射及完整远端文件系统/后端仍缺。 |
| Coverage/profiling / 覆盖率与性能 | Coverage is saved-file, single-module and session-local; no merged go.work report. pprof is available; Graphviz is separate and full profiler parity is unverified. / 覆盖率限已保存文件、单模块与当前会话，不合并 go.work；pprof 可用，Graphviz 另装，未证明完整性能分析器对齐。 |
| Database / 数据库 | Selected JDBC drivers, basic completion and estimated Explain; no complete dialect intelligence, migrations, ER tools or all-driver acceptance. Community promotional defaults are hidden, which adds no commercial database engine. / 限定 JDBC 驱动、基础补全与估计 Explain，不是完整方言、迁移、ER 工具或全驱动验收；隐藏 Community 推广默认入口不会增加商业数据库引擎。 |
| HTTP / 网络 | HTTP(S) collections only; no JavaScript hooks, redirects, WebSocket or gRPC. Raw responses can contain secrets and need review before sharing. / 仅 HTTP(S) 集合，无脚本钩子、重定向、WebSocket 或 gRPC；分享原始响应前需检查敏感数据。 |
| Containers / 容器 | Dev Containers support a limited configuration subset. Docker/Kubernetes need host tools and authorized services. Strict path ownership can refuse Docker Desktop mount aliases; Cancel does not undo accepted server work. / Dev Containers 只覆盖部分配置；Docker/Kubernetes 需系统工具与服务权限，严格路径检查可能拒绝挂载别名，取消不会撤销已接受的服务端操作。 |
| Ecosystem/reliability / 生态与稳定性 | Not all frameworks, languages, commercial plugins, VCS/cloud/AI integrations or settings migrations are implemented. Large projects, long sessions, accessibility, all layouts, recovery and upgrades need broader checks. / 未覆盖所有框架、语言、商业插件、VCS/云/AI 集成或设置迁移；大工程、长会话、可访问性、布局、恢复与升级仍需验证。 |

See the [workflow guide](USAGE.md), [imports](USAGE.md#organize-imports-on-save--保存时整理导入), [benchmarks](USAGE.md#run-one-benchmark--运行单个基准测试), [remote sources](USAGE.md#remote-standard-library-sources--远端标准库源码), [test Stop](USAGE.md#stop-a-local-test-run--停止本地测试), and [Add Parameter](USAGE.md#add-one-parameter--新增一个参数) for operational boundaries. / 操作与边界见以上对应流程。

## Verification scope / 验证范围

**Full 13 (dd87)** passed **756/756 macOS-host JUnit tests** (433 component + 323 platform; 99 XML reports, no failures/errors/skips), plugin build and structure verification. Its 345 build inputs are frozen. This source includes compile-checked Add Parameter support for ordinary imports; its Mac archive is assembled and independently audited; native GUI and Windows verification remain pending.

**Full 13（dd87）**通过 **756/756 项 macOS 宿主 JUnit 测试**（433 component + 323 platform，99 份 XML，零失败/错误/跳过）、插件构建与结构检查，345 项构建输入已冻结。该源码新增普通导入下经过编译检查的 Add Parameter；Mac 包已组装并独立审计，原生界面与 Windows 验证仍待完成。

The current audited macOS package is dd87. Signing, headless startup, relocation and archive checks passed; dd87 native GUI acceptance is pending. The earlier bdf5 bounded native checks cover manual imports with Undo/Redo, the visible Go import-type entry, selected Benchmark Run, and remote SDK read-only navigation/Stop. Benchmark Debug remains an unresolved UI attempt; full GUI acceptance and final exit checks for the current session are pending.

当前已审计 macOS 包为 dd87，验签、无界面启动、迁移及归档检查通过，dd87 原生界面待验收。此前 bdf5 的限定原生检查覆盖手动导入整理及 Undo/Redo、可见的 Go 导入类型入口、指定 Benchmark 运行、远端 SDK 只读跳转与 Stop。Benchmark 调试仍为未确认原因的界面尝试；完整界面验收及当前会话最终退出检查待完成。

The Go import-type entry was observed, but exclusive Go-only chooser selection was not verified. The database native check covered the Settings promotional entry only, not new SQL functionality. The remote native check used an owned localhost SSH fixture, exact fmt/io SDK sources and UI Stop; it does not prove arbitrary remote-host behavior. A locked Mac interrupted Benchmark Debug entry verification, so no product defect or successful native debug session is inferred.

已观察到 Go 导入类型入口，但尚未验证只选 Go 的完整设置流程；数据库原生检查仅覆盖设置中的推广入口，不是新增 SQL 验收。远端原生检查使用自有 localhost SSH 夹具、精确 fmt/io SDK 源码与 UI Stop，不代表任意远端宿主。Mac 锁定中断了 Benchmark Debug 入口验证，不能据此推断产品缺陷或成功原生调试会话。

Windows Run 9 (older 2406 source) passed 34 preflight checks and 722 product tests, with one explicit POSIX-shell skip. The relocated native launcher, bundled tool versions and Go run/test passed; gopls definition failed with `no views`, so Delve acceptance was not reached. The run failed; Windows GUI and newer-source acceptance remain pending.

Windows 第 9 次使用较早 2406 源码：34 项预检、722 项产品测试通过，1 项明确的 POSIX shell 跳过。迁移后的原生启动器、包内工具版本及 Go run/test 通过；gopls 定义请求因 `no views` 失败，尚未进入 Delve 验收。本轮整体失败，Windows 界面及更新源码仍待验收。

Historical native scopes remain build-specific: 5259 scalar parameter/result changes; a252 generic Add Parameter; 319d Stop/output; 311e selected-subtest Debug; and 1caa remote multi-module debug. Their warnings, failures and source identities remain in internal records. They are not repeated passes on dd87. [Installation identities](INSTALL.md#prepared-package-identities) retain old archive checksums.

历史原生范围保留构建归属：5259 标量参数/返回值修改、a252 泛型新增参数、319d Stop/输出、311e 指定子测试 Debug、1caa 远端多模块调试。警告、失败及源码身份留在内部记录，不作为 dd87 重测通过；[安装身份](INSTALL.md#prepared-package-identities)保留旧归档校验值。

Complete acceptance remains **0/101**, counting fully completed acceptance items, not implemented features or an overall percentage. / 完整验收仍为 **0/101**，不是功能实现数量或总体百分比。

## Private validation and publication / 私有验证与发布

The repository `UpLLt/goforge-ide` remains private. [Run 9](https://github.com/UpLLt/goforge-ide/actions/runs/40819479810) tested 2406, not the current dd87 source; Run 10 has not started. Source exports, plugin ZIPs and app archives have separate hashes. No public repository or Release exists, and `publish=false` remains required until complete GoLand alignment is verified.

`UpLLt/goforge-ide` 保持私有。[第 9 次 CI](https://github.com/UpLLt/goforge-ide/actions/runs/40819479810)验证较早 2406，不是当前 dd87；第 10 次尚未启动。源码导出、插件 ZIP 和应用包分别记哈希。完整对齐并验证前，不公开仓库或 Release，保持 `publish=false`。
