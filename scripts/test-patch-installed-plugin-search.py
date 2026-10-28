#!/usr/bin/env python3
"""Exercise the pinned platform patch on a disposable copy; no IDE or Gradle is started."""
from __future__ import annotations
import argparse
from datetime import datetime, timezone
import importlib.util
import json
import os
from pathlib import Path
from pathlib import PurePosixPath
import shutil
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('installed_search_patch', ROOT / 'scripts/patch-installed-plugin-search.py')
patcher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(patcher)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    inputs = parser.add_mutually_exclusive_group(required=True)
    inputs.add_argument('--platform-home', type=Path, help='Pristine platform Contents/ or Windows platform root')
    inputs.add_argument('--platform-zip', type=Path, help='Pristine official Windows platform ZIP; only lib and product-info are copied')
    parser.add_argument('--javac', type=Path, default=ROOT / '.tools/jdk/bin/javac')
    parser.add_argument('--output', type=Path, default=ROOT / '.artifacts/installed-search-patch' / datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ'))
    args = parser.parse_args()
    report_dir = args.output.resolve()
    report_dir.mkdir(parents=True, exist_ok=False)
    source_temp = None
    if args.platform_zip:
        source_temp = tempfile.TemporaryDirectory(prefix='archive-input-', dir=report_dir)
        source = Path(source_temp.name)
        extract_platform_lib(args.platform_zip, source)
    else:
        source = args.platform_home.resolve(strict=True)
    source_jar = source / 'lib/app-client.jar'
    original_digest = patcher.digest(source_jar.read_bytes())
    checks = {}
    try:
        with tempfile.TemporaryDirectory(prefix='platform-', dir=report_dir) as temporary:
            home = Path(temporary)
            shutil.copytree(source / 'lib', home / 'lib')
            identity = next(p for p in (source / 'product-info.json', source / 'Resources/product-info.json') if p.is_file())
            shutil.copy2(identity, home / 'product-info.json')
            receipt = patcher.patch(home, args.javac)
            checks['patched_six_pinned_classes'] = len(receipt['original_classes']) == 6
            before = {e.filename: patcher.digest(z) for e, z in entries(source_jar)}
            after = {e.filename: patcher.digest(z) for e, z in entries(home / 'lib/app-client.jar')}
            checks['exact_changed_entries'] = {n for n in before if n in after and before[n] != after[n]} == set(patcher.ORIGINAL)
            checks['exact_added_entries'] = set(after) - set(before) == patcher.HELPERS | {patcher.MARKER}
            checks['only_obsolete_generated_index_removed'] = set(before) - set(after) == {patcher.INDEX}
            checks['unchanged_entry_preservation'] = all(before[n] == after[n] for n in before if n not in patcher.ORIGINAL and n != patcher.INDEX)
            first = patcher.digest((home / 'lib/app-client.jar').read_bytes())
            repeat = patcher.patch(home, args.javac)
            checks['idempotent_without_rewrite'] = repeat['already_patched'] and first == patcher.digest((home / 'lib/app-client.jar').read_bytes())
            classes = home / 'probe-classes'
            classes.mkdir()
            cp = str(home / 'lib/*')
            patcher.run(args.javac, '--release', '21', '-g:none', '-cp', cp, '-d', classes,
                        patcher.SOURCES / 'InstalledSearchGateProbe.java', patcher.SOURCES / 'EssentialPluginToggleProbe.java')
            java = args.javac.resolve().with_name('java.exe' if args.javac.suffix.lower() == '.exe' else 'java')
            opens = [a for a in json.loads(identity.read_text())['launch'][0].get('additionalJvmArguments', [])
                     if a.startswith(('--add-opens=', '--add-exports='))]
            probe = patcher.run(java, *opens, '-ea', '-Xverify:all', '-Djava.awt.headless=true',
                                '-Djava.system.class.loader=com.intellij.util.lang.PathClassLoader', '-cp', str(classes) + os.pathsep + cp,
                                'InstalledSearchGateProbe')
            checks['edt_lifecycle_and_real_patched_class_verification'] = 'gate_and_real_class_checks=37' in probe
            (report_dir / 'probe.log').write_text(probe)
            essential = patcher.run(java, *opens, '-ea', '-Xverify:all', '-Djava.awt.headless=true',
                '-Djava.system.class.loader=com.intellij.util.lang.PathClassLoader',
                '-Didea.config.path=' + str(home/'private-config'), '-Didea.system.path=' + str(home/'private-system'),
                '-cp', str(classes) + os.pathsep + cp, 'EssentialPluginToggleProbe')
            (report_dir/'essential-probe.log').write_text(essential)
            checks['essential_actual_platform_guards'] = 'essential_actual_platform_checks=25' in essential
            # Same executable assertions with the three unmodified toggle classes.
            # The expected baseline failure is archived separately, never counted
            # as a passing product test or hidden by changing the assertions.
            baseline = home/'unpatched-toggle-baseline'
            with zipfile.ZipFile(source_jar) as original:
                for name in patcher.ORIGINAL:
                    if name.startswith('com/intellij/ide/ui/'):
                        target = baseline/name;target.parent.mkdir(parents=True,exist_ok=True)
                        target.write_bytes(original.read(name))
            command = [str(java), *opens, '-ea', '-Xverify:all', '-Djava.awt.headless=true',
                '-Djava.system.class.loader=com.intellij.util.lang.PathClassLoader',
                '-Didea.config.path='+str(home/'baseline-config'), '-Didea.system.path='+str(home/'baseline-system'),
                '-cp', str(classes)+os.pathsep+str(baseline)+os.pathsep+cp, 'EssentialPluginToggleProbe']
            original_run = subprocess.run(command,capture_output=True,text=True,timeout=60)
            (report_dir/'essential-unpatched-baseline.log').write_text(original_run.stdout+original_run.stderr)
            checks['original_cached_core_toggle_reaches_disable_sink'] = original_run.returncode != 0 and 'DISABLE_REACHED [com.intellij' in original_run.stdout
            (report_dir / 'patch-receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
            # Alter just one original class in a fresh JAR: rejection must happen before a write.
            with zipfile.ZipFile(source_jar) as z, zipfile.ZipFile(home / 'lib/rejected.jar', 'w') as target:
                target.comment = z.comment
                for entry in z.infolist():
                    data = z.read(entry.filename)
                    if entry.filename == next(iter(patcher.ORIGINAL)):
                        data += b'unknown class revision'
                    target.writestr(entry, data)
            os.replace(home / 'lib/rejected.jar', home / 'lib/app-client.jar')
            invalid_before = patcher.digest((home / 'lib/app-client.jar').read_bytes())
            try:
                patcher.patch(home, args.javac)
                raise AssertionError('Unknown platform class was accepted')
            except ValueError as error:
                checks['unknown_class_rejected_before_write'] = 'Unexpected upstream class SHA256' in str(error) and invalid_before == patcher.digest((home / 'lib/app-client.jar').read_bytes())
            shutil.copy2(source_jar, home/'lib/app-client.jar')
            with zipfile.ZipFile(home/'lib/app-client.jar','a') as altered:
                altered.writestr('META-INF/unreviewed-fixture.txt',b'unknown archive content')
            altered_before=patcher.digest((home/'lib/app-client.jar').read_bytes())
            try:
                patcher.patch(home,args.javac)
                raise AssertionError('An unknown complete platform archive was accepted')
            except ValueError as error:
                checks['unknown_archive_rejected_before_write'] = 'Unexpected complete upstream app-client.jar SHA256' in str(error) and altered_before==patcher.digest((home/'lib/app-client.jar').read_bytes())
        checks['input_platform_unchanged'] = original_digest == patcher.digest(source_jar.read_bytes())
        if not all(checks.values()):
            raise AssertionError('Failed checks: ' + ', '.join(k for k, v in checks.items() if not v))
        result = {'passed': True, 'checks': checks, 'source_jar_sha256': original_digest,
                  'native_gui_started': False, 'gradle_sdk_modified': False,
                  'limits': 'Executable gate lifecycle, ASM/JVM verification and exact archive transformation; not full native Plugins UI acceptance.'}
    except BaseException as error:
        result = {'passed': False, 'checks': checks, 'error': str(error), 'source_jar_sha256': original_digest}
        (report_dir / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
        raise
    finally:
        if source_temp:
            source_temp.cleanup()
    (report_dir / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result, indent=2))


def entries(path):
    with zipfile.ZipFile(path) as archive:
        for entry in archive.infolist():
            yield entry, archive.read(entry.filename)


def extract_platform_lib(path, target):
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        identities = [n for n in names if PurePosixPath(n).name == 'product-info.json' and len(PurePosixPath(n).parts) <= 2]
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
            if entry.is_dir() or not (relative.as_posix() == 'product-info.json' or (len(relative.parts) == 2 and relative.parts[0] == 'lib' and relative.suffix == '.jar')):
                continue
            if relative.as_posix().casefold() in seen or (entry.external_attr >> 16) & 0o170000 == 0o120000:
                raise ValueError('Duplicate or linked platform archive path')
            seen.add(relative.as_posix().casefold())
            output = target / relative.as_posix()
            output.parent.mkdir(parents=True, exist_ok=True)
            output.write_bytes(archive.read(entry))


if __name__ == '__main__':
    main()
