package dev.livingsky.render;

import static dev.livingsky.render.Noise.sm;

/**
 * The card's broken cloud (fair-weather, partly cloudy, showers) at every hour, painted per pixel like the column's
 * band so the two read alike: one soft fbm field seen in perspective (big overhead, small and flat toward the horizon)
 * whose covered area follows the cloud cover. Density varies inside each cloud, so thin parts are see-through (blue
 * sky by day, dimmed stars at night). The same clouds are lit continuously by the sun's elevation:
 *   day       sunlit tops and edges on the sun's side, soft grey-blue shaded bases (light through the cloud toward
 *             the sun), a silver lining where the sun sits behind thin cloud; golden, then pink as the sun gets low;
 *   twilight  dark bodies against the bright sky, undersides and sun-side edges catching the afterglow;
 *   night     dark bodies, a faint warm city glow on the bases, silver rims and a glow toward the moon.
 * By day big clouds keep out from behind the clock and temperature (as the old cumulus did); the rule fades out at
 * dusk, so the field itself never changes with the hour.
 *
 * Output: {@link #layer} (ARGB, not premultiplied, drawn SRC_OVER) and {@link #glow} (RGB, drawn with SCREEN), both
 * pw x ph covering x 0..pw/s, y 0..ph/s dp. Pure Java (no Android), so the desktop harness can render it too.
 */
public final class CloudLayer {
    public double cover = 0.5;          // 0..1 (the sky shader's cover)
    public boolean showers, windy;
    public double HY = 134, focal = 73; // horizon (dp) and the deck's focal length, as in SkyShader
    public double el = 30;              // sun elevation (deg)
    public double sunX = 200, sunY = 50;  // the sun on the card (dp; below the horizon after sunset)
    public double moonX = Double.NaN, moonY, moonLight; // moon centre (dp) and its light 0..1 (phase, altitude)

    public int[] layer, glow;
    private final Noise N;

    public CloudLayer(long seed) { N = new Noise(seed); }

    private double field(double x, double y) {
        double dy = Math.max(HY - y, 0) + 7;
        double X = (x - 150) / dy * focal / 140, Z = focal / dy;
        double sx = windy ? 2.0 : 4.6, sz = windy ? 5.6 : 3.6;   // ~50 dp cumulus overhead, as in the column; wind: streets
        double wx = N.fbm(X * 1.1 + 5.2, Z * 2.0 + 1.3, 3), wz = N.fbm(X * 1.1 + 8.3, Z * 2.0 + 2.8, 3);
        double u = X * sx + 0.55 * wx, v = Z * sz + 0.55 * wz;
        return N.fbm(u, v, 4) + 0.22 * N.fbm(u * 3.1 + 3.7, v * 3.1 + 1.9, 3);
    }

    /** 1 right behind the location line, the clock digits and the temperature lines (soft edges), 0 elsewhere. */
    private static double textZone(double x, double y) {
        double loc = (1 - sm(200, 222, x)) * sm(2, 8, y) * (1 - sm(22, 30, y));
        double clock = (1 - sm(150, 168, x)) * sm(18, 26, y) * (1 - sm(74, 82, y));
        double temp = (1 - sm(160, 176, x)) * sm(76, 84, y) * (1 - sm(110, 118, y));
        return Math.max(loc, Math.max(clock, temp));
    }

