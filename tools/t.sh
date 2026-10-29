#!/usr/bin/env bash
# Run the core test suite, reporting only failures. See tools/testsum.py.
set -u
cd "$(dirname "$0")/.." || exit 1
LOG="$(mktemp)"
trap 'rm -f "$LOG"' EXIT
./gradlew :core:test --quiet --console=plain >"$LOG" 2>&1
GRADLE_EXIT=$?
python tools/testsum.py core/build/test-results/test "$GRADLE_EXIT" "$LOG"
