#!/bin/bash
# End to end: builds everything a Test Lab run of the full suite needs.
#   1. a "newer" debug APK (versionCode 6, 0.2.4, same debug certificate) -> build/e2e-assets/newer.apk
#   2. the same APK re-signed with a throwaway certificate               -> build/e2e-assets/other-signer.apk
#   3. the app under test (versionCode 5, 0.2.3) and the instrumentation APK, with the two files above as assets.
#   4. three tiny invented helper apps (no code, one manifest each) -> build/e2e-assets/helper-{alpha,beta,gamma}.apk,
#      which the uninstall flow installs on the test device and then uninstalls through Android's own dialog.
# Run from anywhere; builds are memory-capped and daemon-free. The throwaway key never leaves a temp dir.
set -euo pipefail
APP="$(cd "$(dirname "$0")/.." && pwd)"
export ANDROID_HOME="${ANDROID_HOME:?set ANDROID_HOME to your Android SDK path}"
GRADLE=(./gradlew --no-daemon)
cd "$APP"
OUT="$APP/build/e2e-assets"
# SKIP_NEWER=1 reuses the two asset APKs from an earlier run (they only depend on the app's main sources).
if [ "${SKIP_NEWER:-0}" != "1" ] || [ ! -f "$OUT/newer.apk" ] || [ ! -f "$OUT/other-signer.apk" ]; then
rm -rf "$OUT" && mkdir -p "$OUT"

"${GRADLE[@]}" -PstewardVersionCode=6 -PstewardVersionName=0.2.4 assembleDebug
cp build/outputs/apk/debug/phone-steward-debug.apk "$OUT/newer.apk"

BT="$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -1)"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
keytool -genkeypair -keystore "$TMP/other.jks" -storepass e2e-other -keypass e2e-other -alias other \
  -keyalg RSA -keysize 2048 -validity 30 -dname "CN=E2E Other Signer" >/dev/null 2>&1
"$BT/apksigner" sign --ks "$TMP/other.jks" --ks-pass pass:e2e-other --key-pass pass:e2e-other \
  --out "$OUT/other-signer.apk" "$OUT/newer.apk"
rm -f "$OUT"/*.idsig
fi

# The helper apps (always rebuilt: seconds, no Gradle). A manifest-only APK installs and uninstalls like any app.
export ANDROID_HOME
BT="$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -1)"
JAR="$(ls -d "$ANDROID_HOME"/platforms/android-* | sort -V | tail -1)/android.jar"
HTMP="$(mktemp -d)"; trap 'rm -rf "${TMP:-}" "$HTMP"' EXIT
keytool -genkeypair -keystore "$HTMP/helper.jks" -storepass e2e-helper -keypass e2e-helper -alias helper \
  -keyalg RSA -keysize 2048 -validity 30 -dname "CN=E2E Helper" >/dev/null 2>&1
for name in alpha beta gamma; do
  cat > "$HTMP/$name.xml" <<MANIFEST
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.example.e2ehelper.$name" android:versionCode="1" android:versionName="1.0">
    <application android:label="Invented Helper ${name^}" android:hasCode="false" />
</manifest>
MANIFEST
  "$BT/aapt2" link -I "$JAR" --manifest "$HTMP/$name.xml" --min-sdk-version 33 --target-sdk-version 35 -o "$HTMP/$name-unsigned.apk"
  "$BT/apksigner" sign --ks "$HTMP/helper.jks" --ks-pass pass:e2e-helper --key-pass pass:e2e-helper \
    --out "$OUT/helper-$name.apk" "$HTMP/$name-unsigned.apk"
done
rm -f "$OUT"/*.idsig

"${GRADLE[@]}" -PstewardVersionCode=5 -PstewardVersionName=0.2.3 assembleDebug assembleDebugAndroidTest
ls -l "$OUT" build/outputs/apk/debug/phone-steward-debug.apk build/outputs/apk/androidTest/debug/phone-steward-debug-androidTest.apk
