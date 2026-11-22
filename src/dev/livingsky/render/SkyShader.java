package dev.livingsky.render;

import static dev.livingsky.render.Noise.clamp;
import static dev.livingsky.render.Noise.mixTo;
import static dev.livingsky.render.Noise.sm;

/**
 * The card's per-pixel sky: a port of skyDay / skyNight / deck from sky8.js, extended (same painterly style) with
 * clear and partly cloudy skies driven by cloud cover, a sunrise/sunset glow and twilight, fog, and live rain/snow.
 * With cover = 1, approach = 1, no precipitation and the sun high (or deep night) it reproduces the approved sketch.
 */
public final class SkyShader {
    public static final class Params {
        public boolean night;          // night shader (sun below about -4 deg)
        public double el = 20;         // solar elevation now (deg)
        public double sunX = 190, sunY = 50; // hidden sun position (dp, card coords)
        public double cover = 1;       // 0..1
        public double approach = 0;    // 0..1, rain approaching (heavier base + distant shafts on the right)
        public double precip = 0;      // mm/h now
        public int ptype = Wx.DRY;
        public double fog = 0;         // 0..1
        public double heroGlow = 1;    // floodlit landmark glow at night (0 for generic towns)
        public double focal = 73, HY = 134, heroX = 222;
        public double[] vig = {75, 62, 165, 78};
        public double storm = 0;                 // 0..1 darker, heavier cloud (rain, storms)
        public double[] tint = null;             // {r, g, b, amount}: thunder purple-grey, hail green-grey, snow white-grey
        public double stretch = 1;               // < 1 stretches the clouds horizontally (strong wind)
        public int state = WxState.OVERCAST;
        public boolean daylight = true;          // proposal B "Vívido" by day (false: the original sky8/sky9 sketch)
        public long seed = 20261121;
    }

    private static final double[] C_158_161_170 = {158, 161, 170};
    private static final double[] C_206_201_201 = {206, 201, 201};
    private static final double[] C_70_75_88 = {70, 75, 88};
    private static final double[] C_132_130_137 = {132, 130, 137};
    private static final double[] C_14_16_24 = {14, 16, 24};
    private static final double[] C_44_38_44 = {44, 38, 44};
    private static final double[] C_40_38_47 = {40, 38, 47};
    private static final double[] C_118_86_72 = {118, 86, 72};

    private final Params P;
    private final Noise N;
    private double thr = 2; // cloud-mask threshold on raw deck thickness (quantile for the cover fraction)

    public SkyShader(Params p) { this.P = p; this.N = new Noise(p.seed); }

    // per-pixel deck outputs
    private double dT, dTraw, dRel, dG, dFar;

    private void deck(double x, double y) {
        double dy = Math.max(P.HY - y, 0) + 6;
        double X = (x - 150) / dy * P.focal / 140, Z = P.focal / dy;
        double sx = X * 1.35 * P.stretch, sz = Z * 1.0 + 1.0;
        int oct = dy < 22 ? 3 : dy < 60 ? 4 : 6;
        double wx = N.fbm(sx * 0.5 + 5.2, sz * 0.5 + 1.3, 3), wz = N.fbm(sx * 0.5 + 8.3, sz * 0.5 + 2.8, 3);
        double ux = sx + 0.55 * wx, uz = sz + 0.55 * wz;
        double b = N.billow(ux * 1.25, uz * 2.6, oct);
        double b2 = N.billow(ux * 1.25 + 0.05, uz * 2.6 + 0.07, Math.min(oct, 4));
        double roll = Math.sin(uz * 5.0 + 2.2 * N.fbm(ux * 0.9, uz * 0.9, 2));
        double big = N.fbm(ux * 0.42 + 11, uz * 0.42 + 4, 3);
        double thick = 0.5 + 0.9 * big + 0.55 * (b - 0.25) + 0.10 * roll;
        double g = Math.min(1, y / P.HY);
        double far = sm(0.62, 0.98, g);
        dTraw = sm(0.05, 0.95, thick);
        dT = dTraw * (1 - far * 0.6) + far * 0.3;
        dRel = (b - b2) * (1 - far);
        dG = g;
        dFar = far;
    }

    private final double[] tmp = new double[3], tmp2 = new double[3];

    /** Fraction-of-sky mask for broken cloud (1 = cloud). */
    private double cloudMask() {
        if (P.cover >= 0.999) return 1;
        double m = sm(thr - 0.05, thr + 0.17, dTraw);
        // toward the horizon the cells merge into a haze of the same overall cover
        return m * (1 - dFar * 0.55) + dFar * 0.55 * Math.min(1, P.cover * 1.15);
    }

