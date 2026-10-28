#!/usr/bin/env python3
"""Check signing-time preparation against the actual pinned public LSP4IJ JAR."""
import importlib.util
from pathlib import Path
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("macos_package", ROOT / "scripts/package-macos.py")
packager = importlib.util.module_from_spec(spec)
spec.loader.exec_module(packager)


class LspTextmatePackagingTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        patch_spec = importlib.util.spec_from_file_location("metadata_patch", ROOT / "scripts/patch-lsp4ij-metadata.py")
        patcher = importlib.util.module_from_spec(patch_spec)
        patch_spec.loader.exec_module(patcher)
        upstream = packager.find_one("*/transforms/*/transformed/com.redhat.devtools.lsp4ij-0.21.0/lsp4ij/lib/lsp4ij-0.21.0.jar")
        cls.patched = patcher.patch_bytes(upstream.read_bytes())

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="lsp-textmate-package-", dir=ROOT / ".artifacts")
        self.plugin = Path(self.temporary.name) / "lsp4ij"
        (self.plugin / "lib").mkdir(parents=True)
        # Prepare from the download cache in memory; no installed app is needed.
        self.jar = self.plugin / "lib/lsp4ij-0.21.0.jar"
        self.jar.write_bytes(self.patched)
        self.original = self.jar.read_bytes()

    def tearDown(self):
        self.temporary.cleanup()

    def test_materialized_files_are_exact_upstream_resources_and_repeat_does_not_write(self):
        report = packager.prepare_lsp_textmate(self.plugin)
        before = {}
        with zipfile.ZipFile(self.jar) as archive:
            for name in packager.LSP_TEXTMATE_FILES:
                target = self.plugin / "bundles/0.21.0" / name
                self.assertEqual(archive.read("textmate/" + name), target.read_bytes())
                before[name] = (target.read_bytes(), target.stat().st_mtime_ns)
        self.assertEqual(packager.LSP_TEXTMATE_FILES, report["unchangedResources"])
        self.assertEqual(report, packager.prepare_lsp_textmate(self.plugin))
        for name, state in before.items():
            target = self.plugin / "bundles/0.21.0" / name
            self.assertEqual(state, (target.read_bytes(), target.stat().st_mtime_ns))
        self.assertEqual(self.original, self.jar.read_bytes())

    def test_changed_existing_resource_fails_without_overwrite_or_partial_preparation(self):
        changed = self.plugin / "bundles/0.21.0/syntaxes/disassembly.json"
        changed.parent.mkdir(parents=True)
        changed.write_text("changed")
        with self.assertRaisesRegex(RuntimeError, "differs"):
            packager.prepare_lsp_textmate(self.plugin)
        self.assertEqual("changed", changed.read_text())
        self.assertFalse((changed.parent.parent / "package.json").exists())

    def test_symlink_destination_is_rejected(self):
        external = Path(self.temporary.name) / "external"
        external.mkdir()
        (self.plugin / "bundles").symlink_to(external, target_is_directory=True)
        with self.assertRaisesRegex(RuntimeError, "Symlink"):
            packager.prepare_lsp_textmate(self.plugin)
        self.assertEqual([], list(external.iterdir()))

    def test_different_jar_is_rejected_before_write(self):
        self.jar.write_bytes(self.original + b"changed")
        with self.assertRaisesRegex(RuntimeError, "Unexpected LSP4IJ archive"):
            packager.prepare_lsp_textmate(self.plugin)
        self.assertFalse((self.plugin / "bundles").exists())


if __name__ == "__main__":
    unittest.main()
