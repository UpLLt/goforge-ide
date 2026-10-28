# GoForge development package notices

Original GoForge code and documentation are licensed under Apache-2.0.
This application includes the unchanged standard license at
`Resources/GoForge-LICENSE.txt` and original attribution at
`Resources/GoForge-NOTICE.txt`. This grant does not relicense upstream components,
the EPL LSP4IJ patch, or other identified third-party material. Their licenses and
notices remain applicable, including the original Community LICENSE/NOTICE files.
Public release is withheld until full GoLand feature alignment is verified;
this is the maintainer's release policy, not an additional license condition.

This is an independently assembled local development distribution using a public
IntelliJ IDEA Community 2025.2.6.3 binary platform. It is not a JetBrains product,
does not contain GoLand plugins, and does not claim that the entire platform was
compiled from source as part of this build. GoForge has no registration,
subscription or activation gate. Third-party online services may require their
own accounts; GoForge does not change their access controls.

The original Community `LICENSE.txt`, `NOTICE.txt`, `license/` reports, JBR legal
notices, and licenses embedded in dependency JARs remain included. The public
Community source is pinned to `idea/252.28539.97`:
https://github.com/JetBrains/intellij-community/tree/idea/252.28539.97

The following files were changed by GoForge packaging: `Info.plist`, localized
Info.plist strings (excluded), `Resources/product-info.json`, outer application
signature, and the native launcher filename. Original GoForge product metadata,
icons, probe class and bundled plugin/tool directories were added. No upstream
authorization or licensing implementation was altered. The allowed Community
plugins are listed in `bundled-plugins.json`; optional plugins outside that list
are not included.

LSP4IJ 0.21.0 is distributed under EPL-2.0. Its source and build instructions are
at https://github.com/redhat-developer/lsp4ij/tree/0.21.0. Local changes are the
intention-description metadata patch and the explorer lifecycle EDT/model-event
repair. Their complete source, licenses, provenance and patch scripts are in
`third-party/lsp4ij-0.21.0`. The lifecycle replacement retains the upstream
EPL-2.0/Apache-2.0 header. TextMate resources are prepared from the metadata-only
JAR before the lifecycle patch; the build inputs and package manifest record
these stage identities separately, including the final lifecycle JAR receipt.

Six public Community classes in `app-client.jar` have narrowly scoped
installed-plugin-search initialization and essential-plugin toggle repairs.
The toggle repair protects application-essential plugins and their transitive
nonoptional dependencies, including cached options and notification undo actions.
The original class hashes,
complete transformation/helper source, Apache-2.0 license and explanation are in
`third-party/intellij-252-installed-search`; the application-root and embedded
`goforge-installed-search-patch.json` receipts identify the exact applied change.
This waits for the installed model before running the original search and does
not suppress its assertions. It adapts the ordering fixed upstream in
https://github.com/JetBrains/intellij-community/commit/bf1d84fac9258410c0e3312e9ea905f14b76a825.

Go 1.26.4 and gopls 0.23.0 use BSD-style licenses; Delve 1.27.2 uses MIT.
The complete Go SDK, including its source and license files, is bundled under
`goforge-tools/go`. Tool module versions, sums and copied license notices are in
`third-party/go-modules`. Sources:

- https://github.com/golang/go/tree/go1.26.4
- https://github.com/golang/tools/tree/gopls/v0.23.0
- https://github.com/go-delve/delve/tree/v1.27.2

JetBrains Runtime's original `legal` directory and runtime distribution remain
intact. The macOS runtime identifies itself as `21.0.9+10-b1038.78`, and the
Windows runtime as `21.0.9+1-b1038.78`; both record JBR source commit
`c24e7acbdf28174fbc92e115b34ce8e3e7569a0a`. The runtime is GPLv2 with its
designated Classpath exception; other bundled component terms still apply.
Exact source and build instructions:
https://github.com/JetBrains/JetBrainsRuntime/tree/c24e7acbdf28174fbc92e115b34ce8e3e7569a0a

For a public binary release, distribute the matching
`jbr-21.0.9-b1038.78-source.tar.gz` and `source-assets-manifest.json` beside the
application archive. `scripts/prepare-release-sources.py` in the public GoForge
source prepares this unmodified source archive from the pinned official commit.
This source-distribution path does not create a written source offer or claim a
reproducible runtime rebuild. A binary release must actually include its matching
source assets; the existence of this notice alone does not verify that upload.