    public void render(int pw, int ph, double s) {
        double[] f = new double[pw * ph];
        double[] dens = new double[pw * ph];
        int n = 0;
        double[] sample = new double[(pw / 2 + 1) * (ph / 2 + 1)];
        for (int py = 0; py < ph; py++) {
            double y = (py + 0.5) / s;
            for (int px = 0; px < pw; px++) {
                double x = (px + 0.5) / s;
                double v = y < HY + 2 ? field(x, y) : -9;
                f[py * pw + px] = v;
                if ((px & 1) == 0 && (py & 1) == 0 && y < HY && textZone(x, y) < 0.5) sample[n++] = v;
            }
        }
        // threshold from the cover: the covered share of the open sky is about the cloud cover
        java.util.Arrays.sort(sample, 0, n);
        double cov = Math.max(0.03, Math.min(0.95, cover));
        double dayW = sm(-4, 5, el);                 // sunlit white, fading through dusk
        // the covered share of the sky is the cloud cover (as in the column); by day most of it is solid cumulus with
        // crisp edges, at night soft, thin edges dominate (the field is the same, so dusk has no seam)
        double core = (showers ? 0.62 : 0.5) + (showers ? 0.2 : 0.3) * dayW;
        double e0 = q(sample, n, 1 - cov), e1 = q(sample, n, 1 - cov * core);   // edge .. dense core
        double avoid = 0.42 * sm(-6, 2, el);         // by day, thinner cloud right behind the text
        for (int py = 0; py < ph; py++) {
            double y = (py + 0.5) / s, g = Math.min(1, y / HY);
            for (int px = 0; px < pw; px++) {
                double x = (px + 0.5) / s;
                int i = py * pw + px;
                double d = sm(e0, e1, f[i]);
                if (d > 0) d *= 0.84 + 0.16 * sm(-0.25, 0.3, N.fbm(x / 4.5 + 7.1, y / 2.6 + 3.3, 2)); // uneven inside
                d *= 1 - 0.45 * sm(0.82, 1.0, g);                                                 // haze at the horizon
                if (avoid > 0) d *= 1 - avoid * textZone(x, y);
                dens[i] = d;
            }
        }
        layer = new int[pw * ph];
        glow = new int[pw * ph];
        int step = Math.max(1, (int) Math.round(3.6 * s)), down = Math.max(1, (int) Math.round(2.6 * s));
        boolean moon = !Double.isNaN(moonX) && moonLight > 0.01;
        boolean sunUp = el > -1 && sunY < HY + 4;
        double nightW = 1 - dayW;
        // low sun: golden, then pink near the horizon; the afterglow on undersides from sunset into dusk
        double warm = sm(14, 0, el) * sm(-10, -1, el);
        double wr = 255 + (246 - 255) * sm(6, -1, el), wg = 212 + (166 - 212) * sm(6, -1, el), wb = 166 + (150 - 166) * sm(6, -1, el);
        double after = sm(-12, -3, el) * sm(6, -1, el);
        double amb = sm(-12, -1, el) * nightW;       // twilight sky dome lighting the night bodies
        double[] st = {2.6, 5.6, 9.5, 14.5};
        for (int py = 0; py < ph; py++) {
            double y = (py + 0.5) / s, g = Math.min(1, y / HY);
            for (int px = 0; px < pw; px++) {
                int i = py * pw + px;
                double d = dens[i];
                if (d < 0.004 && !moon && !sunUp) continue;
                double x = (px + 0.5) / s;
                double cityG = Math.pow(g, 1.6);
                double under = edge(dens, pw, ph, px, py, 0, down);
                // ---- night body: thin cloud a little lighter (it scatters the sky glow), thick cloud dark; lower = warmer
                double nr = 46 + (26 - 46) * d, ng = 48 + (27 - 48) * d, nb = 62 + (37 - 62) * d;
                nr += (84 - nr) * cityG * 0.55; ng += (66 - ng) * cityG * 0.55; nb += (62 - nb) * cityG * 0.55;
                if (showers) { nr *= 0.78; ng *= 0.8; nb *= 0.86; }
                double ub = under * (0.35 + 0.65 * cityG);           // the town lights the bases from below
                nr += (124 - nr) * ub * 0.55; ng += (92 - ng) * ub * 0.55; nb += (76 - nb) * ub * 0.55;
                if (amb > 0.003) { double a2 = amb * 0.55 * (1 - 0.45 * d); nr += (104 - nr) * a2; ng += (108 - ng) * a2; nb += (134 - nb) * a2; }
                double gr = 0, gG = 0, gb = 0;
                if (moon) {
                    double dx = moonX - x, dyy = moonY - y, dist = Math.sqrt(dx * dx + dyy * dyy) + 1e-6;
                    int ox = (int) Math.round(dx / dist * step), oy = (int) Math.round(dyy / dist * step);
                    double near = 0.18 + 0.82 * Math.exp(-dist / 70);
                    double rim = edge(dens, pw, ph, px, py, ox, oy) * near * moonLight * (1 - 0.45 * d); // thin edges glow most
                    double sil = moonLight * 0.2 * (1 - d) * near;                      // thin cloud: silvery sheen
                    double wl = Math.min(0.85, rim * 0.95 + sil) * nightW;
                    nr += (200 - nr) * wl; ng += (206 - ng) * wl; nb += (224 - nb) * wl;
                    // the moon behind thin cloud: a bright aureole and a wide soft glow, strongest in thin cloud
                    double cl = Math.min(1, d * 2.2) * (1 - 0.6 * d);
                    double aur = Math.exp(-Math.pow(dist / 9.5, 2)) * 1.0 + Math.exp(-dist / 30) * 0.32;
                    double ga = moonLight * aur * cl * nightW;
                    gr += 214 * ga; gG += 218 * ga; gb += 232 * ga;
                }
                double r = nr, gg = ng, b = nb;
                double tx = sunX - x, ty = sunY - y, tl = Math.sqrt(tx * tx + ty * ty) + 1e-6;
                if (dayW > 0.003 && d > 0.004) {
                    // ---- day: sunlit cumulus. Light reaching a point through the cloud toward the sun (Beer-Lambert
                    // over a few samples), plus bright tops and flat, shaded bases
                    double tau = d * 0.25;
                    for (int si = 0; si < st.length; si++) {
                        double sd = st[si];
                        int qx = px + (int) Math.round(tx / tl * sd * s), qy = py + (int) Math.round(ty / tl * sd * s);
                        if (qx >= 0 && qx < pw && qy >= 0 && qy < ph) tau += dens[qy * pw + qx] * (0.3 - 0.05 * si);
                    }
                    double top = edge(dens, pw, ph, px, py, 0, -down);
                    double light = Math.max(0, Math.min(1, Math.exp(-1.0 * tau) + 0.4 * top - 0.4 * under));
                    double Lr = showers ? 200 : 247, Lg = showers ? 203 : 249, Lb = showers ? 210 : 252;
                    double Sr = showers ? 96 : 164, Sg = showers ? 100 : 175, Sb = showers ? 110 : 198;   // as the column's SHD
                    if (warm > 0.003) {
                        Lr += (wr - Lr) * warm * 0.8; Lg += (wg - Lg) * warm * 0.8; Lb += (wb - Lb) * warm * 0.8;
                        Sr += (118 - Sr) * warm * 0.55; Sg += (106 - Sg) * warm * 0.55; Sb += (138 - Sb) * warm * 0.55;
                    }
                    double dr = Sr + (Lr - Sr) * light, dg = Sg + (Lg - Sg) * light, db = Sb + (Lb - Sb) * light;
                    // toward the horizon, distant cloud melts into the haze
                    double hz = sm(0.62, 1.0, g) * 0.45;
                    dr += (214 - dr) * hz; dg += (222 - dg) * hz; db += (232 - db) * hz;
                    // highlights capped like the column (HI_CAP), a little lower right behind the text
                    double cap = 232 - 30 * textZone(x, y);
                    double ly = 0.2126 * dr + 0.7152 * dg + 0.0722 * db;
                    if (ly > cap) { double fcap = cap / ly; dr *= fcap; dg *= fcap; db *= fcap; }
                    r = nr + (dr - nr) * dayW; gg = ng + (dg - ng) * dayW; b = nb + (db - nb) * dayW;
                }
                if (after > 0.003 && d > 0.004) { // sunset into dusk: undersides and sun-side edges catch the afterglow
                    double side = Math.exp(-Math.pow((x - sunX) / 150, 2));
                    double tr = edge(dens, pw, ph, px, py, (int) Math.round(tx / tl * step), (int) Math.round(ty / tl * step));
                    double t = after * (0.35 + 0.65 * side) * Math.min(1, tr * 1.2 + under * 0.6 + 0.25 * cityG);
                    double ar = 226 + (196 - 226) * nightW, ag = 146 + (124 - 146) * nightW, ab = 132 + (122 - 132) * nightW;
                    r += (ar - r) * t * 0.7; gg += (ag - gg) * t * 0.7; b += (ab - b) * t * 0.7;
                }
                if (sunUp && dayW > 0.003) { // the sun behind thin cloud: a silver lining and a soft bright aureole
                    double cl = Math.min(1, d * 2.2) * (1 - 0.55 * d);
                    double aur = Math.exp(-Math.pow(tl / 13, 2)) + Math.exp(-tl / 38) * 0.3;
                    double ga = aur * cl * dayW * (showers ? 0.3 : 0.9);   // showers: a dull, wet sun
                    gr += 255 * ga; gG += 248 * ga; gb += (232 - 30 * warm) * ga;
                }
                double an = Math.pow(d, 0.85) * (showers ? 0.97 : 0.93), ad = Math.pow(d, 0.7) * (showers ? 0.98 : 0.95);
                double a = an + (ad - an) * dayW;
                if (a > 0.002) layer[i] = argb(a, r, gg, b);
                if (gr + gG + gb > 1.5) glow[i] = argb(1, gr, gG, gb);
            }
        }
    }

    private static double q(double[] v, int n, double at) { return v[Math.max(0, Math.min(n - 1, (int) (at * n)))]; }

    /** 0..1: how much the density drops from this pixel toward (ox, oy) px: the cloud's edge facing that way. */
    private static double edge(double[] dens, int pw, int ph, int px, int py, int ox, int oy) {
        double d = dens[py * pw + px];
        if (d <= 0) return 0;
        int qx = Math.max(0, Math.min(pw - 1, px + ox)), qy = Math.max(0, Math.min(ph - 1, py + oy));
        return sm(0.02, 0.3, d - dens[qy * pw + qx]);
    }

    private static int argb(double a, double r, double g, double b) {
        return ((int) Math.round(Math.max(0, Math.min(1, a)) * 255) << 24) | (c(r) << 16) | (c(g) << 8) | c(b);
    }

    private static int c(double v) { return (int) Math.max(0, Math.min(255, Math.round(v))); }
}
