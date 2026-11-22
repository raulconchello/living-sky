package dev.livingsky.render;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * SVG generators (widget dp coordinates, viewBox 0 0 379.4 192.4), ported from sky8.js:
 *  - {@link #ground}: the dark "near bank" band under the day strip (path nd with heroK, flat in front of the landmark);
 *  - {@link #town}: the legacy procedural town (hills + houses), seeded by the place name, no landmark. Used as the
 *    fallback skyline when neither a hand-drawn nor an automatic (OSM) skyline is available.
 */
public final class SvgGen {
    private SvgGen() {}

    public static final double W = 379.4, H = 192.4, HY = 134;

    private static String f(double v) { return String.format(Locale.US, "%.2f", v); }

    private static String head() {
        return "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 379.4 192.4\" width=\"379.4\" height=\"192.4\">";
    }

    private static String lg(String id, String[][] stops, boolean horizontal) {
        StringBuilder sb = new StringBuilder("<linearGradient id=\"" + id + "\" x1=\"0\" y1=\"0\" x2=\"" + (horizontal ? 1 : 0)
                + "\" y2=\"" + (horizontal ? 0 : 1) + "\">");
        for (String[] s : stops)
            sb.append("<stop offset=\"").append(s[0]).append("\" stop-color=\"").append(s[1]).append("\" stop-opacity=\"")
                    .append(s.length > 2 ? s[2] : "1").append("\"/>");
        return sb.append("</linearGradient>").toString();
    }

    /** The near bank: dark ground for the forecast rows, kept low in front of the hero landmark. */
    public static String ground(boolean night, Noise n) {
        StringBuilder d = new StringBuilder();
        d.append("M-2,").append(f(H + 2)).append("L-2,").append(f(HY + 15));
        for (int x = -2; x <= 382; x += 3) {
            double heroK = 1 - 0.85 * Noise.sm(154, 168, x) * (1 - Noise.sm(272, 282, x));
            double y = HY + 14 - heroK * (Math.max(0, n.fbm(x / 14.0, 7.7, 3)) * 11 + (n.fbm(x / 5.0, 2.2, 2) + 0.3) * 3);
            d.append('L').append(x).append(',').append(f(y));
        }
        d.append("L382,").append(f(H + 2)).append('Z');
        String grad = night
                ? lg("gNear", new String[][]{{"0", "#121218"}, {"0.25", "#0d0e13"}, {"1", "#09090d"}}, false)
                : lg("gNear", new String[][]{{"0", "#25272f"}, {"0.25", "#1b1d24"}, {"1", "#121318"}}, false);
        return head() + "<defs>" + grad + "</defs><path d=\"" + d + "\" fill=\"url(#gNear)\"/></svg>";
    }

    private static final class Houses { String d; List<double[]> wins = new ArrayList<>(); }

    private static Houses houses(double x0, double x1, double by, double hmin, double hmax, double wmin, double wmax, long sd) {
        final long[] s2 = {sd};
        java.util.function.DoubleSupplier r = () -> { s2[0] = (s2[0] * 16807L) % 2147483647L; return (s2[0] - 1) / 2147483646.0; };
        StringBuilder d = new StringBuilder();
        Houses h = new Houses();
        d.append('M').append(f(x0)).append(',').append(f(by + 30)).append('L').append(f(x0)).append(',').append(f(by));
        double x = x0;
        while (x < x1) {
            double w = wmin + r.getAsDouble() * (wmax - wmin), eave = hmin + r.getAsDouble() * (hmax - hmin), roof = w * (0.55 + r.getAsDouble() * 0.45);
            double kind = r.getAsDouble();
            if (kind < 0.16) { // tree
                double th = eave + roof * 0.6;
                for (int k = 0; k <= 6; k++) {
                    double a = Math.PI * (1 - k / 6.0);
                    double px = x + w / 2 + Math.cos(a) * w / 2 * (0.85 + r.getAsDouble() * 0.3);
                    double py = by - th * 0.55 - Math.sin(a) * th * 0.5 * (0.8 + r.getAsDouble() * 0.4);
                    d.append('L').append(f(px)).append(',').append(f(py));
                }
            } else if (kind < 0.22) { // flat-roofed block
                d.append('L').append(f(x)).append(',').append(f(by - eave * 1.1)).append('L').append(f(x + w)).append(',').append(f(by - eave * 1.1));
                for (int k = 0; k < 3; k++) h.wins.add(new double[]{x + 2 + r.getAsDouble() * (w - 4), by - eave * 1.1 + 3 + r.getAsDouble() * (eave - 5)});
            } else if (kind < 0.62) { // eave-on house: long ridge, hipped or straight ends
                double rh = roof * 0.55, inset = Math.min(w * 0.3, rh * 0.7);
                d.append('L').append(f(x)).append(',').append(f(by - eave)).append('L').append(f(x + inset)).append(',').append(f(by - eave - rh));
                if (r.getAsDouble() < 0.4) {
                    double cxh = x + inset + r.getAsDouble() * (w - 2 * inset - 1.6);
                    d.append('L').append(f(cxh)).append(',').append(f(by - eave - rh))
                     .append('L').append(f(cxh)).append(',').append(f(by - eave - rh - 2.2))
                     .append('L').append(f(cxh + 1.4)).append(',').append(f(by - eave - rh - 2.2))
                     .append('L').append(f(cxh + 1.4)).append(',').append(f(by - eave - rh));
                }
                d.append('L').append(f(x + w - inset)).append(',').append(f(by - eave - rh)).append('L').append(f(x + w)).append(',').append(f(by - eave));
                for (int k = 0; k < 3; k++) h.wins.add(new double[]{x + 2 + r.getAsDouble() * (w - 4), by - eave + 2.5 + r.getAsDouble() * (eave - 5)});
            } else { // gable-on, steep
                d.append('L').append(f(x)).append(',').append(f(by - eave)).append('L').append(f(x + w / 2)).append(',').append(f(by - eave - roof * 0.85))
                 .append('L').append(f(x + w)).append(',').append(f(by - eave));
                for (int k = 0; k < 2; k++) h.wins.add(new double[]{x + 2 + r.getAsDouble() * (w - 4), by - eave + 2.5 + r.getAsDouble() * (eave - 5)});
            }
            x += w;
        }
        d.append('L').append(f(x)).append(',').append(f(by)).append('L').append(f(x)).append(',').append(f(by + 30)).append('Z');
        h.d = d.toString();
        return h;
    }

    /** Stable positive 31-bit seed from a place name. */
    public static long seedOf(String name) {
        long h = 1125899906842597L;
        String s = name == null ? "" : name.toLowerCase(Locale.ROOT);
        for (int i = 0; i < s.length(); i++) h = 31 * h + s.charAt(i);
        long v = Math.abs(h % 2147483646L);
        return v == 0 ? 4242 : v;
    }

    /** Generic procedural town (legacy sky8.js hills + mid houses + front houses), no landmark. */
    public static String town(String placeName, boolean night) {
        long seed = seedOf(placeName);
        Noise n = new Noise(seed);
        StringBuilder sb = new StringBuilder(head());
        sb.append("<!-- procedural town for ").append(placeName == null ? "?" : placeName.replace("--", "")).append(" -->");
        sb.append("<defs>");
        if (!night) {
            sb.append(lg("gFar", new String[][]{{"0", "#8e8c97"}, {"1", "#9c97a0"}}, false));
            sb.append(lg("gMid", new String[][]{{"0", "#4d505c"}, {"1", "#5a5a64"}}, false));
        } else {
            sb.append(lg("gFar", new String[][]{{"0", "#3a2f33"}, {"1", "#43363a"}}, false));
            sb.append(lg("gMid", new String[][]{{"0", "#1d1b22"}, {"1", "#221f25"}}, false));
        }
        sb.append("</defs>");
        StringBuilder hd = new StringBuilder("M0," + f(HY + 20) + "L0," + f(HY - 2));
        for (int x = 0; x <= 380; x += 6)
            hd.append('L').append(x).append(',').append(f(HY - 2 - 3 * Math.sin(x / 47.0) - 1.8 * Math.sin(x / 13.0 + 1) - 6 * n.fbm(x / 20.0, 3, 2)));
        hd.append("L380,").append(f(HY + 20)).append('Z');
        sb.append("<path d=\"").append(hd).append("\" fill=\"url(#gFar)\" opacity=\"").append(night ? "0.9" : "0.75").append("\"/>");
        Houses mid = houses(-4, 384, HY + 6, 3, 9, 5, 13, 1 + (seed % 2147483000L));
        sb.append("<path d=\"").append(mid.d).append("\" fill=\"url(#gMid)\"/>");
        if (night) for (double[] w : mid.wins) if (Noise.hash(w[0], w[1]) < 0.42)
            sb.append("<rect x=\"").append(f(w[0])).append("\" y=\"").append(f(w[1])).append("\" width=\"1.4\" height=\"1.8\" fill=\"#ffcf86\" opacity=\"")
              .append(f(0.55 + 0.4 * Noise.hash(w[1], w[0]))).append("\"/>");
        Houses front = houses(-6, 390, HY + 11, 2.5, 7, 6, 15, 1 + ((seed * 7 + 777) % 2147483000L));
        sb.append("<path d=\"").append(front.d).append("\" fill=\"").append(night ? "#17161c" : "#3a3c46").append("\"/>");
        if (night) for (double[] w : front.wins) if (Noise.hash(w[0] + 3, w[1]) < 0.38)
            sb.append("<rect x=\"").append(f(w[0])).append("\" y=\"").append(f(w[1])).append("\" width=\"1.5\" height=\"1.9\" fill=\"#ffc978\" opacity=\"")
              .append(f(0.5 + 0.45 * Noise.hash(w[1], w[0] + 1))).append("\"/>");
        sb.append("</svg>");
        return sb.toString();
    }
}
