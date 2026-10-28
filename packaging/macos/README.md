# GoForge macOS development distribution

This target assembles an independent `GoForge.app` using the pinned public
Community 2025.2.6.3 platform and original GoForge plugin. It has a distinct bundle
ID (`dev.goforge.ide`), native launcher, product metadata and original GF icon.
The internal platform prefix remains `Idea` so the original public Community
main plugin descriptor loads correctly. A first-classpath branding resource and
the platform's resource-loading flag supply GoForge's product information.
No GoLand components are used. There is no GoForge registration, subscription or
activation step.

Usage telemetry collection and upload are disabled using the platform's existing
`idea.disable.collect.statistics` and `idea.suppress.statistics.report` product
properties (headless reporting is disabled as well). The GoForge vendor's default
consent resource marks the upstream usage/EAP/AI collection options unavailable;
these are disabled product definitions, not user approvals. No accepted-consent
file or global user preference is written. This also supplies the vendor resource
expected by Community's consent API, which otherwise throws when its known IDs
are absent. Original legal notices remain included.

This is a binary-platform assembly with compiled GoForge code and branding probe,
not a claim that the entire IntelliJ platform was built from source. Full GoLand
feature parity remains a separate engineering target.

The complete bundled toolchain requires Apple Silicon and macOS 13.5 or newer
(the bundled Node executable sets this minimum). The packaging script sets
`LSMinimumSystemVersion` to `13.5`, verified in the generated app's `Info.plist`.
Native acceptance currently covers the local macOS 27 machine; the declared
minimum does not imply validation on every supported macOS version.

## Reproduce

Use the project JDK and dependency cache to build the current plugin first:

```sh
JAVA_HOME="$PWD/.tools/jdk" GRADLE_USER_HOME="$PWD/.gradle-user-home" \
  ./gradlew --no-daemon -PgoforgeSandboxDirectory=build/verification-sandbox \
  test buildPlugin verifyPluginStructure
python3 scripts/setup-extra-tools.py
python3 scripts/package-macos.py --archive
```

The packaging script requires macOS arm64, Apple's command line tools (Swift and
iconutil), and the pinned Community/LSP4IJ dependencies already downloaded by
Gradle. It copies Go 1.26.4 from the resolved `.tools/bin/go` SDK and copies the
existing gopls 0.23.0 and Delve 1.27.2 binaries. It fails when required tool module
licenses are missing from the module cache. It performs no global installation,
does not alter the official Community development host, and refuses to replace
an app not bearing its own build marker or an actively running GoForge bundle.

### Prepare and restore the extra tools

`scripts/setup-extra-tools.py` prepares only this checkout's pinned macOS arm64
build inputs. It reuses existing files only after checking their exact hashes;
it does not reinstall verified inputs or run `npm install`/package lifecycle
scripts. The Dev Containers CLI published package is already bundled and has no
runtime npm dependencies. Node's executable and upstream license are sufficient
for this target; npm is not required or installed by this script.

