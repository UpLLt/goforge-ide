#!/usr/bin/env python3
"""Portable verifier regressions; passing these is not Windows-native acceptance."""
from pathlib import Path
import contextlib
import importlib.util
import io
import json
import os
import subprocess
import socket
import threading
import time
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.dont_write_bytecode=True
ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('goforge_native_verifier',ROOT/'scripts/verify-windows-package.py')
verify=importlib.util.module_from_spec(spec);spec.loader.exec_module(verify)


def metadata_fixture(system_root,signature='NotSigned'):
    home=Path(system_root)/'System32/WindowsPowerShell/v1.0'
    return {'properties':{'product':'GoForge','description':'GoForge','original':'goforge64.exe','signature':signature},
            'powershell':{'home':str(home),'edition':'Desktop','version':'5.1.26100.0',
                          'securityModule':str(home/'Modules/Microsoft.PowerShell.Security'),
                          'utilityModule':str(home/'Modules/Microsoft.PowerShell.Utility')}}


class WindowsVerifierTest(unittest.TestCase):
    def setUp(self):
        temporary=tempfile.TemporaryDirectory(prefix='goforge-verifier 中文 ')
        self.addCleanup(temporary.cleanup)
        self.root=Path(temporary.name)
        self.app=self.root/'GoForge relocated'

    def test_external_workspace_driver_and_cross_compilation_do_not_leak_to_fixtures(self):
        inherited={'SystemRoot':str(self.root/'Windows'),'GoWork':'outside.go.work',
                   'GoOs':'linux','GOARCH':'arm64','GOAMD64':'v4','GOEXPERIMENT':'user-experiment',
                   'GOPACKAGESDRIVER':'outside-driver','GOPROXY':'https://external.invalid',
                   'GOPRIVATE':'*','GONOPROXY':'*','GONOSUMDB':'*','GOROOT':'outside-root',
                   'GOMODCACHE':'outside-cache','GOFLAGS':'-modfile=outside.mod',
                   'GOENV':'outside-env','GOTOOLCHAIN':'auto','PATH':'outside-bin'}
        saved=dict(inherited)
        env=verify.native_environment(self.app,self.root,inherited)
        self.assertEqual(saved,inherited)
        for key,value in {'GOOS':'windows','GOARCH':'amd64','GOAMD64':'v1','GOWORK':'off',
                          'GOPACKAGESDRIVER':'off','GOPROXY':'off','GOSUMDB':'off','GOVCS':'*:off',
                          'GOENV':'off','GOTOOLCHAIN':'local','GOPRIVATE':'','GONOPROXY':'',
                          'GONOSUMDB':''}.items():self.assertEqual(value,env[key],key)
        for key in ('GOROOT','GOFLAGS','GOEXPERIMENT'):self.assertNotIn(key,env)
        for key in ('APPDATA','LOCALAPPDATA','GOPATH','GOMODCACHE','GOCACHE'):
            self.assertTrue(Path(env[key]).is_relative_to(self.root),key)
        self.assertNotIn('outside-bin',env['PATH'])
        self.assertIn(str(self.app/'goforge-tools/go/bin'),env['PATH'])

    def test_external_jvm_and_ide_options_cannot_replace_the_bundled_runtime(self):
        keys=('JAVA_HOME','JDK_HOME','JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS',
              'GOFORGE_JDK','GOFORGE_VM_OPTIONS','GOFORGE_PROPERTIES',
              'IDEA_JDK','IDEA_VM_OPTIONS','IDEA_PROPERTIES')
        env=verify.native_environment(self.app,self.root,{'SYSTEMROOT':str(self.root/'Windows'),
                                      **{key.lower():'external' for key in keys}})
        for key in keys:self.assertNotIn(key,env)

    def test_missing_windows_root_is_refused_before_any_process_runs(self):
        with patch.object(verify.subprocess,'run',side_effect=AssertionError('no process')):
            with self.assertRaisesRegex(RuntimeError,'SystemRoot'):
                verify.native_environment(self.app,self.root,{})

    def test_powershell_metadata_uses_explicit_system_modules_without_inherited_ps7_paths(self):
        inherited={'SystemRoot':str(self.root/'Windows'),'pSmOdUlEpAtH':'external PowerShell7 modules',
                   'PSExecutionPolicyPreference':'RemoteSigned','__PSLockdownPolicy':'4'}
        saved=dict(inherited)
        native=verify.native_environment(self.app,self.root,inherited)
        self.assertNotIn('PSMODULEPATH',native)
        expected=metadata_fixture(inherited['SystemRoot'])
        with patch.object(verify,'command',return_value=json.dumps(expected)) as run:
            self.assertEqual(expected,verify.windows_file_metadata(self.app/'bin/goforge64.exe',inherited))
        self.assertEqual(saved,inherited)
        args=run.call_args.args[0];env=run.call_args.kwargs['env'];script=args[-1]
        self.assertEqual(Path(inherited['SystemRoot'])/'System32/WindowsPowerShell/v1.0/powershell.exe',args[0])
        self.assertEqual(['-NoProfile','-NonInteractive','-Command'],args[1:-1])
        self.assertNotIn('PSMODULEPATH',env)
        self.assertEqual('RemoteSigned',env['PSEXECUTIONPOLICYPREFERENCE'])
        self.assertEqual('4',env['__PSLOCKDOWNPOLICY'])
        self.assertEqual(str(self.app/'bin/goforge64.exe'),env['GOFORGE_VERIFY_EXE'])
        for module in ('Security','Utility'):
            self.assertIn("[System.IO.Path]::Combine($PSHOME,'Modules','Microsoft.PowerShell."+module+"','Microsoft.PowerShell."+module+".psd1')",script)
        for required in ("$ErrorActionPreference='Stop'","$PSModuleAutoLoadingPreference='None'",
                         '[System.Diagnostics.FileVersionInfo]::GetVersionInfo',
                         'Microsoft.PowerShell.Security\\Get-AuthenticodeSignature',
                         'Microsoft.PowerShell.Utility\\ConvertTo-Json'):
            self.assertIn(required,script)
        self.assertNotIn('Get-Item',script)
        self.assertNotIn('ExecutionPolicy',script)
        self.assertNotIn('SkipEditionCheck',script)

    def test_powershell_metadata_rejects_untrusted_module_origin_and_wrong_edition(self):
        env={'SYSTEMROOT':str(self.root/'Windows')}
        for key,value in [('securityModule',str(self.root/'external')),('utilityModule',str(self.root/'external')),
                          ('home',str(self.root/'external')),('edition','Core'),('version','7.4.0')]:
            with self.subTest(key=key):
                metadata=metadata_fixture(env['SYSTEMROOT']);metadata['powershell'][key]=value
                with patch.object(verify,'command',return_value=json.dumps(metadata)):
                    with self.assertRaisesRegex(RuntimeError,'Windows metadata query'):
                        verify.windows_file_metadata(self.app/'bin/goforge64.exe',env)

    def test_powershell_module_failure_or_missing_signature_cannot_become_success(self):
        env={'SYSTEMROOT':str(self.root/'Windows')};failure=RuntimeError('module import failed: original detail')
        with patch.object(verify,'command',side_effect=failure):
            with self.assertRaises(RuntimeError) as caught:verify.windows_file_metadata(self.app/'bin/goforge64.exe',env)
        self.assertIs(failure,caught.exception)
        for payload in ('{}','null','{"properties":null}',json.dumps({'properties':{},'powershell':{}})):
            with self.subTest(payload=payload),patch.object(verify,'command',return_value=payload):
                with self.assertRaisesRegex(RuntimeError,'version and signature'):
                    verify.windows_file_metadata(self.app/'bin/goforge64.exe',env)

    @unittest.skipUnless(os.name=='nt','Requires real Windows PowerShell and Windows system modules')
    def test_real_windows_system_metadata_ignores_poisoned_inherited_module_path(self):
        # This executes the same native helper before packaging, not a mocked signature query.
        poison=self.root/'incompatible PowerShell7 modules'/'Microsoft.PowerShell.Security';poison.mkdir(parents=True)
        (poison/'Microsoft.PowerShell.Security.psd1').write_text(
            "@{ModuleVersion='99.0'; PowerShellVersion='99.0'; RootModule='poison.psm1'}",encoding='utf-8')
        (poison/'poison.psm1').write_text("throw 'Inherited incompatible module must never execute'",encoding='utf-8')
        inherited={key:value for key,value in os.environ.items() if key.upper()!='PSMODULEPATH'}
        inherited['pSmOdUlEpAtH']=str(poison.parent)
        env=verify.native_environment(self.app,self.root,inherited)
        executable=Path(env['SYSTEMROOT'])/'System32/WindowsPowerShell/v1.0/powershell.exe'
        metadata=verify.windows_file_metadata(executable,env)
        self.assertEqual('Desktop',metadata['powershell']['edition'])
        self.assertTrue(metadata['properties']['product'])
        self.assertTrue(metadata['properties']['description'])
        self.assertEqual('powershell.exe',metadata['properties']['original'].casefold())
        self.assertIn(metadata['properties']['signature'],('Valid','UnknownError','NotSigned','HashMismatch',
                     'NotTrusted','NotSupportedFileFormat','Incompatible'))
        print('Windows system metadata preflight: '+json.dumps(metadata,ensure_ascii=True,sort_keys=True))

    def fixture(self):
        project=self.root/'fixture space 中文';project.mkdir()
        (project/'go.mod').write_bytes(b'module example.com/native\n\ngo 1.26\n')
        (project/'main.go').write_bytes(b'package main\nfunc main() {}\n')
        (project/'nested').mkdir()
        (project/'nested/helper.go').write_bytes(b'package nested\n')
        return project

    def test_unchanged_fixture_and_debug_binary_are_distinguished_from_source_mutations(self):
        project=self.fixture();baseline=verify.fixture_hashes(project)
        (project/'__debug_bin.exe').write_bytes(b'generated executable')
        self.assertTrue(verify.require_fixture_unchanged(project,baseline)['passed'])
        (project/'nested/helper.go').write_bytes(b'package changed\n')
        with self.assertRaisesRegex(RuntimeError,'Go inputs changed'):
            verify.require_fixture_unchanged(project,baseline)

    def test_new_caller_or_workspace_file_invalidates_read_only_probe_result(self):
        project=self.fixture();baseline=verify.fixture_hashes(project)
        for name,content in (('late.go',b'package main\n'),('go.work',b'go 1.26\nuse .\n'),
                             ('go.sum',b'new checksum\n')):
            with self.subTest(name=name):
                path=project/name;path.write_bytes(content)
                with self.assertRaisesRegex(RuntimeError,'Go inputs changed'):
                    verify.require_fixture_unchanged(project,baseline)
                path.unlink()

    def test_real_child_nonzero_adapter_exit_is_never_reported_as_pass(self):
        child=subprocess.Popen([sys.executable,'-c','raise SystemExit(9)'])
        try:
            with self.assertRaisesRegex(RuntimeError,'returned 9'):
                verify.require_adapter_success(child)
        finally:
            child.wait(timeout=15)

    def test_real_child_successful_adapter_exit_is_accepted(self):
        child=subprocess.Popen([sys.executable,'-c','raise SystemExit(0)'])
        try:verify.require_adapter_success(child)
        finally:child.wait(timeout=15)

    def run_failure(self,expected,host_guard=None):
        report=self.root/'reports'
        guard=patch.object(verify,'require_windows_host',side_effect=host_guard)
        with guard,contextlib.redirect_stdout(io.StringIO()):
            with self.assertRaises(expected):verify.main(self.app,report)
        result=json.loads((report/'native-verification.json').read_text(encoding='utf-8'))
        self.assertFalse(result['passed'])
        self.assertEqual({},result['checks'])
        return result

    def test_wrong_host_refusal_is_preserved_in_failure_receipt(self):
        result=self.run_failure(RuntimeError,RuntimeError('Windows native validation requires Windows'))
        self.assertIn('requires Windows',result['error'])
        self.assertFalse(self.app.exists())

    def test_missing_package_identity_is_preserved_in_failure_receipt(self):
        result=self.run_failure(FileNotFoundError)
        self.assertIn('goforge-build-inputs.json',result['error'])

    def test_invalid_package_identity_is_preserved_in_failure_receipt(self):
        self.app.mkdir();(self.app/'goforge-build-inputs.json').write_bytes(b'{invalid')
        result=self.run_failure(json.JSONDecodeError)
        self.assertIn('JSONDecodeError',result['error'])

    def rename(self):
        files=[(self.root/name).as_uri() for name in ('main.go','hello.go','hello_test.go')]
        return files,{'changes':{uri:[{'newText':'Welcome','range':{'start':{'line':0,'character':0},
                          'end':{'line':0,'character':5}}}] for uri in files}}

    def test_multifile_rename_requires_all_expected_files_and_replacement(self):
        files,edit=self.rename()
        self.assertEqual({uri:1 for uri in files},verify.rename_file_counts(edit,files,'Welcome'))
        del edit['changes'][files[-1]]
        with self.assertRaisesRegex(RuntimeError,'cover exactly'):
            verify.rename_file_counts(edit,files,'Welcome')

    def test_rename_cannot_claim_success_for_external_files_or_wrong_text(self):
        files,edit=self.rename();edit['changes'][(self.root/'external.go').as_uri()]=[{'newText':'Welcome'}]
        with self.assertRaisesRegex(RuntimeError,'cover exactly'):
            verify.rename_file_counts(edit,files,'Welcome')
        files,edit=self.rename();edit['changes'][files[0]][0]['newText']='Wrong'
        with self.assertRaisesRegex(RuntimeError,'edit text'):
            verify.rename_file_counts(edit,files,'Welcome')

    def test_versioned_rename_edits_are_supported_but_resource_changes_are_not(self):
        files,edit=self.rename()
        versioned={'documentChanges':[{'textDocument':{'uri':uri,'version':1},'edits':edits}
                    for uri,edits in edit['changes'].items()]}
        self.assertEqual({uri:1 for uri in files},verify.rename_file_counts(versioned,files,'Welcome'))
        versioned['documentChanges'].append({'kind':'create','uri':(self.root/'new.go').as_uri()})
        with self.assertRaisesRegex(RuntimeError,'resource operation'):
            verify.rename_file_counts(versioned,files,'Welcome')


