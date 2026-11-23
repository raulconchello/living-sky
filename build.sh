#!/usr/bin/env bash
# Living Sky build, no Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner.
#
#   ./build.sh            build build/living-sky.apk
#   ./build.sh install    build, install on the attached phone (adb) and refresh the widget
#   ./build.sh desktop    run the pure-Java sky shaders on the PC (tools/DesktopSky.java) -> build/desktop-out
#   ./build.sh test       the weather pipeline's invariant test on the PC (tools/TimelineTest.java)
#   ./build.sh live       live forecast + radar for Ulm, Barcelona, Malgrat, Chicago through the same code (tools/LiveHarness.java)
#
# Env: ANDROID_SDK (default $ANDROID_HOME, then ~/Android/Sdk), BUILD_TOOLS (35.0.0), PLATFORM (android-36),
#      JAVA_TOOL (command prefix for javac/java/d8, e.g. "mise x java@21 --"), KEYSTORE, ADB_SERIAL.
set -euo pipefail
cd "$(dirname "$(readlink -f "$0")")"
ROOT=$PWD

SDK=${ANDROID_SDK:-${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}}
[[ -d "$SDK" || ! -d "$HOME/.local/share/android-sdk" ]] || SDK=$HOME/.local/share/android-sdk
BT=$SDK/build-tools/${BUILD_TOOLS:-35.0.0}
ANDROID_JAR=$SDK/platforms/${PLATFORM:-android-36}/android.jar
ADB=$SDK/platform-tools/adb
[[ -f "$ANDROID_JAR" && -x "$BT/aapt2" ]] || { echo "Android SDK not found: set ANDROID_SDK (needs build-tools and platform)"; exit 1; }
if [[ -z "${JAVA_TOOL+x}" ]]; then
  if command -v javac >/dev/null && javac -version >/dev/null 2>&1; then JAVA_TOOL=""
  elif command -v mise >/dev/null; then JAVA_TOOL="mise x java@21 --"
  else echo "JDK 17+ not found: install one or set JAVA_TOOL"; exit 1; fi
fi
SERIAL=${ADB_SERIAL:-$("$ADB" devices 2>/dev/null | awk 'NR>1 && $2=="device"{print $1; exit}')}

B=$ROOT/build
mkdir -p "$B"

