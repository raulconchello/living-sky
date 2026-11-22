package dev.livingsky.render;

import static dev.livingsky.render.Noise.mixTo;
import static dev.livingsky.render.Noise.sm;

/**
 * The timeline column's continuous sky band: port of bandShade() from sky8.js (one continuous sky whose colour follows
 * the sun elevation, cloud cover, rain probability and precipitation of each moment), blended per hour with sky9.js
 * clear-sky ramp by cloud cover, plus fog hours. Content y = (t - now) * pxh (dp).
 */
public final class BandShader {
    private final Noise N;
    private final Wx wx;
    private final double now, pxh, lat, lon;

    public BandShader(Wx wx, double nowH, double pxhDp, double lat, double lon, long seed) {
        this.wx = wx; this.now = nowH; this.pxh = pxhDp; this.lat = lat; this.lon = lon;
        this.N = new Noise(seed);
    }

    private static final double[][] SKY_KEY = {
            {-18, 27, 26, 33}, {-11, 33, 33, 44}, {-7, 45, 49, 66}, {-4, 70, 74, 94},
            {-1.5, 112, 100, 110}, {1, 150, 126, 122}, {4, 150, 142, 144}, {9, 146, 149, 158}, {20, 154, 157, 166}};
    // sky9.js: clear-sky ramp over solar elevation (blue by day, orange-pink at sunset, deep blue then stars at night)
    private static final double[][] CLEAR_KEY = {
            {-18, 9, 12, 28}, {-11, 14, 20, 44}, {-7, 26, 38, 78}, {-4, 52, 62, 110}, {-1.5, 128, 96, 118},
            {1, 214, 138, 102}, {4, 150, 160, 188}, {10, 96, 142, 200}, {25, 70, 124, 198}};      // sky9 (reference)
    private static final double[][] CLEAR_KEY_DL = {
            {-18, 9, 12, 28}, {-11, 14, 20, 44}, {-7, 26, 38, 78}, {-4, 52, 62, 110}, {-1.5, 128, 96, 118},
            {1, 214, 138, 102}, {4, 168, 176, 206}, {10, 96, 164, 236}, {25, 70, 146, 232}};      // proposal B by day
    private static final double[][] SKY_KEY_DL = {
            {-18, 27, 26, 33}, {-11, 33, 33, 44}, {-7, 45, 49, 66}, {-4, 70, 74, 94}, {-1.5, 112, 100, 110},
            {1, 156, 138, 134}, {4, 168, 166, 172}, {9, 172, 178, 190}, {20, 180, 186, 198}};   // proposal B overcast
    public boolean daylight = true;

    // row cache
    private double rY = Double.NaN, rEl, rCc, rPmm, rPp, rNight, rClear, rFog, rThunder, rHail;
    private int rPt;
    private final double[] base = new double[3], ck = new double[3], light = new double[3], dark = new double[3], n = new double[3];

    // window-smoothed weather (live look): no hard steps between hours or states
    private double sPmm, sSnow, sFog, sThunder, sHail, sCc, sPp;
    private static final double[] OFFS = {-1, -0.75, -0.5, -0.25, 0, 0.25, 0.5, 0.75, 1};

    /** Triangular-weighted average over +-60 min: {rate mm/h, snow share 0..1, fog, thunder, hail, cloud 0..1, prob 0..1}. */
    private void smooth(double t, double[] o) {
        double ws = 0, rate = 0, snowR = 0, fog = 0, th = 0, ha = 0, cc = 0, pp = 0;
        for (double off : OFFS) {
            double w = 1 - Math.abs(off) / 1.25, tt = t + off;
            double r = wx.colRate(tt);
            int ty = wx.colType(tt);
            double sf = ty == Wx.SNOW ? 1 : ty == Wx.SLEET ? 0.5 : 0;
            ws += w; rate += w * r; snowR += w * r * sf;
            fog += w * wx.fogAt(tt);
            th += w * wx.codeAt(tt, 95, 99, -1);
            ha += w * Math.min(1, wx.codeAt(tt, 87, 90, 96) + wx.codeAt(tt, 99, 99, -1));
            cc += w * wx.colCloud(tt) / 100;
            pp += w * wx.colProbAt(tt) / 100;
        }
        o[0] = rate / ws; o[1] = rate > 1e-6 ? snowR / rate : 0; o[2] = fog / ws; o[3] = th / ws; o[4] = ha / ws; o[5] = cc / ws; o[6] = pp / ws;
    }

