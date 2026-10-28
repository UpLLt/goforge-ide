#!/usr/bin/env python3
"""Install a pinned official Temurin JDK in this project, without global changes."""
from pathlib import Path
import hashlib
import json
import platform
import subprocess
import tarfile
import tempfile
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
TOOLS = ROOT / '.tools'
JDK = TOOLS / 'jdk'
RELEASE = 'jdk-25.0.5+8'
NAME = 'OpenJDK25U-jdk_aarch64_mac_hotspot_25.0.5_8.tar.gz'
URL = 'https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.5%2B8/' + NAME


def request(url):
    return urllib.request.urlopen(urllib.request.Request(url, headers={'User-Agent': 'GoForge-setup'}), timeout=60)


if platform.system() != 'Darwin' or platform.machine() != 'arm64':
    raise SystemExit('This bootstrap targets macOS arm64. Supply JAVA_HOME with JDK 25 on other systems.')
if JDK.exists() or JDK.is_symlink():
    java = JDK / 'bin/java'
    if not java.exists():
        raise SystemExit('Existing .tools/jdk is invalid; it was preserved for review.')
    subprocess.run([str(java), '-version'], check=True)
    raise SystemExit(0)

TOOLS.mkdir(exist_ok=True)
with tempfile.TemporaryDirectory(prefix='goforge-jdk-', dir=TOOLS) as tmp:
    archive = Path(tmp) / NAME
    print('Downloading pinned Temurin JDK 25.0.5+8...', flush=True)
    with request(URL) as source, archive.open('wb') as out:
        for chunk in iter(lambda: source.read(1024 * 1024), b''):
            out.write(chunk)
    with request(URL + '.sha256.txt') as response:
        expected = response.read().decode().split()[0]
    digest = hashlib.sha256()
    with archive.open('rb') as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b''):
            digest.update(chunk)
    if digest.hexdigest() != expected:
        raise SystemExit('JDK SHA-256 checksum mismatch; nothing installed.')
    with tarfile.open(archive) as container:
        names = {entry.name.split('/')[0] for entry in container.getmembers() if entry.name}
        if len(names) != 1:
            raise SystemExit('Unexpected archive layout.')
        directory = next(iter(names))
        destination = TOOLS / directory
        if destination.exists():
            raise SystemExit('Extraction directory already exists; preserved for review.')
        for entry in container.getmembers():
            if not (TOOLS / entry.name).resolve().is_relative_to(TOOLS.resolve()):
                raise SystemExit('Unsafe archive member path.')
        container.extractall(TOOLS)
    java_home = destination / 'Contents/Home'
    if not (java_home / 'bin/javac').is_file():
        raise SystemExit('Downloaded archive does not contain the expected JDK compiler.')
    JDK.symlink_to(java_home.relative_to(TOOLS))
    (ROOT / '.artifacts').mkdir(exist_ok=True)
    (ROOT / '.artifacts/jdk-download.json').write_text(json.dumps({
        'release': RELEASE, 'url': URL, 'sha256': expected, 'java_home': str(JDK),
    }, indent=2) + '\n')
print('Installed JDK in', JDK)
