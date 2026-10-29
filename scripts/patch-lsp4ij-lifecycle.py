#!/usr/bin/env python3
"""Serialize the pinned LSP4IJ explorer lifecycle in a GoForge-owned copy only."""
from __future__ import annotations
import argparse
import base64
import hashlib
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
SOURCES = ROOT / 'patches/lsp4ij-0.21.0/lifecycle'
SOURCE = SOURCES / 'LanguageServerExplorerLifecycleListener.java'
BASE_SHA256 = 'c4eed1d20b62453282818dbc72f2582fe9f9c01f7551ae74ffb2f2070976303e'
CLASS = 'com/redhat/devtools/lsp4ij/console/explorer/LanguageServerExplorerLifecycleListener.class'
CLASS_SHA256 = '5696fcd8da1646f1642cdb4f1780ebca5ec1b7a206f85cfb47bdce82484906aa'
SOURCE_SHA256 = '1b2c01737b1f5898dddaa4e22bb4202f0e83bddcdf66a94a8c7561e2a9d4ed79'
PATCHED_CLASS_SHA256 = '2b1f9f85c0a1a7ef617615507a0d390f3fb5a2a4c37e6dba86fdbb70ad322d4c'
PATCHED_SHA256 = '47e5d8cb7794f05f27a77911745e65886b1845c28120bbc29e65f08643c0e313'
MARKER = 'META-INF/goforge-lsp4ij-lifecycle.json'
PATCH = 'goforge-lsp4ij-0.21.0-explorer-edt-v1'


def digest(data):
    return hashlib.sha256(data).hexdigest()


def run(*args):
    result = subprocess.run([str(x) for x in args], capture_output=True, text=True, timeout=90)
    if result.returncode:
        raise RuntimeError('LSP lifecycle compilation/probe failed:\n' + result.stdout + result.stderr)
    return result.stdout


def rewrite(data, replacement, marker=None, remove_marker=False):
    output = io.BytesIO()
    with zipfile.ZipFile(io.BytesIO(data)) as source, zipfile.ZipFile(output, 'w') as target:
        target.comment = source.comment
        for entry in source.infolist():
            if remove_marker and entry.filename == MARKER:
                continue
            target.writestr(entry, replacement if entry.filename == CLASS else source.read(entry.filename))
        if marker is not None:
            entry = zipfile.ZipInfo(MARKER, (2026, 1, 1, 0, 0, 0))
            # ZipInfo defaults to DOS metadata on Windows and Unix elsewhere.
            # Pin the provenance entry's origin as well as its mode/timestamp so
            # the reviewed complete-archive hash is identical on every host.
            entry.create_system = 3
            entry.compress_type = zipfile.ZIP_DEFLATED
            entry.external_attr = 0o100644 << 16
            target.writestr(entry, (json.dumps(marker, indent=2) + '\n').encode())
    return output.getvalue()


def validate_bytes(data):
    """Validate both modified bytes and every unchanged entry against the pinned base."""
    if digest(data) != PATCHED_SHA256 or digest(SOURCE.read_bytes()) != SOURCE_SHA256:
        raise ValueError('Unexpected lifecycle archive/source identity; review and repin explicitly')
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError('Duplicate LSP4IJ entries')
        receipt = json.loads(archive.read(MARKER))
        if (receipt.get('patch') != PATCH or receipt.get('base_sha256') != BASE_SHA256
                or receipt.get('source_sha256') != SOURCE_SHA256):
            raise ValueError('Existing LSP lifecycle patch differs; prepare a fresh dependency copy')
        original = base64.b64decode(receipt['original_class_base64'], validate=True)
        replacement = archive.read(CLASS)
        if (digest(original) != CLASS_SHA256 or digest(replacement) != PATCHED_CLASS_SHA256
                or receipt['patched_class_sha256'] != PATCHED_CLASS_SHA256):
            raise ValueError('LSP lifecycle class identity mismatch')
    if rewrite(data, replacement) != data:
        raise ValueError('Unexpected LSP lifecycle ZIP layout or trailing bytes')
    if digest(rewrite(data, original, remove_marker=True)) != BASE_SHA256:
        raise ValueError('LSP lifecycle archive differs from the pinned base')
    return receipt


