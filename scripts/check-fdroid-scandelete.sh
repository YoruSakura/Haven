#!/usr/bin/env bash
# check-fdroid-scandelete: catch F-Droid's scandelete list going stale BEFORE
# it fails their build instead of after.
#
# Why this exists. F-Droid's recipe lists paths to delete before scanning, so
# that binaries we used to commit could not end up in their APK. `fdroid build`
# hard-fails on a listed path that does not exist:
#
#     ERROR: Non-exist scandelete path: core/ffmpeg/src/main/jniLibs
#     ERROR: Unused scandelete path: core/ffmpeg/src/main/jniLibs
#
# Every time we stop committing a binary, the path stops existing and that entry
# becomes a landmine. `AutoUpdateMode: Version` then clones the previous Builds
# entry verbatim into the next release, so the stale path is inherited forever
# until someone deletes it by hand.
#
# It has already happened twice, both times discovered by a failed bot MR:
#   v5.86.35  core/local/src/main/jniLibs, rdp-kotlin/jniLibs   (8 paths -> 6)
#   v5.86.37  core/ffmpeg/..., core/wayland/...                 (6 paths -> 4)
# and `rclone-android/build` is queued to do it again when #493 finishes.
#
# The list lives in F-Droid's repo, not ours, so nothing local could see the
# breakage. This fetches it and compares against what this repo actually has.
#
# Usage:
#   scripts/check-fdroid-scandelete.sh          # check master's newest entry
#   FDROID_METADATA_URL=... scripts/check-fdroid-scandelete.sh
#
# Exit 0 = every listed path exists (or the check could not run). Exit 1 = a
# listed path is gone and the next bot MR will fail on it.

set -uo pipefail

# Fatal rather than best-effort: every check below is a relative path test, so a
# failed cd would silently check whatever directory we happened to be in and
# report confident nonsense. `set -e` is not on, so this must be explicit.
repo_root="$(git rev-parse --show-toplevel 2>/dev/null)" || {
    echo "✗ not inside a git repository — cannot check paths" >&2
    exit 1
}
cd "$repo_root" || exit 1

METADATA_URL="${FDROID_METADATA_URL:-https://gitlab.com/fdroid/fdroiddata/-/raw/master/metadata/sh.haven.app.yml}"

meta="$(mktemp)"
trap 'rm -f "$meta"' EXIT

# Soft-skip on a fetch failure. This gate's job is to catch OUR drift; an
# unreachable gitlab.com is not a reason to block a release, and a check that
# invents new ways to fail is worse than the problem it solves.
if ! curl -fsSL --retry 2 --retry-delay 3 --retry-connrefused --retry-all-errors \
        --connect-timeout 15 --max-time 60 "$METADATA_URL" -o "$meta"; then
    echo "· F-Droid scandelete check skipped — could not fetch $METADATA_URL"
    exit 0
fi

python3 - "$meta" <<'PY'
import os, re, subprocess, sys

meta = open(sys.argv[1], encoding="utf-8").read()

def git_out(args, cwd=None):
    return subprocess.run(["git"] + args, cwd=cwd,
                          capture_output=True, text=True).stdout.strip()

def presence(path, submodules):
    """True / False / None(=unverifiable), as a CLEAN clone would see it.

    Deliberately git, not the filesystem. These directories are exactly the
    ones we gitignore and build into, so a developer machine has them sitting
    there as build output while F-Droid's fresh clone does not. Testing
    os.path.exists() here reports 'all present' on the one machine that can
    run the check and misses the only failure it was written to catch.
    """
    if git_out(["ls-files", "--", path]):
        return True
    owner = next((s for s in submodules if path.startswith(s + "/")), None)
    if owner:
        # F-Droid clones recursively, so a submodule's own index is what counts.
        if not (os.path.isdir(owner) and os.listdir(owner)):
            return None
        return bool(git_out(["ls-files", "--", path[len(owner) + 1:]], cwd=owner))
    return False

# Last Builds entry — the one AutoUpdateMode: Version will clone next.
entries = re.findall(r"  - versionName: ([^\n]+)\n(.*?)(?=\n  - versionName:|\Z)", meta, re.S)
if not entries:
    print("· F-Droid scandelete check skipped — no Builds entries parsed")
    sys.exit(0)

version, body = entries[-1]
block = re.search(r"    scandelete:\n((?:      - [^\n]+\n)+)", body)
paths = re.findall(r"      - ([^\n]+)", block.group(1)) if block else []
if not paths:
    print(f"✓ F-Droid scandelete list is empty for {version} — nothing to go stale")
    sys.exit(0)

# A path inside a submodule that was never initialised is absent here but
# present for F-Droid, who clone recursively. Report, never fail, on those:
# a false alarm on a fresh checkout would get this check ignored.
submodules = re.findall(r"^\s*path\s*=\s*(.+)$", open(".gitmodules").read(), re.M) \
    if os.path.exists(".gitmodules") else []

missing, unverifiable = [], []
for p in paths:
    state = presence(p, submodules)
    if state is True:
        continue
    if state is None:
        unverifiable.append(p)
    else:
        missing.append(p)

for p in unverifiable:
    print(f"· cannot verify {p} — its submodule is not initialised")

if missing:
    print(f"✗ F-Droid scandelete lists {len(missing)} path(s) this repo no longer has "
          f"(entry {version}):", file=sys.stderr)
    for p in missing:
        print(f"    {p}", file=sys.stderr)
    print("", file=sys.stderr)
    print("  The next checkupdates bot MR inherits this list and will fail with", file=sys.stderr)
    print("  'Non-exist scandelete path'. Get these deleted from the Builds entry in", file=sys.stderr)
    print("  fdroid/fdroiddata metadata/sh.haven.app.yml — comment on the bot MR with", file=sys.stderr)
    print("  the exact lines to drop; maintainers can apply it as a suggestion.", file=sys.stderr)
    sys.exit(1)

checked = len(paths) - len(unverifiable)
print(f"✓ F-Droid scandelete paths all present ({checked}/{len(paths)} checked against entry {version})")
PY
