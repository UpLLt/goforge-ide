#!/usr/bin/env python3
"""Prepare pinned extra tools for the local macOS arm64 GoForge assembly.

No global installs, npm lifecycle scripts, IDE bundle changes or permission changes.
Use --check for read-only/offline verification; --offline permits cache-only repair.
Corrupt existing files are never silently replaced: --repair backs them up first.
"""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import sys
import tarfile
import tempfile
import urllib.request
import uuid
import zipfile

NODE = {'version': '24.22.1',
 'url': 'https://nodejs.org/dist/v24.22.1/node-v24.22.1-darwin-arm64.tar.xz',
 'archiveSha256': '654ba79de6155647e22321932b45f2a1ccb705976cc337c9b8e278beb085a628',
 'files': {'bin/node': '6f32069480ec14d52b6d7b1f3c7124cfbbc46203abf5ddf5c65b127435f3f329',
           'LICENSE': '5888dbb9a1d2b18f2c3e6c5f6af1b39de658372b402a0577b002777f14c62ace'}}

CLI = {'version': '0.89.0',
 'url': 'https://registry.npmjs.org/@devcontainers/cli/-/cli-0.89.0.tgz',
 'integrity': 'sha512-LzaoOGKQ/Zql6PsiZ4hVIYVZagzWkD65aG/1ou5/Kly5Y1PtjLg1yn7qu+LZCzVoAl6DZZ/pbz8qOO4RLNlqMg==',
 'files': {'scripts/updateUID.Dockerfile': 'd77fd5cd5efdc70aadcf2e9006c2914526f4945b0c892fb62a7a8097e5f64b98',
           'devcontainer.js': 'e6df20af860208e00365d7b73f64d3940bad087ea8049de12223d1e7376522e3',
           'dist/spec-node/devContainersSpecCLI.js': '22b5b3a7345608f552db2f1af589ca06c7274d116778d00aa67cbfe1e1cafc65',
           'package.json': '6af5eeda6e4d9a2267a503c17816d74373f52005b1209dc92ab97249751c2971',
           'CHANGELOG.md': 'd340bd7f9e4d1e8850f351920469109ec1c19acf42d9b42b6c3a7956c90bcb3b',
           'README.md': '8f9e4c9179d11fb18217f174593a7d4ce55b3218c0622e4b6778b9011cbf5a27',
           'LICENSE.txt': '9dc5a42b169ffdf78a925772761436e8bf234deb779e0dc16a03decac0797497',
           'ThirdPartyNotices.txt': '6a083f9cb627586aa9cbd0c228d1e6bb28d31ae04419bce6b43745e8575fbf54'}}