    private final double[] sm7 = new double[7];

    private void row(double y) {
        if (y == rY) return;
        rY = y;
        if (daylight) {
            smooth(now + y / pxh, sm7);
            sPmm = sm7[0]; sSnow = sm7[1]; sFog = sm7[2] * 0.6; sThunder = sm7[3]; sHail = sm7[4]; sCc = sm7[5]; sPp = sm7[6];
        }
        double t = now + y / pxh;
        rEl = Sun.elevation(t, lat, lon);
        rCc = wx.colCloud(t) / 100;          // ensemble members' mean where available
        rPmm = wx.colRate(t);
        rPp = wx.colProbAt(t) / 100;
        rPt = wx.colType(t);
        rFog = wx.fogAt(t) * 0.6;
        rThunder = wx.codeAt(t, 95, 99, -1);
        rHail = wx.codeAt(t, 87, 90, 96) + wx.codeAt(t, 99, 99, -1);
        rNight = sm(-4, -10, rEl);
        // per hour: clear ramp below ~30 % cover, the overcast key above ~85 %, blended in between
        rClear = 1 - sm(0.3, 0.85, rCc);
        Noise.ramp(base, daylight ? SKY_KEY_DL : SKY_KEY, rEl);
        if (rClear > 0) { Noise.ramp(ck, daylight ? CLEAR_KEY_DL : CLEAR_KEY, rEl); Noise.mixTo(base, ck, rClear); }
        double f = 1 - (daylight ? 0.14 : 0.22) * rPp; // darker, heavier deck when rain is coming
        base[0] *= f; base[1] *= f; base[2] *= f;
    }

    /** Live look style: REAL (realistic full-width sky, the default) or STATES (468641). */
    public static boolean REAL = true;