def patch(plugin_dir, platform_home, javac):
    plugin_dir = Path(plugin_dir).resolve(strict=True)
    raw_jar = plugin_dir / 'lib/lsp4ij-0.21.0.jar'
    jar = raw_jar.resolve(strict=True)
    if not any(jar.is_relative_to((ROOT / name).resolve()) for name in ('build', '.artifacts', '.tools', 'dist')):
        raise ValueError('Only a GoForge-owned build/.artifacts/.tools/dist copy may be patched')
    if raw_jar.is_symlink() or jar.parent != plugin_dir / 'lib' or jar.stat().st_nlink != 1:
        raise ValueError('Refusing a linked dependency JAR')
    data = jar.read_bytes()
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        if MARKER in archive.namelist():
            receipt = {k: v for k, v in validate_bytes(data).items() if k != 'original_class_base64'}
            return {**receipt, 'already_patched': True, 'jar_sha256': digest(data)}
        if digest(data) != BASE_SHA256 or digest(archive.read(CLASS)) != CLASS_SHA256:
            raise ValueError('Expected the exact metadata-patched LSP4IJ 0.21.0 archive')
        original = archive.read(CLASS)
    home = Path(platform_home).resolve(strict=True)
    identity = next((p for p in (home / 'product-info.json', home / 'Resources/product-info.json') if p.is_file()), None)
    if identity is None or json.loads(identity.read_text())['buildNumber'] != '252.28539.97':
        raise ValueError('Expected pinned Community platform 252.28539.97')
    with tempfile.TemporaryDirectory(prefix='.goforge-lifecycle-', dir=plugin_dir.parent) as temporary:
        work = Path(temporary)
        classpath = os.pathsep.join((str(home / 'lib/*'), str(plugin_dir / 'lib/*')))
        run(javac, '--release', '21', '-g:none', '-encoding', 'UTF-8', '-implicit:none', '-sourcepath', '',
            '-cp', classpath, '-d', work, SOURCE)
        classes = list(work.rglob('*.class'))
        if len(classes) != 1 or classes[0].relative_to(work).as_posix() != CLASS:
            raise ValueError('Expected exactly one replacement listener class')
        replacement = classes[0].read_bytes()
        if digest(SOURCE.read_bytes()) != SOURCE_SHA256 or digest(replacement) != PATCHED_CLASS_SHA256:
            raise ValueError('Compiled lifecycle replacement differs from the pinned reviewed source/output')
        receipt = {'patch': PATCH, 'base_sha256': BASE_SHA256,
                   'upstream_class_sha256': CLASS_SHA256, 'patched_class_sha256': digest(replacement),
                   'source_sha256': digest(SOURCE.read_bytes()), 'java_classes_modified': 1,
                   'license': 'EPL-2.0 OR Apache-2.0; upstream header retained',
                   # Retain the exact public original to reconstruct and hash the entire base.
                   'original_class_base64': base64.b64encode(original).decode()}
        patched = rewrite(data, replacement, receipt)
        validate_bytes(patched)
        target = work / 'lsp4ij.jar'
        target.write_bytes(patched)
        target.chmod(jar.stat().st_mode)
        os.replace(target, jar)
    public_receipt = {k: v for k, v in receipt.items() if k != 'original_class_base64'}
    (plugin_dir / 'goforge-lifecycle-patch.json').write_text(json.dumps(public_receipt, indent=2) + '\n')
    return {**public_receipt, 'already_patched': False, 'jar_sha256': digest(patched)}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--plugin-dir', type=Path, required=True)
    parser.add_argument('--platform-home', type=Path, required=True)
    parser.add_argument('--javac', type=Path, default=ROOT / '.tools/jdk/bin/javac')
    args = parser.parse_args()
    print(json.dumps(patch(args.plugin_dir, args.platform_home, args.javac), indent=2))
