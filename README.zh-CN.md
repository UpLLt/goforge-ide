# GoForge

**独立 Go IDE，无需 GoForge 注册、登录、激活、订阅或付费解锁。**

[English](README.md) · [安装指南](docs/INSTALL.md) · [功能用法](docs/USAGE.md) · [已知差距](docs/KNOWN-LIMITATIONS.md) · [第三方说明](THIRD-PARTY-NOTICES.md)

GoForge 使用原创代码、公开的 IntelliJ Community 平台、LSP4IJ、gopls 和 Delve。项目独立于 JetBrains，不包含专有 GoLand 插件。

**当前为内部开发构建**：已经实现多条实际工作流，但尚未证明与 GoLand 完整功能对齐。**必须验证完整对齐后，才能公开发布源码和二进制。** 准备安装包或确定许可证不代表满足发布门槛。连接 SSH、数据库、Docker、Git 托管等外部服务时，仍需要这些服务正常的配置和访问权限。

## 开始使用

1. 开发阶段从源码构建或使用内部候选安装包；当前发布门槛下尚无公开 **Releases** 下载。按[安装指南](docs/INSTALL.md)校验、解压，留意平台验证状态。
2. 选择 **New Go Project…** 新建应用或库，也可以打开含 `go.mod` 的项目目录。
3. 选择预置 **Run / Test / Debug** 配置。在行号边栏打断点，通过 Debug 窗口查看变量、步进和求值。
4. **Settings → GoForge** 可设置工具路径；**Settings → Go Project** 可设置 build tags、GOOS、GOARCH。

| 平台 | 包与验证情况 |
| --- | --- |
| macOS / Apple Silicon | 开发版 `.app`/ZIP 已组装，macOS 13.5+；最新 1caa 已通过自动检查，新包和原生验收仍待完成；历史 7bff 的结果与缺陷单独保留。历史限定通过结果单独保留。本地 ad-hoc 签名，尚无 Apple Developer ID 公证。 |
| Windows x64 | 便携 ZIP 已组装并通过结构检查；尚未验证 Windows 原生运行。详见 [Windows 安装](docs/INSTALL.md#windows-x64)。 |
| Linux / 其他架构 | 暂无已验证的独立发行包。 |

两平台候选包均内置运行时、Go SDK、gopls、Delve、Node、Dev Containers CLI 和 JDBC 驱动。Docker、kubectl、Git、OpenSSH、Cgo 的 C 编译器及 macOS 调试前置工具使用系统安装。包校验值及“已准备”与“已公开发布”的区别见[安装指南](docs/INSTALL.md#prepared-package-identities)。

## 当前工作流

| 模块 | 已实现范围 |
| --- | --- |
| Go 编辑 | gopls 补全、诊断、跳转、引用、重命名、格式化及可用代码动作。 |
| 项目与运行 | 应用/库模块、运行/构建/测试、测试树、可编辑的 Go 命令配置。 |
| 重构 | 项目内多文件预览与可撤销应用、提取声明到新文件、受保护的跨包参数重排/删除；拒绝有副作用或无法证明安全的调用变换，以及不支持的命令和文件操作。 |
| 调试 | 本地 Delve；SSH 远端 package/test/executable 启动和 PID attach，支持经审阅的项目根 `go.work` 多模块；断点、栈、变量、表达式、步进和停止。 |
| 覆盖率与性能 | 单模块覆盖率及源码着色；CPU/内存采集与 pprof。 |
| 数据库 | SQLite、PostgreSQL、MySQL/MariaDB；SQL、事务、按主键编辑、CSV、基础 schema 补全和估计执行计划。 |
| HTTP | `.http`/`.rest` 集合、环境、顺序运行、断言、取消和脱敏汇总。 |
| 容器 | Docker/Compose、Kubernetes 资源和 loopback 转发，明确限定范围的 Dev Container 工作流。 |
| 远程工作区 | 严格主机密钥校验的 SSH 任务与预览传输；可选 gopls 独立快照分析，支持未保存文档 overlay。 |

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

冻结的 **1caa** 统一构建在 macOS 开发主机通过 **449 项测试**（313 component + 136 platform）、72 份 JUnit 报告，零失败、错误或跳过；插件打包与结构检查通过。插件 SHA-256 为 `1caad6d85ea78419e17fbb0abcd15b16fba8667d803f34d3518da59441ee1038`。这些是 macOS 主机结果，不能换算为功能完成度，也不证明 Windows 兼容性。新包与原生界面验收仍待完成。

受保护的跨包参数重排/删除通过 21 项真实 gopls/AST/编辑规划检查；根目录 `go.work` 调试通过生成的 Linux SSH/Go/Delve 协议检查。历史 **7bff** 原生会话验证了限定签名操作、远端 package/test 跨模块断点、变量、步进和退出，也发现核心插件开关绕过、LSP explorer 树生命周期错误及远程控制台泄漏 DAP 传输文本。新构建包含生命周期保护和按传输流分类的控制台修复，平台/依赖补丁另有固定版本回归；其新原生验收仍待完成，旧失败继续保留。

历史 `d3d7` 已有提取/撤销重做及本地运行/测试/调试的限定原生结果，同时暴露过插件设置搜索初始化错误；旧通过结果不移植给新产物。Windows 目前通过的是跨平台组装和静态检查，原生执行待验证。`UpLLt/goforge-ide` 是私有仓库，已提交 Windows 工作流用于内部验收；没有公开仓库或 Release，`publish` 默认关闭，完整对齐发布门槛仍未满足。详见[验证记录](docs/INSTALL.md#prepared-package-identities)；机器私有日志不随源码导出。

## 许可

原创 GoForge 代码与文档采用 [Apache-2.0](LICENSE)，归属说明见 [NOTICE](NOTICE)。已识别的第三方材料保留各自许可证：LSP4IJ 元数据补丁为 EPL-2.0，运行时和驱动还有各自条款。见 [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md)。GoForge 的许可证不会替第三方授权或改变其条款；分发时应保留适用通知与对应源码材料。

“完整对齐后才公开”是维护者的发布门槛，不是向 Apache 许可证附加新的限制。
