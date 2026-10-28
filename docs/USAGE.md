# Workflows / 功能用法

Use the action search if a menu is hidden in your selected IDE layout. GoForge does not require its own login. Service credentials belong to the services you choose to connect.

如果当前布局隐藏菜单，可以用动作搜索查找下列名称。GoForge 无自身登录门槛；外部服务使用各自的认证。

| Entry / 入口 | Steps / 操作 | Boundary / 边界 |
| --- | --- | --- |
| Go editor | Open a module; wait for gopls; use completion, Go to Declaration, Find Usages, Rename and Reformat. / 打开模块后使用补全、跳转、引用、重命名和格式化。 | Uses the selected gopls and its supported actions. / 语义范围由当前 gopls 决定。 |
| Run / Debug | Select a Go configuration, choose package/arguments/environment, run or debug, and use the Debug pane for frames/variables/step/stop. / 配置包、参数、环境后运行或调试。 | Local debug needs Delve and the OS debugger prerequisites. Expression completion covers the implemented frame/local/field cases, not every Go expression. |
| Go Refactor… | Select source, choose an available action, review all file changes, then Apply. Use normal Undo/Redo. / 选择源码，预览全部文件改动，再应用或撤销。 | Unsupported resource operations/commands and stale/conflicting files are refused. Cross-package move/signature parity is incomplete. |
| Run → Run Go Coverage | Save the module, run coverage, select a row to navigate, use Clear to remove coverage marks. / 保存模块后运行覆盖率，点行跳转，Clear 清除标记。 | One module; saved-file snapshots; no combined go.work report. |
| Tools → Go Profiling… | Collect CPU/memory profiles for one package or open pprof data; use top/compare/viewer. / 单包采集或打开 pprof，查看 top、比较与交互视图。 | Viewer binds loopback; graph layout needs Graphviz. |
| Tools → Go Database | Add a connection; browse schema; execute SQL. Select Begin/Commit/Rollback then Apply. Review primary-key edits before applying; export CSV. / 连接数据库后执行 SQL，事务选动作再 Apply。 | Passwords are not persisted. Row editing requires a supported primary key. Explain estimates SELECT/WITH; no ANALYZE execution. |
| Tools → GoForge HTTP Client | Open `.http`/`.rest`, select environment files explicitly, run one/all requests, inspect assertions and results. / 打开请求文件、选择环境、运行并查看结果。 | No script execution, redirects, WebSocket or gRPC. Raw response save is a separate explicit action. |
| Containers → Docker | Choose a context; refresh containers/images; inspect logs, start/stop or use reviewed build/Compose actions. / 选择 context 后管理。 | Requires Docker CLI/daemon. Cancel stops the local command, not necessarily an accepted daemon operation. |
| Containers → Kubernetes | Choose context/namespace, refresh, inspect logs/describe, preview diff/apply, use loopback forwarding and Cancel. / 选择集群与命名空间后操作。 | Requires kubectl and access to the selected cluster. Deletion checks resource UID. No global context switching. |
| Containers → Dev Containers | Load and review the supported configuration, Start, open Terminal; execute postCreate separately if desired; Stop or Remove owned resources. / 先预览，再启动；postCreate 单独触发。 | Basic image/build projection, not the complete specification. Unsupported metadata/configuration is rejected or explicitly omitted. |

## Change a Go signature / 修改 Go 签名

Place the caret on a declaration's **`func`** keyword, invoke **Go Refactor…**, and select **Change signature across files…**. Edit the proposed function type to reorder existing parameters or remove unused parameters. Keep the original parameter names, parameter types and result types. Review every affected file before Apply; ordinary Undo/Redo covers the whole change.

This bounded operation checks direct callers in the captured project, including synchronized unsaved documents. Caller arguments must be identifiers or literals. Calls, indexing, dereferences, channel receives, indirect function references, variadic expansion, missing references and unsupported generic operations are refused before applying. Adding parameters, changing types/results, and moving a declaration between packages remain incomplete.

把光标放在声明的 **`func`** 关键字上，执行 **Go Refactor… → Change signature across files…**。在函数类型模板中重排现有参数，或删除未使用参数；保留参数名、类型与返回值类型。检查全部受影响文件后 Apply，普通 Undo/Redo 统一撤销或重做整组修改。

当前会检查项目内直接调用及已同步的未保存文档。实参只接受标识符或字面量；函数调用、下标、解引用、通道接收、间接函数引用、可变参数展开、缺失引用及不支持的泛型操作会在应用前拒绝。新增参数、修改类型/返回值、跨包移动声明仍未完成。

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

Debugging uses a private copy of `go.work` and `go.work.sum`; the original files remain unchanged. The current review supports 1–32 modules, up to 64 local replacement directories and at most 512 visible saved files within the startup-manifest limit. Dependencies must already be available remotely. This expands remote **debugging**; snapshot **Go analysis** remains single-module. Windows drive and UNC path mapping has protocol checks, but Windows-native acceptance remains pending.

打开包含 **`go.work`** 的公共目录，所有 `use` 模块及本地 `replace` 目录都须在该根目录下，使用相对路径且不经过符号链接；远端保持相同相对布局。在 **Go debug** 输入相对公共根目录的包路径，例如 `app`，不要选择本身不是模块的工作区根目录。Review 会列出本次绑定的模块。

调试使用 `go.work` 与 `go.work.sum` 的私有副本，不改写原文件。目前支持 1–32 个模块、最多 64 个本地替换目录、最多 512 个可见已保存文件，并受启动清单大小限制；远端依赖须预先就绪。这项扩展适用于**调试**，快照 **Go analysis** 仍为单模块。Windows 盘符及 UNC 映射已有协议检查，尚未完成 Windows 原生验收。
