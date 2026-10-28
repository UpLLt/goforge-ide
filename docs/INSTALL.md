# Install GoForge / 安装 GoForge

[English README](../README.md) · [中文说明](../README.zh-CN.md) · [Usage / 用法](USAGE.md)

**Internal preparation:** public source and binary releases are withheld until full GoLand alignment has been verified. The archive instructions below document internal installation and future distribution; they do not announce an available public download. Original GoForge code is Apache-2.0; third-party terms remain separate.

**内部准备说明：** 完整对齐 GoLand 并验证完成前，不公开源码或二进制。下列步骤用于内部安装及未来发行，不表示已有公开下载。原创 GoForge 代码采用 Apache-2.0，第三方条款另行保留。

GoForge itself has no account or activation step. Install only the archive for your OS and CPU. A source download is not an application installer.

GoForge 本身无需账号或激活。请使用与你的系统和 CPU 一致的发行文件；源码压缩包不是应用安装包。

## macOS Apple Silicon

Requirements: macOS **13.5+**, Apple Silicon. The development package contains its Java runtime and Go tools. Git, Cgo, and local debugging may additionally need Apple's Command Line Tools; Delve uses Apple's debugger components. No global Go installation is required for bundled pure-Go workflows.

1. Download the macOS ARM64 ZIP from this repository's **Releases**, if supplied.
2. Compare its checksum with that release's published checksum:
   ```sh
   shasum -a 256 GoForge-0.1.0-dev-macos-aarch64.zip
   ```
3. Extract it and move **GoForge.app** to a location you control, such as Applications. Keep the app bundle intact.
4. Open it. This development build uses an **ad-hoc signature**, not Apple Developer ID notarization. macOS may block downloaded builds. After verifying the source and checksum, use the normal macOS approval interface if available; do not disable Gatekeeper or system protection. Building locally is an alternative.
5. Select **New Go Project…** or open a module. If macOS requests debugger authorization, handle it through the normal system interface.

要求：Apple Silicon、macOS **13.5 或更新版本**。下载对应 ZIP，按发行页校验 SHA-256，解压后将 GoForge.app 放到自己的应用目录再打开。包内带运行时与 Go 工具；Git、Cgo 和本地调试可能还需要 Apple Command Line Tools。开发包没有 Developer ID 公证，系统可能拦截；核实来源后通过正常系统界面处理，不关闭系统安全功能。

Per-user settings are isolated under `~/Library/Application Support/GoForge/GoForge2026.1`; caches under `~/Library/Caches/GoForge/GoForge2026.1`; logs under `~/Library/Logs/GoForge/GoForge2026.1`. Removing the app does not delete projects or those settings.

设置、缓存与日志分别使用上述独立 GoForge 目录。删除应用不会删除项目或这些个人设置。

## Windows x64

**Current status: the portable ZIP has been assembled, with official-input checksum, PE/package regression and extracted-layout checks passed. Native Windows launch and UI acceptance are not yet verified. Do not treat cross-packaging as a Windows test pass.**

The prepared archive is `GoForge-0.1.0-dev-windows-x64.zip`, targeting Windows 10/11 x64. Preparation does not mean it has been publicly released. When a release includes it:

1. Verify its published SHA-256 in PowerShell:
   ```powershell
   Get-FileHash .\GoForge-0.1.0-dev-windows-x64.zip -Algorithm SHA256
   ```
2. Extract the entire archive to a writable location such as a folder inside your user profile. Do not launch from inside the ZIP.
3. Run `GoForge\bin\goforge64.exe` from the extracted directory. The package includes its runtime and Go toolchain.
4. Check **Settings → GoForge** if you deliberately want to use different tool versions. Use Windows `.exe` tool paths.

The distribution has no GoForge Authenticode signature or installer. The native launcher receives GoForge icon/version resources, which invalidates its original signature; the derivative launcher is unsigned. Follow normal Windows security prompts; do not turn off SmartScreen or antivirus. Remote SSH features additionally require the Windows OpenSSH client; Docker and Kubernetes require their own installations.