    public void shade(double x, double y, double[] col) {
        if (daylight) { if (REAL) shadeReal(x, y, col); else shadeStates(x, y, col); return; }
        row(y);
        double el = rEl, night = rNight;
        double d = N.fbm(x / 34 + 3.1, y / 10 + 11.7, 4) + N.fbm(x / 11, y / 3.6, 3) * 0.22;
        double cover = d + (rCc - 0.75) * 1.6;
        double T = sm(-0.35, 0.25, cover);
        boolean has = false;
        if (night < 1) {
            for (int i = 0; i < 3; i++) { light[i] = base[i] * 1.07 + 6; dark[i] = base[i] * 0.86; }
            Noise.mix(col, light, dark, T);
            has = true;
        }
        if (night > 0) {
            n[0] = 10 + (46 - 10) * T; n[1] = 12 + (40 - 12) * T; n[2] = 21 + (47 - 21) * T;
            // stars where the deck breaks
            double hsh = Noise.hash(Math.floor(x * 2.2), Math.floor(y * 2.2));
            if (hsh > 0.985) { double b = (hsh - 0.985) / 0.015; mixTo(n, 235, 236, 255, (1 - T) * b * 0.9); }
            if (has) Noise.mixTo(col, n, night); else { col[0] = n[0]; col[1] = n[1]; col[2] = n[2]; }
        }
        // sunset / sunrise glow band (warm smear at the horizon crossing)
        double glow = Math.exp(-Math.pow((el + 0.5) / 2.2, 2));
        mixTo(col, 214, 146, 116, glow * 0.55 * (0.55 + 0.45 * (1 - T)));
        if (rFog > 0.01) {
            double fb = 0.75 + 0.25 * N.fbm(x / 30, y / 4, 3);
            if (night > 0.5) mixTo(col, 78, 76, 82, rFog * 0.75 * fb);
            else mixTo(col, 170, 172, 176, rFog * 0.75 * fb);
        }
        // thunder hours: a bruised purple-grey deck with soft flashes inside the cloud
        if (rThunder > 0.01) {
            mixTo(col, 46, 42, 66, 0.38 * rThunder);
            double n = N.noise(x / 38 + y / 85, y / 22 + 7.7);
            if (n > 0.32) mixTo(col, 196, 198, 244, Math.min(1, (n - 0.32) * 2.2) * 0.42 * rThunder);
        }
        // hail hours: green-grey cast and white pellets
        if (rHail > 0.01) {
            mixTo(col, 96, 116, 102, 0.3 * Math.min(1, rHail));
            if (Noise.hash(Math.floor(x * 1.25) + 3, Math.floor(y * 1.25)) > 0.972) mixTo(col, 244, 248, 250, 0.85 * Math.min(1, rHail));
        }
        // precipitation texture: rain streaks, snow flecks, or both (sleet)
        double pmm = rPmm; int pt = rPt;
        if (pmm > 0.02 && pt != Wx.DRY) {
            double inten = Math.min(1, pmm / 1.4);
            double f = 1 - 0.18 * inten;
            col[0] *= f; col[1] *= f; col[2] *= f;
            if (pt != Wx.SNOW) {
                double r = N.noise((x + y * 0.32) * 1.3, y * 0.09);
                double r2 = N.noise((x + y * 0.32) * 2.9 + 50, y * 0.13);
                double streak = sm(0.22, 0.46, r) * 0.8 + sm(0.26, 0.46, r2) * 0.6;
                double k = streak * (pt == Wx.SLEET ? 0.3 : 0.42) * inten;
                if (night > 0.5) mixTo(col, 120, 130, 156, k); else mixTo(col, 205, 212, 226, k);
            }
            if (pt != Wx.RAIN) {
                double h2 = Noise.hash(Math.floor(x * 1.6), Math.floor(y * 1.6));
                if (h2 > (pt == Wx.SNOW ? 0.93 : 0.965)) mixTo(col, 236, 240, 250, 0.75);
            }
        }
    }

    // ------------------------------------------------------------------ per-state band (live look)
    // Every weather state reads at a glance at 102 x 22 dp per hour, calm, no icons or lines: clear blue, partly
    // cloudy puffs with blue gaps, flat grey overcast, milky fog, and (full-resolution overlay) thin cool streaks for
    // rain, white flakes for snow, pellets for hail; thunder bruised purple with flashes, hail green-grey. Precipitation
    // darkens the deck so the texture pops, and stays lighter / thinner than the blue bars drawn on top.
    private final double[] cl = new double[3], cd = new double[3];

