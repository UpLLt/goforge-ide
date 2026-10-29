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
| Windows x64 | a252 便携包已在 macOS 跨平台组装并冻结，全量解压及文件读回通过；Windows CI 已恢复运行，但第 2、3 次运行均在安装包验收前失败；原生启动与界面仍待验证。见 [Windows 安装](docs/INSTALL.md#windows-x64)。 |
| Linux / 其他架构 | 暂无已验证的独立发行包。 |

已准备的内部平台包均内置运行时、Go SDK、gopls、Delve、Node、Dev Containers CLI 和 JDBC 驱动。Docker、kubectl、Git、OpenSSH、Cgo 的 C 编译器及 macOS 调试前置工具使用系统安装。包校验值及“已准备”与“已公开发布”的区别见[安装指南](docs/INSTALL.md#prepared-package-identities)。

## 当前工作流

| 模块 | 已实现范围 |
| --- | --- |
| Go 编辑 | 本地行/块注释、配对括号、限定块缩进及原始字符串回车；gopls 补全、诊断、跳转、引用、重命名及可用代码动作；等待响应完成的 Go 默认保存格式化及手动 Reformat，具体边界见下文。 |
| 项目与运行 | 应用/库模块、运行/构建/测试；顶层 Test/Example 选择、运行时子测试重跑、所属声明跳转及单包失败顶层测试重跑；本地终止保留已完成结果，未完成节点标为中断。 |
| 重构 | 项目内多文件预览与可撤销应用、提取声明到新文件、受保护的跨包参数重排/删除；独立操作支持为项目内已验证的直接调用新增一个标量参数并统一插入字面量默认值，含限定泛型普通函数调用；保留类型参数、约束与实参顺序。当前源码还支持单模块内经过编译检查的标量参数/单返回值类型修改。文件清单变化会使预览失效；拒绝不支持的调用形态和文件操作。 |
| 调试 | 本地 Delve，含保留受支持参数的指定 Test/Example/子树调试；SSH 远端 package/test/executable 启动和 PID attach，支持经审阅的项目根 `go.work` 多模块；断点、栈、变量、表达式、步进和停止。 |
| 覆盖率与性能 | 单模块覆盖率及源码着色；CPU/内存采集与 pprof。 |
| 数据库 | SQLite、PostgreSQL、MySQL/MariaDB；SQL、事务、按主键编辑、CSV、基础 schema 补全和估计执行计划。 |
| HTTP | `.http`/`.rest` 集合、环境、顺序运行、断言、取消和脱敏汇总。 |
| 容器 | Docker/Compose、Kubernetes 资源和 loopback 转发，明确限定范围的 Dev Container 工作流。 |
| 远程工作区 | 严格主机密钥校验的 SSH 任务与预览传输；可选 gopls 独立快照分析，支持未保存文档 overlay；当前源码支持审阅根目录内的 go.work 多模块。 |

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

当前源码版本 **5259** 已通过 **659/659 项 macOS 主机 JUnit 测试**（389 component + 270 platform，94 份报告，零失败/错误/跳过），插件构建和结构校验通过。插件 **14,931,387 字节**，SHA-256：`52598058b70ba2329bb1447be52d6874596fabf02eeb3b76ecaf30fe6c7fb354`，324 项构建输入冻结。新增限定标量参数/返回值类型修改及审阅多模块远程分析，并修复 Windows CI 准备问题。20 项真实重构协议、31 个 helper 子案例、3 项独立编译配置回归及 18 项多模块 SSH/gopls 检查单独通过，不计入 JUnit 总数。新应用原生验收和 Windows CI 仍待完成。


最近一次冻结安装包仍为 **a252**，在 macOS 通过 **633/633 项 JUnit 测试**（366 component + 267 platform，零失败、错误或跳过）。插件 SHA-256：`a252ce31c51d9b427745256245bb1f852cbdf8f1a8bebe4c68d30a4098360aac`，**14,884,065 字节**。两平台包的静态压缩包读回通过，原校验值保留于[安装指南](docs/INSTALL.md#prepared-package-identities)。新源码的统一构建已通过，安装包验收仍待完成，见上方记录。

**a252 的 macOS 限定原生检查**通过泛型三文件预览、Apply、显式保存、全局 Undo/Redo，以及新增调用文件后拒绝旧预览。保存后的包内 Go 测试通过，5 个夹具文件最终逐字节恢复。正常退出无所属进程，保留 0 条新增错误、6 条警告，严格/深度验签通过。这是候选应用的限定检查；自动保存时序未证明，指定调试和 Stop 未在 a252 重测。更早的通过与失败保留各自构建身份，见[已知差距中的验证记录](docs/KNOWN-LIMITATIONS.md#verification-scope--验证范围)。

私有 Windows CI 已恢复运行。a252 源码已导入 `UpLLt/goforge-ide`，提交为 `57fd69c145cbbe3780d256b7a03fec793af17691`。第 2 次运行使用旧 449 测试快照，在沙箱准备阶段失败；第 3 次使用 a252 快照，在准备脚本的路径夹具测试中失败。ZIP 元数据与路径夹具修复已通过本机相关回归，**Windows 产品测试、原生启动和界面验收尚未通过**。详见 [CI 记录](docs/KNOWN-LIMITATIONS.md#private-validation-and-publication--私有验证与发布)。

完整验收台账保持 **0/101**，计数的是已完成整项验收，并非已实现功能或总体百分比。仓库仍为私有，没有公开 Release，保持 `publish=false`，完整对齐后才公开发布。

## 许可

原创 GoForge 代码与文档采用 [Apache-2.0](LICENSE)，归属说明见 [NOTICE](NOTICE)。已识别的第三方材料保留各自许可证：LSP4IJ 元数据补丁为 EPL-2.0，运行时和驱动还有各自条款。见 [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md)。GoForge 的许可证不会替第三方授权或改变其条款；分发时应保留适用通知与对应源码材料。

“完整对齐后才公开”是维护者的发布门槛，不是向 Apache 许可证附加新的限制。
