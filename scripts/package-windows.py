#!/usr/bin/env python3
"""Assemble a Windows x64 GoForge ZIP from pinned public Community binaries.

May run on macOS/Linux for cross-assembly. Windows runtime acceptance is a
separate mandatory check: scripts/verify-windows-package.py. Never represents
cross-assembly, a PE parse, or unchanged third-party signatures as runtime proof.
"""
from __future__ import annotations

import argparse
import concurrent.futures
import hashlib
import importlib.util
import json
import os
from pathlib import Path, PurePosixPath, PureWindowsPath
import shutil
import stat
import struct
import subprocess
import sys
import tempfile
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'packaging/windows'
COMMON = ROOT / 'packaging/macos'
OUTPUT = ROOT / 'build/windows'
CACHE = ROOT / '.tools/windows-downloads'
APP = OUTPUT / 'GoForge'
INPUTS = json.loads((ASSETS / 'inputs.json').read_text())
SELECTOR = 'GoForge2026.1'


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, ROOT / 'scripts' / filename)
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


common = module('goforge_macos_packaging_helpers', 'package-macos.py')
extra = module('goforge_extra_tools', 'setup-extra-tools.py')
digest = common.digest
run = common.run


def safe_extract(archive: Path, target: Path):
    """Reject traversal, NTFS streams, links and case aliases before extraction."""
    seen = set()
    with zipfile.ZipFile(archive) as z:
        for member in z.infolist():
            name = member.filename
            path = PurePosixPath(name)
            if ('\\' in name or ':' in name or path.is_absolute() or '..' in path.parts
                    or PureWindowsPath(name).drive or stat.S_ISLNK(member.external_attr >> 16)
                    or any(p.endswith((' ', '.')) for p in path.parts)):
                raise ValueError('Unsafe Windows archive entry: ' + name)
            folded = name.rstrip('/').casefold()
            if folded in seen: raise ValueError('Duplicate Windows archive entry: ' + name)
            seen.add(folded)
        z.extractall(target)


def obtain(name: str, offline=False) -> Path:
    item = INPUTS[name]
    path = CACHE / item['url'].rsplit('/', 1)[1]
    if path.is_file():
        if digest(path) != item['sha256']: raise RuntimeError('Cached checksum mismatch: ' + str(path))
        return path
    if offline: raise RuntimeError('Verified input is not cached: ' + path.name)
    CACHE.mkdir(parents=True, exist_ok=True)
    partial = path.with_suffix(path.suffix + '.part')
    try:
        with urllib.request.urlopen(item['url'], timeout=60) as src, partial.open('wb') as dest:
            shutil.copyfileobj(src, dest, 1024 * 1024)
        if digest(partial) != item['sha256']: raise RuntimeError('Downloaded checksum mismatch: ' + name)
        partial.replace(path)
    finally:
        partial.unlink(missing_ok=True)
    return path


def pe_identity(path: Path) -> dict:
    data = path.read_bytes()
    if data[:2] != b'MZ' or len(data) < 64: raise ValueError('Not a Windows PE: ' + str(path))
    offset = struct.unpack_from('<I', data, 0x3c)[0]
    if data[offset:offset + 4] != b'PE\0\0': raise ValueError('Missing PE signature')
    machine = struct.unpack_from('<H', data, offset + 4)[0]
    if machine != 0x8664: raise ValueError('Expected Windows AMD64 PE: ' + str(path))
    if struct.unpack_from('<H', data, offset + 24)[0] != 0x20b: raise ValueError('Expected PE32+')
    return {'machine': 'AMD64', 'format': 'PE32+', 'sha256': digest(path), 'bytes': len(data)}


