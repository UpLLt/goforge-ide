#!/usr/bin/env python3
"""Repair the pinned Community Git dashboard read-lock context in an owned copy.

Only the named launchOnShow block is wrapped; its original lifecycle is retained.
Never operates on the downloaded Gradle SDK or the user's installed IDE.
"""
from __future__ import annotations
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
SOURCES = ROOT / 'patches/intellij-252-git-dashboard'
PATCH = 'goforge-ic252-git-dashboard-edt-v1'
MARKER = 'META-INF/goforge-git-dashboard-patch.json'
RECEIPT = 'goforge-git-dashboard-patch.json'
HELPERS = {'dev/goforge/platform/GitDashboardEdt.class'}
JAR = Path('plugins/vcs-git/lib/vcs-git.jar')
INDEX = '__index__'
INDEX_SHA256 = '7fce12cddec689132894041f98b97c27ac8c3c3b2ae5395bbb5955b54cbe4c61'
OPTIMIZED_COMMENT_HEX = '048659c300'
UPSTREAM_JAR_SHA256 = '6857a41b5c13f49bcf60e416c07be59852faee41dc9898327240c1f01646ad78'
UNCHANGED_ENTRIES_SHA256 = '99d1375c3a501f69645a0ec0699fea9afd9e143875143339f762770c8309b559'
OWNER = 'git4idea/ui/branch/dashboard/FilteringBranchesTree'
ORIGINAL = {OWNER + '.class': 'aa8aa641c0c62d2010bd99453581764ac2758094d28f64414d6ddd66d7d2f25b'}
COLLABORATORS = {OWNER + '$1.class': '69555ba78efaaabd3f2e587873cdfc01a7877c42882e5233e6ef94b442417fc8'}
SOURCE_URL = 'https://github.com/JetBrains/intellij-community/blob/idea/252.28539.97/plugins/git4idea/src/git4idea/ui/branch/dashboard/BranchesTree.kt'


def digest(data):
    return hashlib.sha256(data).hexdigest()


def unchanged_fingerprint(archive):
    ignored = set(ORIGINAL) | HELPERS | {INDEX, MARKER}
    values = {n: digest(archive.read(n)) for n in sorted(archive.namelist()) if n not in ignored}
    return digest(json.dumps(values, sort_keys=True, separators=(',', ':')).encode())


def run(*args):
    completed = subprocess.run([str(x) for x in args], capture_output=True, text=True, timeout=120)
    if completed.returncode:
        raise RuntimeError('Git dashboard patch compilation/verification failed:\n' + completed.stdout + completed.stderr)
    return completed.stdout