    /** sky8.js skyDay: the overcast stratocumulus deck (the approved sketch), vignette included. */
    private void skyOvercast(double x, double y, double[] col) {
        double g = dG, T = dT;
        double[] light = tmp, dark = tmp2;
        Noise.mix(light, C_158_161_170, C_206_201_201, g);
        Noise.mix(dark, C_70_75_88, C_132_130_137, g * g);
        Noise.mix(col, light, dark, T);
        double sh = clamp(dRel * 7, -1, 1) * 14 * (1 - T * 0.4);
        col[0] += sh; col[1] += sh; col[2] += sh;
        // hidden sun: forward-scattering glow through the thinner cloud
        double dx = x - P.sunX, dyy = (y - P.sunY) * 1.2, ds = Math.sqrt(dx * dx + dyy * dyy);
        double glow = Math.exp(-Math.pow(ds / 40, 2)), halo = Math.exp(-Math.pow(ds / 120, 2));
        mixTo(col, 250, 236, 214, Math.min(1, glow * (1 - T * 0.7) * 0.95 + halo * 0.20 * (1 - T * 0.5)));
        if (P.approach > 0) { // rain approaching from the west: heavier base and soft distant shafts on the right
            double ap = sm(130, 250, x) * sm(0.35, 0.85, g) * P.approach;
            double f = 1 - 0.22 * ap;
            col[0] *= f; col[1] *= f; col[2] *= f;
            double shaft = sm(-0.05, 0.35, N.fbm(x / 7 + y / 40, 3.3, 3)) * sm(0.78, 0.95, g) * sm(150, 240, x);
            mixTo(col, 150, 154, 166, shaft * 0.35 * P.approach);
        }
        // horizon haze (warmer toward the sunset side)
        double hz = sm(0.80, 1.0, g), hw = sm(60, 260, x) * 0.85;
        mixTo(col, 186 + (222 - 186) * hw, 183 + (204 - 183) * hw, 189 + (190 - 189) * hw, hz * 0.8);
        vignette(x, y, col, 0.30);
    }

    /** sky9.js skyClear: blue gradient, horizon haze, soft sun bloom, fair-weather clouds thresholded by cl (0..1). */
    private double clearMask;
    private void skyClear(double x, double y, double[] col, double cl) {
        double g = Math.min(1, Math.max(0, y / P.HY));
        double gg = Math.pow(g, 1.5);
        col[0] = 44 + (146 - 44) * gg; col[1] = 92 + (182 - 92) * gg; col[2] = 170 + (220 - 170) * gg;
        mixTo(col, 212, 220, 228, sm(0.80, 1.0, g) * 0.85);              // pale horizon haze
        double dx = x - P.sunX, dyy = y - P.sunY, ds = Math.sqrt(dx * dx + dyy * dyy);
        mixTo(col, 255, 249, 232, Math.min(1, Math.exp(-Math.pow(ds / 9, 2)) * 1.0 + Math.exp(-Math.pow(ds / 34, 2)) * 0.35));
        mixTo(col, 255, 236, 206, Math.exp(-Math.pow(ds / 120, 2)) * 0.16);
        clearMask = 0;
        if (cl > 0) {
            double c = sm(0.98 - cl, 1.08 - cl, dT) * (1 - sm(0.9, 1.0, g) * 0.6);
            double lr = 252 + (236 - 252) * g, lg = 252 + (228 - 252) * g, lb = 255 + (222 - 255) * g;
            double br = 168 + (190 - 168) * g, bg = 180 + (194 - 180) * g, bb = 202 + (204 - 202) * g;
            double s = sm(-0.4, 0.7, -dRel * 5 + (dT - 0.85) * 2);
            mixTo(col, lr + (br - lr) * s, lg + (bg - lg) * s, lb + (bb - lb) * s, c * 0.92);
            clearMask = c;
        }
        vignette(x, y, col, 0.24);
    }

    private void vignette(double x, double y, double[] col, double k) {
        double vx = (x - P.vig[0]) / P.vig[2], vy = (y - P.vig[1]) / P.vig[3];
        double f = 1 - k * Math.exp(-(vx * vx + vy * vy) * 1.3);
        col[0] *= f; col[1] *= f; col[2] *= f;
    }

    // ---- proposal B (design/daylight/sky10.js): luminous azure, pearl-grey noon overcast, compact text scrim
    /** Flat-topped superellipse over the text block, per channel (blue darkens least: a deeper sky, not murk). */
    private void scrim(double x, double y, double[] col, double k, double kr, double kg, double kb) {
        double vx = (x - 88) / 108, vy = (y - 60) / 64;
        double v = Math.exp(-(vx * vx * vx * vx + vy * vy * vy * vy) * 1.1) * k;
        col[0] *= 1 - v * kr; col[1] *= 1 - v * kg; col[2] *= 1 - v * kb;
    }

