#!/usr/bin/env bash
# Full verification gate for Audio Cat. Run from anywhere; always from the
# project root. Exits non-zero on the first failure.
set -euo pipefail
cd "$(dirname "$0")/.."

export JAVA_HOME="${JAVA_HOME:-$HOME/Library/Java/jdk-17.0.20.1+1/Contents/Home}"
if [ ! -x "$JAVA_HOME/bin/java" ]; then
  echo "JAVA_HOME points at a missing JDK: $JAVA_HOME" >&2
  exit 1
fi
export GRADLE_USER_HOME="$PWD/.gradle-home"
MAX_APK_MB=15

echo "==> Compile"
./gradlew :app:compileDebugKotlin --console=plain -q

echo "==> Unit + Robolectric tests"
./gradlew :app:testDebugUnitTest --console=plain -q
TOTAL=$(grep -ho 'tests="[0-9]*"' app/build/test-results/testDebugUnitTest/*.xml | grep -o '[0-9]*' | paste -sd+ - | bc)
FAILED=$(grep -ho 'failures="[0-9]*"' app/build/test-results/testDebugUnitTest/*.xml | grep -o '[0-9]*' | paste -sd+ - | bc)
echo "tests: $TOTAL, failures: $FAILED"
[ "$FAILED" -eq 0 ] || { echo "FAILING TESTS" >&2; exit 1; }

echo "==> Lint (error severity fails the build)"
./gradlew :app:lintDebug --console=plain -q

echo "==> APKs"
./gradlew :app:assembleDebug :app:assembleRelease --console=plain -q
for APK in app/build/outputs/apk/debug/app-debug.apk app/build/outputs/apk/release/app-release.apk; do
  SIZE_MB=$(( $(stat -f%z "$APK" 2>/dev/null || stat -c%s "$APK") / 1024 / 1024 ))
  echo "$APK: ${SIZE_MB}MB"
  [ "$SIZE_MB" -lt "$MAX_APK_MB" ] || { echo "APK OVER ${MAX_APK_MB}MB" >&2; exit 1; }
done

echo "==> Offline guarantee: no INTERNET permission in merged release manifest"
MANIFEST=$(find app/build/intermediates/merged_manifest/release -name AndroidManifest.xml | head -1)
if grep -q '<uses-permission[^>]*android.permission.INTERNET' "$MANIFEST"; then
  echo "INTERNET PERMISSION PRESENT - offline design broken" >&2
  exit 1
fi

echo "==> Static analysis (detekt)"
# Capturing the exit code explicitly: `set -e` would otherwise abort on the
# failing assignment inside $( ) and the findings would never be printed.
DETEKT_STATUS=0
DETEKT_OUT=$(./gradlew :app:detekt --console=plain 2>&1) || DETEKT_STATUS=$?
if [ "$DETEKT_STATUS" -eq 0 ]; then
  echo "detekt clean"
elif echo "$DETEKT_OUT" | grep -q "Task 'detekt' not found"; then
  echo "detekt not configured - skipping"
else
  echo "$DETEKT_OUT" | grep -E "\.kt:[0-9]+|What went wrong|BUILD FAILED" | head -20
  echo "DETEKT FINDINGS (new issues vs baseline)" >&2
  exit 1
fi

echo "ALL CHECKS PASSED"
