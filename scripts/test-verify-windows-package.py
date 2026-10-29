#!/usr/bin/env python3
"""Portable verifier regressions; passing these is not Windows-native acceptance."""
from pathlib import Path
import contextlib
import importlib.util
import io
import json
import os
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.dont_write_bytecode=True
ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('goforge_native_verifier',ROOT/'scripts/verify-windows-package.py')
verify=importlib.util.module_from_spec(spec);spec.loader.exec_module(verify)


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


if __name__=='__main__':unittest.main()