| Input | Fixed version | Official source and verification |
| --- | --- | --- |
| Node.js | 24.22.1, Darwin arm64 | [Official archive](https://nodejs.org/dist/v24.22.1/node-v24.22.1-darwin-arm64.tar.xz); SHA-256 from [SHASUMS256.txt](https://nodejs.org/dist/v24.22.1/SHASUMS256.txt), plus pinned executable/license hashes. |
| Dev Containers CLI | 0.89.0 | [npm release metadata](https://registry.npmjs.org/@devcontainers/cli/0.89.0); published tarball SHA-512 SRI and hashes of all eight extracted files. MIT license and ThirdPartyNotices.txt remain included. |
| SQLite JDBC | 3.53.4.0 | [Maven Central source JAR](https://repo.maven.apache.org/maven2/org/xerial/sqlite-jdbc/3.53.4.0/sqlite-jdbc-3.53.4.0-sources.jar); pinned source/binary SHA-256 and embedded Apache/Zentus notices. |
| PostgreSQL JDBC | 42.7.14 | [Maven Central source JAR](https://repo.maven.apache.org/maven2/org/postgresql/postgresql/42.7.14/postgresql-42.7.14-sources.jar); pinned source/binary SHA-256 and all embedded dependency notices. |
| MariaDB Connector/J | 3.5.11 | [Maven Central source JAR](https://repo.maven.apache.org/maven2/org/mariadb/jdbc/mariadb-java-client/3.5.11/mariadb-java-client-3.5.11-sources.jar) and [tagged LGPL license](https://raw.githubusercontent.com/mariadb-corporation/mariadb-connector-j/3.5.11/LICENSE); each SHA-256 is pinned. |

The Node archive SHA-256 is
`654ba79de6155647e22321932b45f2a1ccb705976cc337c9b8e278beb085a628`.
The CLI tarball SRI is
`sha512-LzaoOGKQ/Zql6PsiZ4hVIYVZagzWkD65aG/1ou5/Kly5Y1PtjLg1yn7qu+LZCzVoAl6DZZ/pbz8qOO4RLNlqMg==`.
All remaining file hashes and versioned URLs are embedded in the preparation
script. This checks the pinned HTTPS-delivered artifacts; it does not claim to
verify Node's OpenPGP signature or npm provenance attestations.

```sh
# Read-only, offline verification; fails if any required file is missing/changed.
python3 scripts/setup-extra-tools.py --check

# Prepare missing inputs using only already installed files and verified cache.
python3 scripts/setup-extra-tools.py --offline

# Restore altered/incomplete dedicated inputs, preserving backups first.
python3 scripts/setup-extra-tools.py --repair
```

Outputs are `.tools/node-runtime`,
`.tools/devcontainer-cli/node_modules/@devcontainers/cli`, and
`.artifacts/database-licenses` (source JARs, notices, and the manifest consumed by
the packager). Downloads are cached under `.tools/download-cache/goforge-extra`.
JDBC binaries already in this project's Gradle cache or
`.artifacts/database-jdbc-libs` are reused after their pinned hashes match.
An existing valid JDBC source/notice manifest is verified and kept unchanged.
Normal preparation writes `.tools/extra-tools-manifest.json` with its checks and
download count; `--check` writes nothing. Both modes verify actual Node/CLI
version output after validating their files.

Missing files can be reconstructed from the official archives. A changed or
partial existing tool directory is rejected unless `--repair` is supplied;
repair moves it into a unique `.tools/backups/extra-tools-*` directory before
installing verified replacements. Changed individual notices are similarly
backed up. It rejects symlinked/out-of-checkout destinations and refuses to
replace a tool directory in use by a process. It never updates an existing app
bundle, downloads a different version as fallback, changes a global toolchain,
or changes macOS security settings. A missing uncached input in `--offline` mode
is a reported failure, not a successful partial setup.

The assembly keeps the three JDBC drivers as separate JARs. In particular, the
LGPL MariaDB driver is not merged into GoForge classes; matching source and
license files are copied into the app's third-party resources and the compatible
driver JAR remains independently replaceable. These preparation checks do not
replace the final distribution's license review.

The build steps are repeatable; ZIP timestamps and macOS-generated assets are not currently normalized for byte-for-byte archive reproducibility. The manifest records exact input/output identities for each run.

Outputs:

- `build/macos/GoForge.app` — double-clickable native development app.
- `build/macos/package-manifest.json` — input identities, retained/excluded
  components, local signing result, native probe and relocation evidence.
- `build/macos/GoForge-0.1.0-dev-macos-aarch64.zip` — optional archive.

When `--archive` is used, the ZIP is also extracted into a fresh temporary
directory; its restored signature and native headless launch are verified before
the archive is reported successful. Before announcing `app-ready`, the packager
takes a verified copy-on-write snapshot of the signed bundle for archiving, so
GUI activity cannot race the ZIP traversal.

The pinned LSP4IJ 0.21 disassembly TextMate provider normally copies two resources
from its own JAR into its plugin directory on first GUI startup. The packager
materializes those exact, hash-checked resources before signing; it leaves the
upstream provider and JAR contents unchanged. This prevents startup from adding
unsealed resources to the application. The provider skips existing files. The
package manifest records both resource hashes, and
`python3 scripts/test-package-lsp-textmate.py` verifies exact contents,
idempotence and rejection of altered/symlinked destinations. Native GUI acceptance
must also check the live app's strict signature after startup and normal exit;
the immutable archive snapshot does not substitute for that check.

The script never starts the IDE GUI. Its `goforge-package-info` native-launcher
command starts only a headless product/path probe. GUI acceptance is performed
separately against the output bundle and recorded with its exact plugin identity.

## Isolation and tools

No project checkout, Gradle cache, Homebrew path or old IDE sandbox is referenced
by the native launch configuration. JBR, the full Go SDK, gopls, Delve, Node and
the Dev Containers CLI are
inside the application. Tool defaults resolve from
`PathManager.getHomePath()/goforge-tools/bin`, and explicit user/project tool
choices retain precedence. Existing IDE settings are not imported or modified.

GoForge's own per-user state is stored in these separate directories:

| Data | Location |
| --- | --- |
| Settings and optional user plugins | `~/Library/Application Support/GoForge/GoForge2026.1` |
| Caches and exceptional JVM crash/heap files | `~/Library/Caches/GoForge/GoForge2026.1` |
| Logs | `~/Library/Logs/GoForge/GoForge2026.1` |

Packaging checks an actual second app copy at a path containing spaces. The
native home and `go env GOROOT` must resolve to that copy, and its gopls/Delve
version commands must work. A fresh pure-Go fixture is compiled, tested and run with Homebrew absent from PATH, automatic toolchain downloads disabled and an isolated cache. These are packaging smoke checks, not additional product-test claims. The temporary copy is removed after validation.
Pure-Go compilation and core editor backends are bundled; Cgo requires a compatible
system C toolchain, and Git integration currently uses the system Git executable.
On macOS, Delve also uses Apple's `debugserver` from the command line developer
tools, as documented by Delve's own installation guide. Those Apple components
are not redistributed in this package. The packager does not enable Developer
Mode or change debugger authorization; any required macOS permission is handled
through the normal system interface.

## Signature and upstream sources

The newly assembled derivative is explicitly signed with a **local ad-hoc**
identity, then checked using `codesign --verify --deep --strict`. It retains the
bundled runtime's original signature and legal notices. Only the normal JVM
execution entitlements from the public Community launcher are used. The script
does not change Gatekeeper, quarantine, OS permissions, trust settings or any
original IDE signature.

An ad-hoc signature is not Apple Developer ID signing or notarization. Passing
`codesign` and the native probe does not prove public distribution readiness or
GUI feature completeness. Public release still needs a suitable signing identity,
notarization, release policy and final third-party compliance review.

The implementation follows these pinned primary sources:

- [Community product properties](https://github.com/JetBrains/intellij-community/blob/idea/252.28539.97/build/src/org/jetbrains/intellij/build/IdeaCommunityProperties.kt)
- [Open-source installer build target](https://github.com/JetBrains/intellij-community/blob/idea/252.28539.97/build/src/OpenSourceCommunityInstallersBuildTarget.kt)
- [Native launcher macros and product configuration](https://github.com/JetBrains/intellij-community/blob/idea/252.28539.97/native/XPlatLauncher/src/default.rs)
- [ApplicationInfo resource loading](https://github.com/JetBrains/intellij-community/blob/idea/252.28539.97/platform/core-api/src/com/intellij/openapi/application/ApplicationNamesInfo.java)
- [Community Apache-2.0 license](https://github.com/JetBrains/intellij-community/blob/idea/252.28539.97/LICENSE.txt)
- [Delve macOS requirements](https://github.com/go-delve/delve/blob/v1.27.2/Documentation/installation/README.md#macos-considerations)
- [Platform usage telemetry controls](https://github.com/JetBrains/intellij-community/blob/idea/252.28539.97/platform/statistics/src/com/intellij/internal/statistic/utils/StatisticsUploadAssistant.java)
- [Vendor consent-resource lookup](https://github.com/JetBrains/intellij-community/blob/idea/252.28539.97/platform/platform-impl/src/com/intellij/ide/gdpr/ConsentOptions.java)
- [LSP4IJ 0.21 disassembly TextMate resource initialization](https://github.com/redhat-developer/lsp4ij/blob/0.21.0/src/main/java/com/redhat/devtools/lsp4ij/dap/disassembly/DisassemblyTextMateBundleProvider.java)

`THIRD-PARTY-NOTICES.md` and the packaged original notices explain attribution,
modifications and source locations. The EPL LSP4IJ metadata fix includes its
source patch and license. Optional Community plugins outside the explicit
allowlist are excluded; none of their licensing checks are modified.