def patch(home, javac):
    home = Path(home).resolve(strict=True)
    allowed = [ROOT / p for p in ('build', '.artifacts', 'dist')]
    owned_dev = ROOT / '.tools/GoForge Development.app/Contents'
    if not (any(home.is_relative_to(p.resolve()) for p in allowed) or home == owned_dev.resolve()):
        raise ValueError('Only GoForge-owned build/.artifacts/dist or development-app copies are accepted')
    jar_path = home / JAR
    if jar_path.is_symlink() or jar_path.resolve() != jar_path or jar_path.stat().st_nlink != 1:
        raise ValueError('Refusing a linked platform JAR')
    identity = next((p for p in (home / 'product-info.json', home / 'Resources/product-info.json') if p.is_file()), None)
    if identity is None:
        raise ValueError('Platform product-info.json is missing')
    if json.loads(identity.read_text()).get('buildNumber') != '252.28539.97':
        raise ValueError('Expected Community platform build 252.28539.97')
    source_hashes = {p.name: digest(p.read_bytes()) for p in sorted(SOURCES.iterdir()) if p.is_file()}
    source_hashes['patch-git-dashboard.py'] = digest(Path(__file__).read_bytes())
    with zipfile.ZipFile(jar_path) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError('Duplicate archive entries')
        if any(n.upper().startswith('META-INF/') and n.upper().endswith(('.SF', '.RSA', '.DSA', '.EC')) for n in names):
            raise ValueError('Refusing to invalidate a signed JAR')
        for name, expected in COLLABORATORS.items():
            if digest(archive.read(name)) != expected:
                raise ValueError('Unexpected unchanged platform collaborator: ' + name)
        if MARKER in names:
            receipt = json.loads(archive.read(MARKER))
            if (receipt.get('patch') != PATCH or receipt.get('original_classes') != ORIGINAL
                    or receipt.get('upstream_jar_sha256') != UPSTREAM_JAR_SHA256
                    or receipt.get('unchanged_entries_sha256') != UNCHANGED_ENTRIES_SHA256
                    or receipt.get('unchanged_collaborators') != COLLABORATORS
                    or receipt.get('patch_sources') != source_hashes
                    or set(receipt.get('patched_classes', {})) != set(ORIGINAL) | HELPERS):
                raise ValueError('Existing patch differs; rebuild from the pristine platform input')
            for name, expected in receipt['patched_classes'].items():
                if digest(archive.read(name)) != expected:
                    raise ValueError('Patched platform class was changed: ' + name)
            if unchanged_fingerprint(archive) != UNCHANGED_ENTRIES_SHA256:
                raise ValueError('Unchanged patched archive content was modified')
            if INDEX in names or archive.comment:
                raise ValueError('Patched JAR must use its current standard central directory')
            return {**receipt, 'already_patched': True, 'jar_sha256': digest(jar_path.read_bytes())}
        if HELPERS.intersection(names):
            raise ValueError('Unexpected pre-existing patch helper')
        if digest(archive.read(INDEX)) != INDEX_SHA256 or archive.comment.hex() != OPTIMIZED_COMMENT_HEX:
            raise ValueError('Unexpected platform optimized ZIP index/comment')
        originals = {}
        for name, expected in ORIGINAL.items():
            data = archive.read(name)
            if digest(data) != expected:
                raise ValueError('Unexpected upstream class SHA256: ' + name)
            originals[name] = data
        if digest(jar_path.read_bytes()) != UPSTREAM_JAR_SHA256:
            raise ValueError('Unexpected complete upstream vcs-git.jar SHA256')
        if unchanged_fingerprint(archive) != UNCHANGED_ENTRIES_SHA256:
            raise ValueError('Unexpected unchanged archive fingerprint')

    with tempfile.TemporaryDirectory(prefix='.goforge-git-dashboard-patch-', dir=home.parent) as temporary:
        work = Path(temporary)
        inputs, output, classes = work / 'input', work / 'output', work / 'compiler'
        for name, data in originals.items():
            target = inputs / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
        classes.mkdir()
        javac = Path(javac).resolve()
        java = javac.with_name('java.exe' if javac.suffix.lower() == '.exe' else 'java')
        cp = os.pathsep.join((str(home / 'lib/*'), str(home / 'plugins/vcs-git/lib/*')))
        run(javac, '--release', '21', '-g:none', '-cp', cp, '-d', classes,
            SOURCES / 'GitDashboardEdt.java', SOURCES / 'PatchGitDashboard.java')
        run(java, '-ea', '-cp', str(classes) + os.pathsep + cp, 'PatchGitDashboard', inputs, output)
        shutil.copytree(classes / 'dev', output / 'dev')
        patched = {n: (output / n).read_bytes() for n in [*ORIGINAL, *sorted(HELPERS)]}
        receipt = {
            'patch': PATCH, 'platform_build': '252.28539.97',
            'upstream_jar_sha256': UPSTREAM_JAR_SHA256,
            'original_classes': ORIGINAL, 'unchanged_collaborators': COLLABORATORS,
            'unchanged_entries_sha256': UNCHANGED_ENTRIES_SHA256,
            'patched_classes': {n: digest(b) for n, b in patched.items()},
            'patch_sources': source_hashes, 'license': 'Apache-2.0',
            'removed_generated_index': {INDEX: INDEX_SHA256},
            'removed_optimized_zip_comment_hex': OPTIMIZED_COMMENT_HEX,
            'zip_layout': 'standard central directory; original optimized offsets invalid after rewrite',
            'public_source': SOURCE_URL,
            'behavior': 'Original launchOnShow block runs within Dispatchers.EDT; original job and listener lifecycle unchanged',
            'verification': 'ASM data-flow validation; focused dispatcher/lifecycle probe and native Git dashboard acceptance are separate',
        }
        target = work / 'vcs-git.jar'
        with zipfile.ZipFile(jar_path) as source, zipfile.ZipFile(target, 'w') as destination:
            destination.comment = b''
            for entry in source.infolist():
                if entry.filename != INDEX:
                    destination.writestr(entry, patched.pop(entry.filename) if entry.filename in patched else source.read(entry.filename))
            for name, data in [*patched.items(), (MARKER, (json.dumps(receipt, indent=2) + '\n').encode())]:
                entry = zipfile.ZipInfo(name, (2026, 1, 1, 0, 0, 0))
                entry.compress_type = zipfile.ZIP_DEFLATED
                entry.external_attr = 0o100644 << 16
                destination.writestr(entry, data)
        target.chmod(jar_path.stat().st_mode)
        os.replace(target, jar_path)
    receipt_directory = home / 'Resources' if (home / 'Resources/product-info.json').is_file() else home
    (receipt_directory / RECEIPT).write_text(json.dumps(receipt, indent=2) + '\n')
    return {**receipt, 'already_patched': False, 'jar_sha256': digest(jar_path.read_bytes())}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--platform-home', type=Path, required=True, help='Owned platform copy containing lib/, plugins/vcs-git/ and product-info.json')
    parser.add_argument('--javac', type=Path, default=ROOT / '.tools/jdk/bin/javac')
    args = parser.parse_args()
    print(json.dumps(patch(args.platform_home, args.javac), indent=2))