JDBC = [{'artifact': 'sqlite-jdbc-3.53.4.0',
  'sourceUrl': 'https://repo.maven.apache.org/maven2/org/xerial/sqlite-jdbc/3.53.4.0/sqlite-jdbc-3.53.4.0-sources.jar',
  'sourceSha256': '6f3fca1257fd02891e9b17b296b4e888cdd6af6d3e077ff7e50991657d69ed46',
  'licenseFiles': ['sqlite-jdbc-3.53.4.0-META-INF_maven_org.xerial_sqlite-jdbc_LICENSE',
                   'sqlite-jdbc-3.53.4.0-META-INF_maven_org.xerial_sqlite-jdbc_LICENSE.zentus',
                   'sqlite-jdbc-3.53.4.0-sources-META-INF_maven_org.xerial_sqlite-jdbc_LICENSE',
                   'sqlite-jdbc-3.53.4.0-sources-META-INF_maven_org.xerial_sqlite-jdbc_LICENSE.zentus'],
  'licenseSha256': {'sqlite-jdbc-3.53.4.0-META-INF_maven_org.xerial_sqlite-jdbc_LICENSE': '3ddf9be5c28fe27dad143a5dc76eea25222ad1dd68934a047064e56ed2fa40c5',
                    'sqlite-jdbc-3.53.4.0-META-INF_maven_org.xerial_sqlite-jdbc_LICENSE.zentus': '89167dab92289c7e5e2b65b044f0856b703d05e5d5e35c3548e73d9c7d2f5048',
                    'sqlite-jdbc-3.53.4.0-sources-META-INF_maven_org.xerial_sqlite-jdbc_LICENSE': '3ddf9be5c28fe27dad143a5dc76eea25222ad1dd68934a047064e56ed2fa40c5',
                    'sqlite-jdbc-3.53.4.0-sources-META-INF_maven_org.xerial_sqlite-jdbc_LICENSE.zentus': '89167dab92289c7e5e2b65b044f0856b703d05e5d5e35c3548e73d9c7d2f5048'}},
 {'artifact': 'postgresql-42.7.14',
  'sourceUrl': 'https://repo.maven.apache.org/maven2/org/postgresql/postgresql/42.7.14/postgresql-42.7.14-sources.jar',
  'sourceSha256': 'c0cdfff01ebb10cc05f57d43bc16b9c9cad0c92bbd4946fe41a389cf57739193',
  'licenseFiles': ['postgresql-42.7.14-META-INF_LICENSE',
                   'postgresql-42.7.14-META-INF_licenses_com.ongres.scram_scram-client-3.2_META-INF_LICENSE',
                   'postgresql-42.7.14-META-INF_licenses_com.ongres.scram_scram-common-3.2_META-INF_LICENSE',
                   'postgresql-42.7.14-META-INF_licenses_com.ongres.stringprep_saslprep-2.2_META-INF_LICENSE',
                   'postgresql-42.7.14-META-INF_licenses_com.ongres.stringprep_stringprep-2.2_META-INF_LICENSE',
                   'postgresql-42.7.14-sources-META-INF_LICENSE'],
  'licenseSha256': {'postgresql-42.7.14-META-INF_LICENSE': '08bb580d3cb8f9a4a05f008466a294fdb019e0e177d03a035d46f1eb94077de5',
                    'postgresql-42.7.14-META-INF_licenses_com.ongres.scram_scram-client-3.2_META-INF_LICENSE': '7c89807167a03a92d2fbfc936aff4f87d8ce31434637381c89e38282c8c99198',
                    'postgresql-42.7.14-META-INF_licenses_com.ongres.scram_scram-common-3.2_META-INF_LICENSE': '7c89807167a03a92d2fbfc936aff4f87d8ce31434637381c89e38282c8c99198',
                    'postgresql-42.7.14-META-INF_licenses_com.ongres.stringprep_saslprep-2.2_META-INF_LICENSE': '5c49fc155ebccd206277e0d1f424aadbf365f0a9ccfb54d400d728a375d523f9',
                    'postgresql-42.7.14-META-INF_licenses_com.ongres.stringprep_stringprep-2.2_META-INF_LICENSE': '5c49fc155ebccd206277e0d1f424aadbf365f0a9ccfb54d400d728a375d523f9',
                    'postgresql-42.7.14-sources-META-INF_LICENSE': '5be1f67a4d62692cc2fc8b81a71088943fc4c1462bd76fd2f04bc0e965ca6d53'}},
 {'artifact': 'mariadb-java-client-3.5.11',
  'sourceUrl': 'https://repo.maven.apache.org/maven2/org/mariadb/jdbc/mariadb-java-client/3.5.11/mariadb-java-client-3.5.11-sources.jar',
  'sourceSha256': 'b0ded5b6f00b86d35f3682aa5fa4f39fd6d159c568aa1841705df981e6c4e04a',
  'licenseFiles': ['LICENSE'],
  'licenseSha256': {'LICENSE': 'c8cfa082f122323774057c2808eb7e796703bde9459fa04ff6f2c9cc25f74eff'},
  'licenseSource': 'https://raw.githubusercontent.com/mariadb-corporation/mariadb-connector-j/3.5.11/LICENSE'}]

JDBC_BINARIES = {'sqlite-jdbc-3.53.4.0': 'bcb1f51e36f940867e83342f9efbf5968ac44a6bef4d397bb4af7b17b45cd2fb',
 'postgresql-42.7.14': '73914527305a40cce504b0d3d90b23caf565136912d607ae8ae7c5895512332c',
 'mariadb-java-client-3.5.11': '42604178b9e57ec08ee0035079961516f086e455d0141f0b22c6eff29fb2fff4'}

def digest(path):
    checksum = hashlib.sha256()
    with path.open('rb') as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b''): checksum.update(chunk)
    return checksum.hexdigest()


def validate_tar(archive, prefix, expected, destination):
    """Extract only explicitly pinned regular files; no archive paths or symlinks are trusted."""
    with tarfile.open(archive) as source:
        for relative, expected_hash in expected.items():
            member = source.getmember(prefix + '/' + relative)
            if not member.isfile(): raise RuntimeError('Archive member is not a regular file: ' + relative)
            content = source.extractfile(member).read()
            if hashlib.sha256(content).hexdigest() != expected_hash: raise RuntimeError('Archive file checksum mismatch: ' + relative)
            target = destination / relative; target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(content); target.chmod(member.mode & 0o777)