    private void skyClearDL(double x, double y, double[] col, double cl) {
        double g = Math.min(1, Math.max(0, y / P.HY));
        double gg = Math.pow(g, 1.25);
        col[0] = 34 + (150 - 34) * gg; col[1] = 112 + (204 - 112) * gg; col[2] = 222 + (246 - 222) * gg;
        mixTo(col, 226, 238, 248, sm(0.78, 1.0, g) * 0.72);
        double dx = x - P.sunX, dyy = y - P.sunY, ds = Math.sqrt(dx * dx + dyy * dyy);
        mixTo(col, 255, 250, 238, Math.min(1, Math.exp(-Math.pow(ds / 9, 2)) + Math.exp(-Math.pow(ds / 34, 2)) * 0.40));
        mixTo(col, 255, 244, 222, Math.exp(-Math.pow(ds / 140, 2)) * 0.22);
        clearMask = 0;
        if (cl > 0) {
            double c = sm(0.98 - cl, 1.08 - cl, dT) * (1 - sm(0.9, 1.0, g) * 0.6);
            double lr = 255 + (240 - 255) * g, lg = 255 + (234 - 255) * g, lb = 255 + (228 - 255) * g;
            double br = 182 + (196 - 182) * g, bg = 198 + (202 - 198) * g, bb = 224 + (212 - 224) * g;
            double sh = sm(-0.4, 0.7, -dRel * 5 + (dT - 0.85) * 2);
            mixTo(col, lr + (br - lr) * sh, lg + (bg - lg) * sh, lb + (bb - lb) * sh, c * 0.94);
            clearMask = c;
        }
        scrim(x, y, col, 0.36, 1.12, 1.0, 0.62);
    }

    private void skyOvercastDL(double x, double y, double[] col) {
        double g = dG, T = dT;
        double lr = 206 + (238 - 206) * g, lg = 212 + (240 - 212) * g, lb = 222 + (243 - 222) * g;
        double g2 = g * g;
        double dr = 152 + (198 - 152) * g2, dgg = 160 + (201 - 160) * g2, db = 177 + (208 - 177) * g2;
        col[0] = lr + (dr - lr) * T; col[1] = lg + (dgg - lg) * T; col[2] = lb + (db - lb) * T;
        double sh = clamp(dRel * 7, -1, 1) * 10 * (1 - T * 0.4);
        col[0] += sh; col[1] += sh; col[2] += sh;
        double dx = x - P.sunX, dyy = (y - P.sunY) * 1.2, ds = Math.sqrt(dx * dx + dyy * dyy);
        double glow = Math.exp(-Math.pow(ds / 44, 2)), halo = Math.exp(-Math.pow(ds / 140, 2));
        mixTo(col, 255, 252, 244, Math.min(1, (glow * (1 - T * 0.65) * 0.95 + halo * 0.24 * (1 - T * 0.5)) * 1.1));
        if (P.approach > 0) {
            double ap = sm(130, 250, x) * sm(0.35, 0.85, g) * P.approach;
            double f = 1 - 0.06 * ap;
            col[0] *= f; col[1] *= f; col[2] *= f;
            double shaft = sm(-0.05, 0.35, N.fbm(x / 7 + y / 40, 3.3, 3)) * sm(0.78, 0.95, g) * sm(150, 240, x);
            mixTo(col, 198 + (238 - 198) * 0.3, 201 + (240 - 201) * 0.3, 208 + (243 - 208) * 0.3, shaft * 0.30 * P.approach);
        }
        double hz = sm(0.80, 1.0, g), hw = sm(60, 260, x) * 0.85;
        mixTo(col, 212 + (232 - 212) * hw, 214 + (228 - 214) * hw, 220 + (222 - 220) * hw, hz * 0.8);
        scrim(x, y, col, 0.42, 1.06, 1.0, 0.84);
    }

    private final double[] colB = new double[3];

