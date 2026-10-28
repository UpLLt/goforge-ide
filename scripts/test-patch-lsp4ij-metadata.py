#!/usr/bin/env python3
"""Regression checks for the exact dependency metadata patch (no IDE launch)."""
import importlib.util
import io
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("metadata_patch", ROOT / "scripts/patch-lsp4ij-metadata.py")
patcher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(patcher)


class MetadataPatchTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        candidates = (ROOT / ".gradle-user-home/caches").glob(
            "*/transforms/*/transformed/com.redhat.devtools.lsp4ij-0.21.0/lsp4ij/lib/lsp4ij-0.21.0.jar")
        cls.upstream = next(path for path in candidates if patcher.sha256(path.read_bytes()) == patcher.UPSTREAM_SHA256)
        cls.original = cls.upstream.read_bytes()

    def setUp(self):
        self.folder = tempfile.TemporaryDirectory(prefix="metadata-patch-test-", dir=ROOT / ".artifacts")
        self.plugin = Path(self.folder.name) / "lsp4ij"
        (self.plugin / "lib").mkdir(parents=True)
        self.jar = self.plugin / "lib/lsp4ij-0.21.0.jar"
        self.jar.write_bytes(self.original)

    def tearDown(self):
        self.folder.cleanup()

    def test_only_twenty_metadata_registrations_change(self):
        patched = patcher.patch_bytes(self.original)
        self.assertEqual(patcher.PATCHED_SHA256, patcher.sha256(patched))
        with zipfile.ZipFile(io.BytesIO(self.original)) as before, zipfile.ZipFile(io.BytesIO(patched)) as after:
            self.assertEqual(before.namelist(), after.namelist())
            self.assertEqual([patcher.PLUGIN_XML], [name for name in before.namelist() if before.read(name) != after.read(name)])
            xml = ET.fromstring(after.read(patcher.PLUGIN_XML))
            intentions = [x for x in xml.findall("extensions/intentionAction")
                          if (x.findtext("className") or "").startswith(patcher.ACTION_PREFIX)]
            self.assertEqual(20, len(intentions))
            self.assertTrue(all(x.findtext("descriptionDirectoryName") == "Empty" for x in intentions))
            self.assertTrue(after.read(patcher.DESCRIPTION))

    def test_repeated_application_is_identical_and_records_provenance(self):
        first = patcher.patch_plugin(self.plugin)
        contents = self.jar.read_bytes()
        second = patcher.patch_plugin(self.plugin)
        self.assertFalse(first["already_patched"])
        self.assertTrue(second["already_patched"])
        self.assertEqual(contents, self.jar.read_bytes())
        self.assertTrue((self.plugin / "goforge-metadata-patch.json").exists())

    def test_unknown_or_tampered_archive_fails_without_writing(self):
        for data in (self.original + b"tamper", patcher.patch_bytes(self.original) + b"tamper"):
            self.jar.write_bytes(data)
            with self.assertRaises(ValueError):
                patcher.patch_plugin(self.plugin)
            self.assertEqual(data, self.jar.read_bytes())

    def test_original_download_cache_is_rejected_and_unchanged(self):
        with self.assertRaisesRegex(ValueError, "Only a GoForge-owned"):
            patcher.patch_plugin(self.upstream.parent.parent)
        self.assertEqual(self.original, self.upstream.read_bytes())


if __name__ == "__main__":
    unittest.main()