    private void shadeStates(double x, double y, double[] col) {
        row(y);
        double el = rEl, night = rNight, cc = sCc;
        // clear sky by sun elevation (blue by day, navy at night, warm at the horizon crossing)
        Noise.ramp(ck, CLEAR_KEY_DL, el);
        // cloud field: puffs whose share follows the cover; their tone goes from lit white puffs to the grey deck
        // a calm backdrop: clouds are large, blurred, low-contrast tonal patches (never white puffs on bright blue)
        double ckl = 0.30 * ck[0] + 0.59 * ck[1] + 0.11 * ck[2];
        // the cap below only lowers brightness, so the clear sky keeps its blue (deeper, not greyer)
        double d = N.fbm(x / 70 + 3.1, y / 18 + 11.7, 3) + N.fbm(x / 28 + 7.3, y / 8 + 2.9, 2) * 0.18;
        double th = 0.40 - cc * 0.92;                       // threshold: ~0 % cover -> none, ~100 % -> all
        double m = cc > 0.97 ? 1 : sm(th - 0.16, th + 0.26, d);
        double deckK = sm(0.55, 0.95, cc);                   // patches -> deck
        Noise.ramp(cd, SKY_KEY_DL, el);                      // overcast deck colour by elevation
        double shade = sm(th, th + 0.5, d);
        // patch colour: the blue greyed toward the deck, barely lighter than the gaps
        double pr = ck[0] + (cd[0] - ck[0]) * 0.75 + 10 - 8 * shade;   // soft grey patches
        double pg = ck[1] + (cd[1] - ck[1]) * 0.75 + 8 - 8 * shade;
        double pb = ck[2] + (cd[2] - ck[2]) * 0.75 + 4 - 6 * shade;
        cl[0] = pr + (cd[0] * (1.01 - 0.05 * shade) - pr) * deckK;
        cl[1] = pg + (cd[1] * (1.01 - 0.05 * shade) - pg) * deckK;
        cl[2] = pb + (cd[2] * (1.01 - 0.05 * shade) - pb) * deckK;
        Noise.mix(col, ck, cl, m);
        if (night > 0) {
            double nr = 10, ng = 13, nb = 28;                // clear night
            double hsh = Noise.hash(Math.floor(x * 2.2), Math.floor(y * 2.2));
            double star = hsh > 0.984 ? (hsh - 0.984) / 0.016 * (1 - m) : 0;
            double kr = 38 + (36 - 38) * deckK, kg = 38 + (34 - 38) * deckK, kb = 50 + (44 - 50) * deckK; // clouds lit from below
            kr += -4 * shade; kg += -4 * shade; kb += -4 * shade;
            double xr = nr + (kr - nr) * m, xg = ng + (kg - ng) * m, xb = nb + (kb - nb) * m;
            xr += (235 - xr) * star * 0.9; xg += (236 - xg) * star * 0.9; xb += (255 - xb) * star * 0.9;
            col[0] += (xr - col[0]) * night; col[1] += (xg - col[1]) * night; col[2] += (xb - col[2]) * night;
        }
        double f = 1 - 0.10 * sPp;                           // a heavier deck when rain is likely
        col[0] *= f; col[1] *= f; col[2] *= f;
        // sunrise / sunset glow
        double glow = Math.exp(-Math.pow((el + 0.5) / 2.2, 2));
        mixTo(col, 214, 146, 116, glow * 0.5 * (0.5 + 0.5 * (1 - m)));
        // fog: milky, low contrast, soft horizontal drifts
        if (sFog > 0.005) {
            double fb = 0.62 + 0.38 * sm(-0.3, 0.3, N.fbm(x / 60 + 5.5, y / 2.6, 3));  // soft horizontal mist drifts
            double k = Math.min(1, sFog / 0.6) * 0.92 * fb;
            // milky, warm-grey (overcast is cooler); day and night tones blend through dusk (no switch)
            mixTo(col, 200 + (92 - 200) * night, 198 + (86 - 198) * night, 194 + (86 - 194) * night, k);
        }
        double pmm = sPmm, snow = sSnow;
        double inten = Math.min(1, pmm / 2.5);
        if (pmm > 0.005) { // precipitation darkens the deck (more for heavy) so the texture reads; fades in and out
            double rainDk = 0.24 + 0.26 * inten, snowDk = 0.22 + 0.12 * inten;
            double dk = (rainDk + (snowDk - rainDk) * snow) * sm(0.02, 0.35, pmm);
            col[0] *= 1 - dk; col[1] *= 1 - dk; col[2] *= 1 - dk * 0.85;
        }
        // thunder: bruised purple-grey with soft flashes inside the cloud
        if (sThunder > 0.005) {
            mixTo(col, 50, 42, 72, 0.55 * sThunder);
            double n2 = N.noise(x / 30 + y / 70, y / 16 + 7.7);
            if (n2 > 0.28) mixTo(col, 212, 204, 255, Math.min(1, (n2 - 0.28) * 2.6) * 0.62 * sThunder);
        }
        // hail: green-grey cast (pellets in the overlay)
        if (sHail > 0.005) mixTo(col, 86, 106, 92, 0.5 * Math.min(1, sHail));
        // legibility first: under the hour lane (left) and the temperature lane (right) the band is darkened just
        // enough that 11 dp white text at 62-80 % reads at >= 4.5:1 in every state; soft toward the bar lane
        // the whole band stays a calm, mid-dark backdrop, and the text lanes a little darker still
        double lane = Math.max(1 - sm(14, 38, x), sm(50, 80, x));
        double cap = BAND_MAX + (LANE_MAX - BAND_MAX) * lane;
        double Y = 0.2126 * col[0] + 0.7152 * col[1] + 0.0722 * col[2];
        if (Y > cap) {
            double f2 = cap / Y;
            col[0] *= f2; col[1] *= f2; col[2] *= f2;
        }
    }

