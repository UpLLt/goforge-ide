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

**Current status: the older frozen a252 portable ZIP passed macOS cross-assembly and static readback checks only. Actual Windows Run 4 reported 644 passed / 14 failed / 1 skipped; packaging was skipped. The 318d repair build passed on macOS; Windows Run 5 is pending. Native Windows launch and UI acceptance remain unverified.**

The prepared archive is `GoForge-0.1.0-dev-windows-x64.zip`, targeting Windows 10/11 x64. Preparation does not mean it has been publicly released. When a release includes it:

1. Verify its published SHA-256 in PowerShell:
   ```powershell
   Get-FileHash .\GoForge-0.1.0-dev-windows-x64.zip -Algorithm SHA256
   ```
2. Extract the entire archive to a writable location such as a folder inside your user profile. Do not launch from inside the ZIP.
3. Run `GoForge\bin\goforge64.exe` from the extracted directory. The package includes its runtime and Go toolchain.
4. Check **Settings → GoForge** if you deliberately want to use different tool versions. Use Windows `.exe` tool paths.

The distribution has no GoForge Authenticode signature or installer. The native launcher receives GoForge icon/version resources, which invalidates its original signature; the derivative launcher is unsigned. Follow normal Windows security prompts; do not turn off SmartScreen or antivirus. Remote SSH features additionally require the Windows OpenSSH client; Docker and Kubernetes require their own installations.

**当前状态：较早的冻结 a252 便携 ZIP 仅通过 macOS 跨平台组装与静态读回。实际 Windows 第 4 次 CI 为 644 通过 / 14 失败 / 1 跳过，打包跳过；318d 修复构建已通过 macOS 自动化检查，Windows 第 5 次 CI 待完成。原生启动与界面仍未验证。** 目标为 Windows 10/11 x64。“已准备”不表示“已公开发布”。发行页实际提供 ZIP 后，使用 `Get-FileHash` 校验，完整解压，再打开 `GoForge\bin\goforge64.exe`。不要在 ZIP 内直接启动。包内置 Java 与 Go 工具，但没有 GoForge Authenticode 签名或安装器。通过正常安全提示处理，不关闭 SmartScreen/杀毒软件。SSH、Docker、kubectl 使用各自系统安装。

### Windows CI status / Windows CI 状态

