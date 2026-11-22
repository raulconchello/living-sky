#!/usr/bin/env bash
# Renders every debug weather scenario ON THE PHONE (Ulm; day 12:00, night 22:00, sunset 16:30) through the debug
# receiver and builds labelled contact sheets in build/shots/weather/. Usage: tools/weather_shots.sh [scenario ...]
set -uo pipefail
cd "$(dirname "$(readlink -f "$0")")/.."
ADB=${ADB:-adb}
SERIAL=${ADB_SERIAL:-$("$ADB" devices | awk 'NR>1 && $2=="device"{print $1; exit}')}
A() { local i; for i in 1 2 3; do "$ADB" -s "$SERIAL" "$@" && return 0; sleep 2; done; return 1; }
OUT=build/shots/weather; mkdir -p "$OUT"
SC=("$@"); [[ ${#SC[@]} -gt 0 ]] || SC=(clear partly overcast fog drizzle rain heavy showers thunder hail thunderhail sleet snow heavysnow wind rainwind)
F=/sdcard/Android/data/dev.livingsky/files/preview.png
shot() { # scenario at name
  local before after
  before=$(A shell stat -c %Y $F 2>/dev/null || echo 0)
  A shell am broadcast -a dev.livingsky.DEBUG -n dev.livingsky/.DebugReceiver --es dump true --es place ulm \
     --es mode auto --es mock false --es scenario "$1" --es at "$2" --es cmd render >/dev/null
  for _ in $(seq 1 30); do sleep 1; after=$(A shell stat -c %Y $F 2>/dev/null || echo 0); [[ "$after" != "$before" ]] && break; done
  # the phone writes the PNG after touching it: wait until its size is stable before pulling
  local s1=0 s2=1; for _ in $(seq 1 20); do s1=$(A shell stat -c %s $F 2>/dev/null); sleep 0.7; s2=$(A shell stat -c %s $F 2>/dev/null); [[ "$s1" == "$s2" && "$s1" -gt 1000 ]] && break; done
  A pull $F "$OUT/$3.png" >/dev/null && echo "$3"
}
for s in "${SC[@]}"; do shot "$s" 12:00 "day_$s"; shot "$s" 22:00 "night_$s"; done
for s in clear partly overcast; do shot "$s" 16:30 "sunset_$s"; done
sheet() { # prefix
  local args=()
  for f in "$OUT/$1"_*.png; do args+=( \( "$f" -resize 498x -gravity north -background '#202228' -splice 0x30 -fill white -pointsize 22 -annotate +0+4 "$(basename "$f" .png)" \) ); done
  magick "${args[@]}" miff:- | magick montage - -tile 2x -geometry +6+6 -background '#202228' "$OUT/sheet_$1.png"
}
sheet day; sheet night; sheet sunset
echo "sheets: $OUT/sheet_day.png $OUT/sheet_night.png $OUT/sheet_sunset.png"
