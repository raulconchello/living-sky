#!/usr/bin/env bash
# Regenerates the public README images in docs/img/ from the phone, through the real widget path (--es cmd hosted:
# a private AppWidgetHost on a virtual display, i.e. exactly what the launcher shows). Places are always forced and
# alarms are always mocked (privacy). Temp files live in /var/tmp/livingsky_shots and are deleted at the end.
#
#   tools/readme_shots.sh [hero|skylines|weather|column ...]   (default: all four;
#   skylines also makes skylines_night.png). The phone needs network (forecasts per place, the automatic skyline).
set -uo pipefail
cd "$(dirname "$(readlink -f "$0")")/.."
ADB=${ADB:-adb}
SERIAL=${ADB_SERIAL:-$("$ADB" devices | awk 'NR>1 && $2=="device"{print $1; exit}')}
if [[ -z "${JAVA_TOOL+x}" ]]; then J=""; javac -version >/dev/null 2>&1 || J="mise x java@21 --"; else J=$JAVA_TOOL; fi
W=/var/tmp/livingsky_shots; mkdir -p "$W/raw" "$W/cut" "$W/cls"
OUT=docs/img; mkdir -p "$OUT"
FD=/sdcard/Android/data/dev.livingsky/files
A() { local i; for i in 1 2 3; do timeout 40 "$ADB" -s "$SERIAL" "$@" && return 0; sleep 2; done; return 1; }
bc() { A shell am broadcast -a dev.livingsky.DEBUG -n dev.livingsky/.DebugReceiver "$@" >/dev/null; }
mtime() { A shell stat -c %Y "$FD/$1" 2>/dev/null || echo 0; }

# ---- the cutter: card out of hosted.png (rounded 26 dp corners, de-matted from the host's green), column alone
cat > "$W/Cut.java" <<'JAVA'
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
/** java Cut hosted.png card.png [column.png]: the card with transparent rounded corners (exact de-matting of the
 *  host's solid background), and optionally the column (+ its hairline ring) with transparent 14.6 dp corners. */
public class Cut {
    public static void main(String[] a) throws Exception {
        BufferedImage im = ImageIO.read(new File(a[0]));
        int bg = im.getRGB(2, 2), w = im.getWidth(), h = im.getHeight();
        int x0 = w, y0 = h, x1 = -1, y1 = -1;
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) if (diff(im.getRGB(x, y), bg) > 24) {
            x0 = Math.min(x0, x); y0 = Math.min(y0, y); x1 = Math.max(x1, x); y1 = Math.max(y1, y); }
        int cw = x1 - x0 + 1, ch = y1 - y0 + 1;
        double k = cw / 379.4, r = 26 * k;
        BufferedImage out = new BufferedImage(cw, ch, BufferedImage.TYPE_INT_ARGB);
        int br = (bg >> 16) & 255, bgg = (bg >> 8) & 255, bb = bg & 255;
        for (int y = 0; y < ch; y++) for (int x = 0; x < cw; x++) {
            double cov = cover(x, y, cw, ch, r);
            if (cov <= 0) { out.setRGB(x, y, 0); continue; }
            int p = im.getRGB(x0 + x, y0 + y);
            int R = (p >> 16) & 255, G = (p >> 8) & 255, B = p & 255;
            if (cov < 1) { R = um(R, br, cov); G = um(G, bgg, cov); B = um(B, bb, cov); }
            out.setRGB(x, y, ((int) Math.round(cov * 255) << 24) | (R << 16) | (G << 8) | B);
        }
        ImageIO.write(out, "png", new File(a[1]));
        System.out.printf("card %dx%d at %d,%d (k %.3f)%n", cw, ch, x0, y0, k);
        if (a.length > 2) { // column: 270..372 x 7..185.4 dp in the card, plus the 0.6 dp ring
            double e = 0.6 * k;
            double cx0 = 270 * k - e, cy0 = 7 * k - e, cx1 = 372 * k + e, cy1 = 185.4 * k + e, rr = 14.6 * k;
            int ix0 = (int) Math.floor(cx0), iy0 = (int) Math.floor(cy0), ix1 = (int) Math.ceil(cx1), iy1 = (int) Math.ceil(cy1);
            BufferedImage col = new BufferedImage(ix1 - ix0, iy1 - iy0, BufferedImage.TYPE_INT_ARGB);
            for (int y = iy0; y < iy1; y++) for (int x = ix0; x < ix1; x++) {
                double cov = cover2(x - cx0, y - cy0, cx1 - cx0, cy1 - cy0, rr);
                int p = out.getRGB(x, y) & 0xffffff;
                col.setRGB(x - ix0, y - iy0, cov <= 0 ? 0 : ((int) Math.round(cov * 255) << 24) | p);
            }
            ImageIO.write(col, "png", new File(a[2]));
        }
    }
    static int um(int c, int b, double a) { return Math.max(0, Math.min(255, (int) Math.round((c - b * (1 - a)) / a))); }
    static int diff(int p, int q) { return Math.abs(((p >> 16) & 255) - ((q >> 16) & 255)) + Math.abs(((p >> 8) & 255) - ((q >> 8) & 255)) + Math.abs((p & 255) - (q & 255)); }
    /** Coverage of pixel (x, y) by the rounded rect 0..w x 0..h, radius r (4x4 supersampling). */
    static double cover(int x, int y, int w, int h, double r) { return cover2(x, y, w, h, r); }
    static double cover2(double x, double y, double w, double h, double r) {
        int n = 0;
        for (int sy = 0; sy < 4; sy++) for (int sx = 0; sx < 4; sx++) {
            double px = x + (sx + 0.5) / 4, py = y + (sy + 0.5) / 4;
            if (px < 0 || py < 0 || px > w || py > h) continue;
            double dx = Math.max(0, Math.max(r - px, px - (w - r))), dy = Math.max(0, Math.max(r - py, py - (h - r)));
            if (dx * dx + dy * dy <= r * r) n++;
        }
        return n / 16.0;
    }
}
JAVA
$J javac -d "$W/cls" "$W/Cut.java" || exit 1

