#!/usr/bin/env python3
"""Windows package safety/branding checks; these are not Windows runtime tests."""
import importlib.util
import json
from pathlib import Path
import stat
import tempfile
import unittest
from unittest import mock
import zipfile

ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('windows_packager',ROOT/'scripts/package-windows.py')
pack=importlib.util.module_from_spec(spec);spec.loader.exec_module(pack)


class WindowsPackageTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.root=Path(self.temp.name)

    def tearDown(self):self.temp.cleanup()

    def archive(self,names):
        file=self.root/'input.zip'
        with zipfile.ZipFile(file,'w') as z:
            for name in names:z.writestr(name,b'content')
        return file

    def test_traversal_and_windows_path_aliases_are_rejected_before_any_write(self):
        for unsafe in ('../x','C:/x','foo\\x','x:stream','foo./file','/absolute'):
            with self.subTest(unsafe=unsafe):
                target=self.root/'out'
                with self.assertRaises(ValueError):pack.safe_extract(self.archive(['safe',unsafe]),target)
                self.assertFalse(target.exists())

    def test_case_insensitive_duplicate_is_rejected(self):
        with self.assertRaises(ValueError):pack.safe_extract(self.archive(['bin/Go.exe','bin/go.exe']),self.root/'out')
        self.assertFalse((self.root/'out').exists())

    def test_symlink_entry_is_rejected(self):
        file=self.root/'input.zip'
        with zipfile.ZipFile(file,'w') as z:
            entry=zipfile.ZipInfo('link');entry.external_attr=(stat.S_IFLNK|0o777)<<16;z.writestr(entry,'outside')
        with self.assertRaises(ValueError):pack.safe_extract(file,self.root/'out')

    def test_regular_archive_preserves_unicode_space_and_bytes(self):
        pack.safe_extract(self.archive(['GoForge/a b/中文.txt']),self.root/'out')
        self.assertEqual(b'content',(self.root/'out/GoForge/a b/中文.txt').read_bytes())

    def test_product_metadata_uses_windows_macros_and_independent_vendor(self):
        home=self.root/'GoForge';(home/'bin').mkdir(parents=True)
        (home/'bin/idea64.exe.vmoptions').write_text('-Xmx2048m\n')
        product={'name':'IntelliJ IDEA Community Edition','productCode':'IC','buildNumber':'252.28539.97',
                 'launch':[{'os':'Windows','arch':'amd64','launcherPath':'bin/idea64.exe','mainClass':'com.intellij.idea.Main',
                            'vmOptionsFilePath':'bin/idea64.exe.vmoptions','bootClassPathJarNames':['platform-loader.jar'],
                            'additionalJvmArguments':['-Didea.paths.selector=IdeaIC2025.2','-Didea.vendor.name=JetBrains']}],
                 'layout':[{'classPath':['plugins/java/lib/java.jar']},{'classPath':['plugins/unwanted/lib/x.jar']}]}
        (home/'product-info.json').write_text(json.dumps(product));pack.configure(home,['java'])
        result=json.loads((home/'product-info.json').read_text());launch=result['launch'][0]
        self.assertEqual('GoForge',result['productVendor']);self.assertEqual('bin/goforge64.exe',launch['launcherPath'])
        self.assertEqual('goforge-branding.jar',launch['bootClassPathJarNames'][0])
        self.assertEqual(1,len(result['layout']))
        self.assertIn('-Didea.platform.prefix=Idea',launch['additionalJvmArguments'])
        self.assertIn('-XX:HeapDumpPath=%IDE_CACHE_DIR%',launch['additionalJvmArguments'])
        self.assertNotIn(str(ROOT),json.dumps(result));self.assertNotIn('IdeaIC2025.2',json.dumps(result))

    def test_non_windows_platform_is_rejected(self):
        home=self.root;(home/'product-info.json').write_text(json.dumps({'productCode':'IC','buildNumber':'252.28539.97',
            'launch':[{'os':'macOS','arch':'aarch64'}]}))
        with self.assertRaisesRegex(RuntimeError,'Wrong platform target'):pack.configure(home,[])

    def test_offline_tools_use_only_local_proxy_despite_inherited_private_module_rules(self):
        cache=self.root/'module cache';(cache/'cache/download').mkdir(parents=True)
        destination=self.root/'output';destination.mkdir()
        commands=[]
        def run(*args, **kwargs):
            if args[1:]==('version',):return 'go version go1.26.4 darwin/arm64'
            if args[1:]==('env','GOMODCACHE'):return str(cache)
            if args[1:]==('env','GOHOSTOS','GOHOSTARCH'):return 'darwin\narm64\n'
            commands.append((args,dict(kwargs['env'])))
            if args[1:4]==('mod','download','-json'):return json.dumps({'Sum':'h1:test-fixture'})
            if args[1]=='install':
                name='gopls' if 'gopls@' in args[-1] else 'dlv'
                binary=self.root/'.tools/windows-cross/bin/windows_amd64'/(name+'.exe')
                binary.parent.mkdir(parents=True,exist_ok=True);binary.write_bytes(b'fixture; PE verification mocked here')
                return ''
            raise AssertionError(args)
        inherited={'GOPRIVATE':'*','GONOPROXY':'*','GONOSUMDB':'*','GOWORK':'user.go.work','GOPROXY':'https://invalid.example,direct'}
        with mock.patch.object(pack,'ROOT',self.root),mock.patch.object(pack,'run',side_effect=run),\
             mock.patch.object(pack,'pe_identity',return_value={'fixture':True}),mock.patch.dict(pack.os.environ,inherited):
            pack.build_tools(Path('go'),destination,offline=True)
        self.assertEqual(4,len(commands))
        for _,env in commands:
            self.assertEqual((cache/'cache/download').resolve().as_uri(),env['GOPROXY'])
            for name in ('GOPRIVATE','GONOPROXY','GONOSUMDB'):self.assertEqual('',env[name])
            self.assertEqual('off',env['GOSUMDB']);self.assertEqual('off',env['GOWORK'])
            self.assertEqual('local',env['GOTOOLCHAIN']);self.assertEqual('*:off',env['GOVCS'])

    def test_offline_missing_module_cache_fails_before_install(self):
        def run(*args,**kwargs):
            if args[1:]==('version',):return 'go version go1.26.4 darwin/arm64'
            if args[1:]==('env','GOMODCACHE'):return str(self.root/'missing')
            raise AssertionError('No install or lookup should run without the offline cache')
        with mock.patch.object(pack,'run',side_effect=run):
            with self.assertRaisesRegex(RuntimeError,'Offline Go module download cache is missing'):
                pack.build_tools(Path('go'),self.root,offline=True)

    def test_real_pinned_pe_resource_rewrite_preserves_code_and_removes_signature(self):
        # The cached official Node PE is a genuine signed Windows binary, not a
        # hand-made executable fixture. No attempt is made to run it on macOS.
        archive=pack.obtain('node',offline=True)
        pack.safe_extract(archive,self.root/'node')
        source=next((self.root/'node').glob('*/node.exe'))
        original=source.read_bytes();target=self.root/'goforge64.exe'
        result=pack.brand_launcher(source,target,self.root/'goforge.ico')
        self.assertEqual(original,source.read_bytes());self.assertTrue(result['executableSectionsUnchanged'])
        self.assertIn('unsigned',result['authenticode']);self.assertEqual('AMD64',result['machine'])
        import lief
        pe=lief.PE.parse(str(target))
        self.assertFalse(pe.has_signatures)
        resource=b''.join(bytes(v.content) for n in pe.resources.childs if n.id==16 for i in n.childs for v in i.childs)
        self.assertIn('GoForge'.encode('utf-16le'),resource)
        self.assertIn('goforge64.exe'.encode('utf-16le'),resource)


if __name__=='__main__':unittest.main()