class Preparer:
    def __init__(self, root, *, check=False, offline=False, repair=False):
        self.root = root.resolve()
        self.check = check
        self.offline = offline or check
        self.repair = repair
        self.cache = self.root / '.tools/download-cache/goforge-extra'
        self.actions = []
        self.downloads = 0
        self.backup = self.root / '.tools/backups' / ('extra-tools-' + uuid.uuid4().hex[:12])

    def owned(self, path):
        # The dedicated outputs must not redirect writes outside this checkout.
        if path.resolve() != path.absolute() or not path.absolute().is_relative_to(self.root):
            raise RuntimeError('Refusing symlinked or external preparation path: ' + str(path))
        return path

    def matches(self, path, expected):
        self.owned(path)
        return path.is_file() and digest(path) == expected

    def replace(self, target, content):
        self.owned(target)
        if target.is_file() and target.read_bytes() == content: return
        if self.check: raise RuntimeError('Missing or mismatched prepared file: ' + str(target))
        if target.exists():
            if not self.repair: raise RuntimeError('Existing file differs; use --repair to preserve a backup and restore: ' + str(target))
            backup = self.owned(self.backup / target.relative_to(self.root)); backup.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(target, backup); self.actions.append('backed up ' + str(target.relative_to(self.root)))
        target.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.NamedTemporaryFile(dir=target.parent, prefix='.goforge-prepare-', delete=False) as stream:
            temporary = Path(stream.name); stream.write(content)
        os.replace(temporary, target)

    def obtain(self, url, filename, expected, algorithm='sha256'):
        target = self.owned(self.cache / filename)
        def valid(path):
            if not path.is_file(): return False
            if algorithm == 'sha256': return digest(path) == expected
            actual = 'sha512-' + base64.b64encode(hashlib.sha512(path.read_bytes()).digest()).decode()
            return actual == expected
        if valid(target): return target
        if self.offline: raise RuntimeError('Verified download is not cached (offline): ' + filename)
        if target.exists() and not self.repair: raise RuntimeError('Cached checksum mismatch; use --repair: ' + str(target))
        self.cache.mkdir(parents=True, exist_ok=True)
        request = urllib.request.Request(url, headers={'User-Agent':'GoForge-local-extra-tools/1'})
        with tempfile.NamedTemporaryFile(dir=self.cache, delete=False) as stream:
            temporary = Path(stream.name)
            try:
                with urllib.request.urlopen(request, timeout=60) as response:
                    size = 0
                    while True:
                        chunk = response.read(1024 * 1024)
                        if not chunk: break
                        size += len(chunk)
                        if size > 200 * 1024 * 1024: raise RuntimeError('Unexpected oversized tool download')
                        stream.write(chunk)
                stream.flush()
                if not valid(temporary): raise RuntimeError('Official download checksum mismatch: ' + filename)
                if target.exists():
                    backup = self.owned(self.backup / target.relative_to(self.root)); backup.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copy2(target, backup)
                os.replace(temporary, target)
            finally:
                temporary.unlink(missing_ok=True)
        self.downloads += 1
        return target

    def tool_tree(self, destination, description, prefix, archive_name, *, exact=False):
        complete = all(self.matches(destination / name, checksum) for name, checksum in description['files'].items())
        if complete and exact:
            complete = {str(p.relative_to(destination)) for p in destination.rglob('*') if p.is_file()} == set(description['files'])
        if complete:
            self.actions.append('reused verified ' + str(destination.relative_to(self.root))); return
        if self.check: raise RuntimeError('Missing/mismatched tool files: ' + str(destination))
        if destination.exists() and not self.repair: raise RuntimeError('Incomplete or changed tool; --repair preserves it before restoring: ' + str(destination))
        # This only guards the tool directory being restored, never stops processes.
        processes = subprocess.run(['/bin/ps', '-axo', 'args='], capture_output=True, text=True, check=True).stdout
        if str(destination) + '/' in processes: raise RuntimeError('A process is using this tool directory; stop it before --repair: ' + str(destination))
        archive = self.obtain(description['url'], archive_name,
                              description.get('archiveSha256', description.get('integrity')),
                              'sha256' if 'archiveSha256' in description else 'sha512')
        self.owned(destination); destination.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='.goforge-prepare-', dir=destination.parent) as temporary:
            stage = Path(temporary) / 'content'; stage.mkdir()
            validate_tar(archive, prefix, description['files'], stage)
            if destination.exists():
                backup = self.owned(self.backup / destination.relative_to(self.root)); backup.parent.mkdir(parents=True, exist_ok=True)
                destination.rename(backup)
            stage.rename(destination)
        self.actions.append('restored ' + str(destination.relative_to(self.root)))

    def binary_jar(self, entry):
        name = entry['artifact'] + '.jar'; expected = JDBC_BINARIES[entry['artifact']]
        candidates = [self.root / '.artifacts/database-jdbc-libs' / name]
        candidates += list((self.root / '.gradle-user-home/caches/modules-2/files-2.1').glob('*/*/*/*/' + name))
        for candidate in candidates:
            if self.matches(candidate, expected): return candidate
        url = entry['sourceUrl'].replace('-sources.jar', '.jar')
        return self.obtain(url, name, expected)

    def jdbc(self):
        destination = self.root / '.artifacts/database-licenses'
        for entry in JDBC:
            name = entry['artifact']; source = destination / (name + '-sources.jar')
            if not self.matches(source, entry['sourceSha256']):
                cached = self.obtain(entry['sourceUrl'], source.name, entry['sourceSha256'])
                self.replace(source, cached.read_bytes())
            missing = [file for file, expected in entry['licenseSha256'].items() if not self.matches(destination / name / file, expected)]
            if missing and self.check: raise RuntimeError('Missing or mismatched JDBC notices: ' + ', '.join(missing))
            if missing:
                archives = [source]
                if any(file.startswith(name + '-META-INF_') for file in missing): archives.append(self.binary_jar(entry))
                for archive in archives:
                    with zipfile.ZipFile(archive) as contents:
                        for member in contents.namelist():
                            output_name = archive.stem + '-' + member.replace('/', '_')
                            if output_name not in missing: continue
                            content = contents.read(member)
                            if hashlib.sha256(content).hexdigest() != entry['licenseSha256'][output_name]: raise RuntimeError('Pinned notice mismatch: ' + output_name)
                            self.replace(destination / name / output_name, content)
                if 'licenseSource' in entry and 'LICENSE' in missing:
                    license_file = self.obtain(entry['licenseSource'], name+'-LICENSE.txt', entry['licenseSha256']['LICENSE'])
                    self.replace(destination / name / 'LICENSE', license_file.read_bytes())
            for file, expected in entry['licenseSha256'].items():
                if not self.matches(destination / name / file, expected): raise RuntimeError('JDBC notice not restored: ' + file)
        manifest = destination / 'manifest.json'
        if not manifest.exists() or json.loads(manifest.read_text()) != JDBC:
            self.replace(manifest, (json.dumps(JDBC, indent=2)+'\n').encode())
        self.actions.append('verified/restored pinned JDBC source archives and notices')

    def prepare(self):
        if sys.platform != 'darwin' or platform.machine() != 'arm64': raise RuntimeError('This preparation target is macOS arm64.')
        node = self.root / '.tools/node-runtime'
        cli = self.root / '.tools/devcontainer-cli/node_modules/@devcontainers/cli'
        self.tool_tree(node, NODE, 'node-v24.22.1-darwin-arm64', 'node-v24.22.1-darwin-arm64.tar.xz')
        self.tool_tree(cli, CLI, 'package', 'devcontainers-cli-0.89.0.tgz', exact=True)
        self.jdbc()
        for command, expected in [([node/'bin/node','--version'],'v24.22.1'),
                                  ([node/'bin/node',cli/'dist/spec-node/devContainersSpecCLI.js','--version'],'0.89.0')]:
            output = subprocess.run(list(map(str,command)),capture_output=True,text=True,check=True,timeout=30).stdout.strip()
            if output != expected: raise RuntimeError('Prepared tool returned an unexpected version.')
        report = {'passed':True,'root':str(self.root),'nodeVersion':NODE['version'],'cliVersion':CLI['version'],
                  'downloads':self.downloads,'actions':self.actions,'readOnlyCheck':self.check,
                  'nodeArchiveSha256':NODE['archiveSha256'],'cliIntegrity':CLI['integrity'],
                  'jdbcManifestSha256':digest(self.root/'.artifacts/database-licenses/manifest.json'),
                  'scope':'fixed local build inputs; no global installs, IDE launch, account changes, or OS policy changes'}
        if not self.check:
            target=self.root/'.tools/extra-tools-manifest.json'; self.owned(target)
            target.parent.mkdir(parents=True,exist_ok=True); target.write_text(json.dumps(report,indent=2)+'\n')
        return report


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check',action='store_true',help='Read-only/offline verification of all prepared inputs.')
    parser.add_argument('--offline',action='store_true',help='Reuse installed files or verified download cache only.')
    parser.add_argument('--repair',action='store_true',help='Back up changed dedicated tool files before restoring pinned inputs.')
    args=parser.parse_args()
    if args.check and args.repair: parser.error('--check cannot be combined with --repair')
    result=Preparer(Path(__file__).resolve().parents[1],check=args.check,offline=args.offline,repair=args.repair).prepare()
    print(json.dumps(result,indent=2))

if __name__=='__main__':
    try: main()
    except Exception as error:
        print('Extra tool preparation failed: '+str(error),file=sys.stderr);raise SystemExit(1)
