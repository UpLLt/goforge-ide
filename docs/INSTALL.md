# Install GoForge / 安装 GoForge

[English README](../README.md) · [中文说明](../README.zh-CN.md) · [Usage / 用法](USAGE.md)

**Internal preparation:** public source and binary releases are withheld until full GoLand alignment has been verified. The archive instructions below document internal installation and future distribution; they do not announce an available public download. Original GoForge code is Apache-2.0; third-party terms remain separate.

**内部准备说明：** 完整对齐 GoLand 并验证完成前，不公开源码或二进制。下列步骤用于内部安装及未来发行，不表示已有公开下载。原创 GoForge 代码采用 Apache-2.0，第三方条款另行保留。

GoForge itself has no account or activation step. Install only the archive for your OS and CPU. A source download is not an application installer.

GoForge 本身无需账号或激活。请使用与你的系统和 CPU 一致的发行文件；源码压缩包不是应用安装包。

## macOS Apple Silicon

Current audited internal package: **GoForge-0.1.0-dev-macos-aarch64.zip (Full 13/dd87)**. Packager checks and independent archive readback passed, as did a bounded ordinary-import Add Parameter native workflow. Full GUI acceptance remains pending; earlier Benchmark Debug evidence belongs to bdf5 and has a Stop-generated-file cleanup gap. See [package identities](#prepared-package-identities).

当前已审计内部包为 **GoForge-0.1.0-dev-macos-aarch64.zip（Full 13/dd87）**，打包检查、独立归档读回及限定普通导入 Add Parameter 原生流程通过，完整界面仍待验收。此前 Benchmark Debug 证据属于 bdf5，Stop 后生成文件清理存在缺口；校验值见[包身份](#prepared-package-identities)。

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

**Current status:** Run 10 passed product tests, packaging and nine native engine/launcher checks; Windows interactive IDE GUI acceptance remains pending. This is an unsigned internal archive, not a public release. See [CI status](#windows-ci-status--windows-ci-状态).

The prepared archive is `GoForge-0.1.0-dev-windows-x64.zip`, targeting Windows 10/11 x64. Preparation does not mean it has been publicly released. When a release includes it:

1. Verify its published SHA-256 in PowerShell:
   ```powershell
   Get-FileHash .\GoForge-0.1.0-dev-windows-x64.zip -Algorithm SHA256
   ```
2. Extract the entire archive to a writable location such as a folder inside your user profile. Do not launch from inside the ZIP.
3. Run `GoForge\bin\goforge64.exe` from the extracted directory. The package includes its runtime and Go toolchain.
4. Check **Settings → GoForge** if you deliberately want to use different tool versions. Use Windows `.exe` tool paths.

The distribution has no GoForge Authenticode signature or installer. The native launcher receives GoForge icon/version resources, which invalidates its original signature; the derivative launcher is unsigned. Follow normal Windows security prompts; do not turn off SmartScreen or antivirus. Remote SSH features additionally require the Windows OpenSSH client; Docker and Kubernetes require their own installations.

**当前状态：** 第 10 次产品测试、打包和 9 项原生引擎/启动器检查通过，Windows 交互式 IDE 界面待验收。这是无签名的内部包，不是公开发行。目标 Windows 10/11 x64；取得内部 ZIP 后校验、完整解压，再运行 `GoForge\bin\goforge64.exe`。遵循正常安全提示，不关闭 SmartScreen/杀毒软件。详细结果见下方 CI 状态。

### Windows CI status / Windows CI 状态

Windows [Run 10](https://github.com/UpLLt/goforge-ide/actions/runs/40823363745) passed on the reviewed Full 13 source: **43/43 preflight checks**, **756 product tests = 755 passed + one existing POSIX-shell skip** (99 XML, no failures/errors), packaging and **nine native checks**. The relocated launcher/bundled JBR, Unicode and space paths, exact isolated configuration paths, tool versions, Go run/test, gopls and Delve normal/cancel cleanup passed. Windows interactive IDE GUI remains unverified; the launcher is unsigned, and publication was skipped with `publish=false`.

Windows [第 10 次 CI](https://github.com/UpLLt/goforge-ide/actions/runs/40823363745)使用审阅后的 Full 13 源码，**43/43 项预检**、**756 项产品测试＝755 通过＋1 项既存 POSIX shell 跳过**（99 份 XML，零失败/错误）、打包及 **9 项原生检查**通过。迁移启动器/包内 JBR、中文与空格路径、精确独立配置目录、工具版本、Go run/test、gopls、Delve 正常及取消清理通过。Windows 交互式 IDE 界面仍未验收，启动器无签名，`publish=false` 且发布步骤已跳过。

The run used checkout `c0d92d8397497a1aa33460139bc22210c761093a`, imported from reviewed source ZIP `08e592b36144b958889e99e5fb7de0a5ed4f1c7af30eaaba6ce8a090e348cb34`. Its exact 756 test identities match Full 13. These are real Windows runner results, not a Windows GUI pass or proof of full parity. See [verification scope](KNOWN-LIMITATIONS.md#verification-scope--验证范围). / 本轮 checkout 与源码 ZIP 身份如上，756 项测试身份与 Full 13 一致；这是实际 Windows runner 结果，不代表 Windows 界面或完整功能对齐通过。详见[验证范围](KNOWN-LIMITATIONS.md#verification-scope--验证范围)。

## Prepared package identities

**Current source — Full 17 (6425).** Passed **800/800 macOS-host JUnit tests** (463 component + 337 platform; 105 XML reports, no failures/errors/skips), plugin build and structure verification, with 358 frozen inputs. This source adds default local debug-output cleanup, navigation from completed top-level Benchmark results, and **Debug Go Benchmark at Caret**. Full 14–16 remain recorded failed integration runs. The existing dd87 Mac package and Windows Run 10 below belong to Full 13; new packaged macOS GUI and Windows acceptance remain pending.

The current cleanup change gives default local Delve launches in `debug`/`test` mode a separate, session-owned temporary output directory. After the adapter and observed target processes exit, it removes only that session’s generated executable and empty directory. If exit or ownership cannot be established, it retains the output and reports a warning. Explicit `output` paths, `exec`, attach and remote sessions retain their separate behavior. The change is not included in the verified Full 13/dd87 packages or Run 10 evidence; new macOS GUI and Windows validation remain pending.

**当前源码——Full 17（6425）。**通过 **800/800 项 macOS 宿主 JUnit 测试**（463 component + 337 platform，105 份 XML，零失败/错误/跳过）、插件构建与结构检查，358 项输入已冻结。源码新增默认本地调试输出清理、已完成顶层 Benchmark 结果跳转及 **Debug Go Benchmark at Caret**。Full 14–16 的集成失败记录保留；下方既有 dd87 Mac 包与 Windows 第 10 次属于 Full 13，新版本 macOS 打包界面与 Windows 验收仍待完成。

Current Full 17 macOS-host plugin ZIP: `6425c793e27247a0002e6495635e4d165cdc895c488b682b581ba437abd62685` (15,047,565 bytes). This is a development plugin artifact, not a new standalone app archive. / 此哈希属于 Full 17 的 macOS 宿主插件产物，不表示新的独立应用包已完成。

当前清理改动为默认本地 Delve `debug`/`test` 启动分配独立、由本会话拥有的临时输出目录。适配器及已观察到的目标进程退出后，仅删除本会话生成的可执行文件和空目录；不能确认退出或目录归属时保留输出并警告。显式 `output` 路径、`exec`、attach 和远端会话保持各自行为。此改动未包含在已验证的 Full 13/dd87 包及第 10 次 CI 证据中，新版本 macOS 界面与 Windows 验证均待完成。

**Packaged baseline — Full 13 (dd87)** passed **756/756 macOS-host JUnit tests** (433 component + 323 platform; 99 XML reports, no failures/errors/skips), plugin build and structure verification, with 345 frozen build inputs. The same reviewed source passed Windows Run 10 with 755 product tests and one existing POSIX-shell skip. Its Mac archive is independently audited; ordinary-import Add Parameter has bounded macOS native evidence. Full GUI and complete GoLand parity remain unverified.

**既有包基线——Full 13（dd87）**通过 **756/756 项 macOS 宿主 JUnit 测试**（433 component + 323 platform，99 份 XML，零失败/错误/跳过）、插件构建与结构检查，345 项输入已冻结。同一审阅源码在 Windows 第 10 次通过 755 项产品测试，1 项既存 POSIX shell 跳过。Mac 包已独立审计，普通导入 Add Parameter 已有限定 macOS 原生证据；完整界面与 GoLand 全面对齐仍未完成。

| Current artifact / 当前产物 | Bytes / 字节 | SHA-256 |
| --- | ---: | --- |
| Full 13/dd87 plugin ZIP (not a standalone app) / 插件 ZIP（非独立应用） | 15004033 | `dd8738a5b4fddbf4fc87d3f7ab1b7d7d4cad1b391f0931a7b291e83c54193279` |
| `GoForge-0.1.0-dev-macos-aarch64.zip` (dd87) | 751616260 | `c652c5e3ada58ae8b9f7ba54e7272a57af4bfec51958ce3f3951989f83e54fc1` |

The dd87 Mac archive contains the exact Full 13 plugin listed above, with the reviewed packaging-only promotion default. Its source inputs, plugin members, bundled tools and notices passed independent readback; signing/headless/relocation/extraction checks have separate successful packager receipts. Independent audit SHA-256: `c0b70ec7b355548ef02d873a7b7c03516860ee5d0742eb15e81edb8b1d8c5f92`. The bounded Add Parameter native workflow passed; full GUI acceptance remains pending. Run 10 assembled and natively checked the Windows package. The large Windows archive download is still pending; its checksum and independent archive readback will be recorded after download, separately from these Mac identities.

dd87 Mac 包含上表精确 Full 13 插件及经过审阅的打包层推广提示默认设置。源码输入、插件成员、工具与许可说明已独立读回；验签、无界面启动、迁移和解压检查有独立打包记录。独立审计哈希如上，dd87 限定 Add Parameter 原生流程通过，完整界面仍待验收。第 10 次已组装并原生检查 Windows 包；大归档下载尚未完成，下载后将另行核对校验值并独立读回，与以上 Mac 身份分别记录。

The audited dd87 Mac package passed a bounded ordinary-import Add Parameter workflow: preview/cancel, three-file Apply and Save, Undo/Redo, bundled Go tests after saved edits, restored fixture bytes, normal exit and signature recheck. The fixture uses one standalone module, standard-library imports and an `int` literal default; four platform warnings remain recorded. Earlier bdf5 native evidence covers manual imports, the visible Go type entry, remote SDK read-only navigation/Stop, and selected Benchmark Run/Debug. Benchmark Debug reached breakpoint/evaluation/step/exit; a separate Stop removed adapter/target processes but left a generated `__debug_bin…` file. The cleanup repair has Full 17 macOS-host tests; packaged native and full GUI acceptance remain pending.

已审计 dd87 Mac 包通过限定的普通导入 Add Parameter 原生流程：预览/取消、三文件 Apply 与保存、Undo/Redo、保存后包内 Go 测试、恢复夹具字节、正常退出与再次验签。夹具仅为单独模块、标准库导入及 `int` 字面量默认值，仍记录 4 条平台警告。此前 bdf5 原生证据覆盖手动导入整理、可见 Go 类型入口、远端 SDK 只读跳转/Stop，以及选中 Benchmark Run/Debug；Debug 已到达断点、表达式、步进和正常退出，但另一次 Stop 清除了适配器/目标进程后仍留下 `__debug_bin…` 文件。清理修复已通过 Full 17 macOS 宿主测试，打包原生验证仍待完成，完整界面验收尚未完成。

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
