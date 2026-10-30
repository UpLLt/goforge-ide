# Public source and release scope / 公开源码与发行范围

## Publication decision / 发布决定

On 2026-10-30 the owner authorized public publication of the current usable version as v0.1.0, replacing the former requirement to wait for complete GoLand alignment. This changes the publication decision, not the verified feature coverage. The application has no GoForge registration, login, activation or subscription gate.

2026-10-30 维护者明确授权将当前版本作为 v0.1.0 可用版本公开发布，替代此前“完整对齐后才公开”的门槛。这不改变实际验收范围，不表示完整对齐 GoLand。应用无需 GoForge 注册、登录、激活或订阅。

The release workflow checks the explicit tag/scope in `packaging/release-readiness.json`, product tests, Windows native checks and packaged protocols, then creates a draft. Attach the tested macOS archive and its checksum, verify the assets and publish that draft. Keep platform-specific limitations in the bilingual [release notes](RELEASE-0.1.0.md).

The repository is an allowlisted source export, not the developer's entire working directory. Binary releases carry their own checksums and corresponding third-party materials. / 仓库只公开允许清单内源码；二进制附独立校验值及对应第三方材料。

## Source export

```sh
python3 scripts/export-public-source.py --output goforge-source.zip
```

The ZIP contains repository-relative paths (`src/...`, not an enclosing folder) and `public-export-manifest.json` with relative path, SHA-256 and size for each file. It never runs Git, uploads files or reads credential stores. Existing output is refused. GitHub workflows are deliberately excluded so the repository owner can review and create them separately.

Included: original `src/main`/`src/test` and their resources (including JSONL benchmark replay fixtures), the small Go example, Gradle wrapper/build inputs, approved bootstrap/packaging scripts and assets, public documentation, and the attributed LSP4IJ patch/license. The exact file list is in the exporter. An unknown source/resource extension fails export for review instead of being silently omitted. Windows packaging inputs are included when present.

Excluded: `research/`, `AGENTS.md`, internal status/checkpoints, `.artifacts/`, `.tools/`, caches/build output, `.idea/`, `.git/`, `.github/`, application archives, test databases/keys/logs and private fixture metadata. Internal probe scripts containing developer-specific paths are not exported. The original README is retained only in the internal artifact area.

The exporter rejects symlinks, unexpected large files, private home-directory paths, common private-key/token patterns and installed proprietary-artifact references. This is a bounded content check, not proof that every possible secret is detectable. Review the ZIP before upload. Example credentials in deterministic tests are fixture data, not production access.

导出包只含相对路径及文件指纹，不含本机目录清单或日志。脚本不操作 Git、不上传、不读取凭据库；存在同名输出时会拒绝覆盖。测试中的示例值属于夹具，不是实际服务凭据。规则扫描不能保证识别所有秘密，上传前仍应检查导出内容。

## Binary release materials

For each platform archive, provide:

1. Exact archive checksum and runtime/platform versions; accurately distinguish packaging checks, native launch tests and feature acceptance.
2. Original platform/runtime notices and third-party notices. Preserve individually replaceable JDBC JARs and their matching source JARs.
3. The EPL LSP4IJ source link, complete patch, license and build/patch instructions.
4. Exact JBR source archive and metadata beside the binary. The known macOS baseline uses JBR `21.0.9+10-b1038.78` / commit `c24e7acbdf28174fbc92e115b34ce8e3e7569a0a`. Confirm the actual `release` file for every other build.
5. JCEF/CEF attribution and required third-party notices/source materials. The supplied source-preparation script additionally fetches the matching JCEF, CEF and Chromium FFmpeg source revisions; the CEF archive is not the entire Chromium tree. Retain all notices from the runtime distribution.

```sh
python3 scripts/prepare-release-sources.py --output release-sources
# Revalidate previously recorded asset hashes without downloading:
python3 scripts/prepare-release-sources.py --output release-sources --offline
```

Upload its source archives and `source-assets-manifest.json` alongside the matching binary assets. The script pins official HTTPS source revisions and validates archive structure, then records actual bytes and SHA-256. It does not verify an upstream archive signature or rebuild JBR. Do not reuse the manifest if runtime/source revisions differ. No three-year written source offer is made by this workflow.

源码配套与二进制应在同一发行位置提供。不要把任意上游首页链接当作精确源码身份，也不要把旧版本清单复用到不同运行时。此流程不承诺未经授权的长期书面源码服务。

## Signing and claims

macOS development packages have an ad-hoc signature, not Developer ID notarization. The Windows launcher receives GoForge resources and is unsigned after removal of its invalidated upstream signature. Other upstream executables retaining their signatures do not confer a GoForge publisher signature on the distribution. Keep these facts visible. No platform-wide security bypass is part of installation.

A usable release does not establish complete GoLand parity. Publish the checks actually performed and the remaining platform-specific limitations accurately. Private failure logs and captured proprietary artifacts stay outside the public repository.