if [[ "${1:-}" == "test" || "${1:-}" == "live" ]]; then
  rm -rf "$B/desktop-test" && mkdir -p "$B/desktop-test"
  $JAVA_TOOL javac -nowarn -encoding UTF-8 -d "$B/desktop-test" src/dev/livingsky/render/*.java tools/TimelineTest.java tools/LiveHarness.java
  if [[ "$1" == "test" ]]; then $JAVA_TOOL java -cp "$B/desktop-test" TimelineTest "${@:2}"
  else $JAVA_TOOL java -cp "$B/desktop-test" LiveHarness "${@:2}"; fi
  exit $?
fi

if [[ "${1:-}" == "desktop" ]]; then
  rm -rf "$B/desktop" && mkdir -p "$B/desktop" "$B/desktop-out"
  $JAVA_TOOL javac -nowarn -d "$B/desktop" src/dev/livingsky/render/*.java tools/DesktopSky.java
  $JAVA_TOOL java -cp "$B/desktop" DesktopSky "$B/desktop-out" "${2:-}"
  exit 0
fi

# ---------------------------------------------------------------- 1. skylines
# A hand-drawn place is <id>.json + <id>_day.svg + <id>_night.svg in assets/skylines (see docs/SKYLINES.md).
for j in assets/skylines/*.json; do
  id=$(basename "$j" .json)
  [[ -f "assets/skylines/${id}_day.svg" && -f "assets/skylines/${id}_night.svg" ]] \
    || echo "skylines: WARNING $id has no day/night SVG pair (ignored by the app)"
done
echo "skylines: $(ls assets/skylines/*.json | xargs -n1 basename | sed 's/\.json$//' | tr '\n' ' ')"

# ---------------------------------------------------------------- 2. automatic skyline generator
# autoskyline/src (package dev.livingsky.skyline, Java 11) builds a skyline from OpenStreetMap anywhere else.
# The app reaches it by reflection (AutoSkylineBridge): if it does not compile, the app falls back to a generic town.
BOOT="$ANDROID_JAR:$BT/core-lambda-stubs.jar"
rm -rf "$B/genclasses" && mkdir -p "$B/genclasses"
GEN_OK=0
if $JAVA_TOOL javac -nowarn -encoding UTF-8 --release 11 -cp "$ANDROID_JAR" -d "$B/genclasses" \
     $(find autoskyline/src -name '*.java') > "$B/autoskyline-javac.log" 2>&1; then
  GEN_OK=1; echo "autoskyline: compiled"
else
  echo "autoskyline: WARNING does not compile (see build/autoskyline-javac.log); using the generic fallback"
fi

# ---------------------------------------------------------------- 3. resources
rm -rf "$B/res.zip" "$B/gen" "$B/classes" "$B/dex" "$B"/*.apk
mkdir -p "$B/gen" "$B/classes" "$B/dex"
"$BT/aapt2" compile --dir res -o "$B/res.zip"
VCODE=$(( ($(date +%s) - 1767225600) / 60 ))   # minutes since 2026-01-01: always increasing
"$BT/aapt2" link -o "$B/base.apk" -I "$ANDROID_JAR" --manifest AndroidManifest.xml -A assets \
  --java "$B/gen" --min-sdk-version 31 --target-sdk-version 36 \
  --version-code "$VCODE" --version-name "1.0.$VCODE" --no-compress-regex '\.ttf$' "$B/res.zip"

# ---------------------------------------------------------------- 4. compile
unzip -o -q -p third_party/androidsvg/androidsvg-aar-1.4.aar classes.jar > "$B/androidsvg.jar"
find src "$B/gen" -name '*.java' > "$B/sources.txt"
$JAVA_TOOL javac -nowarn -Xlint:-options -encoding UTF-8 -source 8 -target 8 -bootclasspath "$BOOT" \
  -cp "$B/androidsvg.jar" -d "$B/classes" @"$B/sources.txt"

# ---------------------------------------------------------------- 5. dex
DEX_IN=("$B/androidsvg.jar")
if [[ $GEN_OK == 1 ]]; then while IFS= read -r f; do DEX_IN+=("$f"); done < <(find "$B/genclasses" -name '*.class'); fi
(cd "$B/classes" && find . -name '*.class' > "$B/classes.txt")
$JAVA_TOOL "$BT/d8" --release --min-api 31 --lib "$ANDROID_JAR" --output "$B/dex" \
  $(sed "s#^\./#$B/classes/#" "$B/classes.txt") "${DEX_IN[@]}" 2>&1 | grep -v "^Warning: Missing class" | grep -v "^$" || true
[[ -f "$B/dex/classes.dex" ]] || { echo "d8 failed"; exit 1; }

# ---------------------------------------------------------------- 6. package + sign
cp "$B/base.apk" "$B/unsigned.apk"
(cd "$B/dex" && zip -q -j "$B/unsigned.apk" classes*.dex)
"$BT/zipalign" -f -p 4 "$B/unsigned.apk" "$B/aligned.apk"
# Updates must be signed with the same key: keep keystore/ (it is not in git; a new one is made on first build).
KS=${KEYSTORE:-$ROOT/keystore/debug.keystore}
if [[ ! -f "$KS" ]]; then
  mkdir -p "$(dirname "$KS")"
  $JAVA_TOOL keytool -genkeypair -keystore "$KS" -storepass android -keypass android -alias livingsky \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Living Sky" >/dev/null
fi
$JAVA_TOOL "$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --out "$B/living-sky.apk" "$B/aligned.apk"
rm -f "$B/unsigned.apk" "$B/aligned.apk" "$B/living-sky.apk.idsig"
echo "built $B/living-sky.apk ($(du -h "$B/living-sky.apk" | cut -f1), versionCode $VCODE)"

if [[ "${1:-}" == "install" ]]; then
  [[ -n "$SERIAL" ]] || { echo "no phone attached (adb devices)"; exit 1; }
  "$ADB" -s "$SERIAL" install -r "$B/living-sky.apk"
  for p in ACCESS_COARSE_LOCATION ACCESS_BACKGROUND_LOCATION; do
    "$ADB" -s "$SERIAL" shell pm grant dev.livingsky android.permission.$p 2>/dev/null || true
  done
  "$ADB" -s "$SERIAL" shell am broadcast -a dev.livingsky.DEBUG -n dev.livingsky/.DebugReceiver --es cmd render >/dev/null || true
  echo "installed"
fi
