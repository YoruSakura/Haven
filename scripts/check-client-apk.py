#!/usr/bin/env python3
"""Inspect actual DEX definitions, native libraries and merged APK identity."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import struct
import subprocess
import zipfile

BRIDGES = [
    f"sh.haven.rclone.binding.{bridge}.{name}"
    for bridge, name in [
        ("wgbridge", "Wgbridge"), ("tsbridge", "Tsbridge"),
        ("nbbridge", "Nbbridge"), ("ovpnbridge", "Ovpnbridge"), ("ocbridge", "Ocbridge"),
    ]
]

YAML_PACKAGE_CLASSES = [
    "org.yaml.snakeyaml.TypeDescription",
]

def dex_definitions(data):
    if not data.startswith(b"dex\n"):
        raise ValueError("Unsupported DEX format")
    def u32(offset):
        return struct.unpack_from("<I", data, offset)[0]
    string_count, string_offset = u32(56), u32(60)
    type_count, type_offset = u32(64), u32(68)
    class_count, class_offset = u32(96), u32(100)
    def string_at(index):
        if not 0 <= index < string_count:
            raise ValueError("Invalid DEX string index")
        offset = u32(string_offset + index * 4)
        while data[offset] & 0x80:
            offset += 1
        offset += 1  # skip the final byte of the UTF-16 length
        return data[offset:data.index(b"\0", offset)].decode("utf-8", errors="replace")
    definitions = set()
    for index in range(class_count):
        type_index = u32(class_offset + index * 32)
        if not 0 <= type_index < type_count:
            raise ValueError("Invalid DEX type index")
        descriptor = string_at(u32(type_offset + type_index * 4))
        definitions.add(descriptor.removeprefix("L").removesuffix(";").replace("/", "."))
    return definitions

def android_tool(name):
    found = shutil.which(name)
    if found:
        return found
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk:
        props = Path(__file__).resolve().parents[1] / "local.properties"
        if props.is_file():
            sdk = next((line.split("=", 1)[1] for line in props.read_text().splitlines() if line.startswith("sdk.dir=")), None)
    paths = sorted(Path(sdk, "build-tools").glob(f"*/{name}")) if sdk else []
    if not paths:
        raise RuntimeError(f"{name} not found in the Android SDK")
    return str(paths[-1])

def inspect(apk, abi):
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        definitions = set().union(*(dex_definitions(archive.read(n)) for n in names if re.fullmatch(r"classes\d*\.dex", n)))
        missing = sorted(set(BRIDGES) - definitions)
        if missing:
            raise ValueError(f"Missing VPN class definitions: {missing}")
        missing_yaml = sorted(set(YAML_PACKAGE_CLASSES) - definitions)
        if missing_yaml:
            raise ValueError(f"Missing package-sensitive YAML class definitions: {missing_yaml}")
        libraries = sorted(n for n in names if n.startswith("lib/") and n.endswith(".so"))
        actual_abis = {n.split("/")[1] for n in libraries}
        if actual_abis != {abi}:
            raise ValueError(f"Expected only {abi}, got {actual_abis}")
        required = {f"lib/{abi}/{name}" for name in ["libgojni.so", "librdp_transport.so"]}
        if not required.issubset(libraries):
            raise ValueError(f"Missing native libraries: {required - set(libraries)}")
        retired = re.compile(r"(?:spice|prns|wayland|wayvnc|proot|ffmpeg|avcodec|onnx|rapidocr|reticulum|usbredir|uml|qemu)", re.I)
        unwanted = [n for n in names if (n.startswith("lib/") or n.startswith("assets/")) and retired.search(n)]
        if unwanted:
            raise ValueError(f"Retired payloads in APK: {unwanted}")
    aapt = android_tool("aapt2")
    badging = subprocess.check_output([aapt, "dump", "badging", str(apk)], text=True)
    identity = re.search(r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging)
    if not identity:
        raise ValueError("Cannot read APK identity")
    package, code, version = identity.groups()
    expected_code = {"arm64-v8a": "9491", "x86_64": "9492", "armeabi-v7a": "9493"}[abi]
    if (package, code, version) != ("sh.haven.app", expected_code, "5.94.24"):
        raise ValueError(f"Unexpected package/version: {identity.group(0)}")
    manifest = subprocess.check_output([aapt, "dump", "xmltree", "--file", "AndroidManifest.xml", str(apk)], text=True)
    retired_components = ["McpServer", "TaskerFireReceiver", "TaskerEditActivity", "RestartActivity", "PullBackupShortcutActivity", "DebugReceiver"]
    if any(name in manifest for name in retired_components):
        raise ValueError("Retired component found in merged manifest")
    unwanted_permissions = ["CAMERA", "RECORD_AUDIO", "ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION", "BLUETOOTH_CONNECT", "BLUETOOTH_SCAN", "BIND_NOTIFICATION_LISTENER_SERVICE"]
    if any(f"android.permission.{name}" in badging for name in unwanted_permissions):
        raise ValueError("Retired device permission found in APK")
    return dict(
        apk=str(apk.resolve()), package=package, versionName=version, versionCode=int(code),
        abi=abi, bytes=apk.stat().st_size, sha256=hashlib.sha256(apk.read_bytes()).hexdigest(),
        dexClasses=len(definitions), vpnClassDefinitions=BRIDGES,
        yamlPackageClassDefinitions=YAML_PACKAGE_CLASSES, nativeLibraries=libraries,
    )

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--abi", default="arm64-v8a", choices=["arm64-v8a", "x86_64", "armeabi-v7a"])
    args = parser.parse_args()
    print(json.dumps(inspect(args.apk, args.abi), indent=2, ensure_ascii=False))
