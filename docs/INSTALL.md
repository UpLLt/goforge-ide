# Install GoForge / 安装 GoForge

[English README](../README.md) · [中文说明](../README.zh-CN.md) · [Usage / 用法](USAGE.md)

**Internal preparation:** public source and binary releases are withheld until full GoLand alignment has been verified. The archive instructions below document internal installation and future distribution; they do not announce an available public download. Original GoForge code is Apache-2.0; third-party terms remain separate.

**内部准备说明：** 完整对齐 GoLand 并验证完成前，不公开源码或二进制。下列步骤用于内部安装及未来发行，不表示已有公开下载。原创 GoForge 代码采用 Apache-2.0，第三方条款另行保留。

GoForge itself has no account or activation step. Install only the archive for your OS and CPU. A source download is not an application installer.

GoForge 本身无需账号或激活。请使用与你的系统和 CPU 一致的发行文件；源码压缩包不是应用安装包。

## macOS Apple Silicon

Current audited internal package: **GoForge-0.1.0-dev-macos-aarch64.zip (Full 13/dd87)**. Packager checks and independent archive readback passed. dd87 native GUI acceptance is pending; earlier selected native workflows belong to bdf5. See [package identities](#prepared-package-identities).

当前已审计内部包为 **GoForge-0.1.0-dev-macos-aarch64.zip（Full 13/dd87）**，打包检查与独立归档读回通过。dd87 原生界面待验收，此前部分原生通过属于 bdf5，校验值见[包身份](#prepared-package-identities)。

Requirements: macOS **13.5+**, Apple Silicon. The development package contains its Java runtime and Go tools. Git, Cgo, and local debugging may additionally need Apple's Command Line Tools; Delve uses Apple's debugger components. No global Go installation is required for bundled pure-Go workflows.

1. Obtain the internal macOS ARM64 archive from the maintainer, or build locally. Use **Releases** only after a public release is actually announced.
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

**Current status:** See [CI status](#windows-ci-status--windows-ci-状态). Windows Run 9 (older 2406 source) passed 34 preflight checks and 722 product tests, with one explicit POSIX-shell skip. The relocated native launcher, bundled tool versions and Go run/test passed; gopls definition failed with `no views`, so Delve acceptance was not reached. The run failed; Windows GUI and newer-source acceptance remain pending.

The prepared archive is `GoForge-0.1.0-dev-windows-x64.zip`, targeting Windows 10/11 x64. Preparation does not mean it has been publicly released. When a release includes it:

1. Verify its published SHA-256 in PowerShell:
   ```powershell
   Get-FileHash .\GoForge-0.1.0-dev-windows-x64.zip -Algorithm SHA256
   ```
2. Extract the entire archive to a writable location such as a folder inside your user profile. Do not launch from inside the ZIP.
3. Run `GoForge\bin\goforge64.exe` from the extracted directory. The package includes its runtime and Go toolchain.
4. Check **Settings → GoForge** if you deliberately want to use different tool versions. Use Windows `.exe` tool paths.

The distribution has no GoForge Authenticode signature or installer. The native launcher receives GoForge icon/version resources, which invalidates its original signature; the derivative launcher is unsigned. Follow normal Windows security prompts; do not turn off SmartScreen or antivirus. Remote SSH features additionally require the Windows OpenSSH client; Docker and Kubernetes require their own installations.

**当前状态：** Windows 第 9 次使用较早 2406 源码：34 项预检、722 项产品测试通过，1 项明确的 POSIX shell 跳过。迁移后的原生启动器、包内工具版本及 Go run/test 通过；gopls 定义请求因 `no views` 失败，尚未进入 Delve 验收。本轮整体失败，Windows 界面及更新源码仍待验收。 目标为 Windows 10/11 x64；获授权取得内部 ZIP 或未来实际提供发行 ZIP 后，校验并完整解压，再打开 `GoForge\bin\goforge64.exe`。当前无 GoForge Authenticode 签名或安装器，按正常安全提示处理，不关闭 SmartScreen/杀毒软件。

### Windows CI status / Windows CI 状态

[Private Run 9 (`40819479810`)](https://github.com/UpLLt/goforge-ide/actions/runs/40819479810) checked frozen 2406 plus its configuration-isolation repair: 34/34 preflight passed; 723 product tests = 722 passed + one existing POSIX-shell skip, no failures/errors (97 XML). The native report confirms the relocated launcher, exact owned configuration paths and Go run/test; gopls returned `no views` before Delve. Final release export was not reached. Run 10 has not started.

[私有第 9 次 CI](https://github.com/UpLLt/goforge-ide/actions/runs/40819479810)基于冻结 2406 与配置隔离修复：34/34 预检通过，723 项产品测试为 722 通过、1 项既存 POSIX shell 跳过，0 失败/错误（97 XML）。原生报告确认迁移启动器、精确独立配置路径及 Go run/test；随后 gopls 返回 `no views`，Delve 和最终发行导出未执行。第 10 次尚未启动。

These results do not cover bdf5/dd87 additions or Windows GUI. See [verification scope](KNOWN-LIMITATIONS.md#verification-scope--验证范围). / 这些结果不覆盖 bdf5/dd87 新增功能及 Windows 界面，见[验证范围](KNOWN-LIMITATIONS.md#verification-scope--验证范围)。

## Prepared package identities

**Full 13 (dd87)** passed **756/756 macOS-host JUnit tests** (433 component + 323 platform; 99 XML reports, no failures/errors/skips), plugin build and structure verification. Its 345 build inputs are frozen. This source includes compile-checked Add Parameter support for ordinary imports; its Mac archive is assembled and independently audited; native GUI and Windows verification remain pending.

**Full 13（dd87）**通过 **756/756 项 macOS 宿主 JUnit 测试**（433 component + 323 platform，99 份 XML，零失败/错误/跳过）、插件构建与结构检查，345 项构建输入已冻结。该源码新增普通导入下经过编译检查的 Add Parameter；Mac 包已组装并独立审计，原生界面与 Windows 验证仍待完成。

| Current artifact / 当前产物 | Bytes / 字节 | SHA-256 |
| --- | ---: | --- |
| Full 13/dd87 plugin ZIP (not a standalone app) / 插件 ZIP（非独立应用） | 15004033 | `dd8738a5b4fddbf4fc87d3f7ab1b7d7d4cad1b391f0931a7b291e83c54193279` |
| `GoForge-0.1.0-dev-macos-aarch64.zip` (dd87) | 751616260 | `c652c5e3ada58ae8b9f7ba54e7272a57af4bfec51958ce3f3951989f83e54fc1` |

The dd87 Mac archive contains the exact Full 13 plugin listed above, with the reviewed packaging-only promotion default. Its source inputs, plugin members, bundled tools and notices passed independent readback; signing/headless/relocation/extraction checks have separate successful packager receipts. Independent audit SHA-256: `c0b70ec7b355548ef02d873a7b7c03516860ee5d0742eb15e81edb8b1d8c5f92`. dd87 native GUI acceptance is pending. No accepted current Windows archive is available.

dd87 Mac 包含上表精确 Full 13 插件及经过审阅的打包层推广提示默认设置。源码输入、插件成员、工具与许可说明已独立读回；验签、无界面启动、迁移和解压检查有独立打包记录。独立审计哈希如上，dd87 原生界面待验收，目前没有验收完成的当前 Windows 包。

The current audited macOS package is dd87. Signing, headless startup, relocation and archive checks passed; dd87 native GUI acceptance is pending. The earlier bdf5 bounded native checks cover manual imports with Undo/Redo, the visible Go import-type entry, selected Benchmark Run, and remote SDK read-only navigation/Stop. Benchmark Debug remains an unresolved UI attempt; full GUI acceptance and final exit checks for the current session are pending.

当前已审计 macOS 包为 dd87，验签、无界面启动、迁移及归档检查通过，dd87 原生界面待验收。此前 bdf5 的限定原生检查覆盖手动导入整理及 Undo/Redo、可见的 Go 导入类型入口、指定 Benchmark 运行、远端 SDK 只读跳转与 Stop。Benchmark 调试仍为未确认原因的界面尝试；完整界面验收及当前会话最终退出检查待完成。

Earlier archives below remain checksum references only. Detailed build/test history is retained in internal records. Always read the manifest for the exact archive you install; never transfer a native pass from one build or host to another.

下列旧包只保留校验值，详细构建/测试历史留在内部记录。安装时核对实际归档的清单，不把其他构建或宿主的原生通过结果移用到当前包。

| Historical bdf5 Mac archive / 历史 Mac 包 | Bytes | SHA-256 |
| --- | ---: | --- |
| `GoForge-bdf5-macos-aarch64.zip` | 751613652 | `01a81eb7e87cc336d408464d71ea3ac8bb44fbb69de6d943d124ca7bcc1aa667` |

| Historical 2406 Mac archive / 历史 Mac 包 | Bytes | SHA-256 |
| --- | ---: | --- |
| `GoForge-2406-macos-aarch64.zip` | 751572012 | `d00296e54c4a0cf903842505baac98d370fb98eb36b8a75a5fec07c9f2a8cdde` |

### Previous 318d package

Historical identity only; not a current release or a new host-validation result. / 仅保留历史包身份，不是当前发行或新宿主验收。

| Historical 318d internal archive | Bytes | SHA-256 |
| --- | ---: | --- |
| `GoForge-318d-macos-aarch64.zip` | 751553996 | `7a53be4464eb41afe752b069201f08ec7f5c4a6987b0c9903b8b1c70af8dbb02` |

### Previous 5259 package and latest actual GUI acceptance

Historical identity only; not a current release or a new host-validation result. / 仅保留历史包身份，不是当前发行或新宿主验收。

| Historical 5259 internal archive | Bytes | SHA-256 |
| --- | ---: | --- |
| `GoForge-5259-macos-aarch64.zip` | 751543468 | `c2c27d6b42cc98deaa95f8138b06cddd2cb7beac1042ccf5a3d85d08c4d87ecb` |

### Previous a252 package identities

Historical identity only; not a current release or a new host-validation result. / 仅保留历史包身份，不是当前发行或新宿主验收。

| Historical a252 internal archive | Bytes | SHA-256 |
| --- | ---: | --- |
| `GoForge-a252-windows-x64.zip` | 771129607 | `7285dd2f4f80fd914a164e87fa169207c3a2fb4baacd78025fc69feb4af2b815` |
| `GoForge-a252-macos-aarch64.zip` | 751496018 | `864a9f676325c79efb102e59cbbf46ad1bc4e8f7eb5f93e85d9ac7443de3495c` |

### Previous 319d package identities

Historical identity only; not a current release or a new host-validation result. / 仅保留历史包身份，不是当前发行或新宿主验收。

| Historical internal archive | Bytes | SHA-256 |
| --- | ---: | --- |
| `GoForge-319d-windows-x64.zip` | 771124243 | `47123eb149867d57823342c59acf89706fcfebf5a3b9b800204222290b5176f1` |
| `GoForge-319d-macos-aarch64.zip` | 751490656 | `1e4ec4b6b0ba8e36af676efc30d29d714b7d8cec393109bf3aefdf652fcad3d0` |

### Previous f9e0 package identities

Historical identity only; not a current release or a new host-validation result. / 仅保留历史包身份，不是当前发行或新宿主验收。

| Historical internal archive | Bytes | SHA-256 |
| --- | ---: | --- |
| `GoForge-f9e0-windows-x64.zip` | 771095262 | `0e63479351e5abc6b6a58f9fba49bd3d17ead685d96be2f42266c4041908b047` |
| `GoForge-f9e0-macos-aarch64.zip` | 751461676 | `1f9727d9b69183ab6790d31d5241e15c292cdc165783d9eba50373858201ba78` |

### Previous 921d package identities

Historical identity only; not a current release or a new host-validation result. / 仅保留历史包身份，不是当前发行或新宿主验收。

| Historical internal archive | Bytes | SHA-256 |
| --- | ---: | --- |
| `GoForge-921d-windows-x64.zip` | 770984053 | `2bf07ff5553f6f9241e1a1c1012ab13ad118e65243752d4961cde128dc2542a1` |
| `GoForge-921d-macos-aarch64.zip` | 751350473 | `6fc10a779001db2727f9d282d3f0a07594752ce3f109a12173f1aae5404f5039` |

### Previous 9abc package identities

Historical identity only; not a current release or a new host-validation result. / 仅保留历史包身份，不是当前发行或新宿主验收。

| Internal 9abc archive | Bytes | SHA-256 |
| --- | ---: | --- |
| `GoForge-9abc-macos-aarch64.zip` | 751312246 | `df6d45bb8895f3c5bdb796dafa254e032c611fd90070c52396a3675f0458852d` |
| `GoForge-9abc-windows-x64.zip` | 770945833 | `35f5083be3b20684e8ff9d653308bc0da9f2a8a972c17de8b97b5c435cb75abe` |

### Historical archive identities / 历史包身份

Historical identity only; not a current release or a new host-validation result. / 仅保留历史包身份，不是当前发行或新宿主验收。

| Historical 1caa archive | Bytes | SHA-256 |
| --- | ---: | --- |
| macOS ARM64 | 751301187 | `f953c343f00624d09f16b08021bdac0fd0d76abc4a9ca267859fe2be4f9f0669` |
| Windows x64 | 770934771 | `ccc6bd6d08957f5cb8740622e895267d2a8295d480db54b81012e269946faab2` |
| Historical archive | Bytes | SHA-256 |
| --- | ---: | --- |
| macOS ARM64 d3d7 | 752381187 | `e6fa4390a10de749a7c095f7a694df20f6e1ec6a960d207bfc98553d9f0ea5bd` |
| Windows x64 d3d7 | 772025428 | `df11cdaabeba5676e62eec95caed57b7ba49e584e08ccc6f333760e4257ac642` |

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