**当前状态：便携 ZIP 已组装，官方输入校验、PE/打包回归和解压结构检查通过；尚未验证 Windows 原生启动及界面。跨平台打包成功不等于 Windows 运行通过。** 目标为 Windows 10/11 x64。“已准备”不表示“已公开发布”。发行页实际提供 ZIP 后，使用 `Get-FileHash` 校验，完整解压，再打开 `GoForge\bin\goforge64.exe`。不要在 ZIP 内直接启动。包内置 Java 与 Go 工具，但没有 GoForge Authenticode 签名或安装器。通过正常安全提示处理，不关闭 SmartScreen/杀毒软件。SSH、Docker、kubectl 使用各自系统安装。

## Prepared package identities

The latest frozen plugin build is **1caa**, with **449/449 automated tests** (313 component + 136 platform, 72 JUnit reports) on the macOS development host, plus plugin build and structure checks. Its plugin ZIP is 14,689,230 bytes, SHA-256 `1caad6d85ea78419e17fbb0abcd15b16fba8667d803f34d3518da59441ee1038`. New platform packages and native acceptance are pending; earlier Windows assembly checks do not execute the application on Windows.

The current checkout-local verification records are:

- `.artifacts/strict-alignment-20261028/console-followup-2/verified-build/build-summary.json`: 449-test build and exact plugin identity.
- `.artifacts/strict-alignment-20261028/console-followup-2/verified-build/compiled-sources-manifest.json`: 253 frozen tested inputs.
- `.artifacts/change-signature-20261028/result.json`: 21 real gopls/AST/planner signature cases.
- `.artifacts/remote-workspace-20261028/probe-4/result.json`: generated Linux SSH/Go/Delve workspace protocol checks, including simulated Windows paths rather than Windows execution.
- `.artifacts/strict-alignment-20261028/native-7bff/session-final.json`: historical scoped native results and defects, not acceptance of 1caa.
- `.artifacts/strict-alignment-20261028/native-7bff/remote-process-readback.json`: four remote adapter/target PIDs absent and all 13 original source hashes unchanged, including go.work and go.work.sum. The owned fixture remains available for subsequent acceptance.

These private records are excluded from source exports. Read the manifest matching the exact installed archive; do not reuse an earlier checksum. Lifecycle/essential-plugin and remote-console repairs need new native acceptance. **Settings → GoForge** opens tool configuration; a Find Action **Plugins:** entry is a plugin toggle, not that settings page.

The `UpLLt/goforge-ide` repository is private. Its Windows workflow was committed as `8cbaac7d17d7bad063ded03adfa10678de6dbd4b` for internal source import and validation. Use the reviewed source ZIP's exact SHA-256 and leave **publish=false**. The workflow uploads private CI evidence; it does not make Windows native checks passed before they actually run. No Release has been created and the full-alignment gate remains closed.

最新冻结插件为 **1caa**，在 macOS 主机通过 **449/449 自动化测试**（313 component + 136 platform，72 份 JUnit 报告）及插件打包/结构检查。插件 ZIP 为 14,689,230 字节，SHA-256 见上。新平台包和原生验收待完成，历史 Windows 组装检查不能证明 Windows 实机运行。私有仓库已提交内部验证工作流；上传经核对的源码 ZIP 时填写其精确 SHA，并保持 **publish=false**。当前没有 Release，完整对齐门槛仍关闭。旧 7bff 的限定成功及生命周期/控制台缺陷继续按旧产物保留，不迁移为新包通过。上述本地记录不随源码导出。

### Historical archive identities / 历史包身份

These 2026-10-27 archives are retained as historical artifacts, not the current 1caa packages or a public release. Their d3d7 native passes remain tied to that build, alongside its observed Plugins Settings search initialization error. That search repair is included in 7bff; later essential-toggle protection is separate.

| Historical archive | Bytes | SHA-256 |
| --- | ---: | --- |
| macOS ARM64 d3d7 | 752381187 | `e6fa4390a10de749a7c095f7a694df20f6e1ec6a960d207bfc98553d9f0ea5bd` |
| Windows x64 d3d7 | 772025428 | `df11cdaabeba5676e62eec95caed57b7ba49e584e08ccc6f333760e4257ac642` |

以上为 2026-10-27 历史包，不是当前 1caa 包，也不表示公开发布。d3d7 的限定原生通过和插件设置搜索初始化失败均保留在该构建下；搜索修复已纳入 7bff，之后发现的 essential 插件开关保护另行处理。Windows 原生运行与界面仍待验。

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
