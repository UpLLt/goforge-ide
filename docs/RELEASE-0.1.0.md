# GoForge v0.1.0 — First usable release / 首个可用版本

An independent Go IDE, ready for ordinary local Go project work. No GoForge account, registration, activation, subscription or feature paywall is required.

独立 Go IDE，面向普通本地 Go 项目的日常开发。无需 GoForge 账号、注册、登录、激活或订阅，已实现功能全部开放。

## Download and install / 下载与安装

- **macOS Apple Silicon, macOS 13.5+**: download `GoForge-0.1.0-macos-aarch64.zip`, extract, and open `GoForge.app`. / 下载对应 ZIP，完整解压，将应用放入自己的应用目录后打开。
- **Windows 10/11 x64**: download `GoForge-0.1.0-windows-x64.zip`, extract the whole archive, and run `GoForge/bin/goforge64.exe`. / 下载对应 ZIP，完整解压，运行上述程序，不要直接在压缩包内启动。
- Compare the archive's SHA-256 with `SHA256SUMS-macos.txt` or `SHA256SUMS-windows.txt`. The source-code ZIP is not an application installer. / 使用对应系统的校验文件核对 SHA-256；源码 ZIP 不是安装包。
- Java runtime, Go SDK, gopls and Delve are bundled. Git, Docker, kubectl, OpenSSH and Cgo compilers are separate tools when those workflows need them. / 已附运行时和 Go 工具，相关外部工具按需安装。

[Installation / 安装指南](https://github.com/UpLLt/goforge-ide/blob/main/docs/INSTALL.md) · [Usage / 使用说明](https://github.com/UpLLt/goforge-ide/blob/main/docs/USAGE.md) · [中文首页](https://github.com/UpLLt/goforge-ide/blob/main/README.zh-CN.md)

## Verified scope / 验证范围

The 2026-10-30 macOS native check used an ordinary isolated Go module and completed: opening a project, editing and cross-file completion, formatting and saving, running, three tests including failure/recovery, local breakpoints/evaluation/stepping/stop, Git diff/commit/branch checkout, and normal close/reopen. A reproducible Git branch-dashboard dispatcher exception was repaired and the affected workflows passed again. The repaired session recorded zero ERROR/SEVERE entries, with ten retained platform warnings.

2026-10-30 在 macOS 原生界面使用普通独立 Go 项目走通：打开、编辑及跨文件补全、格式化保存、运行、三项测试及失败恢复、断点/求值/单步/停止、Git 差异/提交/分支切换、正常关闭重开。修复了一项可复现的 Git 分支面板线程异常，相关流程复测通过；修复后的会话日志没有 ERROR/SEVERE，保留十条平台警告。

The unchanged Full 28 GoForge plugin previously passed 926 macOS-host product tests and 141 packaged refactoring cases. This release adds the narrow Community Git dashboard repair; its focused validation covers archive integrity and real dispatcher/tree/lifecycle behavior. The Windows release workflow runs product tests, native launcher/relocation/toolchain checks, the Git repair probe and packaged refactoring protocols before creating release assets. The linked Actions result and included reports describe the actual Windows build; CI does not constitute interactive Windows desktop acceptance.

GoForge 插件保持已验证的 Full 28 版本：此前通过 926 项 macOS 宿主产品测试和 141 个包内重构场景。本次加入限定的 Community Git 面板修复，并检查归档完整性与真实线程/树/生命周期。Windows 发行工作流须通过产品测试、原生启动器/迁移/工具链检查、Git 修复探针及包内重构协议，才生成发行资产；实际结果以 Actions 和随包报告为准，CI 不等于 Windows 桌面交互验收。

## Boundaries / 当前边界

This is an early usable release, **not verified full GoLand parity**. Advanced refactoring, remote-development/source-mapping cases, database dialect intelligence and parts of the plugin ecosystem remain limited. Windows interactive GUI, large-project and long-session acceptance remain outstanding. See [known limitations](https://github.com/UpLLt/goforge-ide/blob/main/docs/KNOWN-LIMITATIONS.md).

这是早期可用版本，**尚未验证与 GoLand 完整等价**。高级重构、部分远程开发与源码映射、数据库方言智能和插件生态仍有限制；Windows 交互界面、大工程及长时间会话尚待更广验证。

The v0.1.0 release deliberately retains the tested internal `0.1.0-dev` / `0.1 Development` application metadata. The release ZIP names and checksums identify this distribution. macOS uses ad-hoc signing without Developer ID notarization; the GoForge Windows launcher is unsigned. Keep operating-system protections enabled and use normal system approval after checking the source and checksum.

v0.1.0 发行保留已测试的内部 `0.1.0-dev` / `0.1 Development` 版本文字，以发行 ZIP 名称和校验值区分本版。macOS 为 ad-hoc 签名、未做 Developer ID 公证；Windows 启动器未签名。核对来源及校验值后使用正常系统授权界面，保留系统安全保护。

Original GoForge code is Apache-2.0. Third-party licenses, notices, patches and corresponding source materials retain their own terms. GoForge is independent of JetBrains and does not include the proprietary GoLand plugin. Optional external services retain their own connection/authentication requirements.

原创 GoForge 代码采用 Apache-2.0；第三方许可、通知、补丁及对应源码保留各自条款。GoForge 独立于 JetBrains，不包含专有 GoLand 插件。外部服务仍需要其正常连接配置和权限。
