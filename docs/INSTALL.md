# Install GoForge / 安装 GoForge

[English README](../README.md) · [中文说明](../README.zh-CN.md) · [Usage / 用法](USAGE.md)

**Internal preparation:** public source and binary releases are withheld until full GoLand alignment has been verified. The archive instructions below document internal installation and future distribution; they do not announce an available public download. Original GoForge code is Apache-2.0; third-party terms remain separate.

**内部准备说明：** 完整对齐 GoLand 并验证完成前，不公开源码或二进制。下列步骤用于内部安装及未来发行，不表示已有公开下载。原创 GoForge 代码采用 Apache-2.0，第三方条款另行保留。

GoForge itself has no account or activation step. Install only the archive for your OS and CPU. A source download is not an application installer.

GoForge 本身无需账号或激活。请使用与你的系统和 CPU 一致的发行文件；源码压缩包不是应用安装包。

## macOS Apple Silicon

Current audited internal package: **GoForge-0.1.0-dev-macos-aarch64.zip (Full 20/4ae7)**. Independent archive readback, packager checks and all 23 real packaged method-refactoring protocol cases passed using bundled tools without a development overlay. Native IDE UI checks for this build remain pending. Earlier Full 19 editor/remote-source and Full 18 debug results retain their own package identities. See [package identities](#prepared-package-identities).

当前已审计内部包为 **GoForge-0.1.0-dev-macos-aarch64.zip（Full 20/4ae7）**。独立归档、打包检查以及使用包内工具、无开发覆盖的 23 个真实方法重构协议场景通过，本构建原生 IDE 界面尚待检查；此前 Full 19 编辑/远端源码与 Full 18 调试结果保留各自包身份。校验值见[包身份](#prepared-package-identities)。

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

**Current status:** Full 19 passed Windows Run 13: 834 product tests plus one existing POSIX-only skip, successful packaging and nine bounded native check groups. Downloaded test/plugin/native reports have been independently verified; complete application-archive readback remains pending. Windows interactive IDE GUI acceptance is pending. This is an unsigned internal archive, not a public release. See [CI status](#windows-ci-status--windows-ci-状态).

The prepared archive is `GoForge-0.1.0-dev-windows-x64.zip`, targeting Windows 10/11 x64. Preparation does not mean it has been publicly released. When a release includes it:

1. Verify its published SHA-256 in PowerShell:
   ```powershell
   Get-FileHash .\GoForge-0.1.0-dev-windows-x64.zip -Algorithm SHA256
   ```
2. Extract the entire archive to a writable location such as a folder inside your user profile. Do not launch from inside the ZIP.
3. Run `GoForge\bin\goforge64.exe` from the extracted directory. The package includes its runtime and Go toolchain.
4. Check **Settings → GoForge** if you deliberately want to use different tool versions. Use Windows `.exe` tool paths.

The distribution has no GoForge Authenticode signature or installer. The native launcher receives GoForge icon/version resources, which invalidates its original signature; the derivative launcher is unsigned. Follow normal Windows security prompts; do not turn off SmartScreen or antivirus. Remote SSH features additionally require the Windows OpenSSH client; Docker and Kubernetes require their own installations.

**当前状态：** Full 19 的第 13 次 Windows CI 已通过：834 项产品测试通过、1 项既存 POSIX 专用跳过，打包及 9 组限定原生检查通过。已下载测试/插件/原生报告经过独立核对；完整应用归档仍待读回，Windows 交互式 IDE 界面未验收。这是无签名的内部包，不是公开发行。目标 Windows 10/11 x64；取得内部 ZIP 后校验、完整解压，再运行 `GoForge\bin\goforge64.exe`。遵循正常安全提示，不关闭 SmartScreen/杀毒软件。详细结果见下方 CI 状态。

### Windows CI status / Windows CI 状态

**Windows Run 13 — CI and bounded native checks passed.** [Run 13](https://github.com/UpLLt/goforge-ide/actions/runs/40844074829) tested the frozen Full 19 source: **834 passed, one existing POSIX-shell skip, 835 total**, 108 XML reports and no failures/errors. All four failures previously observed in Runs 11 and 12 now pass. The downloaded plugin and native reports were independently checked against that source. Nine native check groups passed, including Unicode/space-path launcher relocation, bundled tools, Go run/test, gopls navigation/rename preview/completion/diagnostics, and Delve normal/cancel sessions with child exit. One gopls shutdown warning remains recorded. Complete application-archive download/readback is pending; interactive Windows GUI is unverified. The repository is private and publication was skipped.

**Previous Windows attempt — [Run 12](https://github.com/UpLLt/goforge-ide/actions/runs/40838772682) failed** on Full 18: **805 product tests = 803 passed, one failed and one existing POSIX-shell skip** (105 XML, no errors). All three failed cases from [Run 11](https://github.com/UpLLt/goforge-ide/actions/runs/40834616140) passed. The remaining manual Optimize Imports fixture counted two requests instead of one; packaging/native checks were skipped. Full 19 restores the real action’s committed-PSI test precondition and adds request diagnostics without relaxing that assertion. The subsequent Run 13 passed this case. Run 10 below retains its separate Full 13 product/native/archive results.

Windows [Run 10](https://github.com/UpLLt/goforge-ide/actions/runs/40823363745) passed on the reviewed Full 13 source: **43/43 preflight checks**, **756 product tests = 755 passed + one existing POSIX-shell skip** (99 XML, no failures/errors), packaging and **nine native checks**. The relocated launcher/bundled JBR, Unicode and space paths, exact isolated configuration paths, tool versions, Go run/test, gopls and Delve normal/cancel cleanup passed. Windows interactive IDE GUI remains unverified; the launcher is unsigned, and publication was skipped with `publish=false`.

**Windows 第 13 次——CI 与限定原生检查通过。**[第 13 次](https://github.com/UpLLt/goforge-ide/actions/runs/40844074829)使用冻结的 Full 19 源码：**834 通过、1 项既存 POSIX shell 跳过，共 835 项**，108 份 XML，零失败/错误。第 11、12 次出现的四个失败用例现在均已通过。已下载插件与原生报告均已独立核对源码身份；9 组原生检查通过，覆盖中文/空格路径启动器迁移、内置工具、Go 运行/测试、gopls 跳转/重命名预览/补全/诊断，以及 Delve 正常结束/取消与子进程退出。保留 1 条 gopls 退出阶段警告。完整应用归档仍在下载、尚待读回；Windows 交互界面未验收。仓库保持私有，发布任务已跳过。

**此前 Windows 尝试——[第 12 次 CI](https://github.com/UpLLt/goforge-ide/actions/runs/40838772682)失败**，使用 Full 18：**805 项产品测试＝803 通过、1 失败、1 项既存 POSIX shell 跳过**（105 份 XML，零错误）。[第 11 次](https://github.com/UpLLt/goforge-ide/actions/runs/40834616140)的三项失败用例均已通过；剩余手动 Optimize Imports 夹具观测到两次请求而预期一次，打包与原生检查已跳过。Full 19 按真实动作入口恢复“先提交 PSI”的测试前置条件并增加请求诊断，未放宽该断言；随后的 Windows 第 13 次已通过此用例。下方第 10 次保留其独立 Full 13 测试、原生与归档结果。

Windows [第 10 次 CI](https://github.com/UpLLt/goforge-ide/actions/runs/40823363745)使用审阅后的 Full 13 源码，**43/43 项预检**、**756 项产品测试＝755 通过＋1 项既存 POSIX shell 跳过**（99 份 XML，零失败/错误）、打包及 **9 项原生检查**通过。迁移启动器/包内 JBR、中文与空格路径、精确独立配置目录、工具版本、Go run/test、gopls、Delve 正常及取消清理通过。Windows 交互式 IDE 界面仍未验收，启动器无签名，`publish=false` 且发布步骤已跳过。

The run used checkout `c0d92d8397497a1aa33460139bc22210c761093a`, imported from reviewed source ZIP `08e592b36144b958889e99e5fb7de0a5ed4f1c7af30eaaba6ce8a090e348cb34`. Its exact 756 test identities match Full 13. These are real Windows runner results, not a Windows GUI pass or proof of full parity. See [verification scope](KNOWN-LIMITATIONS.md#verification-scope--验证范围). / 本轮 checkout 与源码 ZIP 身份如上，756 项测试身份与 Full 13 一致；这是实际 Windows runner 结果，不代表 Windows 界面或完整功能对齐通过。详见[验证范围](KNOWN-LIMITATIONS.md#verification-scope--验证范围)。

## Prepared package identities

**Current verified source — Full 20.** All **872/872 macOS-host JUnit tests** passed (517 component and 355 platform; 111 XML, zero failures/errors/skips), with 374 frozen inputs and independent build readback. All original 835 test identities are retained, with 37 additions. The source adds optional bounded HTTP redirects and compact layout, typed/compile-checked private-method Add Parameter, corrected remote-preview wording, and an explicit packaged method-protocol verifier in the next Windows workflow. Eleven verifier contract tests pass. Its Mac archive passed independent readback and all 23 real packaged method-protocol cases passed using bundled tools, without a development overlay. New Mac native UI and Windows CI remain pending at this source export; Full 19 evidence below applies only to that older artifact.

**当前已验证源码——Full 20。** **872/872 项 macOS 宿主 JUnit 测试**全部通过（517 component、355 platform，111 份 XML，零失败/错误/跳过），374 项输入冻结并通过独立构建读回。原有 835 项用例全部保留，新增 37 项。源码加入可选的限定 HTTP 重定向与紧凑布局、经类型/编译检查的私有方法新增参数、远端预览文案修复，以及接入下一轮 Windows 流程的包内方法协议验收器；11 项验收器契约测试通过。Mac 归档已独立核对，使用包内工具且无开发覆盖的 23 个真实方法协议场景通过。本次源码导出时，Mac 原生界面和 Windows 新一轮 CI 尚待完成；下方 Full 19 证据只代表旧构建。

**Previous verified package baseline — Full 19 (c0c9).** Passed **835/835 macOS-host JUnit tests** (482 component + 353 platform; 108 XML reports, no failures/errors/skips), plugin build and structure verification, with 364 frozen inputs. It adds bounded read-only navigation into selected cached remote module versions and local parenthesis/bracket continuation indentation. The manual Optimize Imports test now starts with committed PSI, matching the real action; its exact-one-request assertion remains. The Full 19 Mac archive passed independent source/plugin/resource/notice readback and packager checks. Eight bounded Mac native groups passed: three Enter comparisons with GoLand, selected-module declaration/type navigation, SDK navigation, read-only enforcement and UI Stop. Sources/cache remained unchanged; normal app/listener exit and signature recheck passed, with nine warnings and no ERROR/SEVERE entries. The preview still incorrectly says “External dependency navigation is unavailable.”; that wording is not fixed in this build. Windows Run 13 subsequently passed its product tests and bounded native checks; complete application-archive readback is pending. Full 18 retains its separate debug Stop/normal-exit evidence; complete GUI and GoLand parity remain unverified.

The current cleanup change gives default local Delve launches in `debug`/`test` mode a separate, session-owned temporary output directory. After the adapter and observed target processes exit, it removes only that session’s generated executable and empty directory. If exit or ownership cannot be established, it retains the output and reports a warning. Explicit `output` paths, `exec`, attach and remote sessions retain their separate behavior. Full 17 native normal-exit cleanup passed, but toolbar Stop failed and retained output. Full 18 fixes passed macOS-host tests, archive audit and bounded top-toolbar Stop/normal-exit cleanup. Native Windows IDE cleanup workflows and complete GUI acceptance remain pending.

**既有已验证包基线——Full 19（c0c9）。**通过 **835/835 项 macOS 宿主 JUnit 测试**（482 component + 353 platform，108 份 XML，零失败/错误/跳过）、插件构建与结构检查，364 项输入已冻结。新增受限的已选缓存远端模块版本只读跳转，以及本地圆括号/方括号续行缩进；手动 Optimize Imports 测试按真实动作入口先提交 PSI，仍保留精确一次请求断言。Full 19 Mac 归档已通过独立源码/插件/资源/许可读回及打包检查，8 组限定 Mac 原生检查通过：3 项与 GoLand 对照的回车、已选模块定义/类型跳转、SDK 跳转、只读保护及 UI Stop。源文件/缓存未变，应用与监听器正常退出、再次验签通过，保留 9 条警告且无 ERROR/SEVERE。预览仍误报“External dependency navigation is unavailable.”，本版未修此文字。Windows 第 13 次随后通过产品测试与限定原生检查，完整应用归档尚待读回；Full 18 调试 Stop/正常退出保留独立证据，完整界面及 GoLand 全面对齐仍未完成。

Current Full 20 macOS-host plugin ZIP: `4ae7e9efc48fe2ec059f51de8185234587a68323f6f2f74f463c8035385f68d5` (15,093,828 bytes). It is included in the audited Full 20 Mac archive below; this plugin ZIP is not an app installer. / 当前 Full 20 插件 ZIP 身份如上，已包含于下表已审计 Mac 包；插件 ZIP 不是应用安装包。

Previous Full 19 macOS-host plugin ZIP: `c0c934605092b7c366419bc3c98c283384112cb164faf8d2fc213ef57de59295` (15,060,336 bytes). It is included in the independently audited Full 19 Mac archive below; this plugin ZIP is not an app installer. / 既有 Full 19 插件 ZIP 身份如上，已包含于下表独立审计的 Mac 包；插件 ZIP 不是应用安装包。

Historical Full 18 macOS-host plugin ZIP: `2de4ffea4b620c73be726c9442cbd25dc129ca2b84030963b1b9d9fb63bd442a` (15,047,717 bytes), included in the independently audited Mac archive below. / 此哈希属于 Full 18 的 macOS 宿主插件产物，已包含在下表独立审计的 Mac 包中。

当前清理改动为默认本地 Delve `debug`/`test` 启动分配独立、由本会话拥有的临时输出目录。适配器及已观察到的目标进程退出后，仅删除本会话生成的可执行文件和空目录；不能确认退出或目录归属时保留输出并警告。显式 `output` 路径、`exec`、attach 和远端会话保持各自行为。Full 17 原生正常退出清理通过，但工具栏 Stop 失败并保留输出。Full 18 修复通过 macOS 宿主测试、归档审计与顶部 Stop/正常退出限定清理，Windows 原生 IDE 清理工作流与完整界面验收仍待完成。

**Packaged baseline — Full 13 (dd87)** passed **756/756 macOS-host JUnit tests** (433 component + 323 platform; 99 XML reports, no failures/errors/skips), plugin build and structure verification, with 345 frozen build inputs. The same reviewed source passed Windows Run 10 with 755 product tests and one existing POSIX-shell skip. Its Mac archive is independently audited; ordinary-import Add Parameter has bounded macOS native evidence. Full GUI and complete GoLand parity remain unverified.

**既有包基线——Full 13（dd87）**通过 **756/756 项 macOS 宿主 JUnit 测试**（433 component + 323 platform，99 份 XML，零失败/错误/跳过）、插件构建与结构检查，345 项输入已冻结。同一审阅源码在 Windows 第 10 次通过 755 项产品测试，1 项既存 POSIX shell 跳过。Mac 包已独立审计，普通导入 Add Parameter 已有限定 macOS 原生证据；完整界面与 GoLand 全面对齐仍未完成。

| Audited app archive / 已审计应用包 | Bytes / 字节 | SHA-256 |
| --- | ---: | --- |
| `GoForge-0.1.0-dev-macos-aarch64.zip` (Full 20/4ae7) | 751706047 | `5ee7680f66940e66ddd2016a7af13b02c4e529e6338d3c242d1146c9a2a0a8a4` |
| `GoForge-0.1.0-dev-macos-aarch64.zip` (Full 19/c0c9) | 751672560 | `df4ef621ae18316d222c6febcffc4bb0d5dea029ed4fbd0f68b13e42feef58f8` |
| `GoForge-0.1.0-dev-macos-aarch64.zip` (Full 18/2de4) | 751659936 | `5e9c8a463c79f6271014023d077febdb092dd13942ebb96f0b2dcc7bc4af5559` |
| `GoForge-0.1.0-dev-macos-aarch64.zip` (Full 17/6425) | 751659791 | `24a32b3c062e890a0d03ef57b441a04013e5bcf073482086b6acdecc7aff4e87` |
| `GoForge-0.1.0-dev-windows-x64.zip` (Run 10/Full 13) | 771249254 | `856ed96054c07acbceb286c851f9034581d374ab54e6e7e4ae0f72b59b231424` |

The Full 20 Mac audit binds all 374 frozen build inputs, six plugin members and 28 assembly inputs. Archive audit SHA-256: `761b250c75a4c09d611e2fee47572cd3e740ef89c46bbfbdcce74ca99a78c0b8`. Its 23-case real packaged method-protocol audit is `522b52d6e579b6422f122b8b877c70c196b10f32ea7fadb0206ed5c9acc77824`; bundled JBR/Go/gopls and implementation providers were checked, with no development overlay. These are archive/protocol results, not native IDE GUI acceptance. / Full 20 Mac 归档审计绑定 374 项冻结输入、6 个插件成员及 28 项打包输入；23 场景协议审计核对包内 JBR/Go/gopls 与实现来源，无开发覆盖，不代表原生 IDE 界面验收。

The Full 19 Mac audit binds all 364 frozen build inputs, six plugin members, the exact embedded module-source resource and 28 assembly inputs. Audit SHA-256: `309bfe5b779edcd88fd14049cdfb6baaf0397f54525ccd5c33bd9c63a21ce20b`. Archive verification is not GUI acceptance. / Full 19 Mac 审计绑定 364 项冻结输入、6 个插件成员、精确模块源码读取资源和 28 项打包输入；归档通过不等于界面验收。

The Run 10 GitHub artifact wrapper `windows-release-files.zip` also includes corresponding-source archives and reports: 925169484 bytes, SHA-256 `2b894372c2fe1047332185ff1f3a9f4dd3d45a9c32adb2754a39e96ed71b2613`. Use the inner application ZIP above for installation. Its 16,350 application files, tested plugin, tools/notices and four corresponding-source archives passed independent readback. / 第 10 次 GitHub 外层归档还含对应源码和报告，安装请使用上表内层应用 ZIP；16,350 个应用文件、已测插件、工具/许可及 4 个对应源码包已独立读回通过。

| Historical Full 13 artifact / 历史 Full 13 产物 | Bytes / 字节 | SHA-256 |
| --- | ---: | --- |
| Full 13/dd87 plugin ZIP (not a standalone app) / 插件 ZIP（非独立应用） | 15004033 | `dd8738a5b4fddbf4fc87d3f7ab1b7d7d4cad1b391f0931a7b291e83c54193279` |
| `GoForge-0.1.0-dev-macos-aarch64.zip` (dd87) | 751616260 | `c652c5e3ada58ae8b9f7ba54e7272a57af4bfec51958ce3f3951989f83e54fc1` |

The historical dd87 Mac archive above retains its own audited Full 13 inputs and bounded Add Parameter native result. Its audit SHA-256 is `c0b70ec7b355548ef02d873a7b7c03516860ee5d0742eb15e81edb8b1d8c5f92`. The newer Full 19, Full 18 and preceding Full 17 Mac archives and the separately built Run 10 Windows archive have the distinct identities shown above.

历史 dd87 Mac 包保留其 Full 13 输入审计与限定 Add Parameter 原生结果，审计 SHA-256 为上列 `c0b70ec7…`。较新的 Full 19、Full 18、此前 Full 17 Mac 包与独立构建的第 10 次 Windows 包分别使用上表身份，不混用验收结果。

**Previous Mac native scope — Full 18 (2de4).** Two bounded Benchmark debug workflows passed: actual top-toolbar Stop and normal Resume/exit. Both reached the breakpoint, exited the adapter with code 0, removed their exact generated-output directories and left none of their eight tracked processes. All four fixture files remained unchanged; normal app exit and signature recheck passed. Seven log warnings remain recorded. Full 17 direct-editor evaluation and exact declaration navigation remain separate evidence; its failed Stop and retained zombie/output are preserved, not counted as current-session success. Native Windows IDE cleanup workflows and complete GUI acceptance remain pending.

**既有 Mac 原生范围——Full 18（2de4）。**两个限定 Benchmark 调试流程通过：实际顶部工具栏 Stop，以及 Resume 正常结束。均到达断点、适配器退出 0、精确删除各自生成目录，两次共 8 个跟踪进程均已退出。四个夹具文件未变，应用正常退出与再次验签通过，保留 7 条日志警告。Full 17 编辑器直接求值及精确声明跳转保留各自证据；其失败 Stop 与残留僵尸/输出仍保留，不记为本轮成功。Windows 原生 IDE 清理工作流与完整界面验收仍待完成。

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