    /** Max brightness of the band anywhere (sRGB-weighted): a calm backdrop under white text. */
    public static double BAND_MAX = 96;

    /** Max sRGB-weighted brightness behind the column's text lanes (white 62 % text >= ~4.5:1 before its halo). */
    public static double LANE_MAX = 62;

    /** Weather texture lives in the bar lane: faded near the text lanes. */
    static double textureLane(double x) {
        return 0.06 + 0.94 * sm(20, 32, x) * (1 - sm(58, 74, x));
    }

    // ------------------------------------------------------------------ realistic band (full width, no lanes)
    /** Uniform, mild brightness ceiling (sRGB-weighted) over the whole width; the labels' halo carries the rest. */
    // vivid (after 468678): no ceiling, the sky ramp's own blue, white-ish lit cloud tops; the labels' halo carries
    // legibility. 468678 was CEIL = true, GAP_DESAT = 0.15, GAP_DIM = 0.93, DECK_DK = 0.8, LIT 208/212/220, SHD 146/154/172.
    public static boolean CEIL = false;
    public static double REAL_CAP = 150, GAP_DESAT = 0, GAP_DIM = 1, KNEE = 96, KNEE_R = 0.3, EDGE = 0.12, RELIEF = 10, CS = 48, DECK_DK = 0.86, GLOW_MIN = 0.55;
    /** Lit cloud tops and shaded bases by day; HI_CAP caps only the cloud highlights' luma a little (999 = off). */
    public static double[] LIT = {240, 243, 248}, SHD = {170, 181, 202};
    public static double HI_CAP = 220;
    private final double[] ld = new double[3];

    private double cloudField(double x, double y) {
        return N.fbm(x / CS + 3.1, y / (CS * 0.3) + 11.7, 4) + N.fbm(x / (CS * 0.33), y / (CS * 0.105), 3) * 0.22;
    }

