#!/usr/bin/env bash
# Verify the native outputs required by the retained RDP and VPN clients.
set -euo pipefail
cd "$(dirname "$0")/.."
exec python3 - "$@" <<'PY'
from pathlib import Path
import subprocess
import sys

abis = {
    'arm64': ('arm64-v8a', 'AArch64', 'arm64'),
    'x64': ('x86_64', 'Advanced Micro Devices X86-64', 'amd64'),
    'armv7': ('armeabi-v7a', 'ARM', 'armeabi-v7a'),
}
requested = sys.argv[1] if len(sys.argv) > 1 else None
if requested == 'all':
    selected = list(abis.values())
elif requested:
    selected = [abis[requested]] if requested in abis else [v for v in abis.values() if v[0] == requested]
else:
    selected = [v for v in abis.values() if Path('rclone-android/jniLibs', v[0], 'libgojni.so').is_file()]
if not selected:
    sys.exit('No supported ABI outputs found; build an APK first.')
for abi, machine, archive_suffix in selected:
    files = [
        (Path('rdp-kotlin/jniLibs', abi, 'librdp_transport.so'), ['Java_sh_haven_core_rdp_RdpBitmapBridge_blitRegion']),
        (Path('rclone-android/jniLibs', abi, 'libgojni.so'), [
            f'Java_sh_haven_rclone_binding_{bridge}_{name}_'
            for bridge, name in [('wgbridge', 'Wgbridge'), ('tsbridge', 'Tsbridge'), ('nbbridge', 'Nbbridge'), ('ovpnbridge', 'Ovpnbridge'), ('ocbridge', 'Ocbridge')]
        ]),
        (Path('core/tunnel/jniLibs-openvpn3', abi, f'libopenvpn3_android_{archive_suffix}.a'), ['start_session']),
    ]
    for path, symbols in files:
        if not path.is_file():
            sys.exit(f'Missing native output: {path}')
        headers = subprocess.check_output(['readelf', '-h', str(path)], text=True, errors='replace')
        machines = {line.split(':', 1)[1].strip() for line in headers.splitlines() if 'Machine:' in line}
        if machines != {machine}:
            sys.exit(f'Wrong ABI in {path}: {machines}')
        symbol_table = subprocess.check_output(['readelf', '-Ws', str(path)], text=True, errors='replace')
        for symbol in symbols:
            if symbol not in symbol_table:
                sys.exit(f'Missing entry point {symbol} in {path}')
    print(f'{abi}: RDP, five VPN JNI bridges and OpenVPN3 archive verified')
PY
