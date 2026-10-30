#!/bin/bash
# Manual APK build for Bao TV (no Gradle daemon: this VM garbles Gradle's
# loopback daemon protocol, see README build notes).
# Uses aapt2 + javac + d8 + zipalign + apksigner from the local SDK.
set -e
cd "$(dirname "$0")"
export JAVA_HOME="$PWD/tools/jdk17"
export PATH="$JAVA_HOME/bin:$PATH"
SDK="$PWD/tools/android-sdk"
BT="$SDK/build-tools/34.0.0"
ANDROID_JAR="$SDK/platforms/android-34/android.jar"
AARS="$HOME/workspace/maven/aars"
BUILD="$PWD/build/manual"
rm -rf "$BUILD"
mkdir -p "$BUILD/compiled" "$BUILD/gen" "$BUILD/classes" "$BUILD/dex"

# --- extract library res (only AARs that ship res) ---
mkdir -p "$BUILD/libres/exoplayer" "$BUILD/libres/core"
unzip -q -o "$AARS/media3-exoplayer.aar" 'res/*' -d "$BUILD/libres/exoplayer"
unzip -q -o "$AARS/core.aar" 'res/*' -d "$BUILD/libres/core"

# --- aapt2 compile ---
"$BT/aapt2" compile --dir app/src/main/res -o "$BUILD/compiled/app.zip"
"$BT/aapt2" compile --dir "$BUILD/libres/exoplayer/res" -o "$BUILD/compiled/exoplayer.zip"
"$BT/aapt2" compile --dir "$BUILD/libres/core/res" -o "$BUILD/compiled/core.zip"

# --- aapt2 link ---
"$BT/aapt2" link -I "$ANDROID_JAR" \
  --manifest app/src/main/AndroidManifest.xml \
  --java "$BUILD/gen" \
  --extra-packages androidx.media3.exoplayer \
  --extra-packages androidx.core \
  --min-sdk-version 24 --target-sdk-version 34 \
  -A app/src/main/assets \
  -o "$BUILD/app-unsigned.apk" \
  "$BUILD/compiled/app.zip" "$BUILD/compiled/exoplayer.zip" "$BUILD/compiled/core.zip"

# --- javac ---
CP="$ANDROID_JAR"
for j in "$AARS/libs/"*.jar "$AARS/"*.jar; do CP="$CP:$j"; done
find "$BUILD/gen" -name '*.java' > "$BUILD/gen-sources.txt"
"$JAVA_HOME/bin/javac" -encoding UTF-8 -source 8 -target 8 -nowarn \
  -cp "$CP" -d "$BUILD/classes" \
  @"$BUILD/gen-sources.txt" \
  $(find app/src/main/java -name '*.java' | sort)

# --- d8 dex (app classes + all library classes) ---
(cd "$BUILD/classes" && "$JAVA_HOME/bin/jar" cf "$BUILD/app-classes.jar" .)
"$BT/d8" --lib "$ANDROID_JAR" --min-api 24 --output "$BUILD/dex" \
  "$BUILD/app-classes.jar" "$AARS/libs/"*.jar "$AARS/"*.jar

# --- add dex to apk, align, sign ---
python3 - "$BUILD/app-unsigned.apk" "$BUILD/dex/classes.dex" << 'PY'
import sys, zipfile, shutil
apk, dex = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(apk, 'a') as z:
    z.write(dex, 'classes.dex')
PY
"$BT/zipalign" -f 4 "$BUILD/app-unsigned.apk" "$BUILD/app-aligned.apk"
if [ ! -f tools/debug.keystore ]; then
  "$JAVA_HOME/bin/keytool" -genkeypair -v -keystore tools/debug.keystore \
    -storepass android -keypass android -alias baotv -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=BaoTV, OU=Personal, O=BaoTV, L=TV, ST=TV, C=CN" >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks tools/debug.keystore --ks-pass pass:android --key-pass pass:android \
  --out "app/build/outputs/apk/debug/app-debug-share.apk" "$BUILD/app-aligned.apk" 2>/dev/null || \
  (mkdir -p app/build/outputs/apk/debug && "$BT/apksigner" sign --ks tools/debug.keystore --ks-pass pass:android --key-pass pass:android \
  --out "app/build/outputs/apk/debug/app-debug-share.apk" "$BUILD/app-aligned.apk")
"$BT/apksigner" verify "app/build/outputs/apk/debug/app-debug-share.apk"
"$BT/aapt2" dump badging "app/build/outputs/apk/debug/app-debug-share.apk" | head -5
ls -lh "app/build/outputs/apk/debug/app-debug-share.apk"