The explicit runtime `JCEF_COMMIT_HASH` is
`33f6357c0226f73b0e398b75d8bd22f486e430ab` (version `122.1.9.1060+g33f6357`).
It takes precedence for this source asset over the older auxiliary `jcef_git`
value in the runtime SOURCE field. JCEF and CEF use BSD-style licenses; their
components retain additional notices. The source companion includes exact JCEF,
CEF `d14e0517a1d326a3a8c89a385383814a9d0eaf80`, and Chromium FFmpeg
`17525de887d54b970ffdd421a0879c1db1952307` source archives. The FFmpeg revision
is pinned by Chromium `880dbf29479c6152d5e4f08dfd3a96b30f919e56` DEPS, matching
the bundled CEF framework. CEF sources are not the entire Chromium tree; preserve
all runtime third-party notices and source materials when redistributing.

Exact JCEF source: https://github.com/JetBrains/jcef/tree/33f6357c0226f73b0e398b75d8bd22f486e430ab
Exact CEF source: https://github.com/chromiumembedded/cef/tree/d14e0517a1d326a3a8c89a385383814a9d0eaf80

The local ad-hoc signature is a build identity, not Apple Developer ID signing or
notarization. Public release readiness, full source-offer compliance review and
all GoLand feature parity remain separate work; this package is for local
development and testing.

Database connectors are unmodified, replaceable JARs under `plugins/goforge/lib`:
SQLite JDBC 3.53.4.0 (Apache-2.0/BSD notices), PostgreSQL JDBC 42.7.14 (BSD-2-Clause,
with its bundled dependency notices), and MariaDB Connector/J 3.5.11 (LGPL-2.1-or-later).
Their exact published source archives, licenses and checksum manifest are supplied
in `third-party/jdbc`. Replacing or debugging the LGPL connector for personal use
is not restricted by GoForge. Driver sources and build instructions:

- https://github.com/xerial/sqlite-jdbc
- https://github.com/pgjdbc/pgjdbc
- https://github.com/mariadb-corporation/mariadb-connector-j/tree/3.5.11

Node.js 24.22.1 is included for the Dev Containers CLI, with its complete upstream
license and dependency notices in `third-party/Node-LICENSE.txt`.
Source: https://github.com/nodejs/node/tree/v24.22.1.
The unmodified `@devcontainers/cli` 0.89.0 distribution is under
`goforge-tools/devcontainer-cli`, including `LICENSE.txt` and `ThirdPartyNotices.txt`.
Source and build instructions: https://github.com/devcontainers/cli/tree/v0.89.0.
Docker, kubectl and OpenSSH are separate host tools; their system installations
and licenses are not modified by GoForge.

## JCEF and CEF license texts

### JCEF

```text
// Copyright (c) 2008-2013 Marshall A. Greenblatt. Portions Copyright (c)
// 2006-2009 Google Inc. All rights reserved.
//
// Redistribution and use in source and binary forms, with or without
// modification, are permitted provided that the following conditions are
// met:
//
//    * Redistributions of source code must retain the above copyright
// notice, this list of conditions and the following disclaimer.
//    * Redistributions in binary form must reproduce the above
// copyright notice, this list of conditions and the following disclaimer
// in the documentation and/or other materials provided with the
// distribution.
//    * Neither the name of Google Inc. nor the name Chromium Embedded
// Framework nor the names of its contributors may be used to endorse
// or promote products derived from this software without specific prior
// written permission.
//
// THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
// "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
// LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
// A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
// OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
// SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
// LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
// DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
// THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
// (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
// OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

### CEF

```text
// Copyright (c) 2008-2020 Marshall A. Greenblatt. Portions Copyright (c)
// 2006-2009 Google Inc. All rights reserved.
//
// Redistribution and use in source and binary forms, with or without
// modification, are permitted provided that the following conditions are
// met:
//
//    * Redistributions of source code must retain the above copyright
// notice, this list of conditions and the following disclaimer.
//    * Redistributions in binary form must reproduce the above
// copyright notice, this list of conditions and the following disclaimer
// in the documentation and/or other materials provided with the
// distribution.
//    * Neither the name of Google Inc. nor the name Chromium Embedded
// Framework nor the names of its contributors may be used to endorse
// or promote products derived from this software without specific prior
// written permission.
//
// THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
// "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
// LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
// A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
// OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
// SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
// LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
// DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
// THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
// (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
// OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```
