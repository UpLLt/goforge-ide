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
| macOS / Apple Silicon | macOS 13.5+；当前 dd87 包已审计，原生界面待验收；此前限定原生工作流属于 bdf5，Benchmark 调试仍未确认。ad-hoc 签名，尚无 Developer ID 公证。 |
| Windows x64 | 第 9 次产品测试通过，原生 gopls 检查失败；尚无完整验收的 Windows 包。见 [Windows 安装状态](docs/INSTALL.md#windows-x64)。 |
| Linux / 其他架构 | 暂无已验证的独立发行包。 |

已准备的内部平台包均内置运行时、Go SDK、gopls、Delve、Node、Dev Containers CLI 和 JDBC 驱动。Docker、kubectl、Git、OpenSSH、Cgo 的 C 编译器及 macOS 调试前置工具使用系统安装。包校验值及“已准备”与“已公开发布”的区别见[安装指南](docs/INSTALL.md#prepared-package-identities)。

## 当前工作流

当前源码支持[保存时及手动导入整理](docs/USAGE.md#organize-imports-on-save--保存时整理导入)、[顶层 Benchmark 运行与选中节点调试](docs/USAGE.md#run-one-benchmark--运行单个基准测试)、[精确远端 SDK 源码跳转](docs/USAGE.md#remote-standard-library-sources--远端标准库源码)，以及跨文件限定重构。源码测试、应用包和原生验收分别记录，详见下方验证范围及[功能用法](docs/USAGE.md)。

| 模块 | 已实现范围 |
| --- | --- |
| Go 编辑 | 本地行/块注释、配对括号、限定块缩进及原始字符串回车；gopls 补全、诊断、跳转、引用、重命名及可用代码动作；等待响应完成的 Go 默认保存格式化及手动 Reformat；独立控制的保存时导入整理先于格式化，另有手动 Optimize Imports 与保存类型列表中的 Go 选项；已有平台测试及限定 macOS 原生检查，Windows 待验收。 |
| 项目与运行 | 应用/库模块、运行/构建/测试；顶层 Test/Example 选择、运行时子测试重跑、所属声明跳转及单包失败顶层测试重跑；本地终止保留已完成结果，未完成节点标为中断。支持单个顶层 Benchmark Run，并从明确出现且已结束的 Run 控制台节点调试；直接编辑器 Benchmark Debug、子基准选择仍不可用。 |
| 重构 | 项目内多文件预览与可撤销应用、提取声明到新文件、受保护的跨包参数重排/删除；独立操作支持为项目内已验证的直接调用新增一个标量参数并统一插入字面量默认值，含限定泛型普通函数调用；普通导入走单模块离线编译检查，保留类型参数、约束与实参顺序。当前源码还支持单模块内经过编译检查的标量参数/单返回值类型修改。文件清单变化会使预览失效；拒绝不支持的调用形态和文件操作。 |
| 调试 | 本地 Delve，含保留受支持参数的指定 Test/Example/子树调试；SSH 远端 package/test/executable 启动和 PID attach，支持经审阅的项目根 `go.work` 多模块；断点、栈、变量、表达式、步进和停止。 |
| 覆盖率与性能 | 单模块覆盖率及源码着色；CPU/内存采集与 pprof。 |
| 数据库 | SQLite、PostgreSQL、MySQL/MariaDB；SQL、事务、按主键编辑、CSV、基础 schema 补全和估计执行计划。 |
| HTTP | `.http`/`.rest` 集合、环境、顺序运行、断言、取消和脱敏汇总。 |
| 容器 | Docker/Compose、Kubernetes 资源和 loopback 转发，明确限定范围的 Dev Container 工作流。 |
| 远程工作区 | 严格主机密钥校验的 SSH 任务与预览传输；可选 gopls 独立快照分析，支持未保存文档 overlay；支持审阅根目录内的 go.work 多模块；精确远端 `GOROOT/src` 定义/类型定义打开会话只读源码，任意依赖源码仍不支持。 |

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

**Full 13（dd87）**通过 **756/756 项 macOS 宿主 JUnit 测试**（433 component + 323 platform，99 份 XML，零失败/错误/跳过）、插件构建与结构检查，345 项构建输入已冻结。该源码新增普通导入下经过编译检查的 Add Parameter；Mac 包已组装并独立审计，原生界面与 Windows 验证仍待完成。

当前已审计 macOS 包为 dd87，验签、无界面启动、迁移及归档检查通过，dd87 原生界面待验收。此前 bdf5 的限定原生检查覆盖手动导入整理及 Undo/Redo、可见的 Go 导入类型入口、指定 Benchmark 运行、远端 SDK 只读跳转与 Stop。Benchmark 调试仍为未确认原因的界面尝试；完整界面验收及当前会话最终退出检查待完成。

Windows 第 9 次使用较早 2406 源码：34 项预检、722 项产品测试通过，1 项明确的 POSIX shell 跳过。迁移后的原生启动器、包内工具版本及 Go run/test 通过；gopls 定义请求因 `no views` 失败，尚未进入 Delve 验收。本轮整体失败，Windows 界面及更新源码仍待验收。

包哈希与安装方式见[安装指南](docs/INSTALL.md#prepared-package-identities)，具体缺口见[已知差距](docs/KNOWN-LIMITATIONS.md#verification-scope--验证范围)。历史结果仅代表对应构建与原检查范围，完整记录由维护者保留。

完整验收仍为 **0/101**，表示完整验收项数量，不是功能实现率。仓库保持私有，无公开 Release，`publish=false`；全部对齐并验证后才公开发布。

## 许可

原创 GoForge 代码与文档采用 [Apache-2.0](LICENSE)，归属说明见 [NOTICE](NOTICE)。已识别的第三方材料保留各自许可证：LSP4IJ 元数据补丁为 EPL-2.0，运行时和驱动还有各自条款。见 [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md)。GoForge 的许可证不会替第三方授权或改变其条款；分发时应保留适用通知与对应源码材料。

“完整对齐后才公开”是维护者的发布门槛，不是向 Apache 许可证附加新的限制。
