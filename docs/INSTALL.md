# Install GoForge / 安装 GoForge

[English README](../README.md) · [中文首页](../README.zh-CN.md) · [Usage / 用法](USAGE.md) · [Download v0.1.0 / 下载](https://github.com/UpLLt/goforge-ide/releases/tag/v0.1.0)

GoForge requires no product account, registration, login or activation. Use the application ZIP for your OS/CPU, not GitHub's source-code archive. v0.1.0 retains tested internal `0.1.0-dev` / `0.1 Development` metadata. / 无需 GoForge 账号、注册、登录或激活。下载匹配系统/CPU 的应用 ZIP，不要把源码包当安装包；本版保留已测试的内部版本文字。

## macOS Apple Silicon

Requirements: Apple Silicon, macOS **13.5+**. Java and Go tools are bundled; Git, Cgo and local debugging may additionally require Apple's Command Line Tools. / 要求 Apple Silicon、macOS 13.5+；已内置 Java 和 Go，Git、Cgo、本地调试可能另需 Apple Command Line Tools。

1. Download `GoForge-0.1.0-macos-aarch64.zip` and `SHA256SUMS-macos.txt` from the release page. / 从发行页下载这两个文件。
2. In that folder, run `shasum -a 256 -c SHA256SUMS-macos.txt`. The application archive must report `OK`. / 在下载目录运行校验，应用 ZIP 应显示 `OK`。
3. Extract the whole archive, move `GoForge.app` to a folder you control (for example Applications), and open it. Keep the bundle intact. / 完整解压，把应用放入自己的应用目录再打开，保留整个应用包。
4. This app is ad-hoc signed, without Developer ID notarization. macOS may block a downloaded build; after checking source and checksum, use the normal system approval interface if available. Do not disable system protection. / 应用未做 Developer ID 公证；核对来源和校验值后使用正常系统授权界面，保留系统保护。
5. Select **New Go Project…** or open an existing module. Handle debugger authorization through the normal macOS prompt. / 新建或打开 Go 模块，通过系统弹窗完成调试授权。

Settings: `~/Library/Application Support/GoForge/GoForge2026.1`; caches: `~/Library/Caches/GoForge/GoForge2026.1`; logs: `~/Library/Logs/GoForge/GoForge2026.1`. Removing the app does not remove projects or these settings. / 设置、缓存、日志使用上述独立目录，删除应用不会删除项目或个人设置。

## Windows x64

Targets Windows **10/11 x64**. The launcher is not Authenticode-signed by GoForge. Windows CI verifies the launcher, tools, product tests and bounded packaged protocols; **interactive Windows desktop acceptance remains pending**. / 面向 Windows 10/11 x64，GoForge 启动器未签名；CI 检查启动器、工具、产品测试及限定包内协议，**交互界面尚待验收**。

1. Download `GoForge-0.1.0-windows-x64.zip` and `SHA256SUMS-windows.txt` from the release page. / 从发行页下载对应 ZIP 和校验文件。
2. In PowerShell, run `Get-FileHash .\GoForge-0.1.0-windows-x64.zip -Algorithm SHA256`; compare the result with the same filename in the checksum file. / 在 PowerShell 运行并与同名记录核对。
3. Extract the entire archive to a writable local folder. Run `GoForge\bin\goforge64.exe`. Do not run inside the ZIP. / 完整解压到可写目录，再运行上述程序，不要在压缩包内启动。
4. Keep `bin`, `jbr`, `lib`, `plugins` and `goforge-tools` together. No separate Java or Go installation is needed for bundled pure-Go workflows. / 保留整个目录，普通纯 Go 开发无需另装 Java 或 Go。
5. Use the normal Windows security interface after checking the source and checksum; keep protections enabled. / 核对来源与校验值后使用正常系统安全界面，保留保护功能。

Settings/caches use `%APPDATA%` and `%LOCALAPPDATA%` under `GoForge\GoForge2026.1`. Git, OpenSSH, Docker, kubectl and Cgo compilers are optional host tools for their workflows. / 设置和缓存使用上述独立目录，外部工具按需安装。

## Prepared package identities

The release assets' `SHA256SUMS-macos.txt`, `SHA256SUMS-windows.txt` and platform manifests identify the actual application ZIPs. Historic development archives are different builds; their old checksums must not be used for v0.1.0. Corresponding JBR/JCEF/CEF/FFmpeg sources accompany the release; notices and JDBC source JARs remain in the application. / 使用发行页对应校验文件及平台清单核对真实应用 ZIP；旧开发归档哈希不适用于本版。发行附对应上游源码，包内保留通知和 JDBC 源码 JAR。

## Verification / 验证

macOS ordinary-project native workflows passed on 2026-10-30 after repairing the Git dashboard exception: open, edit/completion, save, run, tests/failure recovery, debug, Git and close/reopen. Windows assets are produced only after the release workflow passes. Full GoLand parity, Windows GUI, large projects and long sessions remain unverified. See [release notes](RELEASE-0.1.0.md) and [limitations/history](KNOWN-LIMITATIONS.md). / Mac 普通项目日常流程及 Git 修复复测通过，Windows 资产须通过发行 CI；完整 GoLand 对齐、Windows 交互、大工程及长会话未完成验收。

## Building on Windows

Development prerequisites: JDK 25, Python 3, Go 1.26+ and network access. The Gradle sandbox hook uses `python` on Windows and `python3` elsewhere; set `GOFORGE_PYTHON` to your Python executable if needed.

```powershell
# Set JAVA_HOME to your existing JDK 25 installation before running these commands.
$env:GRADLE_USER_HOME = "$PWD\.gradle-user-home"
New-Item -ItemType Directory -Force .tools\bin | Out-Null
$env:GOBIN = "$PWD\.tools\bin"
$env:GOTOOLCHAIN = "local"
go install golang.org/x/tools/gopls@v0.23.0
go install github.com/go-delve/delve/cmd/dlv@v1.27.2
.\gradlew.bat -PgoforgeSandboxDirectory=build/verification-sandbox test buildPlugin verifyPluginStructure
$env:GOFORGE_TOOLS_DIR = "$PWD\.tools\bin"
.\gradlew.bat runIde
```

These are development steps, not a recorded Windows acceptance result. Use **Settings → GoForge** to select an existing `go.exe` if it is not resolved from PATH. Test reports are written under `build/test-results/`; downloaded tools and caches stay outside public source exports.

以上为开发步骤，不是已完成的 Windows 验收结果。JDK 25 配入 `JAVA_HOME`，Windows 默认调用 `python`；必要时用 `GOFORGE_PYTHON` 指定 Python。Go SDK 应在 PATH 中，否则在设置里选择 `go.exe`。`runIde` 启动开发沙箱，不会生成独立发行包。

## First project / 第一个项目

- **New Go Project… → Application** creates a module with Run/Test/Build and Delve Debug configurations.
- **Library** creates a library with Test/Build and debug-tests configurations.
- Existing projects: open the module directory, wait for gopls, then use the gutter or Run configuration selector.
- Dependency downloads still use Go's normal module/network rules. Configure private modules with your normal Go setup.
- Configure build tags/GOOS/GOARCH under **Settings → Go Project**, then restart the language server when those settings change.

新建应用项目会生成 Run/Test/Build/Debug 配置；库项目生成 Test/Build/Debug tests。已有项目直接打开 module 根目录并等待 gopls。依赖下载与私有模块沿用 Go 正常机制。修改 build tags 或目标平台后，请重启语言服务器。
