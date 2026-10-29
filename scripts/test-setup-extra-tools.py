#!/usr/bin/env python3
"""Offline filesystem/process-contract regressions; not Windows-native acceptance."""
from pathlib import Path, PurePosixPath, PureWindowsPath
import hashlib
import importlib.util
import io
import json
import subprocess
import sys
import tarfile
import tempfile
import unittest
from unittest.mock import patch

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('goforge_extra_tools_tested', ROOT / 'scripts/setup-extra-tools.py')
extra = importlib.util.module_from_spec(spec)
spec.loader.exec_module(extra)


class Preparation(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='goforge-extra-tests-')
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name).resolve()
        self.prep = extra.Preparer(self.root)
        self.destination = self.root / '.tools/cli'
        self.archive = self.root / 'fixture.tgz'
        files = {'devcontainer.js': b'fixture', 'nested/notice.txt': b'license'}
        with tarfile.open(self.archive, 'w:gz') as archive:
            for name, data in files.items():
                info = tarfile.TarInfo('package/' + name)
                info.size = len(data)
                archive.addfile(info, io.BytesIO(data))
        self.description = {'url': 'https://fixture.invalid/cli.tgz',
                            'archiveSha256': hashlib.sha256(self.archive.read_bytes()).hexdigest(),
                            'files': {name: hashlib.sha256(data).hexdigest() for name, data in files.items()}}

    def tree(self):
        self.prep.tool_tree(self.destination, self.description, 'package', 'fixture.tgz', exact=True)

    def test_fresh_tree_needs_no_process_command_and_exact_tree_is_reused(self):
        with patch.object(self.prep, 'obtain', return_value=self.archive) as obtain, \
                patch.object(extra, 'require_tool_directory_idle', side_effect=AssertionError('not a repair')):
            self.tree()
            self.tree()
        obtain.assert_called_once()
        self.assertEqual(b'fixture', (self.destination / 'devcontainer.js').read_bytes())
        self.assertTrue(self.prep.actions[-1].startswith('reused verified'))

    def test_existing_tree_without_repair_is_never_downloaded_or_replaced(self):
        self.destination.mkdir(parents=True)
        original = self.destination / 'owned.txt'; original.write_bytes(b'unchanged')
        with patch.object(self.prep, 'obtain', side_effect=AssertionError('must not download')):
            with self.assertRaisesRegex(RuntimeError, 'Incomplete or changed'):
                self.tree()
        self.assertEqual(b'unchanged', original.read_bytes())

    def test_repair_refuses_running_tool_and_preserves_original(self):
        self.destination.mkdir(parents=True)
        original = self.destination / 'owned.txt'; original.write_bytes(b'unchanged')
        self.prep.repair = True
        with patch.object(self.prep, 'obtain', return_value=self.archive), \
                patch.object(extra, 'require_tool_directory_idle', side_effect=RuntimeError('using this tool')):
            with self.assertRaisesRegex(RuntimeError, 'using this tool'): self.tree()
        self.assertEqual(b'unchanged', original.read_bytes())
        self.assertFalse(self.prep.backup.exists())

    def test_idle_repair_preserves_backup_and_restores_pinned_files(self):
        self.destination.mkdir(parents=True)
        (self.destination / 'owned.txt').write_bytes(b'old')
        self.prep.repair = True
        with patch.object(self.prep, 'obtain', return_value=self.archive), \
                patch.object(extra, 'require_tool_directory_idle') as idle:
            self.tree()
        idle.assert_called_once_with(self.destination)
        self.assertEqual(b'old', (self.prep.backup / '.tools/cli/owned.txt').read_bytes())
        self.assertEqual(b'fixture', (self.destination / 'devcontainer.js').read_bytes())

    def test_concurrent_destination_is_not_replaced_without_repair(self):
        def downloaded(*args):
            self.destination.mkdir(parents=True)
            (self.destination / 'new-owner.txt').write_bytes(b'preserve')
            return self.archive
        with patch.object(self.prep, 'obtain', side_effect=downloaded):
            with self.assertRaisesRegex(RuntimeError, 'appeared during preparation'): self.tree()
        self.assertEqual(b'preserve', (self.destination / 'new-owner.txt').read_bytes())

    def test_destination_creation_is_exclusive(self):
        original_copy = extra.shutil.copytree
        def other_creator(source, destination):
            destination.mkdir(parents=True)
            (destination / 'sentinel').write_bytes(b'concurrent')
            return original_copy(source, destination)
        with patch.object(self.prep, 'obtain', return_value=self.archive), \
                patch.object(extra.shutil, 'copytree', side_effect=other_creator):
            with self.assertRaises(FileExistsError): self.tree()
        self.assertEqual(b'concurrent', (self.destination / 'sentinel').read_bytes())
        self.assertFalse((self.destination / 'devcontainer.js').exists())

    def test_external_destination_is_rejected_before_io(self):
        self.destination = self.root.parent / 'not-owned-by-fixture'
        with patch.object(self.prep, 'obtain', side_effect=AssertionError('must not download')):
            with self.assertRaisesRegex(RuntimeError, 'external preparation path'): self.tree()

    def download_contract(self, data, expected, *, fail_read=False):
        """Emulate Windows share-delete restrictions while doing real temp-file IO."""
        streams = []; original_temp = extra.tempfile.NamedTemporaryFile
        original_replace = extra.os.replace; original_unlink = Path.unlink
        def named_temporary(*args, **kwargs):
            stream = original_temp(*args, **kwargs); streams.append(stream); return stream
        def replace_closed(source, destination):
            self.assertTrue(streams and all(s.closed for s in streams), 'rename with an open temporary handle')
            return original_replace(source, destination)
        def unlink_closed(path, *args, **kwargs):
            self.assertTrue(all(s.closed for s in streams), 'cleanup with an open temporary handle')
            return original_unlink(path, *args, **kwargs)
        class Broken(io.BytesIO):
            def read(self, size=-1): raise OSError('fixture network failure')
        response = Broken(data) if fail_read else io.BytesIO(data)
        with patch.object(extra.tempfile, 'NamedTemporaryFile', side_effect=named_temporary), \
                patch.object(extra.urllib.request, 'urlopen', return_value=response), \
                patch.object(extra.os, 'replace', side_effect=replace_closed), \
                patch.object(Path, 'unlink', new=unlink_closed):
            return self.prep.obtain('https://fixture.invalid/file', 'download.bin', expected)

    def test_download_closes_handle_before_hash_replace_and_cleanup(self):
        data = b'actual downloaded bytes'
        target = self.download_contract(data, hashlib.sha256(data).hexdigest())
        self.assertEqual(data, target.read_bytes())
        self.assertEqual([target], list(self.prep.cache.iterdir()))
        self.assertEqual(1, self.prep.downloads)

    def test_failed_hash_closes_and_removes_temporary_preserving_existing_cache(self):
        self.prep.cache.mkdir(parents=True)
        target = self.prep.cache / 'download.bin'; target.write_bytes(b'old')
        self.prep.repair = True
        with self.assertRaisesRegex(RuntimeError, 'checksum mismatch'):
            self.download_contract(b'bad', hashlib.sha256(b'expected').hexdigest())
        self.assertEqual(b'old', target.read_bytes())
        self.assertEqual([target], list(self.prep.cache.iterdir()))
        self.assertFalse(self.prep.backup.exists())

    def test_failed_read_closes_before_cleanup(self):
        with self.assertRaisesRegex(OSError, 'fixture network failure'):
            self.download_contract(b'bad', 'unused', fail_read=True)
        self.assertEqual([], list(self.prep.cache.iterdir()))


