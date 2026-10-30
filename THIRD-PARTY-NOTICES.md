# Third-party notices / 第三方说明

Original GoForge code and documentation are licensed under Apache-2.0; see [LICENSE](LICENSE) and [NOTICE](NOTICE). That grant applies to original GoForge contributions only. Identified upstream components, patches and other third-party material retain their own licenses and notices, including any per-file terms.

原创 GoForge 代码与文档采用 Apache-2.0，见 [LICENSE](LICENSE) 和 [NOTICE](NOTICE)。该授权仅适用于原创 GoForge 贡献，不替上游组件、补丁或其他第三方材料重新授权；第三方及文件级许可继续有效。

GoForge is independently developed. JetBrains, IntelliJ IDEA and GoLand are their respective owners' trademarks; no affiliation or endorsement is implied. The source export contains original GoForge implementation, tests and packaging code, plus the explicitly identified Community search-initialization and essential-plugin toggle adaptations, LSP4IJ metadata and explorer lifecycle repairs, and Gradle wrapper. It excludes proprietary GoLand binaries, decompilation output, research captures, private machine logs and local credentials.

GoForge 为独立项目。JetBrains、IntelliJ IDEA、GoLand 商标属于相应权利人，不表示合作或背书。公开源码不包含专有 GoLand 二进制、反编译产物、研究抓取、机器日志或本机凭据。第三方组件不因与 GoForge 一起分发而改变许可证。

## Components

