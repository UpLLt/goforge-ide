# GoForge

**独立 Go IDE，无需 GoForge 注册、登录、激活、订阅或付费解锁。**

[English](README.md) · [安装指南](docs/INSTALL.md) · [功能用法](docs/USAGE.md) · [已知差距](docs/KNOWN-LIMITATIONS.md) · [第三方说明](THIRD-PARTY-NOTICES.md)

GoForge 使用原创代码、公开的 IntelliJ Community 平台、LSP4IJ、gopls 和 Delve。项目独立于 JetBrains，不包含专有 GoLand 插件。

**当前为内部开发构建**：已经实现多条实际工作流，但尚未证明与 GoLand 完整功能对齐。**必须验证完整对齐后，才能公开发布源码和二进制。** 准备安装包或确定许可证不代表满足发布门槛。连接 SSH、数据库、Docker、Git 托管等外部服务时，仍需要这些服务正常的配置和访问权限。

## 开始使用

1. 开发阶段从源码构建或使用内部候选安装包；当前发布门槛下尚无公开 **Releases** 下载。按[安装指南](docs/INSTALL.md)校验、解压，留意平台验证状态。
2. 选择 **New Go Project…** 新建应用或库，也可以打开含 `go.mod` 的项目目录。
3. 选择预置 **Run / Test / Debug** 配置。单独运行测试时，先保存文件，再在顶层 Test/Example 内使用 **Run**；运行时子测试从已完成的测试树选择。详见[测试用法](docs/USAGE.md#run-named-go-tests--运行指定-go-测试)。
4. **Settings → GoForge** 可设置工具路径；**Settings → Go Project** 可设置 build tags、GOOS、GOARCH。

| 平台 | 包与验证情况 |
| --- | --- |
| macOS / Apple Silicon | macOS 13.5+；a252 包已准备并冻结，压缩包读回、ad-hoc 签名、无界面探针及迁移路径检查通过；本轮泛型预览/Apply/Save/Undo/Redo 及旧预览拒绝的限定原生检查通过。此前 319d Stop/输出与 311e 指定调试/新增参数检查保留各自历史范围。本地 ad-hoc 签名，尚无 Apple Developer ID 公证。 |
| Windows x64 | a252 便携包已在 macOS 跨平台组装并冻结，全量解压及文件读回通过；Windows 原生执行与 CI 重跑仍未验证。见 [Windows 安装](docs/INSTALL.md#windows-x64)。 |
| Linux / 其他架构 | 暂无已验证的独立发行包。 |

已准备的内部平台包均内置运行时、Go SDK、gopls、Delve、Node、Dev Containers CLI 和 JDBC 驱动。Docker、kubectl、Git、OpenSSH、Cgo 的 C 编译器及 macOS 调试前置工具使用系统安装。包校验值及“已准备”与“已公开发布”的区别见[安装指南](docs/INSTALL.md#prepared-package-identities)。

## 当前工作流

| 模块 | 已实现范围 |
| --- | --- |
| Go 编辑 | 本地行/块注释、配对括号、限定块缩进及原始字符串回车；gopls 补全、诊断、跳转、引用、重命名及可用代码动作；等待响应完成的 Go 默认保存格式化及手动 Reformat，具体边界见下文。 |
| 项目与运行 | 应用/库模块、运行/构建/测试；顶层 Test/Example 选择、运行时子测试重跑、所属声明跳转及单包失败顶层测试重跑；本地终止保留已完成结果，未完成节点标为中断。 |
| 重构 | 项目内多文件预览与可撤销应用、提取声明到新文件、受保护的跨包参数重排/删除；独立操作支持为项目内已验证的直接调用新增一个标量参数并统一插入字面量默认值，含限定泛型普通函数调用；保留类型参数、约束与实参顺序。文件清单变化会使预览失效；拒绝不支持的调用形态和文件操作。 |
| 调试 | 本地 Delve，含保留受支持参数的指定 Test/Example/子树调试；SSH 远端 package/test/executable 启动和 PID attach，支持经审阅的项目根 `go.work` 多模块；断点、栈、变量、表达式、步进和停止。 |
| 覆盖率与性能 | 单模块覆盖率及源码着色；CPU/内存采集与 pprof。 |
| 数据库 | SQLite、PostgreSQL、MySQL/MariaDB；SQL、事务、按主键编辑、CSV、基础 schema 补全和估计执行计划。 |
| HTTP | `.http`/`.rest` 集合、环境、顺序运行、断言、取消和脱敏汇总。 |
| 容器 | Docker/Compose、Kubernetes 资源和 loopback 转发，明确限定范围的 Dev Container 工作流。 |
| 远程工作区 | 严格主机密钥校验的 SSH 任务与预览传输；可选 gopls 独立快照分析，支持未保存文档 overlay。 |

参见[本地测试 Stop 用法](docs/USAGE.md#stop-a-local-test-run--停止本地测试)及[向已验证直接调用新增一个字面量默认参数](docs/USAGE.md#add-one-parameter--新增一个参数)，了解支持流程与边界。

详见[功能用法](docs/USAGE.md)和[已知差距](docs/KNOWN-LIMITATIONS.md)。接入某个工具不代表完成其他 IDE 的全部功能或插件兼容。

## 从源码构建

需要 **JDK 25、Python 3** 和下载固定依赖所需的网络；构建 Go 后端需要 **Go 1.26+**。Java 产物目标为 21；宿主固定为 Community **2025.2.6.3 / 252.28539.97**，LSP4IJ **0.21.0**。

```sh
# 仅 macOS Apple Silicon：准备项目独立 JDK。
python3 scripts/setup-jdk.py
export JAVA_HOME="$PWD/.tools/jdk"
./scripts/setup-tools.sh
GRADLE_USER_HOME="$PWD/.gradle-user-home" ./gradlew \
  -PgoforgeSandboxDirectory=build/verification-sandbox \
  test buildPlugin verifyPluginStructure
./scripts/dev-ide.sh
```

其他系统请将 JDK 25 配入 `JAVA_HOME`。Windows 使用 `gradlew.bat`，见[开发说明](docs/INSTALL.md#building-on-windows)。开发沙箱与独立发行包是不同入口。[macOS 打包说明](packaging/macos/README.md)记录应用组装方法。

## 验证范围

最新冻结的 **a252** 插件在 macOS 通过 **633/633 项 JUnit 测试**（366 component + 267 platform，91 份 XML），零失败、错误或跳过，311 项输入冻结；88 项 focused 已包含在总数中。插件 SHA-256：`a252ce31c51d9b427745256245bb1f852cbdf8f1a8bebe4c68d30a4098360aac`，大小 **14,884,065 字节**。a252 两平台包已准备并冻结，静态压缩包读回通过，校验值见[安装指南](docs/INSTALL.md#prepared-package-identities)。本轮泛型重构的限定原生检查按下文通过；Windows 原生执行与 CI 重跑仍未验证。另有 12 项真实 gopls/Go 泛型新增参数协议检查、44 个独立 helper 测试节点（含两个父测试）和 7 项普通函数新增参数协议回归通过，均不累加到 JUnit 总数。

**a252 的 macOS 限定原生检查通过**泛型三文件预览、Apply、显式保存及一次全局 Undo/Redo；保留推断、完整显式和部分显式类型实参。预览后新增调用文件时，在写入前拒绝旧预览，5 个基线文件及新文件均未改变。重新预览、Apply 并显式保存后，包内实际 `go test -count=1 ./...` 通过。Undo、Redo 对全部 5 个夹具文件逐字节一致，最终 Undo/保存恢复基线。正常退出后无所属进程，保留 0 条新增错误、6 条警告，严格/深度验签通过。这是候选应用的限定检查，不是直接 GoLand 对照或完整对齐；a252 本轮未证明自动保存时序，也未重测指定调试或 Stop。

历史 **319d** 通过 626/626 项 JUnit，两平台包已准备并冻结。其限定原生 Stop/输出复验保留已完成结果，未完成节点显示 Terminated，缓冲输出与诊断换行正确，跟踪的 Go/测试进程均退出。夹具未变，正常退出与后验签通过，保留 0 条错误、5 条警告；这些仍归属 319d，不作为 a252 新原生通过。

**311e** 已通过 macOS 限定原生 Stop、指定子测试调试及三文件新增参数的预览、Apply、Save、Undo、Redo。两个夹具均恢复，正常退出无所属进程残留，后验签通过；保留 0 条错误、7 条警告。该版独立的 7 项新增参数协议检查保留为历史证据。原生结果限于 311e 当时的非泛型重构与指定调试范围，不代表 a252 泛型原生验收。

历史 **f9e0** 通过 **596/596** 项 JUnit（352 component + 244 platform，87 份报告、300 项冻结输入）；插件 SHA-256 为 `f9e0ebf8734e088a7d017a7a661b14bf8ab1e13bc590f09ef26ddb66f347bad2`，大小 **14,849,715 字节**，两平台包已冻结。其原生 Current File `TestCases`（5 个节点通过）、带 `-count=1` 重跑 `TestCases/dup#01`（仅父测试与所选子测试）及跳转到 18:6 通过。点击已启用的指定测试调试按钮后，未出现新配置、Debug 面板或对话框，日志无 DAP 启动或错误。该历史未启动观察仍保留为原因不明，既不证明 Delve 故障，也不作为当前构建的失败。

独立历史证据还有：e6b3 的 7 项真实平台/Go 工作流、2 次真实 Delve 协议会话、21 项真实 Go 名称选择检查、9 项真实 Go 相对参数检查。这些不累加到 633，也不作为 a252 新执行的检查。跨包运行后选择测试，请先阅读[参数边界与用法](docs/USAGE.md)。Windows 原生执行和完整功能验收仍未完成；完整验收台账保持 **0/101**，不提供总体完成百分比；这是已完成整项验收的数量，并非已实现功能数量。

历史 **e6b3** 自动测试 593 项通过。原生检查通过 Current File 分别选择 `TestOne`、`TestTwo`、含动态子测试的 `TestCases`，以及带 `-count=1` 的失败测试重新执行。Stop 清理了已跟踪的 Go 进程与测试子进程，但界面显示 failed/incomplete，而非 Interrupted；选中子树后的重跑、跳转、调试工具栏按钮始终禁用。后续 f9e0 重跑与跳转通过修复了这两条路径；其指定调试未启动观察按上文独立保留为历史结果。

旧 **4764** macOS/Windows 包已组装冻结，但原生 Current File 运行失败：光标位于 `TestOne` 时执行了 `go test -json .`，误跑整包（8 项通过、1 项忽略）。该轮只有两项限定跳转观察通过：顶层声明，以及运行时子测试的所属声明。该失败记录独立保留，不被后续 e6b3 选择通过改写。

旧 **921d** 两平台包通过了各自的打包检查，该产物原生 Save 的五项目标对照和三项候选检查通过，退出与后验签正常。这些结果不代表 a252 完整原生验收通过。Go 保存格式化等待已连接 gopls 响应；无 overlay、VCS 仅改动行、自动导入/重排/cleanup 及无 Editor 手动格式化仍有[明确限制](docs/KNOWN-LIMITATIONS.md)。

历史失败继续保留：**9ab8** 虽通过自动测试，但首次保存落盘与空白行/光标不一致；**9abc** 原生编辑对照 8/10 一致，另两项缺最终 LF。更早的插件设置、核心插件开关、LSP 树及远端控制台协议文本缺陷保留原证据，**1caa** 的限定原生修复结果单独归属。原版十项编辑观察及这些修复检查都不构成完整产品验收。

`UpLLt/goforge-ide` 仍为私有仓库，保留较早导入的源码。首次 Windows CI 在测试前的 JDK 安装阶段失败；精确版本修复已提交，尚未启动重跑。直接网页控制仍报告禁用，本地新改动尚未上传。没有公开仓库或 Release，保持 `publish=false` 与完整对齐发布门槛。[安装指南](docs/INSTALL.md#prepared-package-identities)保留此前已准备包的身份信息；机器私有证据不随源码导出。

## 许可

原创 GoForge 代码与文档采用 [Apache-2.0](LICENSE)，归属说明见 [NOTICE](NOTICE)。已识别的第三方材料保留各自许可证：LSP4IJ 元数据补丁为 EPL-2.0，运行时和驱动还有各自条款。见 [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md)。GoForge 的许可证不会替第三方授权或改变其条款；分发时应保留适用通知与对应源码材料。

“完整对齐后才公开”是维护者的发布门槛，不是向 Apache 许可证附加新的限制。