    private void shadeReal(double x, double y, double[] col) {
        row(y);
        double el = rEl, night = rNight, cc = sCc;
        // sky colour: clear key, gaps less saturated and a little darker than sky9; toward the deck key as cover closes
        Noise.ramp(ck, CLEAR_KEY_DL, el);
        double ckl = 0.30 * ck[0] + 0.59 * ck[1] + 0.11 * ck[2];
        for (int i = 0; i < 3; i++) ck[i] = (ck[i] + (ckl - ck[i]) * GAP_DESAT) * GAP_DIM;
        Noise.ramp(cd, SKY_KEY_DL, el);
        double clear = 1 - sm(0.55, 0.97, cc);
        for (int i = 0; i < 3; i++) base[i] = cd[i] + (ck[i] - cd[i]) * clear;
        double fp = 1 - 0.14 * sPp;
        for (int i = 0; i < 3; i++) base[i] *= fp;
        // real cloud shapes: one soft field whose covered area follows the (ensemble) cloud cover of the row
        double d = cloudField(x, y);
        double p = Math.max(0.02, Math.min(0.98, cc));
        double dv = d + 0.12 * Math.log(p / (1 - p)) / 1.702;   // d ~ N(0, 0.12): the area with dv > 0 is about cc
        double T = sm(-EDGE * 0.5, EDGE * 0.5, dv) * sm(0.03, 0.2, cc);       // cloud vs gap, soft edges; none in a clear sky
        double rel = d - cloudField(x - 0.9, y - 1.6);          // relief: light from above (earlier) and the left
        double sh = sm(-0.4, 0.7, -rel * RELIEF + (sm(0.0, 0.25, dv) - 0.45) * 1.2);   // 0 lit .. 1 shaded
        double Tov = sm(-0.35, 0.25, d + (cc - 0.75) * 1.6);   // overcast: the original deck relief
        double ovc = sm(0.8, 0.98, cc);
        // overcast: the original deck relief, plus soft rolls lit from above so the deck has a base, not a flat grey
        double roll = Math.max(-1, Math.min(1, -rel * RELIEF * 0.5));
        for (int i = 0; i < 3; i++) { light[i] = base[i] * (1.07 + 0.05 * roll) + 6; dark[i] = base[i] * (DECK_DK - 0.06 * Math.max(0, -roll)); }
        // broken cloud: lit tops grey-white (not white), shaded bases grey-blue
        // at dusk the clouds follow the sky's own light (no white blobs on a dark sky): tones relative to the base
        double dayf = sm(-2, 6, el);
        for (int i = 0; i < 3; i++) ld[i] = LIT[i] + (SHD[i] - LIT[i]) * sh;
        double ly = 0.2126 * ld[0] + 0.7152 * ld[1] + 0.0722 * ld[2];
        if (ly > HI_CAP) for (int i = 0; i < 3; i++) ld[i] *= HI_CAP / ly;   // only the brightest cloud tops
        for (int i = 0; i < 3; i++) {
            double ov = light[i] + (dark[i] - light[i]) * Tov;
            double litTw = base[i] * (1.16 - 0.36 * sh);
            double litc = litTw + (ld[i] - litTw) * dayf;
            double pc = base[i] + (litc - base[i]) * T * 0.92;
            col[i] = pc + (ov - pc) * ovc;
        }
        T = T + (Tov - T) * ovc;
        if (night > 0) { // the original night: clouds faintly lit by the town, stars where the deck breaks
            double Tl = T * (0.72 + 0.28 * ovc);           // broken cloud at night: faint, the deck as before
            n[0] = 10 + (46 - 10) * Tl; n[1] = 12 + (40 - 12) * Tl; n[2] = 21 + (47 - 21) * Tl;
            double hsh = Noise.hash(Math.floor(x * 2.2), Math.floor(y * 2.2));
            if (hsh > 0.985) { double b = (hsh - 0.985) / 0.015; mixTo(n, 235, 236, 255, (1 - T) * b * 0.9); }
            Noise.mixTo(col, n, night);
        }
        // fog (smoothed), the original tones
        if (sFog > 0.005) { // milky, layered mist: flat and pale with slow horizontal veils, no cloud relief
            double fb = 0.62 + 0.38 * sm(-0.25, 0.3, N.fbm(x / 70 + 5.3, y / 5.5, 3));
            double fw = Math.min(1, sFog / 0.6);
            mixTo(col, 176 + (78 - 176) * night, 176 + (76 - 176) * night, 174 + (82 - 174) * night, fw * 0.88 * fb);
        }
        // thunder: bruised deck with soft flashes; hail: green-grey (pellets in the overlay)
        if (sThunder > 0.005) {
            mixTo(col, 46, 42, 66, 0.4 * sThunder);
            double n2 = N.noise(x / 38 + y / 85, y / 22 + 7.7);
            if (n2 > 0.3) mixTo(col, 196, 198, 244, Math.min(1, (n2 - 0.3) * 2.2) * 0.45 * sThunder);
        }
        if (sHail > 0.005) mixTo(col, 96, 116, 102, 0.3 * Math.min(1, sHail));
        // precipitation: the deck darkens with the (smoothed) intensity so the full-width streaks / flakes read
        double inten = Math.min(1, sPmm / 1.4);
        if (sPmm > 0.005) {
            double dk = 0.2 * inten * sm(0.0, 0.25, sPmm) + 0.08 * sm(0.02, 0.3, sPmm);
            col[0] *= 1 - dk; col[1] *= 1 - dk; col[2] *= 1 - dk * 0.9;
        }
        // sunrise / sunset glow band, painted over fog, deck, storm and wet darkening so weather never washes it
        // out: the clear-sky strength where the sky is open, never below GLOW_MIN; mist spreads it a little wider
        double gw = 2.2 + 1.2 * Math.min(1, sFog / 0.6);
        double glow = Math.exp(-Math.pow((el + 0.5) / gw, 2));
        if (glow > 0.003) mixTo(col, 214, 146, 116, glow * Math.max(GLOW_MIN, 0.55 * (0.55 + 0.45 * (1 - T))));
        // 468678 only (CEIL): a uniform soft ceiling over the whole width
        double Y = 0.2126 * col[0] + 0.7152 * col[1] + 0.0722 * col[2];
        if (CEIL && Y > KNEE) { double f = (KNEE + (Y - KNEE) * KNEE_R) / Y; col[0] *= f; col[1] *= f; col[2] *= f; }
        // snow: the earlier soft flecks (one low-res cell each, softened by the upscale), full width; the density
        // follows the smoothed snow share and rate (sleet ~3.5 % of cells, snow ~7 %), so it fades in and out
        double sOn = sSnow * sm(0.02, 0.3, sPmm);
        if (sOn > 0.01) {
            double dens = (0.035 + 0.035 * sm(0.5, 1.0, sSnow)) * Math.min(1, sOn * 1.6);
            if (Noise.hash(Math.floor(x * 1.6), Math.floor(y * 1.6)) > 1 - dens) mixTo(col, 236, 240, 250, 0.75);
        }
    }

