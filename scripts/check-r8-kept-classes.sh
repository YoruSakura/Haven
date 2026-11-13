#!/usr/bin/env bash
# Verify that reflected and JNI entry points retain their names after R8.
# Usage: scripts/check-r8-kept-classes.sh [path/to/mapping.txt]
set -euo pipefail
cd "$(dirname "$0")/.."

if [ $# -ge 1 ]; then
  MAP="$1"
else
  MAP=""
  for cand in \
    app/build/intermediates/mapping/arm64FullRelease/l8DexDesugarLibArm64FullRelease/mapping.txt \
    app/build/outputs/mapping/arm64FullRelease/mapping.txt \
    app/build/intermediates/mapping/arm64FullRelease/minifyArm64FullReleaseWithR8/mapping.txt; do
    if [ -f "$cand" ]; then
      if [ -z "$MAP" ] || [ "$cand" -nt "$MAP" ]; then MAP="$cand"; fi
    fi
  done
  if [ -z "$MAP" ]; then
    MAP=$(find app/build -name 'mapping.txt' -path '*arm64FullRelease*' -printf '%T@ %p\n' 2>/dev/null \
            | sort -rn | head -1 | cut -d' ' -f2- || true)
  fi
  [ -n "$MAP" ] || MAP="app/build/intermediates/mapping/arm64FullRelease/l8DexDesugarLibArm64FullRelease/mapping.txt"
fi
LIST="scripts/r8-must-keep-classes.txt"

if [ ! -f "$MAP" ]; then
  echo "✖ mapping.txt not found (searched known paths + app/build)." >&2
  echo "  Run ./gradlew :app:minifyArm64FullReleaseWithR8 :app:l8DexDesugarLibArm64FullRelease first." >&2
  exit 2
fi

# Curated list (one FQN per line; #-comments and blanks ignored).
fqns=()
while IFS= read -r line; do
  [ -n "$line" ] && fqns+=("$line")
done < <(grep -vE '^\s*(#|$)' "$LIST" || true)

# Deduplicate.
mapfile -t fqns < <(printf '%s\n' "${fqns[@]}" | sort -u)

failures=()
for fqn in "${fqns[@]}"; do
  [ -n "$fqn" ] || continue
  esc=${fqn//./\\.}
  if grep -qE "^${esc} -> ${esc}:" "$MAP"; then
    continue  # identity-mapped == kept
  fi
  actual=$(grep -E "^${esc} -> " "$MAP" | head -1 || true)
  if [ -n "$actual" ]; then
    failures+=("$fqn  →  RENAMED to ${actual##*-> }")
  else
    failures+=("$fqn  →  ABSENT (shrunk away or never compiled in)")
  fi
done

if [ ${#failures[@]} -gt 0 ]; then
  echo "✖ ${#failures[@]} reflectively-loaded class(es) did NOT survive R8 intact:"
  printf '   %s\n' "${failures[@]}"
  echo
  echo "These are resolved at runtime by class name or JNI."
  echo "Add or fix a -keep rule in app/proguard-rules.pro, then re-run."
  exit 1
fi

echo "✓ All ${#fqns[@]} reflectively-loaded classes kept their names through R8."