class CleanupTests(unittest.TestCase):
 def test_protocol_failure_with_blocked_real_reader_returns_and_preserves_original_error(self):
  child=subprocess.run([sys.executable,str(ROOT/'scripts/debug-cleanup-probe.py'),str(ROOT/'scripts/verify-windows-package.py')],capture_output=True,text=True,timeout=6)
  self.assertEqual(0,child.returncode,child.stdout+child.stderr)
  result=json.loads(child.stdout);self.assertTrue(result['passed']);self.assertEqual('InjectedProtocolFailure',result['primaryException'])
 def test_cleanup_errors_do_not_replace_primary_protocol_exception(self):
  one,two=socket.socketpair();reader=one.makefile('rb');writer=one.makefile('wb');protocol=verify.load_protocol().FramedJson(reader,writer)
  primary=RuntimeError('original protocol failure')
  try:
   with patch.object(verify,'terminate_owned',side_effect=OSError('synthetic adapter termination failure')):
    verify.cleanup_debug_session(object(),one,reader,writer,None,primary)
   self.assertTrue(reader.closed);self.assertTrue(writer.closed);self.assertEqual('original protocol failure',str(primary))
   self.assertTrue(any('synthetic adapter termination failure' in note for note in primary.__notes__))
  finally:two.close();one.close()
 def test_cleanup_failure_after_success_is_a_failure(self):
  with patch.object(verify,'terminate_owned',side_effect=OSError('synthetic adapter termination failure')):
   with self.assertRaisesRegex(RuntimeError,'Native debugger cleanup failed'):
    verify.cleanup_debug_session(object(),None,None,None,None,None)
 def test_a_stream_that_stays_blocked_cannot_hold_cleanup_forever(self):
  released=threading.Event();finished=threading.Event()
  class Blocked:
   def close(self):released.wait(10);finished.set()
  primary=RuntimeError('primary');started=time.monotonic()
  try:
   with patch.object(verify,'terminate_owned'):
    verify.cleanup_debug_session(None,None,Blocked(),None,None,primary)
   self.assertLess(time.monotonic()-started,3.5)
   self.assertTrue(any('exceeded 2 seconds' in note for note in primary.__notes__))
  finally:released.set();self.assertTrue(finished.wait(1))
 def make_app(self,root):
  app=root/'app';(app/'bin').mkdir(parents=True);(app/'jbr').mkdir()
  (app/'bin/goforge64.exe').write_bytes(b'generated fixture only')
  (app/'goforge-build-inputs.json').write_text(json.dumps({'pluginZipSha256':'0'*64}))
  return app
 def test_failure_receipt_preserves_cleanup_notes(self):
  with tempfile.TemporaryDirectory() as name:
   root=Path(name);app=self.make_app(root);out=root/'report';failure=RuntimeError('original protocol error');failure.__notes__=['Native debugger cleanup: synthetic detail']
   with patch.object(verify,'require_windows_host'),patch.object(verify,'load_protocol',side_effect=failure):
    with self.assertRaisesRegex(RuntimeError,'original protocol error'):verify.main(app,out)
   receipt=json.loads((out/'native-verification.json').read_text());self.assertFalse(receipt['passed']);self.assertEqual(failure.__notes__,receipt['cleanupNotes'])
 def test_launcher_still_requires_exact_unsigned_goforge_metadata_before_any_engine_runs(self):
  with tempfile.TemporaryDirectory() as name:
   root=Path(name);app=self.make_app(root);out=root/'report'
   def environment(app,temp,inherited):return {'SYSTEMROOT':str(root/'Windows'),'APPDATA':str(temp/'Roaming'),'LOCALAPPDATA':str(temp/'Local')}
   for status in ('Valid','HashMismatch',''):
    with self.subTest(status=status),patch.object(verify,'require_windows_host'),patch.object(verify,'load_protocol',return_value=object()),patch.object(verify,'native_environment',side_effect=environment),patch.object(verify,'windows_file_metadata',return_value=metadata_fixture(root/'Windows',status)),patch.object(verify,'command',side_effect=AssertionError('engine must not run')) as engine,contextlib.redirect_stdout(io.StringIO()):
     with self.assertRaises(AssertionError):verify.main(app,out)
     engine.assert_not_called()
    receipt=json.loads((out/'native-verification.json').read_text());self.assertFalse(receipt['passed']);self.assertEqual({},receipt['checks'])
 def test_late_temporary_directory_cleanup_error_cannot_leave_passed_true(self):
  realTemporaryDirectory=tempfile.TemporaryDirectory
  with realTemporaryDirectory() as name:
   root=Path(name);app=self.make_app(root);out=root/'report'
   class CleanupFailure(realTemporaryDirectory):
    def __exit__(self,*args):super().__exit__(*args);raise PermissionError('synthetic temporary directory cleanup failure')
   def environment(app,temp,inherited):return {'SYSTEMROOT':str(root/'Windows'),'APPDATA':str(temp/'Roaming'),'LOCALAPPDATA':str(temp/'Local')}
   def command(args,**kwargs):
    binary=Path(args[0]);name=binary.name;env=kwargs.get('env',{})
    if name=='powershell.exe':return json.dumps(metadata_fixture(env['SYSTEMROOT']))
    if name=='goforge64.exe':
     home=binary.parents[1];return '\n'.join(['product=GoForge','home='+str(home),'runtime='+str(home/'jbr')]+[key+'='+env['LOCALAPPDATA'] for key in ('config','system','plugins','log')])
    if name=='go.exe':
     if args[1]=='env':return str(binary.parents[1])
     if args[1]=='version':return 'go1.26.4'
     if args[1]=='test':return 'ok'
     if args[1]=='run':return 'Windows native'
    if name=='gopls.exe':return 'v0.23.0'
    if name=='dlv.exe':return '1.27.2'
    if name=='node.exe':return '0.89.0' if len(args)>2 else 'v24.22.1'
    raise AssertionError(args)
   with patch.object(verify,'require_windows_host'),patch.object(verify,'load_protocol',return_value=object()),patch.object(verify,'native_environment',side_effect=environment),patch.object(verify,'command',side_effect=command),patch.object(verify,'lsp',return_value={'passed':True}),patch.object(verify,'debug',return_value={'passed':True}),patch.object(verify.tempfile,'TemporaryDirectory',CleanupFailure):
    with self.assertRaisesRegex(PermissionError,'synthetic temporary directory cleanup failure'):verify.main(app,out)
   receipt=json.loads((out/'native-verification.json').read_text());self.assertFalse(receipt['passed']);self.assertTrue(receipt['checks']['fixtureUnchanged']['passed'])
   self.assertIn('synthetic temporary directory cleanup failure',receipt['error'])


if __name__=='__main__':unittest.main()