    /**
     * Full-resolution texture over the upscaled band (live look): thin cool slanted streaks for rain (denser for
     * heavy, short for drizzle), white flakes for snow, both for sleet, pellets for hail. Crisp, lighter and thinner
     * than the blue bars on top. @param k px per dp
     */
    public void overlay(int[] px, int W, int H, double k) {
        if (!daylight) return;
        double[] c = new double[3], sv = new double[7];
        for (int py = 0; py < H; py++) {
            double y = (py + 0.5) / k;
            double t = now + y / pxh;
            smooth(t, sv);                                     // same window as the deck: texture fades in / out
            double pmm = sv[0], snow = sv[1], hail = Math.min(1, sv[4]);
            double on = sm(0.02, 0.3, pmm);                     // ramps over ~1 h at a dry <-> wet edge
            if (on < 0.01 && hail < 0.02) continue;
            double rainW = (1 - snow) * on, snowW = snow * on;  // rain -> sleet -> snow crossfade
            double night = sm(-4, -10, Sun.elevation(t, lat, lon));
            double inten = Math.min(1, pmm / 2.5);
            double dz = 1 - sm(0.2, 0.7, pmm);                  // drizzle-ness, continuous
            double slant = 0.3 - 0.16 * dz;
            double lane = (2.9 - 1.3 * inten) + (2.1 - (2.9 - 1.3 * inten)) * dz;
            double wdp = (0.55 + 0.2 * inten) + (0.42 - (0.55 + 0.2 * inten)) * dz;
            double seg = 7.5 - 4.3 * dz, len = 0.62 - 0.17 * dz;
            double rDens = (0.34 + 0.4 * inten) * (1 - 0.35 * snow) * (REAL ? 0.8 : 1);
            double fRad0 = 0.42 + 0.13 * sm(0.5, 1, snow);    // sleet: small; snow: larger flakes
            for (int x0 = 0; x0 < W; x0++) {
                double x = (x0 + 0.5) / k;
                double a = 0; double r = 0, g = 0, b = 0;
                if (rainW > 0.01) {
                    double s = x - y * slant, u = s / lane, li = Math.floor(u), fr = u - li;
                    double dist = Math.abs(fr - 0.5) * lane;
                    double line = 1 - sm(wdp * 0.35, wdp * 0.75, dist);
                    if (line > 0) {
                        double v = y / seg + Noise.hash(li, 3.1) * 17;
                        double si = Math.floor(v), p = v - si;
                        if (Noise.hash(li + 0.5, si) < rDens * (0.4 + 0.6 * rainW) && p < len) {
                            double head = 0.35 + 0.65 * (p / len);
                            a = line * head * (0.82 - 0.10 * night) * (1 - 0.3 * dz) * rainW;
                            r = 226 + (178 - 226) * night; g = 238 + (194 - 238) * night; b = 255 + (230 - 255) * night;
                        }
                    }
                }
                if (snowW > 0.01 && !REAL) {   // REAL: the earlier snow flecks, painted in shadeReal
                    double cs = (3.3 + 0.5 * (1 - snow)) * (REAL ? 0.78 : 1);   // REAL: finer, more numerous flakes
                    double gx = Math.floor(x / cs), gy = Math.floor((y - x * 0.08) / cs);
                    double hsh = Noise.hash(gx + 11.3, gy);
                    double dens = (0.22 + (0.32 + 0.33 * inten - 0.22) * snow) * (0.4 + 0.6 * snowW);
                    if (hsh < dens) {
                        double fx = (gx + 0.2 + 0.6 * Noise.hash(gx, gy + 5.1)) * cs, fy = (gy + 0.2 + 0.6 * Noise.hash(gx + 7.7, gy)) * cs + x * 0.08;
                        double rad = (fRad0 + 0.45 * Noise.hash(gx + 2.2, gy + 9.9) * sm(0.5, 1, snow)) * (REAL ? 0.8 : 1);
                        double dd = Math.hypot(x - fx, y - fy);
                        double fa = (1 - sm(rad * 0.45, rad, dd)) * 0.95 * Math.min(1, snowW * 1.6);
                        if (fa > a) { a = fa; r = 250; g = 252; b = 255; }
                    }
                }
                if (hail > 0.02) {
                    double cs = 3.6;
                    double gx = Math.floor(x / cs), gy = Math.floor((y - x * 0.2) / cs);
                    if (Noise.hash(gx + 31.1, gy) < 0.4 * hail) {
                        double fx = (gx + 0.25 + 0.5 * Noise.hash(gx, gy + 1.7)) * cs, fy = (gy + 0.25 + 0.5 * Noise.hash(gx + 4.4, gy)) * cs + x * 0.2;
                        double dd = Math.hypot(x - fx, y - fy);
                        double fa = 1 - sm(0.45, 0.75, dd);
                        double tail = (y < fy && y > fy - 2.2 && Math.abs(x - fx + (fy - y) * 0.2) < 0.35) ? 0.3 * (1 - (fy - y) / 2.2) : 0;
                        double hv = Math.max(fa, tail) * Math.min(1, hail * 1.5);
                        if (hv > a) { a = hv * 0.95; r = 242; g = 248; b = 246; }
                    }
                }
                a *= REAL ? (0.42 + 0.3 * inten) : textureLane(x);    // REAL: full width, the old soft density
                if (a > 0.003) {
                    int i = py * W + x0, p = px[i];
                    c[0] = ((p >> 16) & 255) + (r - ((p >> 16) & 255)) * a;
                    c[1] = ((p >> 8) & 255) + (g - ((p >> 8) & 255)) * a;
                    c[2] = (p & 255) + (b - (p & 255)) * a;
                    px[i] = Px.argb(c);
                }
            }
        }
    }

    /** Low-res render (sky8.js paints the band at 0.6 x device resolution). */
    public int[] render(int pw, int ph, double pxPerDp) {
        int[] out = new int[pw * ph];
        double[] col = new double[3];
        for (int py = 0; py < ph; py++) {
            double y = (py + 0.5) / pxPerDp;
            for (int px = 0; px < pw; px++) {
                double x = (px + 0.5) / pxPerDp;
                shade(x, y, col);
                out[py * pw + px] = Px.argb(col);
            }
        }
        return out;
    }
}