def version_resource() -> bytes:
    """Original Win32 VS_VERSION_INFO, not a modified upstream identity."""
    def block(key, value=b'', children=(), text=False, value_length=None):
        body = struct.pack('<HHH', 0, len(value)//2 if text else len(value)
                           if value_length is None else value_length, int(text)) + (key+'\0').encode('utf-16le')
        body += b'\0' * (-len(body) % 4) + value
        for child in children:
            body += b'\0' * (-len(body) % 4) + child
        return struct.pack('<H', len(body)) + body[2:]
    values = {'CompanyName': 'GoForge contributors', 'FileDescription': 'GoForge',
              'FileVersion': '0.1.0.0', 'InternalName': 'goforge64',
              'OriginalFilename': 'goforge64.exe', 'ProductName': 'GoForge',
              'ProductVersion': '0.1.0 Development', 'LegalCopyright': 'GoForge contributors; upstream notices included'}
    strings = [block(k, (v+'\0').encode('utf-16le'), text=True) for k,v in values.items()]
    string_info = block('StringFileInfo', children=[block('040904b0', children=strings, text=True)], text=True)
    var_info = block('VarFileInfo', children=[block('Translation', struct.pack('<HH', 0x409, 1200))], text=True)
    fixed = struct.pack('<13I', 0xfeef04bd, 0x10000, 0x00000001, 0, 0x00000001, 0,
                        0x3f, 0, 0x40004, 1, 0, 0, 0)
    return block('VS_VERSION_INFO', fixed, [string_info, var_info])


def brand_launcher(source: Path, target: Path, icon_file: Path) -> dict:
    import lief
    from PIL import Image, ImageDraw
    if lief.__version__.split('-')[0] != '0.17.6': raise RuntimeError('Use packaging/windows/requirements.txt')
    image = Image.new('RGBA', (256, 256))
    draw = ImageDraw.Draw(image)
    draw.rounded_rectangle((8,8,248,248), radius=56, fill='#102B3F')
    # Same original paths as packaging/macos/goforge.svg, rendered without fonts.
    for points, color in [([(33,36),(68,36),(68,48),(45,48),(45,80),(57,80),(57,67),(51,67),(51,56),(70,56),(70,92),(33,92)], '#57D6CE'),
                          ([(78,36),(104,36),(104,48),(90,48),(90,58),(101,58),(101,70),(90,70),(90,92),(78,92)], '#FFFFFF')]:
        draw.polygon([(x*2,y*2) for x,y in points], fill=color)
    image.save(icon_file, format='ICO', sizes=[(16,16),(24,24),(32,32),(48,48),(64,64),(128,128),(256,256)])
    binary = lief.PE.parse(str(source))
    if binary is None or not binary.has_resources: raise RuntimeError('Official launcher resources missing')
    code_before = {s.name: bytes(s.content) for s in binary.sections if int(s.characteristics) & 0x20000000}
    root = binary.resources
    def replace_type(kind, entries):
        root.delete_child(kind)
        node = lief.PE.ResourceDirectory(); node.id = kind
        for ident, content in entries:
            item = lief.PE.ResourceDirectory(); item.id = ident
            value = lief.PE.ResourceData(list(content)); value.id = 0x409; value.code_page = 0
            item.add_child(value); node.add_child(item)
        root.add_child(node)
    ico = icon_file.read_bytes(); count = struct.unpack_from('<H', ico, 4)[0]
    group = struct.pack('<HHH', 0, 1, count); icons = []
    for i in range(count):
        entry = ico[6+16*i:22+16*i]
        size, offset = struct.unpack_from('<II', entry, 8)
        icons.append((i+1, ico[offset:offset+size]))
        group += entry[:12] + struct.pack('<H', i+1)
    group_ids = [item.id for kind in root.childs if kind.id == 14 for item in kind.childs] or [1]
    replace_type(3, icons); replace_type(14, [(ident, group) for ident in group_ids]); replace_type(16, [(1, version_resource())])
    # Keep the upstream Windows compatibility/DPI/long-path manifest, change only its identity.
    manifest_nodes = [n for n in root.childs if n.id == 24]
    for kind in manifest_nodes:
        for ident in kind.childs:
            for leaf in ident.childs:
                raw = bytes(leaf.content)
                import re
                raw = re.sub(rb'(<assemblyIdentity\b[^>]*\bname=")[^"]+("[^>]*>)', rb'\1dev.goforge.ide\2', raw)
                leaf.content = raw
    cert = binary.data_directory(lief.PE.DataDirectory.TYPES.CERTIFICATE_TABLE)
    cert.rva = 0; cert.size = 0  # Never retain a now-invalid upstream Authenticode claim.
    config = lief.PE.Builder.config_t(); config.resources = True; config.overlay = False
    binary.write(str(target), config)
    check = lief.PE.parse(str(target))
    if check.has_signatures: raise RuntimeError('Changed launcher must be recorded unsigned')
    if code_before != {s.name: bytes(s.content) for s in check.sections if int(s.characteristics) & 0x20000000}:
        raise RuntimeError('Resource branding unexpectedly modified executable code')
    version_bytes = b''.join(bytes(v.content) for n in check.resources.childs if n.id == 16 for k in n.childs for v in k.childs)
    if 'GoForge'.encode('utf-16le') not in version_bytes or 'IntelliJ'.encode('utf-16le') in version_bytes:
        raise RuntimeError('Launcher branding did not round-trip')
    return {**pe_identity(target), 'sourceSha256': digest(source), 'executableSectionsUnchanged': True,
            'resourceEditor': 'LIEF 0.17.6', 'authenticode': 'unsigned; upstream signature removed after resource changes',
            'iconSha256': digest(icon_file)}