# ---- one widget render through the real host. shot name place scenario at mode colmode alarm [extra extras...]
# The hosted test saves hosted.png (~4 s), then scrolls and taps a row and saves hosted_toggled.png (~15 s): wait for
# both to be newer than the request, then pull hosted.png and check that it decodes (re-pull if it was mid-write).
shot() {
  local n=$1 t0 try
  t0=$(A shell date +%s | tr -d '\r')
  bc --es place "$2" --es mock false --es scenario "$3" --es at "$4" --es mode "$5" --es colmode "$6" --es colscroll 0 \
     --es alarm "'$7'" --es dump false "${@:8}" --es cmd hosted
  for _ in $(seq 1 60); do sleep 1; [[ $(mtime hosted.png) -ge $t0 && $(mtime hosted_toggled.png) -ge $t0 ]] && break; done
  if [[ $(mtime hosted.png) -lt $t0 ]]; then   # no fresh frame: never reuse the previous render
    [[ "${SHOT_RETRY:-0}" == 1 ]] && { echo "  $n: FAILED (no fresh hosted.png)"; return 1; }
    echo "  ($n: no fresh frame, retrying)"; SHOT_RETRY=1 shot "$@"; return
  fi
  sleep 1
  bc --es colmode bars   # undo the hosted test's own row tap (values mode), so the next render starts in bars
  for try in 1 2 3 4 5; do
    A pull "$FD/hosted.png" "$W/raw/$n.png" >/dev/null 2>&1
    $J java -cp "$W/cls" Cut "$W/raw/$n.png" "$W/cut/$n.png" "$W/cut/${n}_col.png" >/dev/null 2>&1 && { echo "  $n ($2, $3, $4)"; return 0; }
    sleep 2
  done
  echo "  $n: FAILED (no readable hosted.png)"; return 1
}
# fetch the forecast (and an automatic skyline) for a place, so the hosted render that follows uses its own data;
# retried a few times (with the screen off the phone dozes and an app's network comes and goes)
prep() { # place [extras]
  local t try log bad
  for try in $(seq 1 12); do
    t=$(A shell "date +'%m-%d %H:%M:%S.000'")
    bc --es place "$1" --es name none --es mock false --es scenario none --es at none --es mode auto --es alarm "'none'" \
       --es dump false "${@:2}" --es cmd refresh
    log=""
    for _ in $(seq 1 120); do sleep 1; log=$(A logcat -d -T "$t" -s LivingSky:* 2>/dev/null); grep -q "rendered:" <<<"$log" && break; done
    bad="weather fetch failed|Unable to resolve"; [[ "$1" == auto ]] && bad="$bad|no Wikidata"
    if grep -qE "$bad" <<<"$log"; then echo "  ($1: network not ready, retry $try)"; sleep 30; continue; fi
    echo "  prep $1: $(grep -m1 -o 'skyline [^;]*' <<<"$log")"
    return 0
  done
  echo "  prep $1: FAILED (no network)"; return 1
}

