#!/usr/bin/env bash
# Renders every hand-drawn place (day + night) and the automatic skyline ON THE PHONE through the debug receiver
# (same code path as the widget), pulls the composited result and builds a contact sheet in build/shots/skylines/.
#
#   tools/verify_skylines.sh [id ...]        (default: every place in assets/skylines + auto)
#   SCREEN=1 tools/verify_skylines.sh        also screencap the home screen after each render (widget must be visible)
set -uo pipefail  # adb over mobile data can be flaky: keep going
cd "$(dirname "$(readlink -f "$0")")/.."
ADB=${ADB:-adb}
SERIAL=${ADB_SERIAL:-$("$ADB" devices | awk 'NR>1 && $2=="device"{print $1; exit}')}
A() { local i; for i in 1 2 3; do "$ADB" -s "$SERIAL" "$@" && return 0; sleep 2; done; return 1; }
OUT=build/shots/skylines
mkdir -p "$OUT"
ids=("$@")
if [[ ${#ids[@]} -eq 0 ]]; then
  for j in assets/skylines/*.json; do ids+=("$(basename "$j" .json)"); done
  ids+=(auto)
fi
render() { # place mode name
  local before after
  before=$(A shell stat -c %Y /sdcard/Android/data/dev.livingsky/files/preview.png 2>/dev/null || echo 0)
  A shell am broadcast -a dev.livingsky.DEBUG -n dev.livingsky/.DebugReceiver --es dump true \
    --es place "$1" --es mode "$2" --es mock false --es scenario "''" --es cmd refresh "${@:4}" >/dev/null
  for _ in $(seq 1 60); do
    sleep 1
    after=$(A shell stat -c %Y /sdcard/Android/data/dev.livingsky/files/preview.png 2>/dev/null || echo 0)
    [[ "$after" != "$before" ]] && break
  done
  local s1=0 s2=1; for _ in $(seq 1 20); do s1=$(A shell stat -c %s /sdcard/Android/data/dev.livingsky/files/preview.png 2>/dev/null); sleep 0.7; s2=$(A shell stat -c %s /sdcard/Android/data/dev.livingsky/files/preview.png 2>/dev/null); [[ "$s1" == "$s2" && "$s1" -gt 1000 ]] && break; done
  A pull /sdcard/Android/data/dev.livingsky/files/preview.png "$OUT/$3.png" >/dev/null
  if [[ "${SCREEN:-0}" == 1 ]]; then sleep 2; A exec-out screencap -p > "$OUT/$3_screen.png"; fi
  echo "$3: $(A shell "logcat -d -s LivingSky:I | grep 'debug:' | tail -1" | sed 's/.*debug: //')"
}
for id in "${ids[@]}"; do
  if [[ "$id" == auto ]]; then
    # automatic skyline at a place without a hand-drawn one (Aalen, Swabian Alb)
    render auto day auto_day --es lat 48.8378 --es lon 10.0933 --es name Aalen
    render auto night auto_night --es lat 48.8378 --es lon 10.0933 --es name Aalen
  else
    render "$id" day "${id}_day"
    render "$id" night "${id}_night"
  fi
done
# back to live
A shell am broadcast -a dev.livingsky.DEBUG -n dev.livingsky/.DebugReceiver --ez reset true --es place "''" --es cmd refresh >/dev/null
files=()
for d in "$OUT"/*_day.png; do id=$(basename "$d" _day.png); files+=("$d" "$OUT/${id}_night.png"); done
magick montage "${files[@]}" -tile 2x -geometry 498x253+6+6 -background '#202228' "$OUT/contact_sheet.png"
echo "contact sheet: $OUT/contact_sheet.png"
