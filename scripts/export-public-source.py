#!/usr/bin/env python3
"""Export only reviewed public source paths; never reads Git or exports build evidence."""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import stat
import zipfile

ROOT = Path(__file__).resolve().parents[1]
# Exact entry points, plus original implementation/test trees below. New top-level
# files are NOT implicitly published. GitHub workflows are intentionally separate.
FILES = '''README.md README.zh-CN.md LICENSE NOTICE THIRD-PARTY-NOTICES.md
.gitignore gradlew gradlew.bat build.gradle.kts settings.gradle.kts gradle.properties
gradle/wrapper/gradle-wrapper.jar gradle/wrapper/gradle-wrapper.properties
docs/INSTALL.md docs/USAGE.md docs/KNOWN-LIMITATIONS.md docs/PUBLIC-RELEASE.md
scripts/export-public-source.py scripts/prepare-release-sources.py
scripts/setup-jdk.py scripts/setup-tools.sh scripts/setup-extra-tools.py scripts/dev-ide.sh
scripts/package-macos.py scripts/patch-lsp4ij-metadata.py
scripts/patch-lsp4ij-lifecycle.py scripts/test-patch-lsp4ij-lifecycle.py
scripts/patch-installed-plugin-search.py scripts/test-patch-installed-plugin-search.py
scripts/test-package-lsp-textmate.py scripts/test-patch-lsp4ij-metadata.py scripts/verify-protocols.py
scripts/package-windows.py scripts/test-package-windows.py scripts/verify-windows-package.py
scripts/test-remote-debug-paths.py
packaging/macos/README.md packaging/macos/THIRD-PARTY-NOTICES.md
packaging/macos/GoForgeApplicationInfo.xml packaging/macos/PackageProbe.java
packaging/macos/bundled-plugins.json packaging/macos/consents-GoForge.json
packaging/macos/entitlements.plist packaging/macos/goforge.svg packaging/macos/make-icon.swift
packaging/windows/README.md packaging/windows/README.txt packaging/windows/THIRD-PARTY-NOTICES.md
packaging/windows/inputs.json packaging/windows/requirements.txt packaging/windows/WindowsPackageProbe.java
packaging/release-readiness.json
patches/lsp4ij-0.21.0/README.md patches/lsp4ij-0.21.0/LICENSE
patches/lsp4ij-0.21.0/stable-intention-description.patch
patches/lsp4ij-0.21.0/lifecycle/README.md
patches/lsp4ij-0.21.0/lifecycle/LanguageServerExplorerLifecycleListener.java
patches/lsp4ij-0.21.0/lifecycle/ExplorerLifecycleProbe.java
patches/intellij-252-installed-search/README.md patches/intellij-252-installed-search/LICENSE
patches/intellij-252-installed-search/InstalledSearchGate.java
patches/intellij-252-installed-search/InstalledSearchGateProbe.java
patches/intellij-252-installed-search/EssentialPluginToggleGuard.java
patches/intellij-252-installed-search/EssentialPluginToggleProbe.java
patches/intellij-252-installed-search/PatchInstalledSearch.java'''.split()
TREES = {'src': {'.java', '.xml', '.txt', '.json', '.jsonl', '.http', '.rest', '.go', '.mod', '.sum'},
         'examples/hello-go': {'.go', '.mod', '.sum'}}
OPTIONAL = {'scripts/package-windows.py', 'scripts/test-package-windows.py', 'scripts/verify-windows-package.py',
            'packaging/windows/README.md', 'packaging/windows/README.txt', 'packaging/windows/THIRD-PARTY-NOTICES.md',
            'packaging/windows/inputs.json', 'packaging/windows/requirements.txt', 'packaging/windows/WindowsPackageProbe.java'}
EXCLUDED_PARTS = {'.git','.github','.idea','.artifacts','.tools','.gradle','.gradle-user-home',
                  'build','out','__pycache__','research'}
