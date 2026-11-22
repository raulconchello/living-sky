package dev.livingsky.render;

/**
 * Exact port of the noise helpers in the approved renderer (design/04-living-sky/sky8.js):
 * Park-Miller seeded permutation, 2D gradient (Perlin) noise, fbm, hash, smoothstep, mix, ramp.
 * Pure Java (no Android dependencies) so it can be unit-rendered on the desktop.
 */
public final class Noise {
    private final int[] P = new int[512];
    private final double[] GX = new double[256], GY = new double[256];
    private long seed;

    public Noise(long seed) {
        this.seed = seed;
        int[] p = new int[256];
        for (int i = 0; i < 256; i++) p[i] = i;
        for (int i = 255; i > 0; i--) {
            int j = (int) Math.floor(rnd() * (i + 1));
            int tmp = p[i]; p[i] = p[j]; p[j] = tmp;
        }
        for (int i = 0; i < 512; i++) P[i] = p[i & 255];
        for (int i = 0; i < 256; i++) {
            double a = rnd() * Math.PI * 2;
            GX[i] = (float) Math.cos(a); // JS stores these in a Float32Array
            GY[i] = (float) Math.sin(a);
        }
    }

    private double rnd() {
        seed = (seed * 16807L) % 2147483647L;
        return (seed - 1) / 2147483646.0;
    }

    public double noise(double x, double y) {
        double fx = Math.floor(x), fy = Math.floor(y);
        int xi = (int) fx, yi = (int) fy;
        double xf = x - fx, yf = y - fy;
        int X = xi & 255, Y = yi & 255;
        int a = P[P[X] + Y], b = P[P[X + 1] + Y], c = P[P[X] + Y + 1], d = P[P[X + 1] + Y + 1];
        double n00 = GX[a] * xf + GY[a] * yf, n10 = GX[b] * (xf - 1) + GY[b] * yf;
        double n01 = GX[c] * xf + GY[c] * (yf - 1), n11 = GX[d] * (xf - 1) + GY[d] * (yf - 1);
        double u = xf * xf * xf * (xf * (xf * 6 - 15) + 10), v = yf * yf * yf * (yf * (yf * 6 - 15) + 10);
        double nx0 = n00 + (n10 - n00) * u, nx1 = n01 + (n11 - n01) * u;
        return nx0 + (nx1 - nx0) * v;
    }

    public double fbm(double x, double y, int o) {
        double s = 0, a = 0.5, f = 1;
        for (int i = 0; i < o; i++) {
            s += a * noise(x * f + i * 17.31, y * f + i * 9.13);
            f *= 2.03; a *= 0.5;
        }
        return s;
    }

    public double billow(double x, double z, int o) {
        double b = 0, a = 0.5, f = 1;
        for (int i = 0; i < o; i++) {
            b += a * (1 - 2.6 * Math.abs(noise(x * f + i * 7.1, z * f + i * 3.3)));
            f *= 2.07; a *= 0.5;
        }
        return b;
    }

    public static double hash(double x, double y) {
        double h = StrictMath.sin(x * 127.1 + y * 311.7) * 43758.5453; // fdlibm, like V8
        return h - Math.floor(h);
    }

    public static double sm(double a, double b, double x) {
        double t = Math.max(0, Math.min(1, (x - a) / (b - a)));
        return t * t * (3 - 2 * t);
    }

    public static double clamp(double v, double lo, double hi) { return v < lo ? lo : v > hi ? hi : v; }

    public static void mix(double[] out, double[] a, double[] b, double t) {
        out[0] = a[0] + (b[0] - a[0]) * t;
        out[1] = a[1] + (b[1] - a[1]) * t;
        out[2] = a[2] + (b[2] - a[2]) * t;
    }

    /** In place: c = mix(c, b, t). */
    public static void mixTo(double[] c, double[] b, double t) {
        c[0] += (b[0] - c[0]) * t;
        c[1] += (b[1] - c[1]) * t;
        c[2] += (b[2] - c[2]) * t;
    }

    public static void mixTo(double[] c, double r, double g, double b, double t) {
        c[0] += (r - c[0]) * t;
        c[1] += (g - c[1]) * t;
        c[2] += (b - c[2]) * t;
    }

    /** stops: {x, r, g, b} ascending in x; smoothstep blend between stops (same as sky8.js ramp). */
    public static void ramp(double[] out, double[][] stops, double x) {
        if (x <= stops[0][0]) { set(out, stops[0]); return; }
        for (int i = 1; i < stops.length; i++) {
            if (x <= stops[i][0]) {
                double f = (x - stops[i - 1][0]) / (stops[i][0] - stops[i - 1][0]);
                f = f * f * (3 - 2 * f);
                double[] a = stops[i - 1], b = stops[i];
                out[0] = a[1] + (b[1] - a[1]) * f;
                out[1] = a[2] + (b[2] - a[2]) * f;
                out[2] = a[3] + (b[3] - a[3]) * f;
                return;
            }
        }
        set(out, stops[stops.length - 1]);
    }

    private static void set(double[] out, double[] s) { out[0] = s[1]; out[1] = s[2]; out[2] = s[3]; }
}
