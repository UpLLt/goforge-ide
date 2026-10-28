#!/usr/bin/env python3
"""Run the same actual-class regression before and after the owned-copy lifecycle patch."""
import argparse
from datetime import datetime, timezone
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, ROOT / 'scripts' / filename)
    result = importlib.util.module_from_spec(spec); spec.loader.exec_module(result)
    return result


patcher = module('lifecycle_patch', 'patch-lsp4ij-lifecycle.py')
metadata = module('metadata_patch', 'patch-lsp4ij-metadata.py')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--platform-home', type=Path, required=True)
    parser.add_argument('--plugin-dir', type=Path, required=True, help='Pristine downloaded LSP4IJ directory, read only')
    parser.add_argument('--javac', type=Path, default=ROOT / '.tools/jdk/bin/javac')
    parser.add_argument('--output', type=Path, default=ROOT / '.artifacts/lsp-lifecycle' / datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ'))
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=False)
    source_jar = args.plugin_dir / 'lib/lsp4ij-0.21.0.jar'
    original_hash = patcher.digest(source_jar.read_bytes())
    checks = {}
    with tempfile.TemporaryDirectory(prefix='owned-', dir=args.output) as temporary:
        work = Path(temporary); plugin = work / 'lsp4ij'
        shutil.copytree(args.plugin_dir, plugin)
        metadata.patch_plugin(plugin)
        classes = work / 'probe'; classes.mkdir()
        cp = os.pathsep.join((str(plugin / 'lib/*'), str(args.platform_home / 'lib/*')))
        compilation = patcher.run(args.javac, '--release', '21', '-g:none', '-implicit:none', '-sourcepath', '', '-cp', cp, '-d', classes,
                                  patcher.SOURCES / 'ExplorerLifecycleProbe.java')
        (args.output / 'compile.log').write_text(compilation)
        java = args.javac.resolve().with_name('java.exe' if args.javac.suffix.lower() == '.exe' else 'java')
        command = [str(java), '-ea', '-Xverify:all', '-Djava.awt.headless=true',
                   '-cp', str(classes) + os.pathsep + cp, 'ExplorerLifecycleProbe']
        baseline = subprocess.run(command, capture_output=True, text=True, timeout=45)
        (args.output / 'baseline.log').write_text(baseline.stdout + baseline.stderr)
        checks['original_regression_fails'] = baseline.returncode != 0 and 'Background model lookup/creation before EDT dispatch' in baseline.stderr
        if not checks['original_regression_fails']:
            raise AssertionError('Expected original actual-class model-threading failure; inspect baseline.log')
        before = (plugin / 'lib/lsp4ij-0.21.0.jar').read_bytes()
        receipt = patcher.patch(plugin, args.platform_home, args.javac)
        (args.output / 'patch-receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
        fixed = subprocess.run(command, capture_output=True, text=True, timeout=45)
        (args.output / 'fixed.log').write_text(fixed.stdout + fixed.stderr)
        checks['same_actual_class_regression_passes'] = fixed.returncode == 0 and 'lifecycle_actual_class_checks=15' in fixed.stdout
        if not checks['same_actual_class_regression_passes']:
            raise AssertionError('Patched actual-class regression failed; inspect fixed.log')
        jar = plugin / 'lib/lsp4ij-0.21.0.jar'
        after = jar.read_bytes()
        repeated = patcher.patch(plugin, args.platform_home, args.javac)
        checks['idempotent'] = repeated['already_patched'] and jar.read_bytes() == after
        repeated_metadata = metadata.patch_plugin(plugin)
        checks['metadata_rerun_accepts_verified_composition'] = repeated_metadata['lifecycle_present'] and jar.read_bytes() == after
        try:
            patcher.patch(args.plugin_dir, args.platform_home, args.javac)
            checks['download_cache_patch_refused'] = False
        except ValueError as error:
            checks['download_cache_patch_refused'] = 'Only a GoForge-owned' in str(error)
        import io
        with zipfile.ZipFile(io.BytesIO(before)) as base, zipfile.ZipFile(io.BytesIO(after)) as final:
            changed = {n for n in base.namelist() if base.read(n) != final.read(n)}
            checks['only_listener_changed'] = changed == {patcher.CLASS}
            checks['only_provenance_added'] = set(final.namelist()) - set(base.namelist()) == {patcher.MARKER}
        jar.write_bytes(after + b'altered archive trailer')
        try:
            patcher.patch(plugin, args.platform_home, args.javac)
            checks['tampered_jar_rejected'] = False
        except ValueError:
            checks['tampered_jar_rejected'] = True
        # ZIP readers tolerate trailers, so ensure the validation contract explicitly covers them.
        checks['input_cache_unchanged'] = patcher.digest(source_jar.read_bytes()) == original_hash
        result = {'passed': all(checks.values()), 'checks': checks, 'upstream_jar_sha256': original_hash,
                  'scope': 'Actual pinned LSP4IJ listener/nodes and platform tree cache, controlled service adapters; no native GUI/Gradle/server',
                  'baseline_exit': baseline.returncode, 'fixed_exit': fixed.returncode}
        (args.output / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
        print(json.dumps(result, indent=2))
        if not result['passed']: raise AssertionError('Lifecycle regression failed')


if __name__ == '__main__':
    main()
