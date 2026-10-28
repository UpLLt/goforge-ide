#!/usr/bin/env python3
"""Prepare pinned, unmodified upstream source assets for a GoForge binary release.

No Git checkout, global install, binary patch, or written source-offer commitment.
Upload these source assets alongside the matching binary, with their manifest.
"""
from __future__ import annotations
import argparse
import concurrent.futures
import hashlib
import json
from pathlib import Path, PurePosixPath
import tarfile
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
SOURCES = [
    ('JetBrainsRuntime','c24e7acbdf28174fbc92e115b34ce8e3e7569a0a',
     'https://codeload.github.com/JetBrains/JetBrainsRuntime/tar.gz/c24e7acbdf28174fbc92e115b34ce8e3e7569a0a',
     'jbr-21.0.9-b1038.78-source.tar.gz','GPL-2.0 with Classpath exception; see included per-file notices'),
    ('JCEF','33f6357c0226f73b0e398b75d8bd22f486e430ab',
     'https://codeload.github.com/JetBrains/jcef/tar.gz/33f6357c0226f73b0e398b75d8bd22f486e430ab',
     'jcef-33f6357c0226-source.tar.gz','BSD-3-Clause and included component notices'),
    ('CEF','d14e0517a1d326a3a8c89a385383814a9d0eaf80',
     'https://codeload.github.com/chromiumembedded/cef/tar.gz/d14e0517a1d326a3a8c89a385383814a9d0eaf80',
     'cef-122.1.9-d14e051-source.tar.gz','BSD-3-Clause and Chromium component notices'),
    ('Chromium FFmpeg','17525de887d54b970ffdd421a0879c1db1952307',
     'https://chromium.googlesource.com/chromium/third_party/ffmpeg/+archive/17525de887d54b970ffdd421a0879c1db1952307.tar.gz',
     'chromium-ffmpeg-17525de887d5-source.tar.gz','LGPL-2.1-or-later and included per-file notices'),
]


def sha(path):
    h=hashlib.sha256()
    with path.open('rb') as f:
        for b in iter(lambda:f.read(1024*1024),b''):h.update(b)
    return h.hexdigest()


def obtain(item,out,offline):
    name,revision,url,filename,license_name=item
    dest=out/filename
    if dest.is_symlink():raise ValueError('Source destination is a symlink: '+filename)
    if not dest.exists():
        if offline:raise FileNotFoundError('Missing offline source: '+filename)
        temp=out/(filename+'.part')
        if temp.exists():raise ValueError('Incomplete download exists: '+temp.name)
        try:
            request=urllib.request.Request(url,headers={'User-Agent':'GoForge-release-sources'})
            with urllib.request.urlopen(request,timeout=60) as response,temp.open('xb') as target:
                total=0
                while True:
                    block=response.read(1024*1024)
                    if not block:break
                    total+=len(block)
                    if total>1024*1024*1024:raise ValueError('Source exceeds one GiB limit')
                    target.write(block)
            temp.rename(dest)
        except Exception:
            temp.unlink(missing_ok=True)
            raise
    # Validate cached archives too; never trust an unrecorded pre-existing file.
    with tarfile.open(dest,'r:gz') as archive:
        names=[member.name for member in archive]
        if not names or any(PurePosixPath(n).is_absolute() or '..' in PurePosixPath(n).parts for n in names):
            raise ValueError('Unexpected source archive paths: '+filename)
        if not any(PurePosixPath(n).name in {'LICENSE','LICENSE.txt','COPYING.LGPLv2.1'} for n in names):
            raise ValueError('Expected source license absent: '+filename)
        if name!='Chromium FFmpeg' and not names[0].split('/')[0].endswith(revision):
            raise ValueError('Archive root does not match pinned commit: '+filename)
    return {'component':name,'revision':revision,'url':url,'file':filename,'sha256':sha(dest),
            'bytes':dest.stat().st_size,'license':license_name}


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--output',type=Path,default=ROOT/'.artifacts/release-sources')
    p.add_argument('--offline',action='store_true')
    args=p.parse_args();out=args.output.resolve();out.mkdir(parents=True,exist_ok=True)
    manifest=out/'source-assets-manifest.json'
    old=json.loads(manifest.read_text()) if manifest.exists() else None
    if old:
        for entry in old['assets']:
            path=out/entry['file']
            if path.is_symlink() or not path.is_file() or sha(path)!=entry['sha256']:
                raise ValueError('Existing source differs from recorded hash: '+entry['file'])
    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
        results=list(pool.map(lambda i:obtain(i,out,args.offline),SOURCES))
    data={'schema':1,'runtimeVersions':{'macOS':'21.0.9+10-b1038.78','Windows':'21.0.9+1-b1038.78'},
          'jcefVersion':'122.1.9.1060+g33f6357',
          'runtimeSourceFieldJcefGit':'4a605f44671a (older auxiliary SOURCE field; explicit JCEF_COMMIT_HASH is used for the runtime source asset)',
          'runtimeReleaseTag':'jbr-release-21.0.9b1038.78',
          'chromiumRevision':'880dbf29479c6152d5e4f08dfd3a96b30f919e56',
          'assets':results,
          'verification':'Pinned official HTTPS source archives; archive root and license presence checked. SHA-256 records downloaded bytes; no claim of upstream signature verification or reproducible binary rebuild.',
          'distribution':'Publish alongside matching GoForge binaries. Retain bundled legal notices, LSP4IJ patch and matching JDBC sources. This does not create a written source offer.',
          'scope':'JBR identity and explicit JCEF_COMMIT_HASH from runtime release file; CEF/Chromium identities from bundled framework and upstream inventory; FFmpeg revision from that Chromium DEPS. CEF archive is not the entire Chromium source tree.'}
    manifest.write_text(json.dumps(data,indent=2)+'\n')
    print(json.dumps({'manifest':manifest.name,'assets':results},indent=2))

if __name__=='__main__':main()
