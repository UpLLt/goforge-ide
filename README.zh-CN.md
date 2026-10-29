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
| macOS / Apple Silicon | macOS 13.5+；冻结基线 5259 的压缩包检查及两组限定标量类型原生工作流通过，含预览/Apply/Save/Undo/Redo。本地 ad-hoc 签名，尚无 Apple Developer ID 公证。验证范围见下文。 |
| Windows x64 | 较早的冻结 a252 便携包仅通过 macOS 跨平台组装与静态读回。实际 Windows 第 4 次 CI 为 644 通过 / 14 失败 / 1 跳过，打包跳过；318d 修复构建已通过 macOS 自动化检查，Windows 第 5 次 CI 待完成，原生启动/界面仍未验证。见 [Windows 安装](docs/INSTALL.md#windows-x64)。 |
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

当前冻结的 **318d 修复源码构建**在 macOS 主机通过 **678/678 项 JUnit 测试**（397 component + 281 platform，95 份 XML 报告，零失败/错误/跳过），插件构建及结构检查通过，326 项构建输入冻结。插件 **14,941,918 字节**，SHA-256：`318d7205541d0fd00b51f74c13ec7ecc22297a3dd19d6da0e1da7f342918fb93`。**Windows 第 5 次 CI 仍待完成。** 这项 macOS 自动化结果不代表 Windows 专属修复已在 Windows 验证，也不代表新应用原生验收；最近已验证的 macOS 应用包及原生记录仍为 5259。

**2026-10-29 冻结基线 5259** 在 macOS 主机通过 **659/659 项 JUnit 测试**（389 component + 270 platform，94 份报告，零失败/错误/跳过），插件构建和结构校验通过。插件 **14,931,387 字节**，SHA-256：`52598058b70ba2329bb1447be52d6874596fabf02eeb3b76ecaf30fe6c7fb354`，当时冻结 324 项构建输入。另有 20 项标量重构协议、31 个 helper 子案例、3 项编译配置回归及 18 项多模块 SSH/gopls 检查通过，不累加到 JUnit 总数。

**5259 macOS 包**已冻结：**751,543,468 字节**，SHA-256：`c2c27d6b42cc98deaa95f8138b06cddd2cb7beac1042ccf5a3d85d08c4d87ecb`。两组限定原生检查通过：标量参数类型修改、泛型单返回值类型修改，均覆盖预览、Apply、显式保存、Undo/Redo，以及稳定状态下的保存字节和包内 Go 测试检查。4 个夹具文件全部恢复；正常退出无所属进程，保留 0 条错误、2 条警告，退出后验签通过。参数修改以稳定状态的 Redo/测试回执为准。这些是候选应用检查，不是 GoLand 对照；远程分析原生验收与 Windows 桌面验收仍未完成。

**Windows 第 4 次 CI 已实际运行产品/平台测试：** 源码提交为 `9036d0a76e15882603d45d05ce0fc295bad2c861`，经审阅的源码 ZIP SHA-256 为 `17a2e91af959fb525fdf381ead90e6560455f4ae2a0117cc41b9c8b0558c3198`。659 项报告中，**644 项通过、14 项失败、1 项跳过**。打包步骤跳过，该轮未产出通过验收的 Windows 包。冻结 318d 修复构建已通过 macOS 自动化检查，Windows 第 5 次 CI 仍待完成。已有冻结 Windows 包仍是较早的 **a252** macOS 跨平台组装，仅有静态读回验证，原生启动/界面尚未通过。 详见 [CI 历史](docs/KNOWN-LIMITATIONS.md#private-validation-and-publication--私有验证与发布)。

此前 a252 泛型重构、319d Stop/输出及 311e 指定调试/新增参数结果保留各自范围，见[已知差距的验证记录](docs/KNOWN-LIMITATIONS.md#verification-scope--验证范围)；历史安装包哈希仍保留于[安装指南](docs/INSTALL.md#prepared-package-identities)。

完整验收台账保持 **0/101**，计数的是已完成整项验收，并非已实现功能或总体百分比。仓库仍为私有，没有公开 Release，保持 `publish=false`，完整对齐后才公开发布。

## 许可

原创 GoForge 代码与文档采用 [Apache-2.0](LICENSE)，归属说明见 [NOTICE](NOTICE)。已识别的第三方材料保留各自许可证：LSP4IJ 元数据补丁为 EPL-2.0，运行时和驱动还有各自条款。见 [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md)。GoForge 的许可证不会替第三方授权或改变其条款；分发时应保留适用通知与对应源码材料。

“完整对齐后才公开”是维护者的发布门槛，不是向 Apache 许可证附加新的限制。
