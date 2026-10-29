#!/usr/bin/env python3
"""Portable runner contracts; these do not constitute Windows method execution."""
import copy
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.dont_write_bytecode=True
ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('method_verifier',ROOT/'scripts/verify-method-refactoring.py')
verify=importlib.util.module_from_spec(spec);spec.loader.exec_module(verify)


class MethodRunnerContracts(unittest.TestCase):
    def setUp(self):
        temporary=tempfile.TemporaryDirectory(prefix='method-runner-contract-');self.addCleanup(temporary.cleanup)
        self.root=Path(temporary.name).resolve();self.layout={}
        for name in ('plugin','lsp','java','javac','go','gopls'):
            path=self.root/(name+'.jar' if name in ('plugin','lsp') else name);path.write_text(name);self.layout[name]=path
        self.layout['jbr']=self.root/'jbr';self.layout['jbr'].mkdir()
        self.result={'passed':True,'checks':[{'case':name,'passed':True,'versionedDiagnosticsReady':True,'fixtureDeleted':True,'goplsAliveAfterCleanup':False,'goplsExit':0} for name in verify.EXPECTED_CASES],
            'plannerCodeSource':self.layout['plugin'].as_uri(),'typedProofCodeSource':self.layout['plugin'].as_uri(),'compilerProofCodeSource':self.layout['plugin'].as_uri(),'lspModelCodeSource':self.layout['lsp'].as_uri(),
            'javaHome':str(self.layout['jbr']),'goExecutable':str(self.layout['go']),'goplsExecutable':str(self.layout['gopls'])}

    def test_exact_cases_and_loaded_package_identity_are_required(self):
        self.assertTrue(verify.require_results(self.result,self.layout)['packagedFeaturePassed'])
        for key in ('plannerCodeSource','typedProofCodeSource','compilerProofCodeSource','lspModelCodeSource'):
            bad=copy.deepcopy(self.result);bad[key]=self.layout['java'].as_uri()
            with self.subTest(key=key),self.assertRaises(RuntimeError):verify.require_results(bad,self.layout)
        bad=copy.deepcopy(self.result);bad['checks'][0]=copy.deepcopy(bad['checks'][1])
        with self.assertRaisesRegex(RuntimeError,'exact 23'):verify.require_results(bad,self.layout)

    def test_tool_substitution_is_rejected(self):
        for key in ('javaHome','goExecutable','goplsExecutable'):
            bad=copy.deepcopy(self.result);bad[key]=str(self.layout['javac'])
            with self.subTest(key=key),self.assertRaisesRegex(RuntimeError,'identity'):verify.require_results(bad,self.layout)

    def test_readiness_and_cleanup_failures_cannot_be_counted_as_passes(self):
        for key,value in [('passed',False),('versionedDiagnosticsReady',False),('fixtureDeleted',False),('goplsAliveAfterCleanup',True),('goplsExit',137),('cleanupFailure','access denied')]:
            bad=copy.deepcopy(self.result);bad['checks'][0][key]=value
            with self.subTest(key=key),self.assertRaisesRegex(RuntimeError,'failed readiness'):verify.require_results(bad,self.layout)

    def test_development_overlay_cannot_claim_packaged_feature_acceptance(self):
        overlay=self.root/'classes';overlay.mkdir();result=copy.deepcopy(self.result)
        for key in ('plannerCodeSource','typedProofCodeSource','compilerProofCodeSource'):result[key]=overlay.as_uri()+'/'
        summary=verify.require_results(result,self.layout,overlay)
        self.assertFalse(summary['packagedFeaturePassed']);self.assertTrue(summary['developmentOverlayOnly'])
        with self.assertRaisesRegex(RuntimeError,'expected provider'):verify.require_results(result,self.layout)

    def test_external_go_jvm_and_classpath_overrides_are_scrubbed(self):
        inherited={'PATH':'host-system','goFlags':'-toolexec=foreign','GoRoot':'foreign','GOPACKAGESDRIVER':'foreign','java_tool_options':'foreign','JDK_JAVA_OPTIONS':'foreign','CLASSPATH':'foreign','SystemRoot':'Windows'}
        saved=dict(inherited);env=verify.probe_environment(self.layout,self.root,inherited)
        self.assertEqual(saved,inherited)
        for name in ('GOFLAGS','GOROOT','JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','CLASSPATH'):self.assertNotIn(name,{x.upper() for x in env})
        self.assertEqual('off',env['GOPACKAGESDRIVER']);self.assertEqual('off',env['GOPROXY'])
        self.assertEqual(str(self.root/'tmp'),env['TMP']);self.assertEqual('Windows',env['SystemRoot'])

    def test_argument_files_quote_spaces_backslashes_and_unicode_without_shell(self):
        self.assertEqual('"C:\\\\space path\\\\中文"',verify.quote_argument('C:\\space path\\中文'))
        self.assertEqual('""',verify.quote_argument(''))
        for value in ['bad\narg','bad\rarg','bad\0arg']:
            with self.assertRaises(ValueError):verify.quote_argument(value)

    def test_failed_app_validation_writes_failure_report(self):
        report=self.root/'failed-report'
        result=verify.verify(self.root/'missing-app',report,self.root)
        self.assertFalse(result['passed']);self.assertFalse(result['packagedFeaturePassed'])
        self.assertIn('failure',json.loads((report/'verification.json').read_text()))

    def test_nonzero_compiler_exit_preserves_log_and_process_receipt(self):
        class Process:
            pid=999999
            def wait(self,timeout):return 7
        report=self.root/'commands';report.mkdir()
        with patch.object(verify.subprocess,'Popen',return_value=Process()),self.assertRaisesRegex(RuntimeError,'did not finish'):
            verify.run_java_tool(self.layout['javac'],['-version'],report,'compile',{},self.root,1)
        self.assertTrue((report/'compile.log').exists());self.assertEqual(7,json.loads((report/'compile-process.json').read_text())['exit'])

    def test_windows_tree_termination_errors_always_leave_failure_receipts(self):
        from types import SimpleNamespace
        class Process:
            pid=999999
            def __init__(self):self.waits=0;self.killed=False
            def wait(self,timeout):
                self.waits+=1
                if self.waits==1:raise subprocess.TimeoutExpired('owned-jvm',timeout)
                return 137
            def kill(self):self.killed=True
        for error in [OSError('taskkill unavailable'),subprocess.TimeoutExpired('taskkill',20)]:
            with self.subTest(error=type(error).__name__):
                report=self.root/('termination-'+type(error).__name__);report.mkdir();process=Process()
                with patch.object(verify,'os',SimpleNamespace(name='nt')),patch.object(verify.subprocess,'CREATE_NEW_PROCESS_GROUP',512,create=True),patch.object(verify.subprocess,'Popen',return_value=process),patch.object(verify.subprocess,'run',side_effect=error),self.assertRaisesRegex(RuntimeError,'did not finish'):
                    verify.run_java_tool(self.layout['java'],['-version'],report,'probe',{'SystemRoot':str(self.root/'Windows')},self.root,1)
                receipt=json.loads((report/'probe-process.json').read_text())
                self.assertTrue(receipt['timedOut']);self.assertEqual(137,receipt['exit']);self.assertTrue(process.killed)
                self.assertTrue(receipt['cleanup']['fallbackOwnedJvmOnly']);self.assertIn(type(error).__name__,receipt['cleanup']['failure'])

    def test_late_owned_directory_cleanup_failure_cannot_report_success(self):
        import zipfile
        with zipfile.ZipFile(self.layout['plugin'],'w') as archive:archive.writestr('dev/goforge/refactor/GoMethodParameterSafety.class',b'fixture')
        layout={**self.layout,'jars':[self.layout['plugin'],self.layout['lsp']]}
        source=self.root/verify.PROBE;source.parent.mkdir(parents=True);source.write_text('// owned fixture')
        owned=self.root/'late-owned';owned.mkdir();report=self.root/'late-report'
        class LateCleanup:
            def __enter__(self):return str(owned)
            def __exit__(self,*ignored):raise PermissionError('late owned cleanup failure')
        def command(executable,args,out,name,*rest):
            if name=='compile':
                path=out/'classes/dev/goforge/refactor/GoMethodAddParameterProtocolProbe.class';path.parent.mkdir(parents=True);path.write_bytes(b'fixture')
            else:
                (out/'protocol').mkdir();(out/'protocol/result.json').write_text(json.dumps(self.result))
            return {'exit':0}
        with patch.object(verify,'package_layout',return_value=layout),patch.object(verify,'run_java_tool',side_effect=command),patch.object(verify.tempfile,'TemporaryDirectory',return_value=LateCleanup()):
            result=verify.verify(self.root,report,self.root)
        self.assertFalse(result['passed']);self.assertFalse(result['packagedFeaturePassed'])
        self.assertIn('late owned cleanup failure',result['failure'])
        self.assertFalse(json.loads((report/'verification.json').read_text())['passed'])

    def test_cli_failure_is_nonzero_and_report_is_retained(self):
        report=self.root/'cli-failed'
        child=subprocess.run([sys.executable,str(ROOT/'scripts/verify-method-refactoring.py'),'--app',str(self.root/'missing'),'--report-dir',str(report)],capture_output=True,text=True,timeout=10)
        self.assertEqual(1,child.returncode);self.assertFalse(json.loads((report/'verification.json').read_text())['passed'])

if __name__=='__main__':unittest.main()
