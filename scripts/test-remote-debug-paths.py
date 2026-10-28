#!/usr/bin/env python3
"""Tests the exact production Python mapper emitted by Java; no remote host or UI involved."""
from pathlib import Path
import os, posixpath, subprocess, tempfile, types, unittest
ROOT=Path(__file__).resolve().parents[1]
JDK=Path(os.environ.get('JAVA_HOME',str(ROOT/'.tools/jdk')))
EXE='.exe' if os.name=='nt' else ''
with tempfile.TemporaryDirectory(prefix='goforge-path-mapper-') as temp:
    out=Path(temp);main=out/'Dump.java'
    main.write_text('public class Dump {public static void main(String[] a) {System.out.print(dev.goforge.remote.GoRemoteDebugPaths.SOURCE);}}',encoding='utf-8')
    subprocess.run([str(JDK/'bin'/('javac'+EXE)),'--release','21','-d',str(out),str(ROOT/'src/main/java/dev/goforge/remote/GoRemoteDebugPaths.java'),str(main)],check=True,capture_output=True)
    SOURCE=subprocess.check_output([str(JDK/'bin'/('java'+EXE)),'-cp',str(out),'Dump']).decode('utf-8')

class SourceMapping(unittest.TestCase):
    def mapping(self,local,files=None):
        # The emitted code always runs on the POSIX SSH host, even when this test runs on Windows.
        ns={'os':types.SimpleNamespace(path=posixpath),'root':'/remote/work 中文','plan':{'localRoot':local,'files':files or {'app/Main.go':'a','lib 中文/库.go':'b'}}}
        exec(compile(SOURCE,'GoRemoteDebugPaths.SOURCE','exec'),ns);ns['initialize_source_mapping']();return ns
    def test_drive_slashes_case_and_manifest_case_preservation(self):
        ns=self.mapping(r'C:\Project Space\项目')
        for path in [r'c:\project space\项目\APP\main.GO','C:/Project Space/项目/app/Main.go']:
            self.assertEqual('app/Main.go',ns['local_relative'](path))
    def test_drive_outside_and_other_drives_rejected(self):
        ns=self.mapping(r'C:\Project')
        for path in [r'C:\Project-else\app\Main.go',r'D:\Project\app\Main.go',r'C:\Project\..\else\app\Main.go','C:app/Main.go',r'\Project\app\Main.go']:
            self.assertIsNone(ns['local_relative'](path),path)
    def test_unc_server_share_and_separators(self):
        ns=self.mapping(r'\\Server\Share Name\项目')
        self.assertEqual('app/Main.go',ns['local_relative']('//server/SHARE NAME/项目/APP/main.go'))
        self.assertIsNone(ns['local_relative'](r'\\Server\Other\项目\app\Main.go'))
        self.assertIsNone(ns['local_relative'](r'\\Other\Share Name\项目\app\Main.go'))
    def test_unc_escape_stays_outside(self):
        ns=self.mapping('//Server/Share/Project')
        self.assertIsNone(ns['local_relative']('//Server/Share/Project/../../app/Main.go'))
    def test_posix_case_sensitive_and_normalized_inside(self):
        ns=self.mapping('/local/project')
        self.assertEqual('app/Main.go',ns['local_relative']('/local/project/app/./Main.go'))
        for p in ['/local/project/app/main.go','/local/project/../app/Main.go','/local/project-else/app/Main.go','app/Main.go']:
            self.assertIsNone(ns['local_relative'](p))
    def test_windows_source_from_remote_is_native_display_path(self):
        ns=self.mapping(r'C:\Local 项目');value={'path':'/remote/work 中文/lib 中文/库.go'};ns['source_path'](value)
        self.assertEqual('C:\\Local 项目\\lib 中文\\库.go',value['path'])
    def test_unc_source_from_remote(self):
        ns=self.mapping('//server/share/root');value={'path':'/remote/work 中文/app/Main.go'};ns['source_path'](value)
        self.assertEqual(r'\\server\share\root\app\Main.go',value['path'])
    def test_existing_local_windows_spelling_is_canonicalized(self):
        ns=self.mapping(r'C:\Project');value={'path':'c:/PROJECT/APP/main.GO'};ns['source_path'](value)
        self.assertEqual(r'C:\Project\app\Main.go',value['path'])
    def test_outside_sources_are_not_mapped_or_exposed(self):
        ns=self.mapping(r'C:\Project')
        for path in ['/remote/work 中文/../other.go','/remote/work 中文/app/main.go',r'C:\other\app\Main.go','/usr/local/go/src/runtime/proc.go']:
            value={'path':path,'sourceReference':42};ns['source_path'](value);self.assertNotIn('path',value);self.assertEqual(0,value['sourceReference'])
    def test_case_ambiguity_rejected_for_windows_only(self):
        files={'app/main.go':'a','app/Main.go':'b'}
        with self.assertRaisesRegex(ValueError,'ambiguous'):self.mapping(r'C:\Project',files)
        ns=self.mapping('/project',files);self.assertEqual('app/main.go',ns['local_relative']('/project/app/main.go'));self.assertEqual('app/Main.go',ns['local_relative']('/project/app/Main.go'))
    def test_nonabsolute_roots_rejected(self):
        for value in ['C:project','relative']:
            with self.assertRaises(ValueError):self.mapping(value)
    def test_unlisted_file_never_gains_a_mapping(self):
        ns=self.mapping(r'C:\Project');self.assertIsNone(ns['local_relative'](r'C:\Project\app\new.go'))

if __name__=='__main__':unittest.main()