**Windows Run 4 reached the actual product/platform tests:** source commit `9036d0a76e15882603d45d05ce0fc295bad2c861`, reviewed source ZIP SHA-256 `17a2e91af959fb525fdf381ead90e6560455f4ae2a0117cc41b9c8b0558c3198`. Of 659 reported tests, **644 passed, 14 failed and 1 was skipped**. Packaging was skipped; this run did not produce an accepted Windows package. The frozen 318d repair build passed macOS automated checks; Windows Run 5 is pending. The available frozen Windows archive remains the older **a252** macOS cross-assembly, with static readback only; native launch/UI have not passed. See the [attempts and verification limits](KNOWN-LIMITATIONS.md#private-validation-and-publication--私有验证与发布).

**Windows 第 4 次 CI 已实际运行产品/平台测试：** 源码提交为 `9036d0a76e15882603d45d05ce0fc295bad2c861`，经审阅的源码 ZIP SHA-256 为 `17a2e91af959fb525fdf381ead90e6560455f4ae2a0117cc41b9c8b0558c3198`。659 项报告中，**644 项通过、14 项失败、1 项跳过**。打包步骤跳过，该轮未产出通过验收的 Windows 包。冻结 318d 修复构建已通过 macOS 自动化检查，Windows 第 5 次 CI 仍待完成。已有冻结 Windows 包仍是较早的 **a252** macOS 跨平台组装，仅有静态读回验证，原生启动/界面尚未通过。 详见[逐次记录与验证边界](KNOWN-LIMITATIONS.md#private-validation-and-publication--私有验证与发布)。

## Prepared package identities

The current frozen **318d repair source build** passed **678/678 macOS-host JUnit tests** (397 component + 281 platform; 95 XML reports; zero failures/errors/skips), plugin build and structure checks. Its 326 build inputs are frozen. Plugin ZIP: **14,941,918 bytes**, SHA-256 `318d7205541d0fd00b51f74c13ec7ecc22297a3dd19d6da0e1da7f342918fb93`. **Windows Run 5 is still pending.** This macOS automated result does not establish the Windows-specific repairs or a new native application result; the latest verified macOS application archive/native record remains 5259.

The frozen **2026-10-29 baseline 5259** passed **659/659 macOS-host JUnit tests** (389 component + 270 platform; 94 reports; zero failures/errors/skips), plugin build and structure checks. Plugin ZIP: **14,931,387 bytes**, SHA-256 `52598058b70ba2329bb1447be52d6874596fabf02eeb3b76ecaf30fe6c7fb354`; 324 build inputs were frozen. Separate checks passed 20 scalar-refactoring protocol cases, 31 helper subcases, 3 compiler-context regressions and 18 multi-module SSH/gopls checks; these are not additions to the JUnit count.

当前冻结的 **318d 修复源码构建**在 macOS 主机通过 **678/678 项 JUnit 测试**（397 component + 281 platform，95 份 XML 报告，零失败/错误/跳过），插件构建及结构检查通过，326 项构建输入冻结。插件 **14,941,918 字节**，SHA-256：`318d7205541d0fd00b51f74c13ec7ecc22297a3dd19d6da0e1da7f342918fb93`。**Windows 第 5 次 CI 仍待完成。** 这项 macOS 自动化结果不代表 Windows 专属修复已在 Windows 验证，也不代表新应用原生验收；最近已验证的 macOS 应用包及原生记录仍为 5259。

**2026-10-29 冻结基线 5259** 在 macOS 主机通过 **659/659 项 JUnit 测试**（389 component + 270 platform，94 份报告，零失败/错误/跳过），插件构建和结构校验通过。插件 **14,931,387 字节**，SHA-256：`52598058b70ba2329bb1447be52d6874596fabf02eeb3b76ecaf30fe6c7fb354`，当时冻结 324 项构建输入。另有 20 项标量重构协议、31 个 helper 子案例、3 项编译配置回归及 18 项多模块 SSH/gopls 检查通过，不累加到 JUnit 总数。

| Frozen 5259 internal archive | Bytes | SHA-256 |
| --- | ---: | --- |
| `GoForge-5259-macos-aarch64.zip` | 751543468 | `c2c27d6b42cc98deaa95f8138b06cddd2cb7beac1042ccf5a3d85d08c4d87ecb` |

The macOS archive passed static archive/plugin/source readback and its recorded signing, headless, relocation and extraction checks. No corresponding accepted 5259 Windows archive was produced by Run 4; the older a252 Windows identity is retained below.

macOS 包的静态归档/插件/源码读回，以及记录中的验签、无界面、迁移路径和解压检查通过。第 4 次 CI 未产生通过验收的 5259 Windows 包；较早的 a252 Windows 身份保留如下。

The **5259 macOS archive** is frozen: **751,543,468 bytes**, SHA-256 `c2c27d6b42cc98deaa95f8138b06cddd2cb7beac1042ccf5a3d85d08c4d87ecb`. Two bounded native groups passed: scalar parameter type change and generic single-result type change, each with preview, Apply, explicit Save, Undo/Redo and stable saved-byte/bundled-Go test checks. All four fixture files were restored; normal exit left no owned processes, 0 errors and 2 retained warnings, and post-exit signature verification passed. The parameter result is validated by the stable redo/test receipt. These are candidate-only checks, not a GoLand comparison; remote-analysis native acceptance and Windows desktop acceptance remain incomplete.

**5259 macOS 包**已冻结：**751,543,468 字节**，SHA-256：`c2c27d6b42cc98deaa95f8138b06cddd2cb7beac1042ccf5a3d85d08c4d87ecb`。两组限定原生检查通过：标量参数类型修改、泛型单返回值类型修改，均覆盖预览、Apply、显式保存、Undo/Redo，以及稳定状态下的保存字节和包内 Go 测试检查。4 个夹具文件全部恢复；正常退出无所属进程，保留 0 条错误、2 条警告，退出后验签通过。参数修改以稳定状态的 Redo/测试回执为准。这些是候选应用检查，不是 GoLand 对照；远程分析原生验收与 Windows 桌面验收仍未完成。

Checkout-local evidence (excluded from source exports): `.artifacts/alignment-20261029/full-2/verified-build/`, `macos-package/summary.json`, and `native-scalar/summary.json` with `native-scalar/verification-receipt.json`.

### Previous a252 package identities

Historical **a252** passed **633/633 macOS-host JUnit tests** (366 component + 267 platform; zero failures/errors/skips), with 311 frozen inputs. Plugin ZIP: **14,884,065 bytes**, SHA-256 `a252ce31c51d9b427745256245bb1f852cbdf8f1a8bebe4c68d30a4098360aac`. Its 88 focused tests are included; 12 generic and 7 ordinary parameter protocol cases plus 42 helper subtests were separate checks.

| Historical a252 internal archive | Bytes | SHA-256 |
| --- | ---: | --- |
| `GoForge-a252-windows-x64.zip` | 771129607 | `7285dd2f4f80fd914a164e87fa169207c3a2fb4baacd78025fc69feb4af2b815` |
| `GoForge-a252-macos-aarch64.zip` | 751496018 | `864a9f676325c79efb102e59cbbf46ad1bc4e8f7eb5f93e85d9ac7443de3495c` |

Both a252 archives passed static readback; Windows matched 16,351 extracted file hashes. The 9 packaging and 18 preparation checks reused verified unchanged 311e inputs, not new Windows execution. The bounded macOS generic three-file preview/Apply/explicit Save/Undo/Redo and stale-preview refusal passed; all five fixture files were restored, bundled Go tests passed, exit left no owned processes, and signing passed with 0 errors / 6 retained warnings. These are historical candidate checks, not complete GoLand parity.

历史 **a252** 的 633/633 项 macOS JUnit、311 项冻结输入及两平台包静态读回结果保留原身份；Windows 解压后的 16,351 个文件哈希匹配。9 项打包与 18 项准备检查按相同 311e 输入复用，不是新 Windows 执行。macOS 泛型三文件预览/Apply/显式保存/Undo/Redo 与旧预览拒绝通过，5 个文件恢复、包内 Go 测试及退出验签通过，无所属进程，保留 0 错误/6 警告。这些历史候选检查不代表完整 GoLand 对齐。

### Previous 319d package identities

The historical frozen plugin build **319d**: **626/626 macOS-host JUnit tests** (365 component + 261 platform, 91 reports, zero failures/errors/skips), 309 frozen inputs. Plugin ZIP: 14,878,701 bytes, SHA-256 `319d7fe5e4fb079e8225958ae7c52963d740f6593b25aec055e5f29b46a97f3f`. Focused tests are included in 626; separate protocol/helper and packaging checks are not added to that count.

| Historical internal archive | Bytes | SHA-256 |
| --- | ---: | --- |
| `GoForge-319d-windows-x64.zip` | 771124243 | `47123eb149867d57823342c59acf89706fcfebf5a3b9b800204222290b5176f1` |
| `GoForge-319d-macos-aarch64.zip` | 751490656 | `1e4ec4b6b0ba8e36af676efc30d29d714b7d8cec393109bf3aefdf652fcad3d0` |

Both archives are frozen and their plugin contents match the tested build. Windows ZIP/application/separately extracted contents matched all 16,351 file hashes. The nine packaging and 18 preparation checks reuse the earlier 311e results only after verifying unchanged inputs and original log identities; they were not rerun or counted as new tests. **Windows native execution and CI remain unverified.** macOS signing, headless, relocation and extracted-archive checks passed; all ZIP entries passed CRC and plugin readback.

Native 319d acceptance reran **only Stop and its diagnostic newline**: one completed child stayed Passed, the waiting child/root became Terminated/Stopped, buffered output appeared with the repaired line break, and no synthetic failed test was added. Both actual Go/test processes exited. Fixture bytes were unchanged, the Show Passed filter was restored, normal exit left zero owned processes and zero new ERROR lines, and post-exit strict/deep signing passed. Five WARN lines remain recorded, including the platform's unfinished-node and terminal-tracker warnings. This is a bounded pass, not full GoLand alignment.

The separate **311e** native record covers selected duplicate-subtest debugging (breakpoint, locals and expression evaluation), Stop status, and three-file add-parameter preview/apply/Undo/Redo. Seven real add-parameter protocol cases are also tied to 311e. Save changed editor buffers before running external Go commands: the native refactor check passed `go test` after an explicit Save. Those debug/refactor checks were not repeated on 319d; its only changed production file repairs the Stop diagnostic newline.

历史 **319d** 在 macOS 主机通过 **626 项 JUnit 测试**（365 component + 261 platform），309 项输入冻结。两平台包均已生成并完成文件校验，**Windows 真机运行与 CI 尚未验证**。9 项 Windows 打包及 18 项准备检查在核对输入和原日志身份后复用 311e 结果，没有冒充新执行。319d 原生复验仅覆盖停止测试及提示换行：已完成子测试保留通过，未完成节点显示终止，缓冲输出与换行正确，两个测试进程均退出；样例文件不变、筛选恢复、正常退出、零新增 ERROR 与退出后验签通过，5 条 WARN 如实保留。

所选重复子测试调试与三文件添加参数的预览、应用、撤销/重做原生结果，以及 7 项真实添加参数协议检查，仍归属独立 **311e** 记录，没有记为 319d 重跑。修改后先保存编辑器缓冲区，再运行外部 Go 命令。整体仍有未对齐项，无公开发行。

Current checkout-local evidence (excluded from source exports): `.artifacts/remaining-alignment-20261028/full-2/verified-build/`, `macos-package-final/summary.json`, `windows-package-final/summary.json`, and `native-final/summary.json`. The earlier 311e scope is recorded separately in `native/summary.json` and `add-parameter/protocol-2/`.

### Previous f9e0 package identities

The historical frozen plugin build **f9e0**: **596/596 macOS-host tests** (352 component + 244 platform, 87 reports, zero failures/errors/skips), 300 frozen inputs. Plugin ZIP: 14,849,715 bytes, SHA-256 `f9e0ebf8734e088a7d017a7a661b14bf8ab1e13bc590f09ef26ddb66f347bad2`. Focused tests are included in 596; separate protocol and packaging checks are not added to that count.

| Historical internal archive | Bytes | SHA-256 |
| --- | ---: | --- |
| `GoForge-f9e0-windows-x64.zip` | 771095262 | `0e63479351e5abc6b6a58f9fba49bd3d17ead685d96be2f42266c4041908b047` |
| `GoForge-f9e0-macos-aarch64.zip` | 751461676 | `1f9727d9b69183ab6790d31d5241e15c292cdc165783d9eba50373858201ba78` |

Both archives are assembled and frozen. Windows extraction and all 16,351 file hashes matched, with nine packaging and 18 preparation checks passed separately; **Windows native execution and CI remain unverified**. macOS strict/deep signing, headless, relocation and extracted-archive checks passed. Native f9e0 checks passed the caret-selected TestCases run, actual toolbar rerun of `dup#01` with only its parent and child (`-count=1`), and navigation to its containing declaration. **Selected-test debug did not start after the observed f9e0 toolbar clicks.** The later 311e selected-debug pass is recorded separately above. The fixture bytes were unchanged, the temporary breakpoint/filter setting was restored, normal exit left zero owned processes/new ERROR lines, and post-exit signing passed. These bounded checks do not establish full GoLand alignment.

历史 **f9e0** 已通过 **596 项 macOS 主机测试**（352 component + 244 platform），300 项构建输入冻结。两平台内部包均已生成并校验；**Windows 真机运行和 CI 尚未通过验证**。macOS 本轮确认光标测试运行、所选 `dup#01` 子测试独立重跑及跳转所属声明，但点击所选测试调试按钮后未启动会话，该次失败保留；后续 311e 通过单独记录。夹具字节不变、临时断点与筛选设置已恢复，正常退出、进程清理及验签通过。仍有功能和验收差异，无公开发行。

Current checkout-local evidence (excluded from source exports): `.artifacts/test-targets-20261028/toolbar-full-1/verified-build/`, `toolbar-macos-package/summary.json`, `toolbar-windows-package/summary.json`, and `toolbar-native/summary.json`. The package summaries retain their original freeze-time observations; the separate final native summary records the bounded results above.

### Previous 921d package identities

The historical frozen plugin build **921d** passed **512/512 macOS-host tests** (322 component + 190 platform, 77 reports, zero failures/errors/skips), with 275 frozen inputs. Plugin ZIP: 14,738,511 bytes, SHA-256 `921dfcd3b2403a528fb9e5b5bab0e99437758db051c3a8e6483f4ed28c2d360a`.

| Historical internal archive | Bytes | SHA-256 |
| --- | ---: | --- |
| `GoForge-921d-windows-x64.zip` | 770984053 | `2bf07ff5553f6f9241e1a1c1012ab13ad118e65243752d4961cde128dc2542a1` |
| `GoForge-921d-macos-aarch64.zip` | 751350473 | `6fc10a779001db2727f9d282d3f0a07594752ce3f109a12173f1aae5404f5039` |

Windows full extraction and all 16,351 file hashes matched; 9 packaging and 18 preparation Python checks passed. **Windows native execution remains unverified.** Its frozen assembly uses the previous macOS helper. The subsequently revised helper accepts an explicit validated dependency path and passed nine compatibility checks; it does not change this archive. The initial macOS packaging attempt failed because two Gradle transform directories matched; its log is retained. The explicit validated dependency-path retry passed strict/deep signing, headless and relocation checks, and extracted-archive signature/probe verification. The separate 921d native report passed five selected original-target saved-byte comparisons and three candidate-only checks, with normal exit, zero owned processes/new ERROR lines and passing post-GUI strict/deep signing. Each completed case used one Save and matched its expected bytes at the first disk read; this is not all ten historical target cases or full parity.

历史 **921d** 已通过 512 项 macOS 主机测试（322 component + 190 platform），275 项输入冻结。Windows 包校验通过，macOS 重打的验签、无界面/重定位/解压探针已通过；新的原生 Save 验收通过五项限定目标对照和三项候选检查，正常退出、零所属进程/新增 ERROR 与后验签通过。历史 **9ab8** 的两项原生 Save 对照均失败：第一次保存只更新缓冲区，磁盘需第二次保存；光标/空白行也不同。921d 的新原生结果单独证明上述限定修复，不改写 9ab8 失败或 9abc 的 8/10 历史。Windows 实机仍未验证，两个平台均未公开发布。

Current local verification records (excluded from public source exports):

- `.artifacts/save-format-20261028/repair-full-1/verified-build/build-summary.json`: 512 tests and exact plugin identity.
- `.artifacts/save-format-20261028/repair-macos-package/package-manifest.json` and `repair-windows-package/summary.json`: distinct platform package evidence.
- `.artifacts/save-format-20261028/repair-native/summary.json`: five bounded target comparisons, three candidate-only checks, expected invalid-source refusal, restoration/exit/signature. SHA-256 `324833d480995e02c3ff839e68ac5807af24968610997f11ced31a6e5577e007`.

### Previous 9abc package identities

The previous frozen plugin build is **9abc**, with **476/476 automated tests** (317 component + 159 platform, 74 JUnit reports) on the macOS development host, plus plugin build and structure checks. Its 266 inputs are frozen. The plugin ZIP is 14,700,291 bytes, SHA-256 `9abc63ca39c83b9c8a3b78fa01bb8cc13246c37b5fd59239199b2edb832be3a4`.

| Internal 9abc archive | Bytes | SHA-256 |
| --- | ---: | --- |
| `GoForge-9abc-macos-aarch64.zip` | 751312246 | `df6d45bb8895f3c5bdb796dafa254e032c611fd90070c52396a3675f0458852d` |
| `GoForge-9abc-windows-x64.zip` | 770945833 | `35f5083be3b20684e8ff9d653308bc0da9f2a8a972c17de8b97b5c435cb75abe` |

macOS headless, strict/deep signature, relocation and extracted-archive checks passed. Windows full extraction and all 16,351 archive file hashes matched; nine packaging and 18 preparation Python checks passed separately. **Windows native execution remains unverified.** After the owner unlocked the Mac, 9abc matched 8/10 original editor cases; cases 03/08 still differed by a missing final LF after Save. Three candidate-only comment/Undo boundaries passed. The fixture was restored, normal exit left zero owned processes and no new ERROR lines, and post-exit strict/deep signing passed. The unresolved save-format gap prevents a clean/full editor pass; no older pass is assigned to this build.

历史 **9abc** 在 macOS 通过 **476/476 测试**（317 component + 159 platform，74 份报告），266 项输入冻结。两平台内部包身份见上表；macOS 无界面、验签、重定位、解压检查通过，Windows 完整解压与 16,351 个文件哈希匹配，另有 9 项打包及 18 项准备脚本检查。**Windows 原生执行尚未验证。** 解锁后新 Mac 原生对照 8/10 一致，03/08 的保存最终 LF 差异仍未解决；另 3 项候选注释/Undo 边界、夹具恢复、正常退出、零所属进程、无新增 ERROR 及验签通过。不能记为完整编辑验收，也不移植旧版本通过结论。

Current and historical checkout-local records (excluded from source exports):

- `.artifacts/editor-basics-20261028/full-1/verified-build/build-summary.json`: historical 476-test build; `compiled-sources-manifest.json` binds 266 inputs.
- `.artifacts/editor-basics-20261028/macos-package/package-manifest.json`: historical macOS packaging/signature/headless/relocation/extraction checks.
- `.artifacts/editor-basics-20261028/windows-package/summary.json`: historical Windows cross-assembly, 16,351-file readback and 9 + 18 Python checks; not Windows execution.
- `.artifacts/editor-basics-20261028/native/summary.json`: final 8/10 exact comparison, two Save mismatches, three candidate-only checks, restoration/exit/signature; earlier `native/pending.json` retains the resolved lock interruption.
- `.artifacts/editor-basics-20261028/target-observations/summary.json`: ten partial original-GoLand editor observations, not a completed baseline.
- `.artifacts/editor-basics-20261028/save-newline-review/report.md`: read-only save-format investigation; no repair is included in this frozen 9abc artifact.
- `.artifacts/strict-alignment-20261028/console-followup-2/verified-build/build-summary.json`: 449-test build and plugin identity.
- `.artifacts/strict-alignment-20261028/console-followup-2/verified-build/compiled-sources-manifest.json`: 253 frozen tested inputs.
- `.artifacts/strict-alignment-20261028/console-followup-2/native/summary.json`: scoped 1caa native regression, normal exit and signature results.
- `.artifacts/strict-alignment-20261028/console-followup-2/native/remote-fixture-cleanup.json`: exact remote fixture cleanup and unchanged marker.
- `.artifacts/strict-alignment-20261028/console-followup-2/windows-package/summary.json`: Windows cross-assembly, complete archive readback and separate platform/dependency patch regressions; not Windows execution.
- `.artifacts/change-signature-20261028/result.json`: 21 real gopls/AST/planner signature cases.
- `.artifacts/remote-workspace-20261028/probe-4/result.json`: generated Linux SSH/Go/Delve workspace protocol checks, including simulated Windows paths rather than Windows execution.
- `.artifacts/strict-alignment-20261028/native-7bff/session-final.json`: historical scoped successes and defects, kept separate from 1caa.

Read the manifest for the exact archive you install. **Settings → GoForge** opens tool configuration; a Find Action **Plugins:** entry is a plugin toggle, not that settings page.

The current Windows result is Run 4: 644 passed / 14 failed / 1 skipped, with packaging skipped. See the [current Windows CI status](#windows-ci-status--windows-ci-状态). The historical package identities below remain unchanged.

历史 1caa 的 449 项测试与限定原生稳定性复验保留在各自记录中，不作为 9abc 的编辑原生验收。1caa 的完整包身份见下表；该历史 Windows 包只有跨平台组装与静态验证。

当前 Windows 第 4 次 CI 为 644 通过 / 14 失败 / 1 跳过，打包跳过，见[当前 Windows CI 状态](#windows-ci-status--windows-ci-状态)。下方历史包身份保持不变。

### Historical archive identities / 历史包身份

The previous **1caa** plugin passed 449 macOS-host tests (313 component + 136 platform, 72 reports). Its native stability regression covered essential-plugin protection, plugin search, project-switch/LSP-tree behavior and remote go.work Tests console output. Normal exit, process cleanup and post-GUI signature checks passed. Those are historical scoped results, not 9abc editor acceptance.

| Historical 1caa archive | Bytes | SHA-256 |
| --- | ---: | --- |
| macOS ARM64 | 751301187 | `f953c343f00624d09f16b08021bdac0fd0d76abc4a9ca267859fe2be4f9f0669` |
| Windows x64 | 770934771 | `ccc6bd6d08957f5cb8740622e895267d2a8295d480db54b81012e269946faab2` |

These 2026-10-27 archives are retained as historical artifacts, not the current 5259 macOS baseline or a public release. Their d3d7 native passes remain tied to that build, alongside its observed Plugins Settings search initialization error. That search repair is included in 7bff; later essential-toggle protection is separate.

| Historical archive | Bytes | SHA-256 |
| --- | ---: | --- |
| macOS ARM64 d3d7 | 752381187 | `e6fa4390a10de749a7c095f7a694df20f6e1ec6a960d207bfc98553d9f0ea5bd` |
| Windows x64 d3d7 | 772025428 | `df11cdaabeba5676e62eec95caed57b7ba49e584e08ccc6f333760e4257ac642` |

以上为 2026-10-27 历史包，不是当前 5259 macOS 基线包，也不表示公开发布。d3d7 的限定原生通过和插件设置搜索初始化失败均保留在该构建下；搜索修复已纳入 7bff，之后发现的 essential 插件开关保护另行处理。Windows 原生运行与界面仍待验。

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