def configure(home: Path, retained: list[str]):
    product = json.loads((home/'product-info.json').read_text())
    if product['buildNumber'] != INPUTS['platform']['build'] or product['productCode'] != 'IC':
        raise RuntimeError('Wrong Community platform')
    launch = product['launch'][0]
    if launch['os'] != 'Windows' or launch['arch'] not in ('amd64','x86_64'): raise RuntimeError('Wrong platform target')
    old_options = home/launch['vmOptionsFilePath']
    shutil.copy2(old_options, home/'bin/goforge64.exe.vmoptions')
    product.update(name='GoForge', version='0.1.0', productCode='GF', productVendor='GoForge',
                   envVarBaseName='GOFORGE', dataDirectoryName=SELECTOR, svgIconPath='bin/goforge.svg')
    launch.update(launcherPath='bin/goforge64.exe', vmOptionsFilePath='bin/goforge64.exe.vmoptions')
    launch['bootClassPathJarNames'].insert(0, 'goforge-branding.jar')
    args = [a for a in launch['additionalJvmArguments'] if not a.startswith(
        ('-Didea.vendor.name=','-Didea.paths.selector=','-Didea.platform.prefix=','-Dsplash='))]
    args += ['-Didea.vendor.name=GoForge', '-Didea.paths.selector='+SELECTOR, '-Didea.platform.prefix=Idea',
             '-Dintellij.platform.load.app.info.from.resources=true', '-Didea.disable.collect.statistics=true',
             '-Didea.suppress.statistics.report=true', '-Didea.headless.enable.statistics=false',
             '-Dide.no.platform.update=true', '-Didea.initially.ask.config=never', '-Dsplash=false',
             '-XX:ErrorFile=%IDE_CACHE_DIR%/java_error_in_goforge_%p.log', '-XX:HeapDumpPath=%IDE_CACHE_DIR%']
    # The native Windows launcher restores APPDATA/LOCALAPPDATA from KnownFolders;
    # normal defaults then use these locations with the configured vendor/selector.
    launch['additionalJvmArguments'] = args
    launch['customCommands'] = [{'commands':['goforge-package-info'], 'vmOptionsFilePath':launch['vmOptionsFilePath'],
        'bootClassPathJarNames':launch['bootClassPathJarNames'], 'additionalJvmArguments':args+['-Djava.awt.headless=true'],
        'mainClass':'dev.goforge.packaging.WindowsPackageProbe'}]
    product['layout'] = [v for v in product.get('layout',[]) if all(not p.startswith('plugins/') or p.split('/')[1] in retained for p in v.get('classPath',[]))]
    (home/'product-info.json').write_text(json.dumps(product,indent=2)+'\n')


def add_branding(home: Path, javac: Path, work: Path):
    classes = work/'classes'; classes.mkdir()
    run(javac, '--release','21','-cp',str(home/'lib/*'),'-d',classes,ASSETS/'WindowsPackageProbe.java')
    with zipfile.ZipFile(home/'lib/goforge-branding.jar','w',zipfile.ZIP_DEFLATED) as z:
        for path in classes.rglob('*.class'): z.write(path,path.relative_to(classes).as_posix())
        for name, source in [('idea/IdeaApplicationInfo.xml','GoForgeApplicationInfo.xml'),('goforge.svg','goforge.svg'),('consents-GoForge.json','consents-GoForge.json')]:
            z.writestr(name,(COMMON/source).read_bytes())
    shutil.copy2(COMMON/'goforge.svg',home/'bin/goforge.svg')