| Component | Pinned version | License / source |
| --- | --- | --- |
| IntelliJ Community platform | 2025.2.6.3 / 252.28539.97 | [Apache-2.0](https://github.com/JetBrains/intellij-community/blob/idea/252.28539.97/LICENSE.txt), with additional bundled component terms. [Exact platform source](https://github.com/JetBrains/intellij-community/tree/idea/252.28539.97). |
| JetBrains Runtime | 21.0.9+10-b1038.78 in the verified macOS baseline | [GPLv2 with designated Classpath exception](https://github.com/JetBrains/JetBrainsRuntime/blob/jbr-release-21.0.9b1038.78/LICENSE); retain `legal/` and its component notices. [Exact source/release](https://github.com/JetBrains/JetBrainsRuntime/releases/tag/jbr-release-21.0.9b1038.78). A different runtime must have matching source metadata. |
| JCEF / CEF | Runtime JCEF commit `33f6357c0226`; CEF 122.1.9 | [JCEF BSD-3-Clause](https://github.com/JetBrains/jcef/blob/33f6357c0226f73b0e398b75d8bd22f486e430ab/LICENSE.txt) and [CEF BSD-3-Clause](https://github.com/chromiumembedded/cef/blob/d14e0517a1d326a3a8c89a385383814a9d0eaf80/LICENSE.txt), plus their third-party terms. |
| LSP4IJ | 0.21.0 | [EPL-2.0](https://github.com/redhat-developer/lsp4ij/blob/0.21.0/LICENSE). [Source](https://github.com/redhat-developer/lsp4ij/tree/0.21.0); local metadata patch and license in [patches/lsp4ij-0.21.0](patches/lsp4ij-0.21.0/README.md), plus the attributed [explorer lifecycle repair](patches/lsp4ij-0.21.0/lifecycle/README.md). |
| Go SDK | 1.26.4 | [BSD-style license and source](https://github.com/golang/go/tree/go1.26.4). Full SDK/source and license are retained in application distributions. |
| gopls | 0.23.0 | [BSD-style license and source](https://github.com/golang/tools/tree/gopls/v0.23.0). Tool dependency versions and notices are generated from Go module build metadata. |
| Delve | 1.27.2 | [MIT and source](https://github.com/go-delve/delve/tree/v1.27.2); dependencies retain their notices. |
| Node.js | 24.22.1 | [MIT and included third-party licenses](https://github.com/nodejs/node/blob/v24.22.1/LICENSE). |
| Dev Containers CLI | 0.89.0 | [MIT and source](https://github.com/devcontainers/cli/tree/v0.89.0); retain `LICENSE.txt` and `ThirdPartyNotices.txt`. |
| SQLite JDBC | 3.53.4.0 | [Apache-2.0 / bundled BSD notices](https://github.com/xerial/sqlite-jdbc); matching source JAR included with packaged JDBC source materials. |
| PostgreSQL JDBC | 42.7.14 | [BSD-2-Clause and dependency notices](https://github.com/pgjdbc/pgjdbc); matching source JAR included with packaged JDBC source materials. |
| MariaDB Connector/J | 3.5.11 | [LGPL-2.1-or-later and source](https://github.com/mariadb-corporation/mariadb-connector-j/tree/3.5.11); distributed as an unmodified, independently replaceable JAR with matching source and license. GoForge does not prohibit modifying/relinking the connector or debugging those modifications. |
| Gradle wrapper | 9.8.2 distribution | [Apache-2.0 / Gradle source](https://github.com/gradle/gradle). Build tooling, not part of the GoForge runtime. |

Docker, kubectl, OpenSSH, Git, Graphviz, C compilers and Apple developer tools are separately installed host tools; GoForge does not redistribute the user's installations.

## Changes and source materials

The pinned Community Git branch dashboard has a separate local dispatcher repair,
documented in [patches/intellij-252-git-dashboard](patches/intellij-252-git-dashboard/README.md).
It keeps the original component lifecycle and Git operations while running
model-dependent tree updates on the platform EDT. The Apache-2.0 transformation,
helper, verification probe and provenance are included with packaged applications.

Application packaging changes product metadata, branding, launcher naming/configuration and outer bundle signing. The public Community platform is assembled from its binary distribution, not represented as rebuilt entirely from this repository. Original platform `LICENSE.txt`, `NOTICE.txt`, `license/` reports and runtime legal notices must remain in a distributed application.

LSP4IJ changes comprise 20 stable intention-description metadata entries and one explorer lifecycle listener class. The listener repair dispatches tree lookup/insertion/update on the EDT, preserves disposal and normal event behavior, and adds a provenance receipt; its original EPL-2.0/Apache-2.0 header is retained. Complete replacement source, probe, provenance, licenses and patch scripts are included. TextMate resources are prepared from the metadata-only JAR before the lifecycle replacement, with each stage recorded separately. Other original entries remain unchanged. No proprietary plugin or license/activation code is copied or bypassed.

The Community platform additionally receives narrow repairs in six SHA-pinned classes: three for installed-plugin search initialization and three for application-essential plugin toggle protection. Search preserves the original behavior and waits asynchronously for its model, following the ordering addressed by JetBrains in IJPL-254032. Toggle protection includes required dependency closure, cached options and notification undo actions; ordinary plugin enable/disable behavior remains available. The complete Apache-2.0 adaptation/helper source, original hashes and verification boundaries are documented in [patches/intellij-252-installed-search](patches/intellij-252-installed-search/README.md) and included with the application. No assertion is disabled, and the downloaded platform SDK is not modified.

For a binary release, publish matching source materials alongside the binary, including the exact runtime source assets prepared by `scripts/prepare-release-sources.py`. That script records immutable source revisions and SHA-256 values; it does not claim a reproducible runtime rebuild and does not issue a written source offer. Retain the JDBC sources, Go SDK source, module notices, Node/CLI notices and LSP4IJ patch already assembled by the packager. See [public release preparation](docs/PUBLIC-RELEASE.md).

分发应用时，应保留原平台及运行时的通知和许可证。运行时、JDBC、LSP4IJ 等的对应源码和补丁应与匹配二进制一起提供。源码配套脚本固定上游提交并记录校验值，不代表已完成整个运行时的可复现编译，也不构成额外的长期书面源码提供承诺。
