package dev.livingsky.skyline;

/** Tiny SVG writer restricted to the AndroidSVG-safe subset in SPEC.md (no CSS, filters, masks, use, text). */
final class Svg {
    static final String VIEWBOX = "0 0 379.4 192.4";
    final StringBuilder defs = new StringBuilder(4096);
    final StringBuilder body = new StringBuilder(65536);
    private int gid;

    /** Format a coordinate with at most 2 decimals, no trailing zeros. */
    static String f(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) v = 0;
        long r = Math.round(v * 100);
        boolean neg = r < 0;
        if (neg) r = -r;
        long ip = r / 100, fp = r % 100;
        StringBuilder b = new StringBuilder(10);
        if (neg && r != 0) b.append('-');
        b.append(ip);
        if (fp != 0) {
            b.append('.');
            if (fp < 10) b.append('0').append(fp);
            else if (fp % 10 == 0) b.append(fp / 10);
            else b.append(fp);
        }
        return b.toString();
    }

    /** Linear gradient; stops are {offset, "#rrggbb", opacity}. Returns the fill reference. */
    String linear(String prefix, double x1, double y1, double x2, double y2, Object[][] stops) {
        String id = prefix + (gid++);
        defs.append("<linearGradient id=\"").append(id).append("\" x1=\"").append(f(x1)).append("\" y1=\"").append(f(y1))
                .append("\" x2=\"").append(f(x2)).append("\" y2=\"").append(f(y2)).append("\">");
        for (Object[] s : stops) {
            defs.append("<stop offset=\"").append(f(((Number) s[0]).doubleValue())).append("\" stop-color=\"").append(s[1]).append('"');
            double op = s.length > 2 ? ((Number) s[2]).doubleValue() : 1;
            if (op < 1) defs.append(" stop-opacity=\"").append(f(op)).append('"');
            defs.append("/>");
        }
        defs.append("</linearGradient>");
        return "url(#" + id + ")";
    }

    String radial(String prefix, Object[][] stops) {
        String id = prefix + (gid++);
        defs.append("<radialGradient id=\"").append(id).append("\">");
        for (Object[] s : stops) {
            defs.append("<stop offset=\"").append(f(((Number) s[0]).doubleValue())).append("\" stop-color=\"").append(s[1]).append('"');
            double op = s.length > 2 ? ((Number) s[2]).doubleValue() : 1;
            if (op < 1) defs.append(" stop-opacity=\"").append(f(op)).append('"');
            defs.append("/>");
        }
        defs.append("</radialGradient>");
        return "url(#" + id + ")";
    }

    void path(CharSequence d, String fill, double opacity) {
        if (d.length() == 0) return;
        body.append("<path d=\"").append(d).append("\" fill=\"").append(fill).append('"');
        if (opacity < 0.999) body.append(" opacity=\"").append(f(opacity)).append('"');
        body.append("/>\n");
    }

    void rect(double x, double y, double w, double h, String fill, double opacity) {
        body.append("<rect x=\"").append(f(x)).append("\" y=\"").append(f(y)).append("\" width=\"").append(f(w))
                .append("\" height=\"").append(f(h)).append("\" fill=\"").append(fill).append('"');
        if (opacity < 0.999) body.append(" opacity=\"").append(f(opacity)).append('"');
        body.append("/>\n");
    }

    void circle(double cx, double cy, double r, String fill, double opacity) {
        body.append("<circle cx=\"").append(f(cx)).append("\" cy=\"").append(f(cy)).append("\" r=\"").append(f(r))
                .append("\" fill=\"").append(fill).append('"');
        if (opacity < 0.999) body.append(" opacity=\"").append(f(opacity)).append('"');
        body.append("/>\n");
    }

    void raw(String s) { body.append(s); }

    /** Append a closed polygon (screen coordinates) to a path, normalising winding so union fills never cancel. */
    static void poly(StringBuilder d, double[] xy) {
        int n = xy.length / 2;
        if (n < 3) return;
        double area = 0;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            area += xy[2 * i] * xy[2 * j + 1] - xy[2 * j] * xy[2 * i + 1];
        }
        boolean rev = area < 0;
        double lx = Double.NaN, ly = Double.NaN;
        for (int k = 0; k < n; k++) {
            int i = rev ? n - 1 - k : k;
            double x = xy[2 * i], y = xy[2 * i + 1];
            String sx = f(x), sy = f(y);
            if (k == 0) d.append('M').append(sx).append(',').append(sy);
            else {
                if (Math.abs(x - lx) < 0.005 && Math.abs(y - ly) < 0.005) continue;
                d.append('L').append(sx).append(',').append(sy);
            }
            lx = x; ly = y;
        }
        d.append('Z');
    }

    String toSvg(String comment) {
        StringBuilder b = new StringBuilder(body.length() + defs.length() + 400);
        b.append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"").append(VIEWBOX).append("\" width=\"379.4\" height=\"192.4\">\n");
        if (comment != null && !comment.isEmpty()) b.append("<!-- ").append(safeComment(comment)).append(" -->\n");
        b.append("<defs>").append(defs).append("</defs>\n");
        b.append("<g id=\"skyline\">\n").append(body).append("</g>\n</svg>\n");
        return b.toString();
    }

    /** ASCII-only comment text without "--". */
    static String safeComment(String s) {
        String n = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            b.append(c >= 32 && c < 127 && c != '<' && c != '>' && c != '&' ? c : ' ');
        }
        return b.toString().replace("--", "-").trim();
    }

    // ------------------------------------------------------------------ colours

    static String mix(String a, String b, double t) {
        int ca = Integer.parseInt(a.substring(1), 16), cb = Integer.parseInt(b.substring(1), 16);
        int r = (int) Math.round(((ca >> 16) & 255) * (1 - t) + ((cb >> 16) & 255) * t);
        int g = (int) Math.round(((ca >> 8) & 255) * (1 - t) + ((cb >> 8) & 255) * t);
        int bl = (int) Math.round((ca & 255) * (1 - t) + (cb & 255) * t);
        return String.format(java.util.Locale.ROOT, "#%02x%02x%02x", r, g, bl);
    }
}
