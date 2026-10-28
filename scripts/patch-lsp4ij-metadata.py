#!/usr/bin/env python3
"""Apply GoForge's reproducible, metadata-only LSP4IJ 0.21.0 fix.

Only project-owned sandbox/distribution copies are accepted. The downloaded
dependency cache and user-installed plugins must never be patched in place.
See patches/lsp4ij-0.21.0/README.md for provenance and the upstream license.
"""
import argparse
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import re
import tempfile
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
UPSTREAM_SHA256 = "c874e03849aeab974483913ea72161bca0c315c7800c77cbf530d82da0001900"
UPSTREAM_XML_SHA256 = "1be28f40b53e6084b1f6a26769656b9425bdeaa9949983942b183ae7c629bb68"
PATCHED_SHA256 = "c4eed1d20b62453282818dbc72f2582fe9f9c01f7551ae74ffb2f2070976303e"
PLUGIN_XML = "META-INF/plugin.xml"
ACTION_PREFIX = "com.redhat.devtools.lsp4ij.features.codeAction.intention.LSPIntentionAction"
DESCRIPTION = "intentionDescriptions/Empty/description.html"


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def patch_bytes(data):
    """Validate the exact upstream artifact, then change only its XML metadata."""
    if sha256(data) != UPSTREAM_SHA256:
        raise ValueError("Unexpected LSP4IJ input SHA256; review a new upstream artifact explicitly")
    with zipfile.ZipFile(io.BytesIO(data)) as source:
        names = source.namelist()
        if len(names) != len(set(names)):
            raise ValueError("Duplicate archive entries are not supported")
        if any(re.match(r"META-INF/.*\.(SF|RSA|DSA|EC)$", name, re.I) for name in names):
            raise ValueError("Refusing to invalidate a signed JAR")
        description = source.read(DESCRIPTION)
        if b"<html" not in description:
            raise ValueError("Expected existing intention description is missing or invalid")
        original = source.read(PLUGIN_XML)
        if sha256(original) != UPSTREAM_XML_SHA256:
            raise ValueError("Unexpected plugin.xml")
        root = ET.fromstring(original)
        if root.findtext("id") != "com.redhat.devtools.lsp4ij" or root.findtext("version") != "0.21.0":
            raise ValueError("Unexpected plugin identity")
        actions = [x for x in root.findall("extensions/intentionAction")
                   if (x.findtext("className") or "").startswith(ACTION_PREFIX)]
        if {x.findtext("className") for x in actions} != {ACTION_PREFIX + str(i) for i in range(20)} or len(actions) != 20:
            raise ValueError("Expected exactly LSPIntentionAction0 through LSPIntentionAction19")
        if any(x.find("descriptionDirectoryName") is not None for x in actions):
            raise ValueError("Unexpected existing description override")
        # Preserve every other XML byte, including line endings and release notes.
        pattern = re.compile(rb"(<className>" + ACTION_PREFIX.encode().replace(b".", rb"\.")
                             + rb"(?:[0-9]|1[0-9])</className>)(\r?\n)([ \t]*)")
        replacement = rb"\1\2\3<descriptionDirectoryName>Empty</descriptionDirectoryName>\2\3"
        patched_xml, count = pattern.subn(replacement, original)
        if count != 20:
            raise ValueError("Unexpected XML layout; no archive was written")
        output = io.BytesIO()
        with zipfile.ZipFile(output, "w") as target:
            target.comment = source.comment
            for entry in source.infolist():
                target.writestr(entry, patched_xml if entry.filename == PLUGIN_XML else source.read(entry.filename))
    return output.getvalue()


def patch_plugin(plugin_dir):
    plugin_dir = Path(plugin_dir).resolve(strict=True)
    jar = (plugin_dir / "lib/lsp4ij-0.21.0.jar").resolve(strict=True)
    allowed = [ROOT / name for name in ("build", ".artifacts", ".tools", "dist")]
    if not any(jar.is_relative_to(base.resolve()) for base in allowed):
        raise ValueError("Only a GoForge-owned build/.artifacts/.tools/dist copy may be patched")
    if jar.stat().st_nlink != 1:
        raise ValueError("Refusing to patch a hard-linked dependency")
    data = jar.read_bytes()
    digest = sha256(data)
    already_patched = digest == PATCHED_SHA256
    lifecycle_present = False
    if not already_patched:
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            lifecycle_present = "META-INF/goforge-lsp4ij-lifecycle.json" in archive.namelist()
        if lifecycle_present:
            # The separate class patch runs after this metadata transformation. Validate
            # its exact final artifact, so rerunning sandbox preparation remains safe.
            spec = importlib.util.spec_from_file_location("goforge_lsp_lifecycle", ROOT / "scripts/patch-lsp4ij-lifecycle.py")
            lifecycle = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(lifecycle)
            lifecycle.validate_bytes(data)
            already_patched = True
    if not already_patched:
        patched = patch_bytes(data)
        if sha256(patched) != PATCHED_SHA256:
            raise ValueError("Patched output does not match the pinned reproducible SHA256")
        mode = jar.stat().st_mode
        temporary = None
        try:
            with tempfile.NamedTemporaryFile(dir=jar.parent, prefix=".goforge-lsp4ij-", delete=False) as out:
                temporary = Path(out.name)
                out.write(patched)
            os.chmod(temporary, mode)
            os.replace(temporary, jar)
        finally:
            if temporary and temporary.exists():
                temporary.unlink()
    receipt = {
        "patch": "goforge-lsp4ij-0.21.0-stable-intention-description-v1",
        "upstream_sha256": UPSTREAM_SHA256,
        "patched_sha256": PATCHED_SHA256,
        "changed_entry": PLUGIN_XML,
        "intention_descriptions": 20,
        "description_resource": DESCRIPTION,
        "java_classes_modified": 0,
        "scope": "Metadata transformation only; the separate lifecycle receipt records its class change",
        "license": "EPL-2.0; see patches/lsp4ij-0.21.0/LICENSE",
    }
    (plugin_dir / "goforge-metadata-patch.json").write_text(json.dumps(receipt, indent=2) + "\n")
    return {**receipt, "already_patched": already_patched, "lifecycle_present": lifecycle_present, "jar": str(jar)}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--plugin-dir", required=True, help="Owned copy of the lsp4ij plugin directory")
    args = parser.parse_args()
    print(json.dumps(patch_plugin(args.plugin_dir), indent=2))
