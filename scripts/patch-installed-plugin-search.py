#!/usr/bin/env python3
"""Repair pinned Community plugin-search readiness and essential toggles in an owned copy.

Never operates on the downloaded SDK used by Gradle. See the accompanying public
source provenance, license, lifecycle design, and explicit verification limits.
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
SOURCES = ROOT / 'patches/intellij-252-installed-search'
PATCH = 'goforge-ic252-search-ready-essential-toggle-v2'
MARKER = 'META-INF/goforge-installed-search-patch.json'
HELPERS = {'dev/goforge/platform/InstalledSearchGate.class', 'dev/goforge/platform/EssentialPluginToggleGuard.class'}
INDEX = '__index__'
INDEX_SHA256 = 'e136f1b510da54600b3e4e9dca5611913685417ed2aabc7d0fcf194757a57f60'
OPTIMIZED_COMMENT_HEX = '04a5e9d50d'
UPSTREAM_JAR_SHA256 = 'f1791ff550fc4f42e495c1f5ab65779661d002b3c55dc4183209c207345c7bc5'
PREFIX = 'com/intellij/ide/plugins/PluginManagerConfigurable'
ORIGINAL = {
    PREFIX + '$13.class': '7f0a9fe3a9cca8652c78a332a8a7103202058a9b56827723779429b4adc59a2a',
    PREFIX + '$13$6.class': 'ce72ec3c914d971a6e64d217005d8631b5899f77f616113b8007157f75c02bfe',
    PREFIX + '$13$3.class': 'ce4cc983f94c0e80fe2ded1a0a1e701454bbaa65e60c701ba612aedfa335a6a9',
    'com/intellij/ide/ui/PluginOptionsTopHitProvider.class': '943a08d748d1575f48079015cbfcb53225ed91400b90543f23a79300bdae5855',
    'com/intellij/ide/ui/PluginBooleanOptionDescriptor.class': '553229973c907b40d76fd2c053fdb2e9488fb2687f6516ea193d786daf887a16',
    'com/intellij/ide/ui/PluginBooleanOptionDescriptor$1.class': '1d5a9e1542344ecce1158bbe5897ccbb66a730f870a097e247c0a07bf899fb95',
}
# These collaborators remain byte-for-byte unchanged. Check their ABI too.
COLLABORATORS = {
    PREFIX + '.class': 'efbdf03e9433f06daeb80116522030bdfe62fae9694bc33d54be85d02ed013dd',
    'com/intellij/ide/plugins/newui/SearchResultPanel.class': 'be38ca4aa2ccd9eeaeeeda0a01de0d2c24651a8d985b019918d512ab36735efd',
}


def digest(data):
    return hashlib.sha256(data).hexdigest()


def run(*args):
    completed = subprocess.run([str(x) for x in args], capture_output=True, text=True)
    if completed.returncode:
        raise RuntimeError('Patch compilation/verification failed:\n' + completed.stdout + completed.stderr)
    return completed.stdout


def patch(home, javac):
    home = Path(home).resolve(strict=True)
    allowed = [ROOT / p for p in ('build', '.artifacts', 'dist')]
    owned_dev = ROOT / '.tools/GoForge Development.app/Contents'
    if not (any(home.is_relative_to(p.resolve()) for p in allowed) or home == owned_dev.resolve()):
        raise ValueError('Only GoForge-owned build/.artifacts/dist or development-app copies are accepted')
    jar_path = home / 'lib/app-client.jar'
    if jar_path.is_symlink() or jar_path.resolve().parent != home / 'lib' or jar_path.stat().st_nlink != 1:
        raise ValueError('Refusing a linked platform JAR')
    identity = next((p for p in (home / 'product-info.json', home / 'Resources/product-info.json') if p.is_file()), None)
    if identity is None:
        raise ValueError('Platform product-info.json is missing')
    info = json.loads(identity.read_text())
    if info.get('buildNumber') != '252.28539.97':
        raise ValueError('Expected Community platform build 252.28539.97')
    source_hashes = {p.name: digest(p.read_bytes()) for p in sorted(SOURCES.iterdir()) if p.is_file()}
    source_hashes['patch-installed-plugin-search.py'] = digest(Path(__file__).read_bytes())
    with zipfile.ZipFile(jar_path) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError('Duplicate archive entries')
        for name, expected in COLLABORATORS.items():
            if digest(archive.read(name)) != expected:
                raise ValueError('Unexpected unchanged platform collaborator: ' + name)
        if MARKER in names:
            receipt = json.loads(archive.read(MARKER))
            if (receipt.get('patch') != PATCH or receipt.get('original_classes') != ORIGINAL
                    or receipt.get('upstream_jar_sha256') != UPSTREAM_JAR_SHA256
                    or receipt.get('patch_sources') != source_hashes
                    or set(receipt.get('patched_classes', {})) != set(ORIGINAL) | HELPERS):
                raise ValueError('Existing patch differs; rebuild from the pristine platform input')
            for name, expected in receipt['patched_classes'].items():
                if digest(archive.read(name)) != expected:
                    raise ValueError('Patched platform class was changed: ' + name)
            if INDEX in names or archive.comment:
                raise ValueError('Patched JAR must use its current standard central directory')
            return {**receipt, 'already_patched': True, 'jar_sha256': digest(jar_path.read_bytes())}
        if HELPERS.intersection(names):
            raise ValueError('Unexpected pre-existing patch helper')
        if digest(archive.read(INDEX)) != INDEX_SHA256 or archive.comment.hex() != OPTIMIZED_COMMENT_HEX:
            raise ValueError('Unexpected platform optimized ZIP index/comment')
        if any(n.upper().startswith('META-INF/') and n.upper().endswith(('.SF', '.RSA', '.DSA', '.EC')) for n in names):
            raise ValueError('Refusing to invalidate a signed JAR')
        originals = {}
        for name, expected in ORIGINAL.items():
            data = archive.read(name)
            if digest(data) != expected:
                raise ValueError('Unexpected upstream class SHA256: ' + name)
            originals[name] = data
        if digest(jar_path.read_bytes()) != UPSTREAM_JAR_SHA256:
            raise ValueError('Unexpected complete upstream app-client.jar SHA256')

    # All compilation/output is disposable and outside the SDK/download caches.
    with tempfile.TemporaryDirectory(prefix='.goforge-search-patch-', dir=home.parent) as temporary:
        work = Path(temporary)
        inputs, output, classes = work / 'input', work / 'output', work / 'compiler'
        for name, data in originals.items():
            target = inputs / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
        classes.mkdir()
        java = Path(javac).resolve().with_name('java.exe' if Path(javac).suffix.lower() == '.exe' else 'java')
        cp = str(home / 'lib/*')
        run(javac, '--release', '21', '-g:none', '-cp', cp, '-d', classes,
            SOURCES / 'InstalledSearchGate.java', SOURCES / 'EssentialPluginToggleGuard.java', SOURCES / 'PatchInstalledSearch.java')
        run(java, '-ea', '-cp', str(classes) + os.pathsep + cp, 'PatchInstalledSearch', inputs, output)
        shutil.copytree(classes / 'dev', output / 'dev')
        patched = {n: (output / n).read_bytes() for n in [*ORIGINAL, *sorted(HELPERS)]}
        receipt = {
            'patch': PATCH, 'platform_build': '252.28539.97',
            'upstream_jar_sha256': UPSTREAM_JAR_SHA256,
            'original_classes': ORIGINAL, 'unchanged_collaborators': COLLABORATORS,
            'patched_classes': {n: digest(b) for n, b in patched.items()},
            'patch_sources': source_hashes, 'license': 'Apache-2.0',
            'removed_generated_index': {INDEX: INDEX_SHA256},
            'removed_optimized_zip_comment_hex': OPTIMIZED_COMMENT_HEX,
            'zip_layout': 'standard central directory; original optimized offsets invalid after rewrite',
            'upstream_followup': 'https://github.com/JetBrains/intellij-community/commit/bf1d84fac9258410c0e3312e9ea905f14b76a825',
            'verification': 'ASM data-flow validation; search and essential-toggle native UI acceptance is separate',
        }
        target = work / 'app-client.jar'
        with zipfile.ZipFile(jar_path) as source, zipfile.ZipFile(target, 'w') as destination:
            # The original five-byte ZIP comment points to JetBrains' generated
            # index offsets. Both become invalid on any archive rewrite. An empty
            # comment selects the same loader's normal ZIP central-directory path.
            destination.comment = b''
            for entry in source.infolist():
                if entry.filename == INDEX:
                    continue
                destination.writestr(entry, patched.pop(entry.filename) if entry.filename in patched else source.read(entry.filename))
            for name, data in [*patched.items(), (MARKER, (json.dumps(receipt, indent=2) + '\n').encode())]:
                entry = zipfile.ZipInfo(name, (2026, 1, 1, 0, 0, 0))
                entry.compress_type = zipfile.ZIP_DEFLATED
                entry.external_attr = 0o100644 << 16
                destination.writestr(entry, data)
        target.chmod(jar_path.stat().st_mode)
        os.replace(target, jar_path)
    # macOS Contents root is reserved for bundle structure; ordinary resources
    # must live under Resources so strict codesign does not classify them as code.
    receipt_directory = home / 'Resources' if (home / 'Resources/product-info.json').is_file() else home
    (receipt_directory / 'goforge-installed-search-patch.json').write_text(json.dumps(receipt, indent=2) + '\n')
    return {**receipt, 'already_patched': False, 'jar_sha256': digest(jar_path.read_bytes())}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--platform-home', type=Path, required=True, help='Owned platform copy containing lib/ and product-info.json')
    parser.add_argument('--javac', type=Path, default=ROOT / '.tools/jdk/bin/javac')
    args = parser.parse_args()
    print(json.dumps(patch(args.platform_home, args.javac), indent=2))