def build_tools(host_go: Path, dest: Path, offline=False) -> dict:
    if 'go1.26.4 ' not in run(host_go,'version'): raise RuntimeError('Build toolchain must be Go 1.26.4')
    workspace=ROOT/'.tools/windows-cross'
    env = {**os.environ, 'GOOS':'windows','GOARCH':'amd64','CGO_ENABLED':'0','GOENV':'off','GOTOOLCHAIN':'local','GOFLAGS':'',
           'GOPATH':str(workspace),'GOBIN':'','GOWORK':'off','GOMODCACHE':run(host_go,'env','GOMODCACHE').strip()}
    if offline:
        # Versioned go install also asks for cached deprecation metadata. A local
        # file proxy can serve it; GOPROXY=off rejects that lookup unconditionally.
        # No direct/network fallback or inherited private-module bypass is allowed.
        proxy=Path(env['GOMODCACHE'])/'cache/download'
        if not proxy.is_dir(): raise RuntimeError('Offline Go module download cache is missing')
        env.update(GOPROXY=proxy.resolve().as_uri(),GOSUMDB='off',GOPRIVATE='',GONOPROXY='',GONOSUMDB='',GOVCS='*:off')
    native_target=run(host_go,'env','GOHOSTOS','GOHOSTARCH').splitlines()==['windows','amd64']
    result = {}
    for name in ('gopls','dlv'):
        item = INPUTS[name]
        info = json.loads(run(host_go,'mod','download','-json',item['module']+'@'+item['version'],env=env))
        install=item['module']+('' if item['package']=='.' else item['package'][1:])+'@'+item['version']
        # Version-suffixed install preserves module build identity; building from
        # its downloaded directory would falsely label gopls as (devel).
        run(host_go,'install','-trimpath','-buildvcs=false','-mod=readonly',install,env=env,timeout=600)
        target=workspace/'bin'/('' if native_target else 'windows_amd64')/(name+'.exe')
        shutil.copy2(target,dest/(name+'.exe'))
        result[name] = {**pe_identity(dest/(name+'.exe')), 'module':item['module'], 'version':item['version'], 'sum':info['Sum']}
    return result


def structure(home: Path) -> dict:
    product=json.loads((home/'product-info.json').read_text()); launch=product['launch'][0]
    binaries = {p:pe_identity(home/p) for p in ['bin/goforge64.exe','jbr/bin/java.exe','jbr/bin/server/jvm.dll',
        'goforge-tools/go/bin/go.exe','goforge-tools/bin/gopls.exe','goforge-tools/bin/dlv.exe','goforge-tools/bin/node.exe']}
    for jar in launch['bootClassPathJarNames']:
        if not (home/'lib'/jar).is_file(): raise RuntimeError('Missing boot classpath jar: '+jar)
    for p in [launch['vmOptionsFilePath'],'LICENSE.txt','NOTICE.txt','goforge-tools/go/LICENSE',
              'plugins/goforge/lib','plugins/lsp4ij/lib','third-party/jdbc/manifest.json']:
        if not (home/p).exists(): raise RuntimeError('Missing package component: '+p)
    if product['name']!='GoForge' or product['productVendor']!='GoForge': raise RuntimeError('Product identity mismatch')
    return {'passed':True,'peBinaries':binaries,'nativeRuntimeAcceptance':'not_run_by_cross_assembly'}