    /**
     * Day sky: continuous blend by cloud cover between skyClear (below ~30 % a few clouds, 30-80 % partly cloudy)
     * and the overcast deck (above ~80 %), then low-sun dimming, the sunrise/sunset glow, fog and live precipitation.
     */
    private void skyDay(double x, double y, double[] col) {
        double cov = P.cover, el = P.el, g = dG;
        double w = sm(0.65, 0.85, cov);
        double teff;
        if (w >= 1) {
            if (P.daylight) skyOvercastDL(x, y, col); else skyOvercast(x, y, col);
            teff = dT;
        } else {
            if (P.daylight) skyClearDL(x, y, col, Math.min(0.85, cov)); else skyClear(x, y, col, Math.min(0.85, cov));
            teff = clearMask;
            if (w > 0) {
                if (P.daylight) skyOvercastDL(x, y, colB); else skyOvercast(x, y, colB);
                Noise.mixTo(col, colB, w);
                teff = teff + (dT - teff) * w;
            }
        }
        // low sun: the scene dims, and toward civil dusk the upper sky deepens to blue (continuous with skyNight's blue hour)
        double lum = 1 - 0.18 * sm(10, 4, el);
        double dusk = sm(6, -4, el);
        if (dusk > 0) {
            double up = Math.pow(1 - g, 0.8);
            mixTo(col, 30, 42, 86, dusk * up * 0.7 * (1 - 0.4 * teff));
            lum *= 1 - 0.42 * dusk;
        }
        if (lum < 1) { col[0] *= lum; col[1] *= lum; col[2] *= lum; }
        // sunrise / sunset glow near the horizon, strongest on the sun's side
        double tw = Math.exp(-Math.pow((el - 1) / 4.2, 2));
        if (tw > 0.003) {
            double side = 0.45 + 0.55 * Math.exp(-Math.pow((x - P.sunX) / 150, 2));
            double hwt = sm(0.25, 1.0, g);
            double s = sm(0.55, 1.0, g);
            double wr = 252 + (240 - 252) * s, wg = 208 + (134 - 208) * s, wb = 162 + (98 - 162) * s;
            mixTo(col, wr, wg, wb, tw * hwt * side * (0.78 - 0.38 * teff));
            mixTo(col, 226, 148, 140, tw * teff * (1 - g * 0.4) * 0.22 * side); // cloud undersides catch the colour
        }
        storm(col, false);
        applyWeather(x, y, col, false);
    }

    private void skyNight(double x, double y, double[] col) {
        double g = dG, T = dT, cov = P.cover, el = P.el;
        // overcast night over a city: the cloud base glows with reflected street light; thin parts are darker
        double[] gap = tmp, lit = tmp2;
        Noise.mix(gap, C_14_16_24, C_44_38_44, g);
        Noise.mix(lit, C_40_38_47, C_118_86_72, g * g);
        Noise.mix(col, gap, lit, T * 0.85 + 0.15);
        double sh = clamp(dRel * 7, -1, 1) * 5 * g;
        col[0] += sh; col[1] += sh; col[2] += sh;
        double hz = sm(0.78, 1.0, g);
        mixTo(col, 150, 106, 82, hz * 0.7);
        double teff = T;
        if (cov < 0.999) {
            double m = cloudMask();
            double gg = Math.pow(g, 1.5);
            double cr = 8 + (34 - 8) * gg, cg = 11 + (32 - 11) * gg, cb = 24 + (50 - 24) * gg;
            double ug = sm(0.72, 1.0, g) * 0.75; // light dome over the town
            cr += (104 - cr) * ug; cg += (78 - cg) * ug; cb += (70 - cb) * ug;
            // stars where it is clear
            double hs = Noise.hash(Math.floor(x * 1.9), Math.floor(y * 1.9));
            if (hs > 0.982) {
                double bri = (hs - 0.982) / 0.018 * (1 - m) * (1 - sm(0.45, 0.85, g)) * sm(-5, -11, el);
                cr += (232 - cr) * bri; cg += (236 - cg) * bri; cb += (255 - cb) * bri;
            }
            double kr = 32 + (96 - 32) * g * g, kg = 30 + (72 - 30) * g * g, kb = 40 + (66 - 40) * g * g;
            double rr = clamp(dRel * 7, -1, 1) * 6 * g;
            kr += rr; kg += rr; kb += rr;
            double br = cr + (kr - cr) * m, bg = cg + (kg - cg) * m, bb = cb + (kb - cb) * m;
            double w = sm(0.80, 1.0, cov);
            col[0] = br + (col[0] - br) * w;
            col[1] = bg + (col[1] - bg) * w;
            col[2] = bb + (col[2] - bb) * w;
            teff = m + (T - m) * w;
        }
        // blue hour / late twilight (sun between -4 and about -12 deg)
        double tw = sm(-13, -4, el);
        if (tw > 0.003) {
            double s = Math.pow(sm(0.35, 1.0, g), 1.2);
            double side = 0.5 + 0.5 * Math.exp(-Math.pow((x - P.sunX) / 160, 2));
            double r = 44 + (196 - 44) * s * side, gg2 = 56 + (118 - 56) * s * side, b = 100 + (96 - 100) * s * side;
            mixTo(col, r, gg2, b, tw * (0.62 - 0.30 * teff));
        }
        storm(col, true);
        applyWeather(x, y, col, true);
        // floodlit landmark lifts the haze around it
        if (P.heroGlow > 0) {
            double mdx = (x - P.heroX) / 30, mdy = (y - (P.HY - 30)) / 60;
            mixTo(col, 168, 124, 92, Math.exp(-(mdx * mdx + mdy * mdy)) * 0.28 * P.heroGlow);
        }
        double vx = (x - P.vig[0]) / P.vig[2], vy = (y - P.vig[1]) / P.vig[3];
        double v = Math.exp(-(vx * vx + vy * vy) * 1.3);
        double f = 1 - 0.22 * v;
        col[0] *= f; col[1] *= f; col[2] *= f;
    }

