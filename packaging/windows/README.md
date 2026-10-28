# Windows x64 development distribution

The assembler uses the pinned **IntelliJ IDEA Community 2025.2.6.3** Windows ZIP
(build 252.28539.97), including its Windows JBR. It does not use GoLand files.
The original native launcher code is retained; its icon, version resources and
application metadata are changed to GoForge. Its invalidated upstream signature
is removed. **The derivative launcher is unsigned.** No installer, code-signing
certificate, Windows UI acceptance or full GoLand parity is implied.

The portable ZIP contains `GoForge/bin/goforge64.exe`, its JBR and all required
local Go tools. Extract the entire directory before starting it. Windows 10/11
x64 is the target; ARM64 emulation and older releases are not accepted targets.
Product settings use the standard `%APPDATA%/GoForge/GoForge2026.1` and
`%LOCALAPPDATA%/GoForge/GoForge2026.1` locations. No account or activation is added.

## Build

Use Python 3.9 or later, a JDK with `javac` 21 or later, and exactly Go 1.26.4.
Build the plugin with the repository Gradle wrapper first. The assembler supports
Windows, macOS and Linux; it never claims Windows execution when cross-assembled.

```sh
python -m pip install -r packaging/windows/requirements.txt
python scripts/package-windows.py --archive --go /path/to/go --javac /path/to/javac
python scripts/test-package-windows.py
```

Inputs in `inputs.json` have pinned SHA-256 hashes and official URLs. Downloads
are cached under `.tools/windows-downloads`; `--offline` rejects missing inputs.
The existing extra-tools preparer restores the exact portable Dev Containers CLI
and JDBC notices/sources. It does not install the macOS Node runtime in this path.
Go tools are built for `windows/amd64` with `CGO_ENABLED=0`, `-trimpath`, readonly
modules and the local fixed toolchain. Windows Go runs directly from
`goforge-tools/go/bin/go.exe`, preserving SDK relocation and cancellation behavior.

Outputs: `build/windows/GoForge`,
`build/windows/GoForge-0.1.0-dev-windows-x64.zip`, and `package-manifest.json`.
The manifest separates structural checks from pending native runtime acceptance.
Bundled license directories and source references are retained. See
`THIRD-PARTY-NOTICES.md` for dependency and source-distribution boundaries.

## Windows native verification

Run the following **on Windows**, including after extracting the published ZIP:

```powershell
python scripts/verify-windows-package.py --app build/windows/GoForge
```

It copies the app to a path containing spaces and Chinese characters, invokes the
native EXE's package probe with isolated settings directories, checks relocated
GOROOT/tool versions, performs real Go run/test and gopls navigation, and exercises
Delve compilation, breakpoint, locals, evaluate, step, continue, cancellation and
target/adapter process cleanup. It rejects non-Windows execution. Evidence is
written to `.artifacts/windows-native/native-verification.json`, even on failure.

A Windows CI job needs Python, the fixed build Go and JDK, network access to the
pinned dependencies, and enough disk space for assembly plus relocation/extraction
(about 12 GB). It must retain the report on failure. These engine checks do not
replace manual IDE UI checks: first launch, new/open project, editor/LSP behavior,
Run/Test tree, debugger UI, database and optional integration workflows remain
separate. Do not suppress Windows security checks or fabricate GUI acceptance.

## 中文说明

此脚本生成独立的 Windows x64 开发预览 ZIP，入口为
`GoForge/bin/goforge64.exe`。它采用公开 Community 平台的真正原生启动器，
替换自有图标、版本信息及产品配置；修改后的 GoForge 启动器未签名。
完整内置 Windows JBR、Go SDK、gopls、Delve、Node 和 Dev Containers CLI，
无需 GoForge 注册或订阅。Docker、kubectl、OpenSSH 等可选外部服务仍需正常配置。

在 macOS 上成功组装、校验 PE 或解压 ZIP，不代表 Windows 实际运行通过。
请在 Windows 10/11 x64 执行上面的 native 验证命令并保存真实报告，随后进行
原生 IDE 界面验证。不得修改系统安全设置来冒充兼容性通过。
