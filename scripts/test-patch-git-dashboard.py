#!/usr/bin/env python3
"""Focused Git dashboard patch regression on disposable files; no IDE or Gradle."""
from __future__ import annotations
import argparse
from datetime import datetime, timezone
import importlib.util
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('git_dashboard_patch', ROOT / 'scripts/patch-git-dashboard.py')
patcher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(patcher)


def hashes(path):
    with zipfile.ZipFile(path) as archive:
        return {n: patcher.digest(archive.read(n)) for n in archive.namelist()}


def extract_platform(path, target):
    with zipfile.ZipFile(path) as archive:
        identities = [n for n in archive.namelist() if PurePosixPath(n).name == 'product-info.json' and len(PurePosixPath(n).parts) <= 2]
        if len(identities) != 1:
            raise ValueError('Expected one official Windows product-info.json')
        root = PurePosixPath(identities[0]).parent
        seen = set()
        for entry in archive.infolist():
            name = PurePosixPath(entry.filename)
            if name.is_absolute() or '..' in name.parts or '\\' in entry.filename or ':' in entry.filename:
                raise ValueError('Unsafe platform archive path')
            try:
                relative = name.relative_to(root)
            except ValueError:
                continue
            selected = (relative.as_posix() == 'product-info.json'
                        or len(relative.parts) == 2 and relative.parts[0] == 'lib' and relative.suffix == '.jar'
                        or relative.parent.as_posix() == 'plugins/vcs-git/lib' and relative.suffix == '.jar')
            if entry.is_dir() or not selected:
                continue
            if relative.as_posix().casefold() in seen or (entry.external_attr >> 16) & 0o170000 == 0o120000:
                raise ValueError('Duplicate or linked archive path')
            seen.add(relative.as_posix().casefold())
            output = target / relative.as_posix()
            output.parent.mkdir(parents=True, exist_ok=True)
            output.write_bytes(archive.read(entry))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    inputs = parser.add_mutually_exclusive_group(required=True)
    inputs.add_argument('--platform-home', type=Path)
    inputs.add_argument('--platform-zip', type=Path)
    parser.add_argument('--javac', type=Path, default=ROOT / '.tools/jdk/bin/javac')
    parser.add_argument('--output', type=Path, default=ROOT / '.artifacts/git-dashboard-patch' / datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ'))
    args = parser.parse_args()
    report_dir = args.output.resolve()
    report_dir.mkdir(parents=True, exist_ok=False)
    checks = {}
    result = {'passed': False, 'checks': checks, 'native_gui_started': False, 'gradle_sdk_modified': False}
    try:
        with tempfile.TemporaryDirectory(prefix='platform-', dir=report_dir) as temporary:
            work = Path(temporary)
            home = work / 'stage'
            home.mkdir()
            if args.platform_zip:
                source = work / 'source'
                extract_platform(args.platform_zip, source)
            else:
                source = args.platform_home.resolve(strict=True)
            source_jar = source / patcher.JAR
            source_digest = patcher.digest(source_jar.read_bytes())
            result['source_jar_sha256'] = source_digest
            identity = next(p for p in (source / 'product-info.json', source / 'Resources/product-info.json') if p.is_file())
            shutil.copytree(source / 'lib', home / 'lib')
            shutil.copytree(source / 'plugins/vcs-git/lib', home / 'plugins/vcs-git/lib')
            shutil.copy2(identity, home / 'product-info.json')
            jar = home / patcher.JAR
            receipt = patcher.patch(home, args.javac)
            (report_dir / 'patch-receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
            before, after = hashes(source_jar), hashes(jar)
            checks['only_pinned_constructor_class_changed'] = {n for n in before if n in after and before[n] != after[n]} == set(patcher.ORIGINAL)
            checks['exact_helper_and_marker_added'] = set(after) - set(before) == patcher.HELPERS | {patcher.MARKER}
            checks['only_obsolete_generated_index_removed'] = set(before) - set(after) == {patcher.INDEX}
            checks['original_coroutine_listener_finally_unchanged'] = all(before[n] == after[n] == h for n, h in patcher.COLLABORATORS.items())
            checks['all_other_entries_preserved'] = all(before[n] == after[n] for n in before if n not in patcher.ORIGINAL and n != patcher.INDEX)
            first = patcher.digest(jar.read_bytes())
            repeated = patcher.patch(home, args.javac)
            checks['idempotent_without_rewrite'] = repeated['already_patched'] and first == patcher.digest(jar.read_bytes())
            patched_archive = work / 'patched-original.jar'
            shutil.copy2(jar, patched_archive)

            classes = work / 'probe-classes'
            classes.mkdir()
            cp = os.pathsep.join((str(home / 'lib/*'), str(home / 'plugins/vcs-git/lib/*')))
            patcher.run(args.javac, '--release', '21', '-g:none', '-cp', cp, '-d', classes, patcher.SOURCES / 'GitDashboardEdtProbe.java')
            java = args.javac.resolve().with_name('java.exe' if args.javac.suffix.lower() == '.exe' else 'java')
            opens = [a for a in json.loads(identity.read_text())['launch'][0].get('additionalJvmArguments', []) if a.startswith(('--add-opens=', '--add-exports='))]
            command = [str(java), *opens, '-ea', '-Xverify:all', '-Djava.awt.headless=true',
                       '-Djava.system.class.loader=com.intellij.util.lang.PathClassLoader',
                       '-Didea.config.path=' + str(work / 'config'), '-Didea.system.path=' + str(work / 'system')]
            fixed = subprocess.run([*command, '-cp', str(classes) + os.pathsep + cp, 'GitDashboardEdtProbe'], capture_output=True, text=True, timeout=45)
            (report_dir / 'patched-probe.log').write_text(fixed.stdout + fixed.stderr)
            checks['real_dispatcher_tree_expansion_and_lifecycle'] = (fixed.returncode == 0
                and 'actual_patched_callsite=true' in fixed.stdout
                and 'git_dashboard_real_dispatcher_lifecycle_checks=21' in fixed.stdout
                and 'PASS block errors propagate unchanged' in fixed.stdout)
            if not checks['real_dispatcher_tree_expansion_and_lifecycle']:
                raise AssertionError('Patched probe failed; inspect patched-probe.log')
            baseline = work / 'baseline'
            with zipfile.ZipFile(source_jar) as archive:
                for name in patcher.ORIGINAL:
                    target = baseline / name
                    target.parent.mkdir(parents=True, exist_ok=True)
                    target.write_bytes(archive.read(name))
            original = subprocess.run([*command, '-cp', str(classes) + os.pathsep + str(baseline) + os.pathsep + cp, 'GitDashboardEdtProbe'], capture_output=True, text=True, timeout=45)
            original_log = original.stdout + original.stderr
            (report_dir / 'unpatched-baseline.log').write_text(original_log)
            checks['same_probe_fails_before_at_real_tree_read_lock'] = (original.returncode != 0
                and 'actual_patched_callsite=false' in original_log
                and 'ThreadingSupport$LockAccessDisallowed' in original_log
                and 'TreeUtil.lambda$promiseVisit' in original_log
                and 'TreeUtil.expandAll' in original_log)

            def rejected(label, text):
                digest = patcher.digest(jar.read_bytes())
                try:
                    patcher.patch(home, args.javac)
                    checks[label] = False
                except ValueError as error:
                    checks[label] = text in str(error) and digest == patcher.digest(jar.read_bytes())

            with zipfile.ZipFile(source_jar) as archive, zipfile.ZipFile(jar, 'w') as altered:
                altered.comment = archive.comment
                for entry in archive.infolist():
                    data = archive.read(entry.filename)
                    if entry.filename in patcher.ORIGINAL:
                        data += b'unknown class revision'
                    altered.writestr(entry, data)
            rejected('unknown_class_rejected_before_write', 'Unexpected upstream class SHA256')
            shutil.copy2(source_jar, jar)
            with zipfile.ZipFile(jar, 'a') as archive:
                archive.writestr('META-INF/unreviewed-fixture.txt', b'unknown archive content')
            rejected('unknown_archive_rejected_before_write', 'Unexpected complete upstream vcs-git.jar SHA256')
            shutil.copy2(patched_archive, jar)
            with zipfile.ZipFile(jar, 'a') as archive:
                archive.writestr('META-INF/unreviewed-fixture.txt', b'unknown patched content')
            rejected('tampered_patched_archive_rejected_before_write', 'Unchanged patched archive content was modified')
            jar.unlink()
            os.link(source_jar, jar)
            rejected('hardlinked_archive_rejected_before_write', 'Refusing a linked platform JAR')
            jar.unlink()
            shutil.copy2(source_jar, jar)
            with tempfile.TemporaryDirectory(prefix='goforge-unowned-probe-') as unowned:
                try:
                    patcher.patch(Path(unowned), args.javac)
                    checks['unowned_target_rejected'] = False
                except ValueError as error:
                    checks['unowned_target_rejected'] = 'Only GoForge-owned' in str(error)
            checks['input_platform_unchanged'] = source_digest == patcher.digest(source_jar.read_bytes())
        result['passed'] = all(checks.values())
        result['limits'] = 'Real platform dispatcher, lock, isolated kernel, TreeUtil and component lifecycle probe; native Git model/tool-window acceptance and native Windows desktop remain separate.'
        if not result['passed']:
            raise AssertionError('Failed checks: ' + ', '.join(k for k, v in checks.items() if not v))
    except BaseException as error:
        result['error'] = str(error)
        raise
    finally:
        (report_dir / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