    /** Heavier, darker cloud for rain and storms, and the state's colour cast. */
    private void storm(double[] col, boolean night) {
        if (P.storm > 0) {
            double g = dG;
            double d = P.storm * (0.45 + 0.35 * dT) * (1 - 0.4 * sm(0.75, 1, g)) * (night ? 0.6 : 1);
            double f = 1 - d;
            col[0] *= f; col[1] *= f; col[2] *= f;
        }
        if (P.tint != null) mixTo(col, P.tint[0] * (night ? 0.35 : 1), P.tint[1] * (night ? 0.35 : 1), P.tint[2] * (night ? 0.35 : 1), P.tint[3]);
    }

    /** Fog and the precipitation falling right now (not part of the dry sketch scenarios). */
    private void applyWeather(double x, double y, double[] col, boolean night) {
        double g = dG;
        if (P.fog > 0.01) {
            double fb = 0.75 + 0.25 * N.fbm(x / 30, y / 4, 3);
            double k = P.fog * (0.45 + 0.4 * g) * fb;
            if (night) mixTo(col, 70, 66, 70, k);
            else mixTo(col, 184, 186, 190, k);
        }
        if (P.precip > 0.02 && P.ptype != Wx.DRY) {
            double inten = Math.min(1, P.precip / 1.4);
            double f = 1 - 0.14 * inten;
            col[0] *= f; col[1] *= f; col[2] *= f;
            if (P.ptype != Wx.SNOW) {
                double r = N.noise((x + y * 0.32) * 1.3, y * 0.09);
                double r2 = N.noise((x + y * 0.32) * 2.9 + 50, y * 0.13);
                double streak = sm(0.22, 0.46, r) * 0.8 + sm(0.26, 0.46, r2) * 0.6;
                double k = streak * (P.ptype == Wx.SLEET ? 0.22 : 0.30) * (0.35 + 0.65 * inten);
                if (night) mixTo(col, 120, 130, 156, k);
                else mixTo(col, 205, 212, 226, k);
            }
            if (P.ptype != Wx.RAIN) {
                double h2 = Noise.hash(Math.floor(x * 1.6), Math.floor(y * 1.6));
                if (h2 > (P.ptype == Wx.SNOW ? 0.95 : 0.975)) mixTo(col, 236, 240, 250, 0.7);
            }
        }
    }

    /**
     * Renders the sky at reduced resolution, exactly like sky8.js paint(): pixel centres at (px + 0.5) / pxPerDp.
     * @return ARGB pixels (opaque)
     */
    public int[] render(int pw, int ph, double pxPerDp) {
        // pass 1: deck, to find the cover threshold (quantile of thickness over the visible sky)
        if (P.cover < 0.999) {
            int n = 0;
            double[] vals = new double[pw * ph];
            for (int py = 0; py < ph; py += 2) for (int px = 0; px < pw; px += 2) {
                double x = (px + 0.5) / pxPerDp, y = (py + 0.5) / pxPerDp;
                if (y > P.HY) continue;
                deck(x, y);
                vals[n++] = dTraw;
            }
            java.util.Arrays.sort(vals, 0, n);
            double c = Math.max(0, Math.min(1, P.cover));
            thr = n == 0 ? 0.5 : vals[(int) Math.round((1 - c) * (n - 1))];
        }
        int[] out = new int[pw * ph];
        double[] col = new double[3];
        for (int py = 0; py < ph; py++) for (int px = 0; px < pw; px++) {
            double x = (px + 0.5) / pxPerDp, y = (py + 0.5) / pxPerDp;
            deck(x, y);
            if (P.night) skyNight(x, y, col); else skyDay(x, y, col);
            out[py * pw + px] = Px.argb(col);
        }
        return out;
    }
}