class ProcessInventory(unittest.TestCase):
    destination = PureWindowsPath('C:/Project Space/项目/.tools/cli')
    idle = {'ProcessId': 17, 'ExecutablePath': r'C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe',
            'CommandLine': 'powershell -NoProfile -Command inventory'}

    def inventory(self, entries):
        return patch.object(extra.subprocess, 'run', return_value=subprocess.CompletedProcess(
            [], 0, json.dumps(entries, ensure_ascii=False), ''))

    def check(self):
        extra.require_tool_directory_idle(self.destination, windows=True, environment={'SYSTEMROOT': r'C:\Windows'})

    def test_windows_native_command_utf8_and_kernel_pid_exemption(self):
        with self.inventory([{'ProcessId': 0, 'ExecutablePath': None, 'CommandLine': None},
                             {'ProcessId': 4, 'ExecutablePath': None, 'CommandLine': None}, self.idle]) as run:
            self.check()
        args, kwargs = run.call_args
        self.assertTrue(args[0][0].replace('\\', '/').endswith('/System32/WindowsPowerShell/v1.0/powershell.exe'))
        self.assertIn('Get-CimInstance Win32_Process', args[0][-1])
        self.assertEqual('utf-8', kwargs['encoding']); self.assertEqual(30, kwargs['timeout'])

    def test_windows_case_slashes_and_non_ascii_running_tool_are_detected(self):
        for line in [r'node "c:\PROJECT SPACE\项目\.tools\CLI\devcontainer.js"',
                     'node "C:/Project Space/项目/.tools/cli/devcontainer.js"']:
            with self.subTest(line=line), self.inventory([{**self.idle, 'CommandLine': line}]):
                with self.assertRaisesRegex(RuntimeError, 'using this tool directory'): self.check()

    def test_native_executable_path_under_tree_is_detected(self):
        with self.inventory([{**self.idle, 'ExecutablePath': str(self.destination / 'node.exe')} ]):
            with self.assertRaisesRegex(RuntimeError, 'using this tool directory'): self.check()

    def test_similarly_named_sibling_is_not_the_owned_directory(self):
        with self.inventory([{**self.idle, 'CommandLine': str(self.destination) + '-other/node.exe'}]): self.check()

    def test_unreadable_user_or_system_process_is_uncertainty_not_idle(self):
        for pid in (22, 503):
            with self.subTest(pid=pid), self.inventory([{'ProcessId': pid, 'ExecutablePath': None, 'CommandLine': None}]):
                with self.assertRaisesRegex(RuntimeError, 'unreadable command line'): self.check()

    def test_invalid_inventory_is_never_treated_as_idle(self):
        for entries in ({}, [], [None], [{'ProcessId': 20}], [{**self.idle, 'ProcessId': True}],
                        [{**self.idle, 'CommandLine': ''}], [{**self.idle, 'CommandLine': 3}]):
            with self.subTest(entries=entries), self.inventory(entries):
                with self.assertRaises(RuntimeError): self.check()
        with patch.object(extra.subprocess, 'run', return_value=subprocess.CompletedProcess([], 0, '', '')):
            with self.assertRaisesRegex(RuntimeError, 'invalid Windows process inventory'): self.check()

    def test_missing_systemroot_and_failed_query_are_not_bypassed(self):
        with self.assertRaisesRegex(RuntimeError, 'SystemRoot'):
            extra.require_tool_directory_idle(self.destination, windows=True, environment={})
        for error in (FileNotFoundError('missing powershell'), subprocess.CalledProcessError(1, ['powershell'])):
            with patch.object(extra.subprocess, 'run', side_effect=error):
                with self.assertRaisesRegex(RuntimeError, 'repair was not attempted'): self.check()

    def test_posix_running_tool_and_empty_inventory_are_rejected(self):
        for stdout, pattern in (('/opt/tools/node', 'using this tool directory'), ('', 'empty POSIX')):
            with patch.object(extra.subprocess, 'run', return_value=subprocess.CompletedProcess([], 0, stdout, '')):
                with self.assertRaisesRegex(RuntimeError, pattern):
                    # This branch models POSIX even when the test runs on Windows.
                    extra.require_tool_directory_idle(PurePosixPath('/opt/tools'), windows=False)


if __name__ == '__main__': unittest.main()
