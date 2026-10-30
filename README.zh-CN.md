# GoForge

**无需注册、登录、激活或订阅的独立 Go IDE，已实现功能全部开放。**

[English](README.md) · [下载 v0.1.0](https://github.com/UpLLt/goforge-ide/releases/tag/v0.1.0) · [安装指南](docs/INSTALL.md) · [使用说明](docs/USAGE.md) · [已知限制](docs/KNOWN-LIMITATIONS.md)

GoForge 使用原创代码及公开的 IntelliJ Community、LSP4IJ、gopls 和 Delve，独立于 JetBrains，不包含专有 GoLand 插件。v0.1.0 面向普通本地 Go 项目的日常开发；**尚未验证与 GoLand 完整等价**。外部数据库、SSH、容器和 Git 服务仍需要正常连接配置。

## 安装与开始使用

1. 在 [Releases](https://github.com/UpLLt/goforge-ide/releases/tag/v0.1.0) 下载对应系统的应用 ZIP，按同页 SHA-256 校验文件核对，完整解压。
2. macOS Apple Silicon（13.5+）：打开 `GoForge.app`。Windows 10/11 x64：运行 `GoForge/bin/goforge64.exe`。
3. 选择 **New Go Project…**，或打开带 `go.mod` 的项目目录，等待索引与 gopls 初始化。
4. 使用生成的 **Run / Test / Debug** 配置；在 **Settings → GoForge** 配置工具，在 **Settings → Go Project** 配置构建参数。

包内含 Java 运行时、Go SDK、gopls、Delve、Node、Dev Containers CLI 和 JDBC 驱动。Git、Docker、kubectl、OpenSSH、Cgo 编译器及 macOS 调试前置工具按需另装。macOS 为 ad-hoc 签名、未公证；Windows 启动器未签名。Linux 和其他 CPU 架构暂无已验证独立发行包。

## 当前工作流

当前源码支持[保存时及手动导入整理](docs/USAGE.md#organize-imports-on-save--保存时整理导入)、[顶层 Benchmark 运行与选中节点调试](docs/USAGE.md#run-one-benchmark--运行单个基准测试)、[远端 SDK 及已选缓存依赖源码跳转](docs/USAGE.md#remote-standard-library-sources--远端标准库源码)，以及跨文件限定重构。源码测试、应用包和原生验收分别记录，详见下方验证范围及[功能用法](docs/USAGE.md)。

| 模块 | 已实现范围 |
| --- | --- |
| Go 编辑 | 本地行/块注释、配对括号、限定块/圆括号/方括号续行缩进及原始字符串回车；gopls 补全、诊断、跳转、引用、重命名及可用代码动作；等待响应完成的 Go 默认保存格式化及手动 Reformat；独立控制的保存时导入整理先于格式化，另有手动 Optimize Imports 与保存类型列表中的 Go 选项；既有流程保留 macOS/Windows 平台与限定 Mac 原生证据；新续行行为通过 macOS 宿主测试及 3 项与 GoLand 对照的限定原生回车检查，对应 Full 19 的 Windows 产品测试已通过，Windows 原生交互界面仍待验收。 |
| 项目与运行 | 应用/库模块、运行/构建/测试；顶层 Test/Example 选择、运行时子测试重跑、所属声明跳转及单包失败顶层测试重跑；本地终止保留已完成结果，未完成节点标为中断。支持单个顶层 Benchmark Run、受保护的完成节点重跑及 Debug/源码跳转，以及保存后从编辑器声明运行一次迭代的 Debug Go Benchmark at Caret；直接子基准选择仍不可用。各构建证据见下方验证范围。 |
| 重构 | 项目内多文件预览与可撤销应用、提取声明到新文件、受保护的跨包参数重排/删除；独立操作支持为项目内已验证的直接调用新增一个标量参数并统一插入字面量默认值，含限定泛型普通函数调用，以及经类型检查的私有具体方法直接调用；普通导入走单模块离线编译检查，保留类型参数、约束与实参顺序。当前源码还支持单模块内经编译检查的普通函数标量参数/单返回值类型修改，以及受限私有具体方法的单个标量参数或具名/未命名单返回值类型修改。新增独立的私有普通函数标量参数重排：通过带类型临时变量与整模块类型/编译检查，保留常量及静态函数调用实参的求值顺序。文件清单变化会使预览失效；拒绝不支持的调用形态和文件操作。 |
| 调试 | 本地 Delve，含保留受支持参数的指定 Test/Example/子树调试；SSH 远端 package/test/executable 启动和 PID attach，支持经审阅的项目根 `go.work` 多模块；断点、栈、变量、表达式、步进和停止；远端包/测试会话暂停时可查看限定 SDK 及已选缓存依赖只读快照；依赖查看须单独模块与校验/缓存证明，可执行文件/attach 的 SDK 及依赖源码快照仍不支持。 |
| 覆盖率与性能 | 单模块覆盖率及源码着色；CPU/内存采集与 pprof。 |
| 数据库 | SQLite、PostgreSQL、MySQL/MariaDB；SQL、事务、按主键编辑、CSV、基础 schema 补全和估计执行计划。 |
| HTTP | `.http`/`.rest` 集合、环境、顺序运行、断言、取消和脱敏汇总；默认最多五跳重定向、请求级 `@no-redirect` 与跨来源头隔离。 |
| 容器 | Docker/Compose、Kubernetes 资源和 loopback 转发，明确限定范围的 Dev Container 工作流。 |
| 远程工作区 | 严格主机密钥校验的 SSH 任务与预览传输；可选 gopls 独立快照分析，支持未保存文档 overlay；支持审阅根目录内的 go.work 多模块；精确远端 `GOROOT/src` 定义/类型定义打开会话只读源码，单根 go.mod/go.sum 下已选且已缓存的模块版本经校验后也可只读打开；replace、vendor、go.work 依赖源码仍不支持；暂停的包/测试调试依赖查看另有独立保护流程。 |

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

2026-10-30 在 macOS 原生界面走通普通 Go 项目的打开、编辑/跨文件补全、保存、运行、测试及失败恢复、断点调试、Git 差异/提交/分支、关闭重开。修复 Git 分支面板线程异常后，相关流程再次通过。插件保持已通过 926 项 macOS 产品测试及 141 个包内重构场景的 Full 28 字节。

Windows 发行包须通过同次 CI 的产品、启动器、工具链、Git 修复及包内协议检查。**Windows 桌面交互、大工程和长会话尚未完整验收。** 各次构建证据与差异见[发行说明](docs/RELEASE-0.1.0.md)及[已知限制](docs/KNOWN-LIMITATIONS.md)。v0.1.0 保留已测试的内部 `0.1.0-dev` / `0.1 Development` 版本文字，以发行包校验值区分本版。

## 许可

原创 GoForge 代码与文档采用 [Apache-2.0](LICENSE)，归属说明见 [NOTICE](NOTICE)。已识别的第三方材料保留各自许可证：LSP4IJ 元数据补丁为 EPL-2.0，运行时和驱动还有各自条款。见 [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md)。GoForge 的许可证不会替第三方授权或改变其条款；分发时应保留适用通知与对应源码材料。
