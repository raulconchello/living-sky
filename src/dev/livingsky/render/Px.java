package dev.livingsky.render;

import java.util.Random;

/** Pixel helpers mirroring the canvas pipeline in sky8.js paint(): clamp/round, high-quality upscale, film grain. */
public final class Px {
    private Px() {}

    private static int c8(double v) {
        if (v <= 0) return 0;
        if (v >= 255) return 255;
        return (int) Math.rint(v); // Uint8ClampedArray rounds half to even
    }

    public static int argb(double[] c) {
        return 0xff000000 | (c8(c[0]) << 16) | (c8(c[1]) << 8) | c8(c[2]);
    }

    private static double mitchell(double x) {
        final double B = 1.0 / 3, C = 1.0 / 3;
        x = Math.abs(x);
        if (x < 1) return ((12 - 9 * B - 6 * C) * x * x * x + (-18 + 12 * B + 6 * C) * x * x + (6 - 2 * B)) / 6;
        if (x < 2) return ((-B - 6 * C) * x * x * x + (6 * B + 30 * C) * x * x + (-12 * B - 48 * C) * x + (8 * B + 24 * C)) / 6;
        return 0;
    }

    /** Bicubic (Mitchell) resize of an opaque ARGB image, like canvas drawImage with imageSmoothingQuality 'high'. */
    public static int[] upscale(int[] src, int sw, int sh, int dw, int dh) {
        // separable: horizontal pass into float planes, then vertical
        float[] tr = new float[dw * sh], tg = new float[dw * sh], tb = new float[dw * sh];
        double sx = (double) sw / dw, sy = (double) sh / dh;
        int[] i0 = new int[dw]; double[][] wx = new double[dw][4];
        for (int x = 0; x < dw; x++) {
            double u = (x + 0.5) * sx - 0.5; int b = (int) Math.floor(u) - 1; i0[x] = b;
            double s = 0; for (int k = 0; k < 4; k++) { wx[x][k] = mitchell(u - (b + k)); s += wx[x][k]; }
            for (int k = 0; k < 4; k++) wx[x][k] /= s;
        }
        for (int y = 0; y < sh; y++) for (int x = 0; x < dw; x++) {
            double r = 0, g = 0, bl = 0;
            for (int k = 0; k < 4; k++) {
                int xx = Math.max(0, Math.min(sw - 1, i0[x] + k));
                int p = src[y * sw + xx]; double w = wx[x][k];
                r += w * ((p >> 16) & 255); g += w * ((p >> 8) & 255); bl += w * (p & 255);
            }
            tr[y * dw + x] = (float) r; tg[y * dw + x] = (float) g; tb[y * dw + x] = (float) bl;
        }
        int[] out = new int[dw * dh];
        double[] c = new double[3];
        for (int y = 0; y < dh; y++) {
            double v = (y + 0.5) * sy - 0.5; int b = (int) Math.floor(v) - 1;
            double[] wy = new double[4]; double s = 0;
            for (int k = 0; k < 4; k++) { wy[k] = mitchell(v - (b + k)); s += wy[k]; }
            for (int x = 0; x < dw; x++) {
                double r = 0, g = 0, bl = 0;
                for (int k = 0; k < 4; k++) {
                    int yy = Math.max(0, Math.min(sh - 1, b + k)); int i = yy * dw + x; double w = wy[k] / s;
                    r += w * tr[i]; g += w * tg[i]; bl += w * tb[i];
                }
                c[0] = r; c[1] = g; c[2] = bl;
                out[y * dw + x] = argb(c);
            }
        }
        return out;
    }

    /** Photographic grain at full resolution (sky8.js: n = (rand - .5) * grain; blue gets 1.05 n). */
    public static void grain(int[] px, double grain, long seed) {
        Random r = new Random(seed);
        double[] c = new double[3];
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            double n = (r.nextDouble() - 0.5) * grain;
            c[0] = ((p >> 16) & 255) + n; c[1] = ((p >> 8) & 255) + n; c[2] = (p & 255) + n * 1.05;
            px[i] = (p & 0xff000000) | (argb(c) & 0xffffff);
        }
    }
}