# ---------------------------------------------------------------------------------------------------- sections
X=/var/tmp/livingsky_shots/cut
# widget tiles: rounded card with transparent corners, scaled
tile() { magick "$X/$1.png" -filter Lanczos -resize "$2" "$3"; }
grid() { # out cols gap files...
  local out=$1 cols=$2 gap=$3; shift 3
  magick montage "$@" -tile "${cols}x" -geometry "+$((gap / 2))+$((gap / 2))" -background none "$out"
  magick "$out" -trim +repage "$out"   # drop the outer half-gaps; the transparent outside stays
}

sec_hero() {
  prep ulm || return
  shot h1_clear     ulm clear    12:30 auto bars none
  shot h2_partly    ulm partly   13:30 auto bars none
  shot h3_sunset    ulm clear    16:20 auto bars none
  shot h4_rain      ulm rain     14:00 auto bars none
  shot h5_thunder   ulm thunder  15:00 auto bars none
  shot h6_snow      ulm snow     11:00 auto bars none
  shot h7_fog       ulm fog      10:30 auto bars none
  shot h8_night     ulm clear    22:00 auto bars none
  local f=()
  for n in h1_clear h2_partly h3_sunset h4_rain h5_thunder h6_snow h7_fog h8_night; do
    tile "$n" 800x "$W/g_$n.png"; f+=("$W/g_$n.png")
  done
  magick -delay 160 -loop 0 -dispose Background "${f[@]}" -alpha set -channel A -threshold 50% +channel \
    -dither FloydSteinberg -colors 255 "$OUT/hero.gif"
}

PLACES=(ulm munich stuttgart chicago barcelona blanes malgrat terrassa alcaniz portell fornoles)
AUTO_PLACE="florence 43.7696 11.2558 Florence"   # the automatic OSM skyline (a city without a hand-drawn one)
sec_skylines() {
  local p
  for p in "${PLACES[@]}"; do
    prep "$p" || continue
    shot "s_$p" "$p" partly 14:30 auto bars none
    shot "n_$p" "$p" clear 21:30 auto bars none
  done
  set -- $AUTO_PLACE
  prep auto --es lat "$2" --es lon "$3" --es name "'${*:4}'" && {
    shot s_auto auto partly 14:30 auto bars none
    shot n_auto auto clear 21:30 auto bars none
  }
  prep ulm --es name none   # back to a plain forecast cache, no display-name override
  sec_skylinegrid
}
# the two grids from the renders already in $W/cut (KEEP=1 runs)
sec_skylinegrid() {
  local d=() n=() p
  for p in "${PLACES[@]}" auto; do
    tile "s_$p" 522x "$W/t_s_$p.png"; d+=("$W/t_s_$p.png")
    tile "n_$p" 522x "$W/t_n_$p.png"; n+=("$W/t_n_$p.png")
  done
  grid "$OUT/skylines.png" 3 16 "${d[@]}"
  grid "$OUT/skylines_night.png" 3 16 "${n[@]}"
}
sec_night() { :; }
# helper to choose the automatic city: renders a few candidates into $W/cut/try_*.png
sec_autotry() {
  local c
  for c in "paris 48.8584 2.2945 Paris" "london 51.5072 -0.1276 London" "newyork 40.7128 -74.0060 New York" "florence 43.7696 11.2558 Florence"; do
    set -- $c
    prep auto --es lat "$2" --es lon "$3" --es name "'${*:4}'"
    shot "try_$1" auto partly 14:30 auto bars none
  done
}   # made together with sec_skylines (same forecast fetch per place)

