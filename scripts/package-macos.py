#!/usr/bin/env python3
"""Assemble and locally sign a relocatable GoForge macOS development distribution.

Uses a pinned public Community binary platform, not the installed GoLand and not
a claim that the platform was rebuilt from source. Never launches a GUI, accepts
terms, changes quarantine/Gatekeeper, or modifies an existing IDE installation.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import plistlib
import shutil
import subprocess
import sys
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "packaging/macos"
OUTPUT = ROOT / "build/macos"
APP = OUTPUT / "GoForge.app"
MANIFEST = OUTPUT / "package-manifest.json"
PLATFORM = "2025.2.6.3"
BUILD = "252.28539.97"
BUNDLE = "dev.goforge.ide"
SELECTOR = "GoForge2026.1"
LSP_PATCHED_SHA256 = "c4eed1d20b62453282818dbc72f2582fe9f9c01f7551ae74ffb2f2070976303e"
LSP_TEXTMATE_FILES = {
    "package.json": "2a654090b48f8bcb9d1e4831a3b52d23ee9207624092b08fcb1fd73d95f92323",
    "syntaxes/disassembly.json": "2dae8bb50879164cc61c0b35fa0bfdd38523766e6150448271e0d6c65c2a79fe",
}


def run(*args: str | Path, **kwargs) -> str:
    result = subprocess.run([str(a) for a in args], check=False, text=True,
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, **kwargs)
    if result.returncode:
        raise RuntimeError(f"Command failed ({result.returncode}): {args[0]}\n{result.stdout[-12000:]}")
    return result.stdout


def digest(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""): h.update(block)
    return h.hexdigest()


def find_one(pattern: str) -> Path:
    paths = sorted(set(p.resolve() for p in (ROOT / ".gradle-user-home/caches").glob(pattern)))
    if len(paths) != 1: raise RuntimeError(f"Expected one pinned dependency matching {pattern}: {paths}")
    return paths[0]


def clone(source: Path, target: Path) -> None:
    target.parent.mkdir(parents=True, exist_ok=True)
    run("/bin/cp", "-cR", source, target)


def safe_extract(archive: Path, target: Path) -> None:
    with zipfile.ZipFile(archive) as z:
        for member in z.infolist():
            path = Path(member.filename)
            if path.is_absolute() or ".." in path.parts: raise RuntimeError("Unsafe plugin archive path")
        z.extractall(target)


def prepare_lsp_textmate(plugin: Path) -> dict:
    """Materialize unchanged upstream resources before the app is sealed.

    LSP4IJ 0.21's DisassemblyTextMateBundleProvider copies these exact resources
    into pluginPath/bundles/version only when absent. Leaving this to first GUI
    startup adds unsealed files to a signed macOS application.
    """
    jar = plugin / "lib/lsp4ij-0.21.0.jar"
    if jar.is_symlink() or digest(jar) != LSP_PATCHED_SHA256:
        raise RuntimeError("Unexpected LSP4IJ archive for TextMate preparation")
    bundle = plugin / "bundles/0.21.0"
    prepared = {}
    with zipfile.ZipFile(jar) as archive:
        # Validate the complete small set before writing anything.
        for relative, expected in LSP_TEXTMATE_FILES.items():
            data = archive.read("textmate/" + relative)
            if hashlib.sha256(data).hexdigest() != expected:
                raise RuntimeError("Unexpected LSP4IJ TextMate resource")
            destination = bundle / relative
            for path in [destination, *destination.parents]:
                if path.is_symlink(): raise RuntimeError("Symlink in LSP4IJ TextMate destination")
                if path == plugin: break
            if destination.exists() and destination.read_bytes() != data:
                raise RuntimeError("Existing LSP4IJ TextMate resource differs from its archive")
            prepared[relative] = (destination, data)
    for destination, data in prepared.values():
        if not destination.exists():
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes(data)
    return {"version": "0.21.0", "sourceJarSha256": digest(jar),
            "unchangedResources": LSP_TEXTMATE_FILES,
            "destination": "plugins/lsp4ij/bundles/0.21.0",
            "upstreamProvider": "https://github.com/redhat-developer/lsp4ij/blob/0.21.0/src/main/java/com/redhat/devtools/lsp4ij/dap/disassembly/DisassemblyTextMateBundleProvider.java"}


def add_branding(contents: Path, work: Path) -> None:
    classes = work / "classes"
    classes.mkdir()
    run(ROOT / ".tools/jdk/bin/javac", "--release", "21", "-cp", str(contents / "lib/*"),
        "-d", classes, ASSETS / "PackageProbe.java")
    with zipfile.ZipFile(contents / "lib/goforge-branding.jar", "w", zipfile.ZIP_DEFLATED) as z:
        entries = [(p.relative_to(classes).as_posix(), p.read_bytes()) for p in classes.rglob("*.class")]
        # Keep Community's platform prefix/main-plugin descriptor. The documented
        # resource-loading flag and this first classpath JAR customize product data.
        entries += [("idea/IdeaApplicationInfo.xml", (ASSETS / "GoForgeApplicationInfo.xml").read_bytes()),
                    ("goforge.svg", (ASSETS / "goforge.svg").read_bytes()),
                    ("consents-GoForge.json", (ASSETS / "consents-GoForge.json").read_bytes())]
        for name, data in sorted(entries):
            info = zipfile.ZipInfo(name, (2026, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            z.writestr(info, data)
    iconset = work / "goforge.iconset"
    run("/usr/bin/swift", ASSETS / "make-icon.swift", iconset)
    run("/usr/bin/iconutil", "-c", "icns", "-o", contents / "Resources/goforge.icns", iconset)
    shutil.copy2(ASSETS / "goforge.svg", contents / "bin/goforge.svg")


def module_licenses(go: Path, binary: Path, destination: Path) -> dict:
    metadata = run(go, "version", "-m", binary)
    # The first line names the inspected file, not a dependency. Release notices
    # retain its toolchain metadata without publishing the builder's home path.
    inspected = str(binary) + ":"
    if metadata.startswith(inspected):
        metadata = binary.name + ":" + metadata[len(inspected):]
    modcache = Path(run(go, "env", "GOMODCACHE").strip())
    modules = []
    for line in metadata.splitlines():
        fields = line.strip().split("\t")
        if len(fields) < 3 or fields[0] not in {"mod", "dep"}: continue
        module, version = fields[1:3]
        escaped = "".join("!" + c.lower() if c.isupper() else c for c in module)
        folder = modcache / f"{escaped}@{version}"
        candidates = [p for p in folder.iterdir() if p.is_file() and
                      p.name.upper().startswith(("LICENSE", "COPYING", "NOTICE", "AUTHORS"))] if folder.exists() else []
        if not any(p.name.upper().startswith(("LICENSE", "COPYING")) for p in candidates):
            raise RuntimeError(f"License missing for bundled tool module {module}@{version}; restore its module cache first")
        target = destination / (escaped.replace("/", "_") + "@" + version)
        target.mkdir(parents=True, exist_ok=True)
        for source in candidates:
            copy = target / source.name
            if copy.exists():
                if digest(copy) != digest(source): raise RuntimeError(f"Conflicting module license: {copy}")
            else: shutil.copy2(source, copy)
        modules.append({"module": module, "version": version, "sum": fields[3] if len(fields) > 3 else None,
                        "licenseFiles": [p.name for p in candidates]})
    destination.mkdir(parents=True, exist_ok=True)
    (destination / (binary.name + "-build-info.txt")).write_text(metadata)
    return {"sha256": digest(binary), "modules": modules}


def configure(contents: Path, retained: list[str]) -> None:
    plist = plistlib.loads((contents / "Info.plist").read_bytes())
    plist.update(CFBundleName="GoForge", CFBundleDisplayName="GoForge", CFBundleIdentifier=BUNDLE,
                 CFBundleExecutable="goforge", CFBundleIconFile="goforge.icns",
                 CFBundleShortVersionString="0.1.0", CFBundleVersion="0.1.0",
                 LSMinimumSystemVersion="13.5",
                 CFBundleGetInfoString="GoForge local development distribution; IntelliJ Platform attribution in Resources.")
    plist["CFBundleDocumentTypes"] = [{"CFBundleTypeExtensions": ["go", "mod", "sum", "work"],
                                       "CFBundleTypeName": "Go source or module", "CFBundleTypeRole": "Editor",
                                       "CFBundleTypeIconFile": "goforge.icns"}]
    plist.pop("CFBundleURLTypes", None)
    for key, value in list(plist.items()):
        if key.startswith("NS") and isinstance(value, str): plist[key] = value.replace("IntelliJ IDEA", "GoForge")
    (contents / "Info.plist").write_bytes(plistlib.dumps(plist))
    # Localized upstream Info.plist strings would otherwise override the product name.
    for path in (contents / "Resources").glob("*.lproj/InfoPlist.strings"): path.unlink()
    (contents / "MacOS/idea").rename(contents / "MacOS/goforge")
    product = json.loads((contents / "Resources/product-info.json").read_text())
    product.update(name="GoForge", version="0.1.0", productCode="GF", envVarBaseName="GOFORGE",
                   dataDirectoryName=SELECTOR, productVendor="GoForge", svgIconPath="../bin/goforge.svg")
    launch = product["launch"][0]
    launch.update(launcherPath="../MacOS/goforge", vmOptionsFilePath="../bin/goforge.vmoptions")
    launch["bootClassPathJarNames"].insert(0, "goforge-branding.jar")
    args = [a for a in launch["additionalJvmArguments"] if not a.startswith(
        ("-Didea.vendor.name=", "-Didea.paths.selector=", "-Didea.platform.prefix=", "-Dsplash=",
         "-Didea.ultimate.features.hints.enabled="))]
    args += ["-Didea.vendor.name=GoForge", f"-Didea.paths.selector={SELECTOR}",
             "-Didea.platform.prefix=Idea", "-Dintellij.platform.load.app.info.from.resources=true",
             "-Didea.disable.collect.statistics=true", "-Didea.suppress.statistics.report=true",
             "-Didea.headless.enable.statistics=false",
             # Community promotion pages are not GoForge features. Use the platform
             # display preference; this does not add or unlock any commercial code.
             "-Didea.ultimate.features.hints.enabled=false",
             "-Dide.no.platform.update=true", "-Didea.initially.ask.config=never", "-Dsplash=false",
             f"-Didea.config.path=$USER_HOME/Library/Application Support/GoForge/{SELECTOR}",
             f"-Didea.system.path=$USER_HOME/Library/Caches/GoForge/{SELECTOR}",
             f"-Didea.plugins.path=$USER_HOME/Library/Application Support/GoForge/{SELECTOR}/plugins",
             f"-Didea.log.path=$USER_HOME/Library/Logs/GoForge/{SELECTOR}",
             "-XX:ErrorFile=$IDE_CACHE_DIR/java_error_in_goforge_%p.log",
             "-XX:HeapDumpPath=$IDE_CACHE_DIR"]
    launch["additionalJvmArguments"] = args
    launch["customCommands"] = [{"commands": ["goforge-package-info"],
        "vmOptionsFilePath": launch["vmOptionsFilePath"], "bootClassPathJarNames": launch["bootClassPathJarNames"],
        "additionalJvmArguments": args + ["-Djava.awt.headless=true"],
        "mainClass": "dev.goforge.packaging.PackageProbe"}]
    def keep_layout(item):
        paths = item.get("classPath", [])
        return all(not path.startswith("plugins/") or path.split("/")[1] in retained for path in paths)
    product["layout"] = [item for item in product.get("layout", []) if keep_layout(item)]
    (contents / "Resources/product-info.json").write_text(json.dumps(product, indent=2) + "\n")
    shutil.copy2(contents / "bin/idea.vmoptions", contents / "bin/goforge.vmoptions")


def verify_relocation() -> dict:
    # An actual second bundle, at a different path containing spaces, exercises
    # both native macro expansion and Go's runtime GOROOT discovery.
    with tempfile.TemporaryDirectory(prefix="relocation check ", dir=OUTPUT) as temp:
        relocated = Path(temp) / "GoForge Relocated.app"
        clone(APP, relocated)
        run("/usr/bin/codesign", "--verify", "--deep", "--strict", relocated)
        probe = run(relocated / "Contents/MacOS/goforge", "goforge-package-info", timeout=90)
        if str(relocated / "Contents") not in probe: raise RuntimeError("Relocated native home is incorrect")
        tools = relocated / "Contents/goforge-tools"
        goroot = run(tools / "bin/go", "env", "GOROOT").strip()
        if Path(goroot).resolve() != tools / "go": raise RuntimeError("Relocated Go SDK escaped the bundle")
        versions = {name: run(tools / "bin" / name, "version") for name in ["go", "gopls", "dlv"]}
        fixture = Path(temp) / "standalone go fixture"
        fixture.mkdir()
        (fixture / "go.mod").write_text("module example.com/goforge-package-check\n\ngo 1.26\n")
        (fixture / "main.go").write_text('package main\nimport "fmt"\nfunc main() { fmt.Println("bundled GoForge toolchain") }\n')
        (fixture / "main_test.go").write_text('package main\nimport "testing"\nfunc TestEntrypointWithBundledSDK(t *testing.T) { main() }\n')
        environment = {**os.environ, "PATH": "/usr/bin:/bin", "GOENV": "off", "GOTOOLCHAIN": "local",
                       "CGO_ENABLED": "0", "GOCACHE": str(Path(temp) / "go-cache"), "GOPATH": str(Path(temp) / "go-path")}
        environment.pop("GOROOT", None)
        compilation = run(tools / "bin/go", "test", "./...", cwd=fixture, env=environment, timeout=120)
        execution = run(tools / "bin/go", "run", ".", cwd=fixture, env=environment, timeout=120)
        if execution.strip() != "bundled GoForge toolchain": raise RuntimeError("Bundled Go execution failed")
        return {"passed": True, "testedApp": str(relocated), "nativeProbe": probe,
                "GOROOT": goroot, "toolVersions": versions, "isolatedGoTest": compilation,
                "isolatedGoRun": execution, "testEnvironment": "PATH=/usr/bin:/bin; GOENV=off; GOTOOLCHAIN=local; CGO_ENABLED=0",
                "temporaryCopyRemovedAfterTest": True}


def package(plugin_zip: Path, archive: bool, platform_home: Path | None = None, lsp_dir: Path | None = None) -> dict:
    if sys.platform != "darwin": raise RuntimeError("This script builds the macOS arm64 development package")
    if not plugin_zip.is_file(): raise RuntimeError("Build the GoForge plugin ZIP first")
    plugin_sha256 = digest(plugin_zip)
    # Toolchain changes can leave more than one valid Gradle transform in the cache.
    # An explicit dependency from the current build avoids guessing between them.
    source = platform_home.resolve() if platform_home else find_one(f"*/transforms/*/transformed/ideaIC-{PLATFORM}-aarch64/Resources/product-info.json").parent.parent
    original = json.loads((source / "Resources/product-info.json").read_text())
    if (original["productCode"], original["buildNumber"]) != ("IC", BUILD): raise RuntimeError("Unexpected platform identity")
    lsp = lsp_dir.resolve() if lsp_dir else find_one("*/transforms/*/transformed/com.redhat.devtools.lsp4ij-0.21.0/lsp4ij/lib/lsp4ij-0.21.0.jar").parent.parent
    if not (lsp / "lib/lsp4ij-0.21.0.jar").is_file():
        raise RuntimeError("Expected the pinned LSP4IJ dependency directory")
    go = (ROOT / ".tools/bin/go").resolve(strict=True)
    if run(ROOT / ".tools/bin/gopls", "version").strip() != "golang.org/x/tools/gopls v0.23.0":
        raise RuntimeError("Expected gopls 0.23.0")
    if "Version: 1.27.2\n" not in run(ROOT / ".tools/bin/dlv", "version"):
        raise RuntimeError("Expected Delve 1.27.2")
    goroot = Path(run(go, "env", "GOROOT").strip()).resolve()
    if not (goroot / "VERSION").read_text().startswith("go1.26.4"): raise RuntimeError("Expected Go SDK 1.26.4")
    node = ROOT / ".tools/node-runtime/bin/node"
    devcontainer = ROOT / ".tools/devcontainer-cli/node_modules/@devcontainers/cli"
    if run(node, "--version").strip() != "v24.22.1": raise RuntimeError("Expected Node.js 24.22.1")
    if json.loads((devcontainer / "package.json").read_text())["version"] != "0.89.0":
        raise RuntimeError("Expected Dev Containers CLI 0.89.0")
    jdbc_licenses = ROOT / ".artifacts/database-licenses"
    for item in json.loads((jdbc_licenses / "manifest.json").read_text()):
        if digest(jdbc_licenses / (item["artifact"] + "-sources.jar")) != item["sourceSha256"]:
            raise RuntimeError("JDBC source archive checksum mismatch")
        for name, expected in item["licenseSha256"].items():
            if digest(jdbc_licenses / item["artifact"] / name) != expected:
                raise RuntimeError("JDBC license checksum mismatch")
    OUTPUT.mkdir(parents=True, exist_ok=True)
    if APP.exists():
        marker = APP / "Contents/Resources/goforge-build-marker.json"
        if not marker.exists() or json.loads(marker.read_text()).get("bundleId") != BUNDLE:
            raise RuntimeError("Refusing to replace an application not owned by this packaging script")
        if str(APP / "Contents") + "/" in run("/bin/ps", "-axo", "args="):
            raise RuntimeError("Quit this GoForge app and its bundled tool processes before rebuilding it")
    source_hashes = {name: digest(source / name) for name in ["Info.plist", "Resources/product-info.json", "MacOS/idea"]}
    stage = OUTPUT / ".staging-GoForge.app"
    if stage.exists(): shutil.rmtree(stage)
    stage.mkdir()
    clone(source, stage / "Contents")
    contents = stage / "Contents"
    marker = contents / "Resources/.toolbox-ignore"
    if marker.exists():
        sealed = plistlib.loads((contents / "_CodeSignature/CodeResources").read_bytes())
        if marker.stat().st_size != 0 or any("Resources/.toolbox-ignore" in sealed.get(key, {}) for key in ["files", "files2"]):
            raise RuntimeError("The cache marker unexpectedly belongs to the official signature")
        marker.unlink()  # Only Gradle's added, unsealed empty extraction marker.
    source_verification = run("/usr/bin/codesign", "--verify", "--deep", "--strict", "--verbose=2", stage)
    source_identity = run("/usr/bin/codesign", "--display", "--verbose=4", stage)
    if "Identifier=com.jetbrains.intellij.ce" not in source_identity or "Authority=Developer ID Application: JetBrains s.r.o." not in source_identity:
        raise RuntimeError("The public Community input has an unexpected signing identity")
    allow = json.loads((ASSETS / "bundled-plugins.json").read_text())["plugins"]
    if any(not (contents / "plugins" / name).is_dir() for name in allow): raise RuntimeError("Pinned bundled plugin missing")
    removed = []
    for directory in (contents / "plugins").iterdir():
        if directory.name not in allow:
            removed.append(directory.name)
            if directory.is_dir(): shutil.rmtree(directory)
            else: directory.unlink()
    safe_extract(plugin_zip, contents / "plugins")
    clone(lsp, contents / "plugins/lsp4ij")
    patch_report = run(sys.executable, ROOT / "scripts/patch-lsp4ij-metadata.py", "--plugin-dir", contents / "plugins/lsp4ij")
    platform_search_patch = json.loads(run(sys.executable, ROOT / "scripts/patch-installed-plugin-search.py",
                                          "--platform-home", contents, "--javac", ROOT / ".tools/jdk/bin/javac"))
    textmate_report = prepare_lsp_textmate(contents / "plugins/lsp4ij")
    # TextMate preparation intentionally verifies the metadata-only JAR. Apply
    # the separately pinned lifecycle repair afterwards, before signing.
    lifecycle_report = json.loads(run(sys.executable, ROOT / "scripts/patch-lsp4ij-lifecycle.py",
                                     "--plugin-dir", contents / "plugins/lsp4ij",
                                     "--platform-home", contents, "--javac", ROOT / ".tools/jdk/bin/javac"))
    tools = contents / "goforge-tools"
    clone(goroot, tools / "go")
    # Homebrew installs the SDK's top-level license beside libexec, while the
    # upstream archive keeps it inside GOROOT. Preserve it in both layouts.
    go_license = next((p for p in [goroot / "LICENSE", goroot.parent / "LICENSE"] if p.is_file()), None)
    if go_license is None: raise RuntimeError("The Go SDK license is missing")
    if not (tools / "go/LICENSE").exists(): shutil.copy2(go_license, tools / "go/LICENSE")
    (tools / "bin").mkdir()
    (tools / "bin/go").symlink_to("../go/bin/go")
    for name in ["gopls", "dlv"]: shutil.copy2(ROOT / ".tools/bin" / name, tools / "bin" / name)
    shutil.copy2(node, tools / "bin/node")
    shutil.copytree(devcontainer, tools / "devcontainer-cli")
    licenses = contents / "Resources/third-party"
    licenses.mkdir()
    shutil.copy2(ROOT / ".tools/node-runtime/LICENSE", licenses / "Node-LICENSE.txt")
    shutil.copytree(jdbc_licenses, licenses / "jdbc")
    tool_info = {name: module_licenses(go, tools / "bin" / name, licenses / "go-modules") for name in ["gopls", "dlv"]}
    shutil.copytree(ROOT / "patches/lsp4ij-0.21.0", licenses / "lsp4ij-0.21.0")
    shutil.copy2(ROOT / "scripts/patch-lsp4ij-metadata.py", licenses / "lsp4ij-0.21.0/patch-lsp4ij-metadata.py")
    shutil.copy2(ROOT / "scripts/patch-lsp4ij-lifecycle.py", licenses / "lsp4ij-0.21.0/patch-lsp4ij-lifecycle.py")
    shutil.copytree(ROOT / "patches/intellij-252-installed-search", licenses / "intellij-252-installed-search")
    shutil.copy2(ROOT / "scripts/patch-installed-plugin-search.py", licenses / "intellij-252-installed-search/patch-installed-plugin-search.py")
    shutil.copy2(go_license, licenses / "Go-LICENSE.txt")
    # Keep GoForge's grant separate from the original Community/runtime notices.
    shutil.copy2(ROOT / "LICENSE", contents / "Resources/GoForge-LICENSE.txt")
    shutil.copy2(ROOT / "NOTICE", contents / "Resources/GoForge-NOTICE.txt")
    shutil.copy2(ASSETS / "THIRD-PARTY-NOTICES.md", contents / "Resources/THIRD-PARTY-NOTICES.md")
    shutil.copy2(ASSETS / "bundled-plugins.json", contents / "Resources/bundled-plugins.json")
    with tempfile.TemporaryDirectory(prefix="goforge-package-", dir=OUTPUT) as temp:
        add_branding(contents, Path(temp))
    configure(contents, allow)
    (contents / "Resources/goforge-build-marker.json").write_text(json.dumps(
        {"bundleId": BUNDLE, "packager": "scripts/package-macos.py", "platformBuild": BUILD}) + "\n")
    (contents / "Resources/goforge-build-inputs.json").write_text(json.dumps({
        "bundleId": BUNDLE, "platformVersion": PLATFORM, "platformBuild": BUILD,
        "pluginZipSha256": plugin_sha256, "packagerSha256": digest(Path(__file__)),
        "sourceMetadataSha256": source_hashes, "retainedCommunityPlugins": allow,
        "tools": {name: {"sha256": info["sha256"], "modules": info["modules"]} for name, info in tool_info.items()},
        "extraTools": {"node": {"version": "24.22.1", "sha256": digest(node)},
                       "devcontainers": {"version": "0.89.0", "cliSha256": digest(devcontainer / "dist/spec-node/devContainersSpecCLI.js")}},
        "jdbcSourcesManifestSha256": digest(jdbc_licenses / "manifest.json"),
        "lspTextMateResources": textmate_report,
        "lspLifecyclePatch": lifecycle_report,
        "installedPluginSearchPatch": platform_search_patch,
        "localDevelopmentSignature": "ad-hoc", "fullPlatformSourceBuild": False,
    }, indent=2) + "\n")
    marker = contents / "Resources/.toolbox-ignore"
    if marker.exists(): marker.unlink()
    # Metadata changes intentionally invalidate the copied outer signature. This
    # new derivative gets its own local identity; upstream installations stay intact.
    signing = run("/usr/bin/codesign", "--force", "--sign", "-", "--options", "runtime",
                  "--entitlements", ASSETS / "entitlements.plist", "--timestamp=none", stage)
    verification = run("/usr/bin/codesign", "--verify", "--deep", "--strict", "--verbose=2", stage)
    identity = run("/usr/bin/codesign", "--display", "--verbose=4", stage)
    if "Signature=adhoc" not in identity or f"Identifier={BUNDLE}" not in identity:
        raise RuntimeError("Unexpected local signing identity")
    if APP.exists(): shutil.rmtree(APP)
    stage.rename(APP)
    probe = run(APP / "Contents/MacOS/goforge", "goforge-package-info", timeout=90)
    if "product=GoForge" not in probe or str(APP / "Contents") not in probe: raise RuntimeError("Native product probe failed")
    bundled_go = APP / "Contents/goforge-tools/bin/go"
    if Path(run(bundled_go, "env", "GOROOT").strip()).resolve() != APP / "Contents/goforge-tools/go":
        raise RuntimeError("Bundled Go SDK resolved outside the application")
    versions = {name: run(APP / "Contents/goforge-tools/bin" / name, "version")
                for name in ["go", "gopls", "dlv"]}
    versions["node"] = run(APP / "Contents/goforge-tools/bin/node", "--version")
    versions["devcontainers"] = run(APP / "Contents/goforge-tools/bin/node",
        APP / "Contents/goforge-tools/devcontainer-cli/dist/spec-node/devContainersSpecCLI.js", "--version")
    config = (APP / "Contents/Resources/product-info.json").read_text()
    for forbidden in [str(ROOT), ".gradle-user-home", "config_native", "plugins_runIde", "/opt/homebrew"]:
        if forbidden in config: raise RuntimeError(f"Native launch configuration has external build path: {forbidden}")
    relocation = verify_relocation()
    if source_hashes != {name: digest(source / name) for name in source_hashes}: raise RuntimeError("Source cache changed")
    if digest(plugin_zip) != plugin_sha256: raise RuntimeError("Plugin ZIP changed while packaging; rebuild from stable inputs")
    manifest = {"schema": 1, "bundleId": BUNDLE, "app": str(APP), "platform": PLATFORM, "platformBuild": BUILD,
        "platformSource": str(source), "sourceHashes": source_hashes, "pluginZipSha256": plugin_sha256,
        "sourceSignatureVerification": source_verification, "sourceSigningIdentity": source_identity,
        "packagerSha256": digest(Path(__file__)),
        "packagingAssets": {p.relative_to(ASSETS).as_posix(): digest(p) for p in sorted(ASSETS.rglob("*")) if p.is_file()},
        "localSignature": "ad-hoc; not Developer ID signed or notarized", "signature": identity,
        "signatureVerification": verification, "nativeHeadlessProbe": probe, "tools": tool_info,
        "toolVersions": versions, "retainedCommunityPlugins": allow, "excludedCommunityPlugins": sorted(removed),
        "lspMetadataPatch": patch_report, "lspTextMateResources": textmate_report, "relocation": relocation,
        "lspLifecyclePatch": lifecycle_report,
        "installedPluginSearchPatch": platform_search_patch,
        "fullPlatformSourceBuild": False, "bitForBitReproducibleArchive": False, "guiAcceptance": "pending"}
    MANIFEST.write_text(json.dumps(manifest, indent=2) + "\n")
    # Freeze the signed input before announcing that the GUI may be started.
    # Runtime activity cannot race the ZIP traversal; post-GUI signature checks
    # remain a separate acceptance requirement for the actual live application.
    with tempfile.TemporaryDirectory(prefix="archive input ", dir=OUTPUT) as temp:
        snapshot = Path(temp) / "GoForge.app"
        if archive:
            clone(APP, snapshot)
            run("/usr/bin/codesign", "--verify", "--deep", "--strict", snapshot)
        print(json.dumps({"event": "app-ready", "app": str(APP), "pluginZipSha256": plugin_sha256,
                          "signatureVerified": True, "nativeHeadlessProbePassed": True}), flush=True)
        if archive:
            archive_file = OUTPUT / "GoForge-0.1.0-dev-macos-aarch64.zip"
            if archive_file.exists(): archive_file.unlink()
            run("/usr/bin/ditto", "-c", "-k", "--sequesterRsrc", "--keepParent", snapshot, archive_file)
            with tempfile.TemporaryDirectory(prefix="archive verify ", dir=OUTPUT) as extracted_temp:
                run("/usr/bin/ditto", "-x", "-k", archive_file, extracted_temp)
                extracted = Path(extracted_temp) / "GoForge.app"
                archive_signature = run("/usr/bin/codesign", "--verify", "--deep", "--strict", "--verbose=2", extracted)
                archive_probe = run(extracted / "Contents/MacOS/goforge", "goforge-package-info", timeout=90)
                if str(extracted / "Contents") not in archive_probe: raise RuntimeError("Archived native product probe failed")
            manifest["archive"] = {"path": str(archive_file), "sha256": digest(archive_file), "bytes": archive_file.stat().st_size,
                                   "input": "verified signed snapshot taken before app-ready",
                                   "extractedSignatureVerification": archive_signature, "extractedNativeProbe": archive_probe}
            MANIFEST.write_text(json.dumps(manifest, indent=2) + "\n")
    print(json.dumps({"app": str(APP), "bundleId": BUNDLE, "manifest": str(MANIFEST),
                      "signatureVerified": True, "nativeHeadlessProbePassed": True}, indent=2))
    return manifest


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--plugin-zip", type=Path, default=ROOT / "build/distributions/goforge-0.1.0-dev.zip")
    parser.add_argument("--archive", action="store_true", help="Also create a distributable development ZIP")
    parser.add_argument("--platform-home", type=Path, help="Pinned Community platform contents directory resolved by this build")
    parser.add_argument("--lsp-dir", type=Path, help="Pinned LSP4IJ plugin directory resolved by this build")
    arguments = parser.parse_args()
    package(arguments.plugin_zip.resolve(), arguments.archive, arguments.platform_home, arguments.lsp_dir)
