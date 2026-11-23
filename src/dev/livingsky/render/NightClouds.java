package dev.livingsky.render;

import static dev.livingsky.render.Noise.sm;

/**
 * Broken cloud at night (partly cloudy, showers), painted per pixel like the column's band so the two read alike:
 * one soft fbm field seen in perspective (big overhead, small and flat toward the horizon) whose covered area follows
 * the cloud cover. Density varies inside each cloud, so thin parts are see-through (stars dim instead of vanishing).
 * Light: dark bodies, a faint warm city glow on low bases, silver rims on the moon side with a glow where the moon
 * sits behind thin cloud, and in late twilight warm undersides toward the set sun.
 *
 * Output: {@link #layer} (ARGB, not premultiplied, drawn SRC_OVER) and {@link #glow} (RGB, drawn with SCREEN), both
 * pw x ph covering x 0..pw/s, y 0..ph/s dp. Pure Java (no Android), so the desktop harness can render it too.
 */
public final class NightClouds {
    public double cover = 0.5;          // 0..1 (the sky shader's cover)
    public boolean showers, windy;
    public double HY = 134, focal = 73; // horizon (dp) and the deck's focal length, as in SkyShader
    public double moonX = Double.NaN, moonY, moonLight; // moon centre (dp) and its light 0..1 (phase, altitude)
    public double twilight, sunX = 250;  // 0..1 late twilight (sun -13 .. -4 deg) and the set sun's x (dp)

    public int[] layer, glow;
    private final Noise N;

    public NightClouds(long seed) { N = new Noise(seed); }

    private double field(double x, double y) {
        double dy = Math.max(HY - y, 0) + 7;
        double X = (x - 150) / dy * focal / 140, Z = focal / dy;
        double sx = windy ? 1.0 : 2.2, sz = windy ? 3.4 : 2.2;   // wind: long torn streets of cloud
        double wx = N.fbm(X * 1.1 + 5.2, Z * 2.0 + 1.3, 3), wz = N.fbm(X * 1.1 + 8.3, Z * 2.0 + 2.8, 3);
        double u = X * sx + 0.55 * wx, v = Z * sz + 0.55 * wz;
        return N.fbm(u, v, 4) + 0.22 * N.fbm(u * 3.1 + 3.7, v * 3.1 + 1.9, 3);
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
                if ((px & 1) == 0 && (py & 1) == 0 && y < HY) sample[n++] = v;
            }
        }
        // threshold from the cover: the covered share of the sky is about the cloud cover
        java.util.Arrays.sort(sample, 0, n);
        double cov = Math.max(0.05, Math.min(0.95, cover * (showers ? 1.0 : 0.92)));
        double e0 = q(sample, n, 1 - cov), e1 = q(sample, n, 1 - cov * (showers ? 0.62 : 0.5)); // edge .. dense core
        for (int py = 0; py < ph; py++) {
            double y = (py + 0.5) / s, g = Math.min(1, y / HY);
            for (int px = 0; px < pw; px++) {
                double x = (px + 0.5) / s;
                int i = py * pw + px;
                double d = sm(e0, e1, f[i]);
                if (d > 0) d *= 0.84 + 0.16 * sm(-0.25, 0.3, N.fbm(x / 4.5 + 7.1, y / 2.6 + 3.3, 2)); // uneven inside
                d *= 1 - 0.45 * sm(0.82, 1.0, g);                                                 // haze at the horizon
                dens[i] = d;
            }
        }
        layer = new int[pw * ph];
        glow = new int[pw * ph];
        int step = Math.max(1, (int) Math.round(3.6 * s)), down = Math.max(1, (int) Math.round(2.6 * s));
        boolean moon = !Double.isNaN(moonX) && moonLight > 0.01;
        for (int py = 0; py < ph; py++) {
            double y = (py + 0.5) / s, g = Math.min(1, y / HY);
            for (int px = 0; px < pw; px++) {
                int i = py * pw + px;
                double d = dens[i];
                if (d < 0.004 && !moon) continue;
                double x = (px + 0.5) / s;
                // body: thin cloud a little lighter (it scatters the sky glow), thick cloud dark; lower = warmer
                double cityG = Math.pow(g, 1.6);
                double r = 46 + (26 - 46) * d, gg = 48 + (27 - 48) * d, b = 62 + (37 - 62) * d;
                r += (84 - r) * cityG * 0.55; gg += (66 - gg) * cityG * 0.55; b += (62 - b) * cityG * 0.55;
                if (showers) { r *= 0.78; gg *= 0.8; b *= 0.86; }
                // bases: where the cloud ends downward, the town lights it from below (warm, faint)
                double under = edge(dens, pw, ph, px, py, 0, down) * (0.35 + 0.65 * cityG);
                r += (124 - r) * under * 0.55; gg += (92 - gg) * under * 0.55; b += (76 - b) * under * 0.55;
                double gr = 0, gG = 0, gb = 0;
                if (moon) {
                    double dx = moonX - x, dyy = moonY - y, dist = Math.sqrt(dx * dx + dyy * dyy) + 1e-6;
                    int ox = (int) Math.round(dx / dist * step), oy = (int) Math.round(dyy / dist * step);
                    double near = 0.18 + 0.82 * Math.exp(-dist / 70);
                    double rim = edge(dens, pw, ph, px, py, ox, oy) * near * moonLight * (1 - 0.45 * d); // thin edges glow most
                    double sil = moonLight * 0.2 * (1 - d) * near;                      // thin cloud: silvery sheen
                    double wl = Math.min(0.85, rim * 0.95 + sil);
                    r += (200 - r) * wl; gg += (206 - gg) * wl; b += (224 - b) * wl;
                    // the moon behind thin cloud: a bright aureole and a wide soft glow, strongest in thin cloud
                    double cl = Math.min(1, d * 2.2) * (1 - 0.6 * d);
                    double aur = Math.exp(-Math.pow(dist / 9.5, 2)) * 1.0 + Math.exp(-dist / 30) * 0.32;
                    double ga = moonLight * aur * cl;
                    gr = 214 * ga; gG = 218 * ga; gb = 232 * ga;
                }
                if (twilight > 0.003) { // late twilight: undersides and sun-side edges catch the afterglow
                    double side = Math.exp(-Math.pow((x - sunX) / 140, 2));
                    double tx = sunX - x, ty = HY + 30 - y, tl = Math.sqrt(tx * tx + ty * ty) + 1e-6;
                    double tr = edge(dens, pw, ph, px, py, (int) Math.round(tx / tl * step), (int) Math.round(ty / tl * step));
                    double t = twilight * (0.35 + 0.65 * side) * Math.min(1, tr * 1.2 + under * 0.6 + 0.25 * cityG);
                    r += (196 - r) * t * 0.7; gg += (124 - gg) * t * 0.7; b += (122 - b) * t * 0.7;
                }
                double a = Math.pow(d, 0.85) * (showers ? 0.97 : 0.93);
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
