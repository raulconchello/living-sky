package dev.livingsky.skyline;

/** Small deterministic PRNG (splitmix64) plus hash and 1-D value noise helpers. */
final class Rng {
    private long s;

    Rng(long seed) { s = seed * 0x9E3779B97F4A7C15L + 0x632BE59BD9B4E019L; }

    double next() {
        s += 0x9E3779B97F4A7C15L;
        long z = s;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z ^= (z >>> 31);
        return (z >>> 11) * 0x1.0p-53;
    }

    double range(double a, double b) { return a + (b - a) * next(); }

    int nextInt(int n) { return (int) Math.floor(next() * n); }

    boolean chance(double p) { return next() < p; }

    /** Stable 64-bit hash of a few values. */
    static long hash(long... v) {
        long h = 0x84222325CBF29CE4L;
        for (long x : v) {
            h ^= x + 0x9E3779B97F4A7C15L + (h << 6) + (h >>> 2);
            h *= 0x100000001B3L;
        }
        h ^= h >>> 29; h *= 0xBF58476D1CE4E5B9L; h ^= h >>> 32;
        return h;
    }

    static long hashString(String s) {
        long h = 1125899906842597L;
        for (int i = 0; i < s.length(); i++) h = 31 * h + s.charAt(i);
        return hash(h);
    }

    /** Hash of two doubles to [0,1), like sky8.js hash(). */
    static double hash01(double x, double y) {
        double h = StrictMath.sin(x * 127.1 + y * 311.7) * 43758.5453;   // StrictMath: same result on every device
        return h - Math.floor(h);
    }

    /** Smooth 1-D value noise in [-1,1]. */
    static double noise(double x, long seed) {
        double fl = Math.floor(x);
        double t = x - fl;
        long i = (long) fl;
        double a = lattice(i, seed), b = lattice(i + 1, seed);
        double u = t * t * (3 - 2 * t);
        return a + (b - a) * u;
    }

    private static double lattice(long i, long seed) {
        long h = hash(i, seed);
        return ((h >>> 11) * 0x1.0p-53) * 2 - 1;
    }

    /** Fractal 1-D noise, roughly in [-1,1]. */
    static double fbm(double x, long seed, int oct) {
        double sum = 0, amp = 0.5, f = 1, norm = 0;
        for (int o = 0; o < oct; o++) {
            sum += amp * noise(x * f, seed + o * 1013);
            norm += amp; amp *= 0.5; f *= 2.03;
        }
        return sum / norm;
    }

    static double clamp(double v, double a, double b) { return v < a ? a : (v > b ? b : v); }

    static double smooth(double e0, double e1, double x) {
        double t = clamp((x - e0) / (e1 - e0), 0, 1);
        return t * t * (3 - 2 * t);
    }

    static double lerp(double a, double b, double t) { return a + (b - a) * t; }
}