def package(plugin_zip: Path, host_go: Path, javac: Path, archive=False, offline=False) -> dict:
    plugin_hash=digest(plugin_zip)
    OUTPUT.mkdir(parents=True,exist_ok=True)
    if APP.exists() and not (APP/'goforge-build-inputs.json').is_file(): raise RuntimeError('Refusing to replace unowned directory')
    with concurrent.futures.ThreadPoolExecutor(4) as pool:
        archives=dict(zip(('platform','go','node','lsp4ij'),pool.map(lambda n:obtain(n,offline),('platform','go','node','lsp4ij'))))
    prep=extra.Preparer(ROOT,offline=offline)
    cli=ROOT/'.tools/devcontainer-cli/node_modules/@devcontainers/cli'
    prep.tool_tree(cli,extra.CLI,'package','devcontainers-cli-0.89.0.tgz',exact=True); prep.jdbc()
    stage=OUTPUT/'.staging-GoForge'
    if stage.exists(): shutil.rmtree(stage)
    stage.mkdir()
    with tempfile.TemporaryDirectory(prefix='windows-assembly-',dir=OUTPUT) as tmp:
        work=Path(tmp); extracted=work/'platform'; safe_extract(archives['platform'],extracted)
        descriptors=list(extracted.glob('product-info.json'))+list(extracted.glob('*/product-info.json'))
        if len(descriptors)!=1: raise RuntimeError('Unexpected official ZIP root')
        shutil.copytree(descriptors[0].parent,stage,dirs_exist_ok=True)
        allow=json.loads((COMMON/'bundled-plugins.json').read_text())['plugins']
        for name in allow:
            if not (stage/'plugins'/name).is_dir(): raise RuntimeError('Missing Community plugin: '+name)
        for p in (stage/'plugins').iterdir():
            if p.name not in allow:
                if p.is_dir(): shutil.rmtree(p)
                else:p.unlink()
        safe_extract(plugin_zip,stage/'plugins');safe_extract(archives['lsp4ij'],stage/'plugins')
        patch=run(sys.executable,ROOT/'scripts/patch-lsp4ij-metadata.py','--plugin-dir',stage/'plugins/lsp4ij')
        platform_search_patch=json.loads(run(sys.executable,ROOT/'scripts/patch-installed-plugin-search.py',
                                             '--platform-home',stage,'--javac',javac))
        textmate=common.prepare_lsp_textmate(stage/'plugins/lsp4ij')
        # Resource preparation records the metadata-only input; the final JAR
        # identity is recorded independently by the lifecycle patch receipt.
        lifecycle=json.loads(run(sys.executable,ROOT/'scripts/patch-lsp4ij-lifecycle.py',
                                 '--plugin-dir',stage/'plugins/lsp4ij','--platform-home',stage,'--javac',javac))
        tools=stage/'goforge-tools';tools.mkdir();safe_extract(archives['go'],tools)
        (tools/'bin').mkdir();info=build_tools(host_go,tools/'bin',offline)
        node_dir=work/'node';safe_extract(archives['node'],node_dir)
        node=next(node_dir.glob('*/node.exe'));shutil.copy2(node,tools/'bin/node.exe')
        shutil.copytree(cli,tools/'devcontainer-cli')
        notices=stage/'third-party';notices.mkdir(exist_ok=True)
        shutil.copy2(node.parent/'LICENSE',notices/'Node-LICENSE.txt')
        shutil.copytree(ROOT/'.artifacts/database-licenses',notices/'jdbc')
        for name in ('gopls','dlv'):
            common.module_licenses(host_go,tools/'bin'/(name+'.exe'),notices/'go-modules')
        shutil.copytree(ROOT/'patches/lsp4ij-0.21.0',notices/'lsp4ij-0.21.0')
        shutil.copy2(ROOT/'scripts/patch-lsp4ij-metadata.py',notices/'lsp4ij-0.21.0/patch-lsp4ij-metadata.py')
        shutil.copy2(ROOT/'scripts/patch-lsp4ij-lifecycle.py',notices/'lsp4ij-0.21.0/patch-lsp4ij-lifecycle.py')
        shutil.copytree(ROOT/'patches/intellij-252-installed-search',notices/'intellij-252-installed-search')
        shutil.copy2(ROOT/'scripts/patch-installed-plugin-search.py',notices/'intellij-252-installed-search/patch-installed-plugin-search.py')
        # Keep GoForge's grant separate from the original Community/runtime notices.
        shutil.copy2(ROOT/'LICENSE',stage/'GoForge-LICENSE.txt')
        shutil.copy2(ROOT/'NOTICE',stage/'GoForge-NOTICE.txt')
        shutil.copy2(ASSETS/'THIRD-PARTY-NOTICES.md',stage/'THIRD-PARTY-NOTICES.md')
        shutil.copy2(COMMON/'bundled-plugins.json',stage/'bundled-plugins.json')
        shutil.copy2(ASSETS/'README.txt',stage/'README.txt')
        original=json.loads((stage/'product-info.json').read_text())
        original_launcher=stage/original['launch'][0]['launcherPath']
        launcher=brand_launcher(original_launcher,stage/'bin/goforge64.exe',stage/'bin/goforge.ico')
        # Remove alternative upstream launch paths to prevent inconsistent identity/config.
        for p in [original_launcher,stage/'bin/idea.bat',stage/'bin/idea64.exe.vmoptions']:
            if p.name.endswith('.vmoptions'): continue  # configure reads the original first
            p.unlink(missing_ok=True)
        add_branding(stage,javac,work);configure(stage,allow)
        (stage/'bin/idea64.exe.vmoptions').unlink(missing_ok=True)
        build={'product':'GoForge','platformVersion':INPUTS['platform']['version'],'platformBuild':INPUTS['platform']['build'],
               'pluginZipSha256':plugin_hash,'inputs':INPUTS,'launcher':launcher,'tools':info,'lspTextMate':textmate,
               'lspLifecyclePatch':lifecycle,
               'installedPluginSearchPatch':platform_search_patch,
               'packagerSha256':digest(Path(__file__)),'target':'windows-x64','authenticode':'unsigned derivative launcher',
               'thirdPartyNoticesSha256':digest(stage/'THIRD-PARTY-NOTICES.md'),
               'fullPlatformSourceBuild':False,'noAccountRequired':True}
        (stage/'goforge-build-inputs.json').write_text(json.dumps(build,indent=2)+'\n')
        result={'target':'windows-x64','application':str(APP),'pluginZipSha256':plugin_hash,'pluginZipBytes':plugin_zip.stat().st_size,
                'structure':structure(stage),'launcher':launcher,'jbrRelease':(stage/'jbr/release').read_text(),
                'platformInputSha256':digest(archives['platform']),'minimumTargetOS':'Windows 10/11 x64; native verification required',
                'nativeRuntime':{'status':'pending','command':'python scripts/verify-windows-package.py --app build/windows/GoForge'},
                'buildInputsSha256':digest(stage/'goforge-build-inputs.json'),'limitations':['No GoForge Authenticode certificate or MSI installer','Cross-assembly is not Windows runtime or UI acceptance']}
        result['lspLifecyclePatch']=lifecycle
    if digest(plugin_zip)!=plugin_hash: raise RuntimeError('Plugin ZIP changed during assembly')
    if APP.exists(): shutil.rmtree(APP)
    stage.rename(APP)
    if archive:
        path=OUTPUT/'GoForge-0.1.0-dev-windows-x64.zip'
        with zipfile.ZipFile(path,'w',zipfile.ZIP_DEFLATED,compresslevel=6) as z:
            for file in sorted(APP.rglob('*')):
                if file.is_file(): z.write(file,'GoForge/'+file.relative_to(APP).as_posix())
        with tempfile.TemporaryDirectory(prefix='archive check 中文 ',dir=OUTPUT) as temp:
            safe_extract(path,Path(temp));result['archiveStructure']=structure(Path(temp)/'GoForge')
        result['archive']={'path':str(path),'bytes':path.stat().st_size,'sha256':digest(path)}
    (OUTPUT/'package-manifest.json').write_text(json.dumps(result,indent=2)+'\n')
    print(json.dumps(result,indent=2));return result


def executable(name, default):
    p=Path(default)
    return p if p.exists() else Path(shutil.which(name) or default)


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--plugin-zip',type=Path,default=ROOT/'build/distributions/goforge-0.1.0-dev.zip')
    parser.add_argument('--go',type=Path,default=executable('go',ROOT/'.tools/bin/go'))
    parser.add_argument('--javac',type=Path,default=executable('javac',ROOT/'.tools/jdk/bin/javac'))
    parser.add_argument('--archive',action='store_true');parser.add_argument('--offline',action='store_true')
    args=parser.parse_args();package(args.plugin_zip.resolve(),args.go.resolve(),args.javac.resolve(),args.archive,args.offline)