PATTERNS = {
    'private-key': re.compile(rb'-----BEGIN (?:OPENSSH |RSA |EC |DSA )?PRIVATE KEY-----'),
    'access-token': re.compile(rb'\b(?:ghp_|github_pat_|sk-proj-|xox[bpars]-|AKIA)[A-Za-z0-9_-]{12,}'),
    'private-home-path': re.compile(rb'/(?:Users|home)/[A-Za-z0-9_.-]+/'),
    'installed-proprietary-artifact': re.compile(rb'/Applications/GoLand\.app|go-plugin\.jar'),
}


def selected():
    result = set()
    for name in FILES:
        p = ROOT/name
        if not p.exists():
            if name in OPTIONAL: continue
            raise ValueError('Required public source is missing: '+name)
        result.add(name)
    for folder, extensions in TREES.items():
        for p in (ROOT/folder).rglob('*'):
            relative = p.relative_to(ROOT)
            if p.name == '.DS_Store': continue
            if any(x in EXCLUDED_PARTS for x in relative.parts): continue
            if p.is_symlink(): raise ValueError('Symlink in public tree: '+relative.as_posix())
            if p.is_file():
                if p.suffix in extensions:
                    result.add(relative.as_posix())
                elif folder == 'src':
                    # A new source/resource format must be reviewed, not silently
                    # omitted from a release that should reproduce the test suite.
                    raise ValueError('Unreviewed source/resource extension: '+relative.as_posix())
    return sorted(result)


def export(destination, report=None):
    entries = []
    for name in selected():
        path = ROOT/name
        if path.is_symlink() or not path.resolve().is_relative_to(ROOT):
            raise ValueError('Unsafe public source: '+name)
        if any(x in EXCLUDED_PARTS for x in PurePosixPath(name).parts):
            raise ValueError('Excluded public source: '+name)
        data = path.read_bytes()
        if len(data)>5_000_000: raise ValueError('Unexpected large source: '+name)
        if name != 'gradle/wrapper/gradle-wrapper.jar':
            data.decode('utf-8')
            for kind, pattern in PATTERNS.items():
                if pattern.search(data): raise ValueError(kind+' in '+name+' (contents withheld)')
        entries.append((name,data,path.stat().st_mode))
    manifest = {'schema':1,'files':[{'path':n,'sha256':hashlib.sha256(b).hexdigest(),'size':len(b)} for n,b,_ in entries]}
    payload = (json.dumps(manifest,indent=2,ensure_ascii=False)+'\n').encode()
    destination.parent.mkdir(parents=True,exist_ok=True)
    if destination.exists(): raise ValueError('Destination exists; choose a new filename')
    with zipfile.ZipFile(destination,'x',compression=zipfile.ZIP_DEFLATED,compresslevel=9) as archive:
        for name,data,mode in entries+[('public-export-manifest.json',payload,0o644)]:
            info=zipfile.ZipInfo(name,(2026,1,1,0,0,0))
            info.compress_type=zipfile.ZIP_DEFLATED
            info.create_system=3
            info.external_attr=((stat.S_IFREG | (0o755 if mode & 0o111 else 0o644))<<16)
            archive.writestr(info,data)
    result={'file':destination.name,'sha256':hashlib.sha256(destination.read_bytes()).hexdigest(),
            'bytes':destination.stat().st_size,'sourceFiles':len(entries),'rootLayout':True,
            'githubWorkflowsIncluded':False,'projectLicenseIncluded':'LICENSE' in dict((n,1) for n,_,_ in entries)}
    if report:
        report.parent.mkdir(parents=True,exist_ok=True)
        report.write_text(json.dumps(result,indent=2)+'\n')
    print(json.dumps(result,indent=2))

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--output',type=Path,default=ROOT/'.artifacts/public-release-prep-20261027/goforge-source.zip')
    p.add_argument('--report',type=Path)
    args=p.parse_args()
    export(args.output.resolve(),args.report)
