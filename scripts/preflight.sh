#!/usr/bin/env bash
# Fast source checks for the trimmed client; unit tests and APK checks run in CI.
set -euo pipefail
cd "$(dirname "$0")/.."
git diff --check
./scripts/check-no-committed-binaries.sh
bash -n scripts/check-native-libs.sh scripts/check-r8-kept-classes.sh
python3 - <<'PY'
from pathlib import Path
compile(Path('scripts/check-client-apk.py').read_text(), 'scripts/check-client-apk.py', 'exec')
print('Client source checks passed.')
PY
