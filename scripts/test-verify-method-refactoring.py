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

    def native_fixture(self):
        home=self.root/'native-home';jbr=home/'jbr';jbr.mkdir(parents=True,exist_ok=True)
        release=jbr/'release';release.write_text('OS_ARCH="aarch64"\n')
        library=home/'lib/jna/aarch64/libjnidispatch.jnilib';library.parent.mkdir(parents=True,exist_ok=True);library.write_bytes(b'owned native fixture')
        provider=home/'lib/util-8.jar';provider.write_bytes(b'owned class fixture')
        return {'jnaDispatch':library,'jnaProvider':provider,'jbrRelease':release}

    def native_result(self,layout):
        return {'passed':True,'noSystemFallback':True,'noClasspathFallback':True,
                'dispatchPath':str(layout['jnaDispatch']),'providerPath':str(layout['jnaProvider']),
                'bootLibraryPath':str(layout['jnaDispatch'].parent)}

    def test_native_layout_requires_exact_bundled_architecture_files(self):
        from types import SimpleNamespace
        for system,platform,arch,dirname,filename in [('nt','win32','amd64','amd64','jnidispatch.dll'),('posix','darwin','aarch64','aarch64','libjnidispatch.jnilib')]:
            with self.subTest(system=system):
                home=self.root/('bundle-'+system);jbr=home/'jbr';jbr.mkdir(parents=True)
                (jbr/'release').write_text('OS_ARCH="'+arch+'"\n')
                library=home/'lib/jna'/dirname/filename;library.parent.mkdir(parents=True);library.write_bytes(b'owned')
                provider=home/'lib/util-8.jar';provider.write_bytes(b'owned')
                with patch.object(verify,'os',SimpleNamespace(name=system)),patch.object(verify,'sys',SimpleNamespace(platform=platform)):
                    result=verify.native_layout(home,jbr);self.assertEqual(library.resolve(),result['jnaDispatch'])
                    library.unlink()
                    with self.assertRaises(FileNotFoundError):verify.native_layout(home,jbr)
                    (jbr/'release').write_text('OS_ARCH="unreviewed"\n')
                    with self.assertRaisesRegex(RuntimeError,'architecture'):verify.native_layout(home,jbr)

    def test_native_layout_rejects_external_jbr_release(self):
        from types import SimpleNamespace
        layout=self.native_fixture();outside=self.root/'external-jbr';outside.mkdir();(outside/'release').write_text('OS_ARCH="aarch64"\n')
        with patch.object(verify,'os',SimpleNamespace(name='posix')),patch.object(verify,'sys',SimpleNamespace(platform='darwin')):
            with self.assertRaisesRegex(RuntimeError,'escapes the app'):verify.native_layout(self.root/'native-home',outside)

    def test_native_arguments_and_runtime_identity_disallow_fallback_or_substitution(self):
        layout=self.native_fixture();arguments=verify.native_arguments(layout)
        self.assertIn('-Djna.boot.library.path='+str(layout['jnaDispatch'].parent),arguments)
        self.assertIn('-Djna.nosys=true',arguments);self.assertIn('-Djna.noclasspath=true',arguments)
        self.assertIn('-Djna.boot.library.name=jnidispatch',arguments)
        good=self.native_result(layout);self.assertEqual(good,verify.require_native_result(good,layout))
        for key,value in [('passed',False),('noSystemFallback',False),('noClasspathFallback',False),('dispatchPath',str(self.layout['java'])),('providerPath',str(self.layout['java'])),('bootLibraryPath',str(self.root))]:
            bad=dict(good);bad[key]=value
            with self.subTest(key=key),self.assertRaises(RuntimeError):verify.require_native_result(bad,layout)

    def group_result(self, group):
        _, _, cases=verify.CASE_GROUPS[group]
        result=copy.deepcopy(self.result)
        result['checks']=[dict(self.result['checks'][0],case=case,
            originalSourceBytesUnchanged=True,
            originalSourceBytesUnchangedOrRestoredAfterExplicitFixtureTest=True) for case in cases]
        if group=='free-function-reorder':
            for item in result['checks']:
                if item['case'] in verify.REORDER_BEHAVIOR:
                    output=verify.REORDER_BEHAVIOR[item['case']]
                    item.update(fixtureAppliedExecutedAndRestored=True,beforeOutput=output,afterOutput=output,expectedOutput=output)
        return result

    def group_summary(self, group, overlay=None):
        name, _, cases=verify.CASE_GROUPS[group]
        result={'passed':True,'packagedFeaturePassed':overlay is None,'developmentOverlayOnly':overlay is not None,
                'cases':len(cases),'actualCaseIds':['dev.goforge.refactor.'+name+'#'+case for case in cases]}
        if group=='free-function-reorder':result['behaviorComparisons']=dict(verify.REORDER_BEHAVIOR)
        return result

    def test_suite_requires_all_72_exact_class_qualified_cases(self):
        seen=[]
        def group_run(app, report, source_root, overlay, *, group, strict_sources):
            self.assertTrue(strict_sources);seen.append(group)
            return self.group_summary(group,overlay)
        with patch.object(verify,'verify',side_effect=group_run):
            result=verify.verify_suite(self.root,self.root/'suite',self.root)
        self.assertTrue(result['passed']);self.assertTrue(result['packagedFeaturePassed'])
        self.assertEqual(['method-add','method-scalar','free-scalar','free-function-reorder'],seen)
        self.assertEqual(3,result['schemaVersion']);self.assertEqual(103,result['cases'])
        self.assertEqual(72,len(set(result['actualCaseIds'][:72])))
        self.assertEqual(103,len(set(result['actualCaseIds'])))
        self.assertEqual(result['expectedCaseIds'],result['actualCaseIds'])
        self.assertEqual([23,29,20],[result['groups'][key]['cases'] for key in seen[:3]])
        self.assertEqual(31,result['groups']['free-function-reorder']['cases'])
        self.assertEqual(result,json.loads((self.root/'suite/verification.json').read_text()))

    def test_each_group_rejects_missing_duplicate_and_foreign_case_ids(self):
        for group,(_,ready,cases) in verify.CASE_GROUPS.items():
            result=self.group_result(group)
            self.assertEqual(len(cases),verify.require_results(result,self.layout,expected_cases=cases,require_ready=ready)['cases'])
            for mutation in ['missing','duplicate','foreign']:
                bad=copy.deepcopy(result)
                if mutation=='missing':bad['checks'].pop()
                elif mutation=='duplicate':bad['checks'][0]=copy.deepcopy(bad['checks'][1])
                else:bad['checks'][0]['case']='unexecuted-case'
                with self.subTest(group=group,mutation=mutation),self.assertRaisesRegex(RuntimeError,'exact'):
                    verify.require_results(bad,self.layout,expected_cases=cases,require_ready=ready)

    def test_all_groups_reject_not_ready_dirty_source_and_cleanup_failure(self):
        for group,(_,ready,cases) in verify.CASE_GROUPS.items():
            self.assertTrue(ready)
            preservation='originalSourceBytesUnchanged' if group=='free-scalar' else 'originalSourceBytesUnchangedOrRestoredAfterExplicitFixtureTest'
            for key,value in [('versionedDiagnosticsReady',False),('fixtureDeleted',False),('goplsAliveAfterCleanup',True),('goplsExit',137),('cleanupFailure','sharing violation'),('failure','planner failed'),('suppressedFailures',['occupied scratch']),(preservation,False)]:
                bad=self.group_result(group);bad['checks'][0][key]=value
                with self.subTest(group=group,key=key),self.assertRaisesRegex(RuntimeError,'failed'):
                    verify.require_results(bad,self.layout,expected_cases=cases,require_ready=ready,source_preservation=preservation)
            bad=self.group_result(group);bad['checks'][0].pop('versionedDiagnosticsReady')
            with self.subTest(group=group,key='missing readiness'),self.assertRaises(RuntimeError):
                verify.require_results(bad,self.layout,expected_cases=cases,require_ready=ready,source_preservation=preservation)

    def test_suite_rejects_successful_group_with_missing_duplicate_or_foreign_ids(self):
        for mutation in ['missing','duplicate','foreign']:
            def group_run(app,report,source_root,overlay,*,group,strict_sources):
                result=self.group_summary(group)
                if group=='method-scalar':
                    if mutation=='missing':result.pop('actualCaseIds')
                    elif mutation=='duplicate':result['actualCaseIds'][0]=result['actualCaseIds'][1]
                    else:result['actualCaseIds'][0]='dev.goforge.refactor.GoMethodAddParameterProtocolProbe#value-imports'
                return result
            with self.subTest(mutation=mutation),patch.object(verify,'verify',side_effect=group_run):
                result=verify.verify_suite(self.root,self.root/('invalid-'+mutation),self.root)
            self.assertFalse(result['passed']);self.assertFalse(result['packagedFeaturePassed'])
            self.assertIn('exact verified case IDs',result['failure'])
            self.assertEqual(['method-add','method-scalar'],list(result['groups']))

    def test_later_group_failure_or_exception_preserves_prior_reports_without_success(self):
        for mode in ['failed-report','exception']:
            def group_run(app,report,source_root,overlay,*,group,strict_sources):
                if group=='free-scalar':
                    if mode=='exception':raise PermissionError('late final-group cleanup')
                    return {'passed':False,'packagedFeaturePassed':False,'failure':'persistent owned cleanup failure'}
                return self.group_summary(group)
            with self.subTest(mode=mode),patch.object(verify,'verify',side_effect=group_run):
                result=verify.verify_suite(self.root,self.root/('late-'+mode),self.root)
            self.assertFalse(result['passed']);self.assertFalse(result['packagedFeaturePassed'])
            self.assertTrue(result['groups']['method-add']['passed']);self.assertTrue(result['groups']['method-scalar']['passed'])
            self.assertIn('cleanup',result['failure'])
            self.assertNotIn('cases',result)

    def test_suite_overlay_remains_control_and_inconsistent_group_claim_is_rejected(self):
        overlay=self.root/'overlay';overlay.mkdir()
        def group_run(app,report,source_root,value,*,group,strict_sources):return self.group_summary(group,value)
        with patch.object(verify,'verify',side_effect=group_run):
            result=verify.verify_suite(self.root,self.root/'overlay-suite',self.root,overlay)
        self.assertTrue(result['passed']);self.assertFalse(result['packagedFeaturePassed']);self.assertTrue(result['developmentOverlayOnly'])
        for key,value in [('packagedFeaturePassed',True),('developmentOverlayOnly',False)]:
            def wrong(app,report,source_root,received,*,group,strict_sources):
                result=self.group_summary(group,received)
                if group=='method-scalar':result[key]=value
                return result
            with self.subTest(key=key),patch.object(verify,'verify',side_effect=wrong):
                result=verify.verify_suite(self.root,self.root/('lying-'+key),self.root,overlay)
            self.assertFalse(result['passed']);self.assertFalse(result['packagedFeaturePassed'])
            self.assertIn('package/overlay identity',result['failure'])

    def test_each_group_late_cleanup_cannot_escape_as_suite_success(self):
        import zipfile
        with zipfile.ZipFile(self.layout['plugin'],'w') as archive:
            for name in ('GoMethodParameterSafety','GoFunctionReorder','GoFunctionReorderSource','GoFunctionReorderSafety','GoFunctionReorderSafetySource'):
                archive.writestr('dev/goforge/refactor/'+name+'.class',b'fixture')
        layout={**self.layout,**self.native_fixture(),'jars':[self.layout['plugin'],self.layout['lsp']]}
        for group,(name,_,_) in verify.CASE_GROUPS.items():
            source=self.root/'src/test/java/dev/goforge/refactor'/(name+'.java');source.parent.mkdir(parents=True,exist_ok=True);source.write_text('// owned fixture')
            owned=self.root/('owned-'+group);owned.mkdir()
            class LateCleanup:
                def __enter__(self):return str(owned)
                def __exit__(self,*ignored):raise PermissionError('late '+group+' cleanup failure')
            def command(executable,args,out,phase,*rest):
                if phase=='compile':
                    path=out/'classes/dev/goforge/refactor'/(name+'.class');path.parent.mkdir(parents=True);path.write_bytes(b'fixture');(out/'classes'/ (verify.NATIVE_BOOTSTRAP+'.class')).write_bytes(b'fixture')
                else:
                    (out/'protocol').mkdir();(out/'protocol/result.json').write_text(json.dumps(self.group_result(group)));(out/'native-binding.json').write_text(json.dumps(self.native_result(layout)))
                return {'exit':0}
            with self.subTest(group=group),patch.object(verify,'package_layout',return_value=layout),patch.object(verify,'run_java_tool',side_effect=command),patch.object(verify.tempfile,'TemporaryDirectory',return_value=LateCleanup()):
                result=verify.verify(self.root,self.root/('cleanup-'+group),self.root,group=group,strict_sources=True)
            self.assertFalse(result['passed']);self.assertFalse(result['packagedFeaturePassed'])
            self.assertIn('late '+group+' cleanup failure',result['failure'])

    def test_suite_cannot_omit_or_repeat_a_group(self):
        original=dict(verify.CASE_GROUPS)
        for mode in ['missing','repeated']:
            groups=dict(original)
            if mode=='missing':groups.pop('free-scalar')
            else:groups['repeat-method-add']=groups['method-add']
            def group_run(app,report,source_root,overlay,*,group,strict_sources):return self.group_summary(group,overlay)
            with self.subTest(mode=mode),patch.object(verify,'CASE_GROUPS',groups),patch.object(verify,'verify',side_effect=group_run):
                result=verify.verify_suite(self.root,self.root/('group-'+mode),self.root)
            self.assertFalse(result['passed']);self.assertFalse(result['packagedFeaturePassed'])
            self.assertIn('103 unique class-qualified',result['failure'])

    def test_cli_default_is_suite_and_legacy_flag_keeps_original_report_shape(self):
        for legacy in [False,True]:
            report=self.root/('cli-legacy' if legacy else 'cli-suite')
            args=[sys.executable,str(ROOT/'scripts/verify-method-refactoring.py'),'--app',str(self.root/'missing'),'--report-dir',str(report)]
            if legacy:args.append('--legacy-method-only')
            child=subprocess.run(args,capture_output=True,text=True,timeout=10)
            self.assertEqual(1,child.returncode)
            result=json.loads((report/'verification.json').read_text())
            self.assertFalse(result['passed']);self.assertFalse(result['packagedFeaturePassed'])
            self.assertEqual(not legacy,'groups' in result)
            if not legacy:
                self.assertTrue((report/'method-add/verification.json').is_file())
                self.assertEqual(103,len(result['expectedCaseIds']))

    def test_reorder_fixed_outputs_reject_equal_but_wrong_triples(self):
        good=self.group_result('free-function-reorder')
        comparisons=verify.require_reorder_behavior(good)
        self.assertEqual(9,len(comparisons))
        self.assertEqual('g|h|1,11|',comparisons['shared-state'])
        self.assertEqual('0.300000012,1099511627776|',comparisons['constants-exact-conversion'])
        self.assertEqual('g-recover:<nil>|h|1,11|boom',comparisons['recover-in-argument'])
        for case in comparisons:
            bad=copy.deepcopy(good)
            row=next(x for x in bad['checks'] if x['case']==case)
            row.update(beforeOutput='same but wrong',afterOutput='same but wrong',expectedOutput='same but wrong')
            with self.subTest(case=case),self.assertRaisesRegex(RuntimeError,'fixed behavior output'):
                verify.require_reorder_behavior(bad)

    def test_reorder_execution_proof_cannot_be_missing_or_claimed_by_negative_case(self):
        good=self.group_result('free-function-reorder')
        for field in ('fixtureAppliedExecutedAndRestored','beforeOutput','afterOutput','expectedOutput'):
            bad=copy.deepcopy(good);bad['checks'][0].pop(field)
            with self.subTest(field=field),self.assertRaisesRegex(RuntimeError,'fixed behavior output'):
                verify.require_reorder_behavior(bad)
        bad=copy.deepcopy(good);next(x for x in bad['checks'] if x['case']=='mutable-variable-refused')['fixtureAppliedExecutedAndRestored']=True
        with self.assertRaisesRegex(RuntimeError,'Unexpected reorder behavior'):verify.require_reorder_behavior(bad)

    def test_last_reorder_group_failure_cannot_reuse_successful_baseline_72(self):
        for mode in ('failed-report','exception','missing-behavior','wrong-behavior','failure-alongside-pass'):
            def group_run(app,report,source_root,overlay,*,group,strict_sources):
                result=self.group_summary(group)
                if group=='free-function-reorder':
                    if mode=='exception':raise PermissionError('last owned cleanup failure')
                    if mode=='failed-report':result.update(passed=False,packagedFeaturePassed=False,failure='last owned cleanup failure')
                    elif mode=='missing-behavior':result.pop('behaviorComparisons')
                    elif mode=='wrong-behavior':result['behaviorComparisons']['shared-state']='h|g|11,10|'
                    elif mode=='failure-alongside-pass':result['failure']='late cleanup failure'
                return result
            with self.subTest(mode=mode),patch.object(verify,'verify',side_effect=group_run):
                result=verify.verify_suite(self.root,self.root/('last-'+mode),self.root)
            self.assertFalse(result['passed']);self.assertFalse(result['packagedFeaturePassed'])
            self.assertNotIn('cases',result)
            self.assertEqual(72,sum(x['cases'] for key,x in result['groups'].items() if key!='free-function-reorder'))
            self.assertFalse(json.loads((self.root/('last-'+mode)/'verification.json').read_text())['passed'])

    def test_reorder_group_ids_and_declared_count_are_strict(self):
        for mutation in ('missing','duplicate','foreign','wrong-count'):
            def group_run(app,report,source_root,overlay,*,group,strict_sources):
                result=self.group_summary(group)
                if group=='free-function-reorder':
                    if mutation=='missing':result['actualCaseIds'].pop()
                    elif mutation=='duplicate':result['actualCaseIds'][0]=result['actualCaseIds'][1]
                    elif mutation=='foreign':result['actualCaseIds'][0]='dev.goforge.refactor.GoMethodAddParameterProtocolProbe#value-imports'
                    else:result['cases']=30
                return result
            with self.subTest(mutation=mutation),patch.object(verify,'verify',side_effect=group_run):
                result=verify.verify_suite(self.root,self.root/('reorder-'+mutation),self.root)
            self.assertFalse(result['passed']);self.assertFalse(result['packagedFeaturePassed'])
            self.assertIn('exact verified case IDs',result['failure'])

    def test_progress_is_flushed_per_group_and_contains_only_bounded_metadata(self):
        def group_run(app,report,source_root,overlay,*,group,strict_sources):return self.group_summary(group)
        with patch.object(verify,'verify',side_effect=group_run),patch('builtins.print') as output:
            result=verify.verify_suite(self.root,self.root/'progress',self.root)
        self.assertTrue(result['passed']);self.assertEqual(8,output.call_count)
        expected_completed=[0,23,23,52,52,72,72,103]
        for i,call in enumerate(output.call_args_list):
            self.assertEqual({'flush':True},call.kwargs)
            message=call.args[0];group=verify.SUITE_GROUPS[i//2]
            self.assertRegex(message,r'^\[method-refactoring\] '+('start' if i%2==0 else 'end')+' group='+group+r' cases=(23|29|20|31) completed='+str(expected_completed[i])+r'/103 elapsed=\d+\.\d{3}s'+('' if i%2==0 else ' status=passed')+'$')
            self.assertNotIn(str(self.root),message)

    def test_failed_progress_does_not_expose_failure_payload_or_start_later_groups(self):
        secret='sentinel-secret '+str(self.root)
        def group_run(app,report,source_root,overlay,*,group,strict_sources):
            if group=='method-scalar':raise RuntimeError(secret)
            return self.group_summary(group)
        with patch.object(verify,'verify',side_effect=group_run),patch('builtins.print') as output:
            result=verify.verify_suite(self.root,self.root/'progress-failed',self.root)
        self.assertFalse(result['passed']);self.assertEqual(4,output.call_count)
        messages=[call.args[0] for call in output.call_args_list]
        self.assertIn('end group=method-scalar cases=29 completed=23/103',messages[-1])
        self.assertTrue(messages[-1].endswith('status=failed'))
        for call in output.call_args_list:
            self.assertEqual({'flush':True},call.kwargs);self.assertNotIn(secret,call.args[0]);self.assertNotIn(str(self.root),call.args[0])

    def test_reorder_behavior_is_checked_by_real_verify_entry_before_success(self):
        import zipfile
        with zipfile.ZipFile(self.layout['plugin'],'w') as archive:
            for name in ('GoMethodParameterSafety','GoFunctionReorder','GoFunctionReorderSource','GoFunctionReorderSafety','GoFunctionReorderSafetySource'):
                archive.writestr('dev/goforge/refactor/'+name+'.class',b'fixture')
        layout={**self.layout,**self.native_fixture(),'jars':[self.layout['plugin'],self.layout['lsp']]}
        source=self.root/'src/test/java/dev/goforge/refactor/GoFunctionReorderProtocolProbe.java';source.parent.mkdir(parents=True);source.write_text('// owned fixture')
        for valid in (True,False):
            def command(executable,args,out,phase,*rest):
                if phase=='compile':
                    path=out/'classes/dev/goforge/refactor/GoFunctionReorderProtocolProbe.class';path.parent.mkdir(parents=True);path.write_bytes(b'fixture');(out/'classes'/(verify.NATIVE_BOOTSTRAP+'.class')).write_bytes(b'fixture')
                else:
                    raw=self.group_result('free-function-reorder')
                    if not valid:raw['checks'][0]['afterOutput']='changed order'
                    (out/'protocol').mkdir();(out/'protocol/result.json').write_text(json.dumps(raw));(out/'native-binding.json').write_text(json.dumps(self.native_result(layout)))
                return {'exit':0}
            with self.subTest(valid=valid),patch.object(verify,'package_layout',return_value=layout),patch.object(verify,'run_java_tool',side_effect=command):
                result=verify.verify(self.root,self.root/('real-entry-'+str(valid)),self.root,group='free-function-reorder',strict_sources=True)
            self.assertEqual(valid,result['passed']);self.assertEqual(valid,result['packagedFeaturePassed'])
            if valid:self.assertEqual(verify.REORDER_BEHAVIOR,result['behaviorComparisons'])
            else:self.assertIn('fixed behavior output',result['failure'])

    def phase_fixture(self):
        import zipfile
        with zipfile.ZipFile(self.layout['plugin'],'w') as archive:archive.writestr('dev/goforge/refactor/GoMethodParameterSafety.class',b'fixture')
        source=self.root/verify.PROBE;source.parent.mkdir(parents=True);source.write_text('// fixture')
        return {**self.layout,'jars':[self.layout['plugin'],self.layout['lsp']]}

    def phase_command(self,executable,args,out,name,*rest):
        current=json.loads((out/'phase-checkpoint.json').read_text())['current']
        self.assertEqual('javac' if name=='compile' else 'jvm',current['phase'])
        self.assertEqual('start',current['event'])
        if name=='compile':
            path=out/'classes/dev/goforge/refactor/GoMethodAddParameterProtocolProbe.class';path.parent.mkdir(parents=True);path.write_bytes(b'fixture')
        else:
            (out/'protocol').mkdir();(out/'protocol/result.json').write_text(json.dumps(self.result))
        return {'exit':0}

    def test_phase_checkpoints_exist_before_child_commands_and_finish_after_cleanup(self):
        layout=self.phase_fixture();report=self.root/'phased-success'
        with patch.object(verify,'package_layout',return_value=layout),patch.object(verify,'run_java_tool',side_effect=self.phase_command),patch('builtins.print') as output:
            result=verify.verify(self.root,report,self.root)
        self.assertTrue(result['passed']);self.assertTrue(result['packagedFeaturePassed'])
        state=json.loads((report/'phase-checkpoint.json').read_text())
        expected=[(phase,event) for phase in ('layout','hash','temp-allocation','javac','jvm','result-validation','temp-cleanup') for event in ('start','completed')]
        self.assertEqual(expected,[(x['phase'],x['event']) for x in state['events']]);self.assertEqual([],state['failedPhases'])
        self.assertTrue(state['diagnosticOnly']);self.assertNotIn('passed',state)
        self.assertEqual(list(range(1,15)),[x['sequence'] for x in state['events']])
        self.assertEqual(sorted(x['elapsedSeconds'] for x in state['events']),[x['elapsedSeconds'] for x in state['events']])
        self.assertFalse((report/'phase-checkpoint.tmp').exists())
        self.assertEqual(14,output.call_count)
        for call in output.call_args_list:
            self.assertEqual({'flush':True},call.kwargs)
            self.assertRegex(call.args[0],r'^\[method-refactoring\] phase=(layout|hash|temp-allocation|javac|jvm|result-validation|temp-cleanup) group=method-add event=(start|completed)$')
            self.assertNotIn(str(self.root),call.args[0])

    def test_hash_failure_is_recorded_before_any_temp_or_child_start(self):
        layout=self.phase_fixture();report=self.root/'hash-failure'
        with patch.object(verify,'package_layout',return_value=layout),patch.object(verify,'sha256',side_effect=PermissionError('owned hash read failed')),patch.object(verify,'run_java_tool') as child,patch.object(verify.tempfile,'TemporaryDirectory') as temporary:
            result=verify.verify(self.root,report,self.root)
        self.assertFalse(result['passed']);child.assert_not_called();temporary.assert_not_called()
        state=json.loads((report/'phase-checkpoint.json').read_text())
        self.assertEqual(('hash','failed'),(state['current']['phase'],state['current']['event']))
        self.assertEqual(['hash'],state['failedPhases']);self.assertIn('owned hash read failed',result['failure'])

    def test_temp_allocation_failure_has_a_checkpoint_without_false_cleanup(self):
        layout=self.phase_fixture();report=self.root/'allocation-failure'
        with patch.object(verify,'package_layout',return_value=layout),patch.object(verify.tempfile,'TemporaryDirectory',side_effect=PermissionError('owned allocation failed')),patch.object(verify,'run_java_tool') as child:
            result=verify.verify(self.root,report,self.root)
        self.assertFalse(result['passed']);child.assert_not_called()
        state=json.loads((report/'phase-checkpoint.json').read_text())
        self.assertEqual(['temp-allocation'],state['failedPhases']);self.assertEqual('failed',state['current']['event'])
        self.assertNotIn('temp-cleanup',[x['phase'] for x in state['events']])

    def test_cleanup_failure_retains_prior_failed_phase_and_never_becomes_success(self):
        layout=self.phase_fixture()
        for earlier_failure in (False,True):
            owned=self.root/('owned-'+str(earlier_failure));owned.mkdir();report=self.root/('cleanup-phases-'+str(earlier_failure))
            class CleanupFailure:
                def __enter__(self):return str(owned)
                def __exit__(self,*ignored):raise PermissionError('owned cleanup failed')
            def command(*args):
                if earlier_failure:raise RuntimeError('original compile failure')
                return self.phase_command(*args)
            with self.subTest(earlier_failure=earlier_failure),patch.object(verify,'package_layout',return_value=layout),patch.object(verify.tempfile,'TemporaryDirectory',return_value=CleanupFailure()),patch.object(verify,'run_java_tool',side_effect=command):
                result=verify.verify(self.root,report,self.root)
            self.assertFalse(result['passed']);self.assertFalse(result['packagedFeaturePassed'])
            state=json.loads((report/'phase-checkpoint.json').read_text())
            self.assertEqual(['javac','temp-cleanup'] if earlier_failure else ['temp-cleanup'],state['failedPhases'])
            self.assertEqual(('temp-cleanup','failed'),(state['current']['phase'],state['current']['event']))
            self.assertIn('owned cleanup failed',result['failure'])
            self.assertFalse(json.loads((report/'verification.json').read_text())['passed'])

    def test_checkpoint_survives_abrupt_worker_exit_without_claiming_success(self):
        report=self.root/'abrupt';report.mkdir();script=ROOT/'scripts/verify-method-refactoring.py'
        code="import importlib.util,os;from pathlib import Path;s=importlib.util.spec_from_file_location('v',"+repr(str(script))+");m=importlib.util.module_from_spec(s);s.loader.exec_module(m);r=m.PhaseRecorder(Path("+repr(str(report))+"),'method-add');r.checkpoint('jvm','start');os._exit(23)"
        child=subprocess.run([sys.executable,'-B','-c',code],capture_output=True,text=True,timeout=10)
        self.assertEqual(23,child.returncode)
        state=json.loads((report/'phase-checkpoint.json').read_text())
        self.assertEqual('start',state['current']['event']);self.assertEqual('jvm',state['current']['phase'])
        self.assertEqual(1,len(state['events']));self.assertTrue(state['diagnosticOnly']);self.assertNotIn('passed',state)
        self.assertIn('phase=jvm group=method-add event=start',child.stdout)
        self.assertFalse((report/'verification.json').exists())

    def test_phase_payload_is_fixed_bounded_and_contains_no_failure_detail(self):
        report=self.root/'phase-bound';report.mkdir();secret='sentinel private path '+str(self.root)
        recorder=verify.PhaseRecorder(report,'method-add')
        with patch('builtins.print') as output:
            with self.assertRaisesRegex(RuntimeError,'sentinel'):
                with recorder.phase('jvm'):raise RuntimeError(secret)
            for _ in range(30):recorder.checkpoint('jvm','start')
            saved=(report/'phase-checkpoint.json').read_bytes()
            with self.assertRaisesRegex(RuntimeError,'event limit'):recorder.checkpoint('jvm','start')
            with self.assertRaises(ValueError):recorder.checkpoint(secret,'start')
        self.assertEqual(saved,(report/'phase-checkpoint.json').read_bytes());self.assertNotIn(secret,saved.decode())
        self.assertEqual(32,len(json.loads(saved)['events']))
        self.assertNotIn(secret,' '.join(call.args[0] for call in output.call_args_list))

    def test_checkpoint_io_failure_still_cleans_temporary_directory_and_refuses_success(self):
        layout=self.phase_fixture();factory=verify.tempfile.TemporaryDirectory
        for operation,fail_at in (('fsync',6),('fsync',13),('replace',14)):
            ended=[];underlying=getattr(verify.os,operation);calls=[0]
            class ObservedTemporary:
                def __init__(self,**kwargs):self.delegate=factory(**kwargs)
                def __enter__(self):return self.delegate.__enter__()
                def __exit__(self,*args):
                    value=self.delegate.__exit__(*args);ended.append(not Path(self.delegate.name).exists());return value
            def fail_once(*args,**kwargs):
                calls[0]+=1
                if calls[0]==fail_at:raise OSError('owned diagnostic I/O failure')
                return underlying(*args,**kwargs)
            report=self.root/('io-'+operation+'-'+str(fail_at))
            with self.subTest(operation=operation,fail_at=fail_at),patch.object(verify,'package_layout',return_value=layout),patch.object(verify,'run_java_tool',side_effect=self.phase_command),patch.object(verify.tempfile,'TemporaryDirectory',side_effect=ObservedTemporary),patch.object(verify.os,operation,side_effect=fail_once):
                result=verify.verify(self.root,report,self.root)
            self.assertEqual([True],ended)
            self.assertFalse(result['passed']);self.assertFalse(result['packagedFeaturePassed'])
            self.assertIn('diagnostic checkpoint',result['failure'])
            self.assertFalse(json.loads((report/'verification.json').read_text())['passed'])

if __name__=='__main__':unittest.main()
