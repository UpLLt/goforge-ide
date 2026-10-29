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
| macOS / Apple Silicon | macOS 13.5+；当前 318d 归档读回与绑定打包检查通过，界面验收待完成。最近实际界面检查为 5259 的两组限定标量类型工作流。本地 ad-hoc 签名，尚无 Apple Developer ID 公证。 |
| Windows x64 | 实际 Windows 第 5 次产品/平台测试：677 通过，1 项明确的 POSIX shell 跳过，0 失败/错误。Windows 组装通过，但原生验证器预检失败；无最终包导出或原生启动/界面验收。见 [Windows 安装](docs/INSTALL.md#windows-x64)。 |
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

当前冻结的 **318d 修复源码构建**在 macOS 主机通过 **678/678 项 JUnit 测试**（397 component + 281 platform，95 份 XML 报告，零失败/错误/跳过），插件构建及结构检查通过，326 项构建输入冻结。插件 **14,941,918 字节**，SHA-256：`318d7205541d0fd00b51f74c13ec7ecc22297a3dd19d6da0e1da7f342918fb93`。Windows 第 5 次另已通过产品/平台测试；自动化结果不代表应用界面验收。当前 Mac 包为 318d，最近实际界面记录仍为 5259。

**2026-10-29 冻结基线 5259** 在 macOS 主机通过 **659/659 项 JUnit 测试**（389 component + 270 platform，94 份报告，零失败/错误/跳过），插件构建和结构校验通过。插件 **14,931,387 字节**，SHA-256：`52598058b70ba2329bb1447be52d6874596fabf02eeb3b76ecaf30fe6c7fb354`，当时冻结 324 项构建输入。另有 20 项标量重构协议、31 个 helper 子案例、3 项编译配置回归及 18 项多模块 SSH/gopls 检查通过，不累加到 JUnit 总数。

当前 **318d macOS 包**已冻结：**751,553,996 字节**，SHA-256：`7a53be4464eb41afe752b069201f08ec7f5c4a6987b0c9903b8b1c70af8dbb02`。独立归档、插件、源码与许可证读回通过；绑定的打包记录显示 ad-hoc 验签、无界面、迁移路径及解压检查通过。审计 SHA-256：`cffe0e152a10d2bd2a498ea5ad6e1ea45708ca8ba1447b5f7a531bdb13e832da`。**318d 界面验收仍待完成；最近实际界面检查保留为下述 5259 两组限定结果。**

历史 **5259 macOS 包**已冻结：**751,543,468 字节**，SHA-256：`c2c27d6b42cc98deaa95f8138b06cddd2cb7beac1042ccf5a3d85d08c4d87ecb`。两组限定原生检查通过：标量参数类型修改、泛型单返回值类型修改，均覆盖预览、Apply、显式保存、Undo/Redo，以及稳定状态下的保存字节和包内 Go 测试检查。4 个夹具文件全部恢复；正常退出无所属进程，保留 0 条错误、2 条警告，退出后验签通过。参数修改以稳定状态的 Redo/测试回执为准。这些是候选应用检查，不是 GoLand 对照；远程分析原生验收与 Windows 桌面验收仍未完成。

**Windows 第 5 次产品/平台测试已通过：** [私有 CI `40808662484`](https://github.com/UpLLt/goforge-ide/actions/runs/40808662484)，源码提交 `cf208a581836640331c24d9ec5eb8c71d1a6e7d1`。95 份 XML 报告共 **678 项：677 项通过，0 失败/错误，1 项明确的 POSIX shell 跳过**（`GoRemoteProfileTest.shellQuotedDataRemainsExactlyOneLiteralArgument`）。第 4 次失败的 14 项全部在本轮通过。审阅源码 ZIP 的 SHA-256 为 `7a5fd28aa330c816e33912edde4dcd65055f8e3a077d989780699e8bdab42f05`（**878,196 字节**）。Windows 组装及两项平台补丁检查通过。**第 5 次最终在原生验证器预检失败：** `Get-AuthenticodeSignature` 无法加载 `Microsoft.PowerShell.Security`。原生报告为 `checks: {}`，尚未执行启动器、Go、gopls 或 Delve 探针；最终发行文件导出跳过，原生启动和界面仍未验证。较早 a252 Windows 包继续保留独立的跨平台组装身份。保持 `publish=false`，本轮仅为私有验证。

此前 a252 泛型重构、319d Stop/输出及 311e 指定调试/新增参数结果保留各自范围，见[已知差距的验证记录](docs/KNOWN-LIMITATIONS.md#verification-scope--验证范围)；历史安装包哈希仍保留于[安装指南](docs/INSTALL.md#prepared-package-identities)。

完整验收台账保持 **0/101**，计数的是已完成整项验收，并非已实现功能或总体百分比。仓库仍为私有，没有公开 Release，保持 `publish=false`，完整对齐后才公开发布。

## 许可

原创 GoForge 代码与文档采用 [Apache-2.0](LICENSE)，归属说明见 [NOTICE](NOTICE)。已识别的第三方材料保留各自许可证：LSP4IJ 元数据补丁为 EPL-2.0，运行时和驱动还有各自条款。见 [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md)。GoForge 的许可证不会替第三方授权或改变其条款；分发时应保留适用通知与对应源码材料。

“完整对齐后才公开”是维护者的发布门槛，不是向 Apache 许可证附加新的限制。