WEATHER=(w_clear w_partly w_overcast w_fog w_rain w_thunder w_hail w_snow w_sleet w_wind w_sunset w_nightrain)
sec_weather() {
  prep ulm || return
  shot w_clear      ulm clear        12:30 auto bars none
  shot w_partly     ulm partly       13:30 auto bars none
  shot w_overcast   ulm overcast     13:00 auto bars none
  shot w_fog        ulm fog          10:30 auto bars none
  shot w_rain       ulm rain         14:00 auto bars none
  shot w_thunder    ulm thunder      15:00 auto bars none
  shot w_hail       ulm thunderhail  14:00 auto bars none
  shot w_snow       ulm snow         11:00 auto bars none
  shot w_sleet      ulm sleet        12:00 auto bars none
  shot w_wind       ulm wind         13:00 auto bars none
  shot w_sunset     ulm partly       16:20 auto bars none
  shot w_nightrain  ulm rain         22:00 auto bars none
  local f=() n
  for n in "${WEATHER[@]}"; do tile "$n" 390x "$W/t_$n.png"; f+=("$W/t_$n.png"); done
  grid "$OUT/weather.png" 4 14 "${f[@]}"
}

# values mode shows the legend ("Rain" in #a6cfff) in the column's bottom 22 dp: count those pixels
legend_px() { magick "$X/${1}_col.png" -gravity south -crop x70+0+0 +repage \
  -fx '(r>0.58&&r<0.74&&g>0.76&&g<0.88&&b>0.94&&a>0.9)?1:0' -format '%[fx:round(mean*w*h)]' info:; }
sec_column() {
  prep ulm || return
  local d try; d=$(A shell date +%a | tr -d '\r')   # today's weekday, for the mocked alarms
  # an afternoon that turns wet: dry until 15:00, rain, then snow from 17:30 (the "mixed" debug scenario)
  shot c_bars ulm mixed 13:00 auto bars none
  for try in 1 2 3; do   # the hosted test's own row tap can race the mode switch: check for the values legend
    shot c_values ulm mixed 13:00 auto values none
    [[ $(legend_px c_values) -gt 40 ]] && break
    echo "  (c_values came out in bars mode, retry $try)"
  done
  shot c_pill ulm mixed 13:00 auto bars "$d 14:15"    # 1.25 h ahead: the pill at its minute
  shot c_tab  ulm mixed 13:00 auto bars "$d 22:30"    # 9.5 h ahead: the tab on the bottom edge, no pill
  magick "$X/c_bars_col.png" "$X/c_values_col.png" "$X/c_pill_col.png" "$X/c_tab_col.png" \
    -background none -gravity west -splice 24x0 +append +repage -gravity west -chop 24x0 +repage "$OUT/column.png"
}

reset_live() {
  local t; t=$(A shell "date +'%m-%d %H:%M:%S.000'")
  bc --ez reset true --es place live --es scenario none --es mode auto --es at none --es colmode bars --es colscroll 0 \
     --es alarm real --es dump false --es cmd render
  local log=""
  for _ in $(seq 1 60); do sleep 1; log=$(A logcat -d -T "$t" -s LivingSky:I 2>/dev/null | grep "rendered:"); [[ -n "$log" ]] && break; done
  echo "  back to live: $(tail -1 <<<"$log" | sed 's/.*rendered: //' | grep -o 'reason .*')"
}

SECTIONS=("$@"); [[ ${#SECTIONS[@]} -gt 0 ]] || SECTIONS=(hero skylines weather column)
for s in "${SECTIONS[@]}"; do echo "== $s"; "sec_$s"; done
reset_live
[[ "${KEEP:-0}" == 1 ]] || rm -rf "$W"   # KEEP=1 keeps the raw renders for inspection
ls -la "$OUT"
