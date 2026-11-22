package dev.livingsky.render;

import java.util.ArrayList;
import java.util.List;

import static dev.livingsky.render.Noise.sm;

/**
 * Daylight grade (proposal "B · Vívido", design/daylight/sky10.js gradeSkyline): recolours the rendered day skyline
 * bitmap with sunlight and materials, without redrawing the SVG. Depth comes from the SVG's own luma (the spec palette
 * encodes it), materials from a palette (chroma only) plus per-place hints, light from narrow runs (towers, spires)
 * split into a sun face and a shadow face, sun-side rims and roof tops, and aerial perspective for far layers.
 *
 * Changes from the prototype: hero towers get their own soft model (each tone of the SVG is its own form, lit with a
 * smooth gradient from the sun side, neighbours separated by a slightly darker seam, tops lighter), which fixes the
 * striped / muddy Sagrada Família; the grade fades out at low sun so dusk hands over to the night skyline.
 *
 * Places can opt into a stricter landmark model ("heroModel": "towers", used by Barcelona): the SVG paints every
 * element of the landmark as one flat warm tone (a distinct luma per overlapping element, the material in R - B), and
 * {@link #heroTowers} lights each element as a crisp round form from the sun side, with no blur (see Towers).
 */
public final class DaylightGrade {
    private DaylightGrade() {}

    public static final class Preset {
        double gain, off, sat, facade, wMin, wMax, rim, aerK, h, fol, model;
        double[] shade, lit, aer, water;

        Preset(double gain, double off, double sat, double[] shade, double[] lit, double facade, double wMin, double wMax,
               double rim, double[] aer, double aerK, double h, double fol, double[] water, double model) {
            this.gain = gain; this.off = off; this.sat = sat; this.shade = shade; this.lit = lit; this.facade = facade;
            this.wMin = wMin; this.wMax = wMax; this.rim = rim; this.aer = aer; this.aerK = aerK; this.h = h; this.fol = fol;
            this.water = water; this.model = model;
        }

        static Preset mix(Preset a, Preset b, double t) {
            return new Preset(l(a.gain, b.gain, t), l(a.off, b.off, t), l(a.sat, b.sat, t), l(a.shade, b.shade, t), l(a.lit, b.lit, t),
                    l(a.facade, b.facade, t), l(a.wMin, b.wMin, t), l(a.wMax, b.wMax, t), l(a.rim, b.rim, t), l(a.aer, b.aer, t),
                    l(a.aerK, b.aerK, t), l(a.h, b.h, t), l(a.fol, b.fol, t), l(a.water, b.water, t), l(a.model, b.model, t));
        }

        private static double l(double a, double b, double t) { return a + (b - a) * t; }
        private static double[] l(double[] a, double[] b, double t) { return new double[]{l(a[0], b[0], t), l(a[1], b[1], t), l(a[2], b[2], t)}; }
    }

    // proposal B presets (sunny / overcast)
    public static final Preset SUN = new Preset(1.10, 0.09, 0.85, new double[]{0.64, 0.74, 0.96}, new double[]{1.40, 1.33, 1.18}, 0.64, 6, 15,
            0.5, new double[]{116, 146, 186}, 0.58, 0.04, 1, new double[]{70, 134, 200}, 0);
    public static final Preset OVC = new Preset(1.12, 0.085, 0.46, new double[]{1.03, 1.05, 1.09}, new double[]{1.03, 1.05, 1.09}, 0, 6, 15,
            0, new double[]{166, 172, 186}, 0.58, 0.03, 0.8, new double[]{156, 166, 180}, 0.14);

    /** Material palettes (only their chroma is used; lightness comes from the SVG's own shading). */
    static final String[][] PAL_ALPINE = {{"#d6c3a2", "#cdb898", "#e0d8c8"}, {"#a65a46", "#8c5546", "#6f7380"}, {"#c8bda8", "#8d93a0"}};
    static final String[][] PAL_MED = {{"#efe3cc", "#e2c398", "#f1dcc2"}, {"#c0623a", "#a9583a", "#c98a5a"}, {"#caa575", "#b98a5c"}};
    static final String[][] PAL_STEEL = {{"#8fa8c6", "#b9b2a2", "#7f93ad"}, {"#b3c3d6", "#c7c2b4", "#9fb2c8"}, {"#7e95b4", "#a9bcd2"}};

    public static final class Accent {
        public double[] b;      // box in dp
        public double[] cc;     // chroma of the colour
        public double k, l;     // strength, lightness offset
    }

    /** Per-place hints (the "daylight" block of <id>.json). */
    public static final class Hints {
        public String pal = "alpine";
        public double[] hero = {168, 268};
        public String heroC, heroTopC;
        public double[] water;
        public boolean softHero = true;
        public double heroBottom = 139;          // the hero's colour stops here (nearer roofs in front of its base keep theirs)
        public List<Accent> acc = new ArrayList<>();
        public String heroModel;                // null: the soft model above; "towers": heroTowers()
        public Towers towers;

        public static Hints generic(String country) {
            Hints h = new Hints();
            h.pal = country != null && (country.equals("ES") || country.equals("IT") || country.equals("PT") || country.equals("GR")) ? "med"
                    : country != null && country.equals("US") ? "steel" : "alpine";
            return h;
        }
    }

    /**
     * The "towers" landmark model (the "towers" block of the daylight hints). The day SVG paints the landmark in warm
     * greys (R - B >= 0, the rest of the skyline is cool): each element is one flat tone, a depth code (elements that
     * overlap differ by ~8 luma levels, the nearer one darker; 0.47 and more: white glazed parts), openings are small
     * darker marks, and R - B carries the material: about +8 old stone, 0 new pale stone, +18 mosaic. Colours are full
     * colours (not chroma): lit / shadow faces in the sun, and the overcast colour, for old, new and white stone.
     */
    public static final class Towers {
        public double[] litOld, litNew, litWhite, shadeOld, shadeNew, shadeWhite, ovcOld, ovcNew, ovcWhite, mosaic;
        /** Dusk: as the grade fades out at low sun the landmark fades into this one silhouette tone (soft edges, faint
         *  openings), never into the SVG's colour code; null: into the raw SVG like the rest of the skyline. */
        public double[] silhouette;
        public double mosaicK = 0.7;        // how strongly the mosaic colours the pinnacles
        public double kY = 2.6;             // how dark the openings read: 1 - kY * (element tone - opening tone)
    }

    public static double[] hex(String h) {
        return new double[]{Integer.parseInt(h.substring(1, 3), 16), Integer.parseInt(h.substring(3, 5), 16), Integer.parseInt(h.substring(5, 7), 16)};
    }

    public static double[] chroma(double[] c) {
        double l = 0.299 * c[0] + 0.587 * c[1] + 0.114 * c[2];
        return new double[]{c[0] - l, c[1] - l, c[2] - l};
    }

    private static double[] mixv(double[] a, double[] b, double t) {
        return new double[]{a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t};
    }

    private static double boxW(double[] b, double x, double y) {
        return sm(b[0] - 1.5, b[0] + 1.5, x) * (1 - sm(b[2] - 1.5, b[2] + 1.5, x)) * sm(b[1] - 1.5, b[1] + 1.5, y) * (1 - sm(b[3] - 1.5, b[3] + 1.5, y));
    }

    /**
     * Grades the skyline layer in place (ARGB, transparent sky).
     * @param s px per dp; cover 0..1 (blends the sunny and overcast presets like the sky); sunX dp; el sun elevation
     */
    public static void grade(int[] px, int pw, int ph, double s, Hints H, double cover, double sunX, double el, Noise N) {
        grade(px, pw, ph, s, H, weight(cover), side(sunX), amount(el), N);
    }

    /** Overcast weight of the preset blend (the sky's own blend). */
    public static double weight(double cover) { return sm(0.65, 0.85, cover); }
    /** Light side: -1 from the left (morning), +1 from the right. */
    public static int side(double sunX) {
        double sd = Math.max(-1, Math.min(1, (sunX - 195) / 40));
        return Math.abs(sd) < 0.6 ? (sd < 0 ? -1 : 1) : (int) Math.signum(sd);
    }
    /** Grade strength: fades out toward dusk, handing over to the plain (then night) skyline. */
    public static double amount(double el) { return sm(-3, 6, el); }

    /** True when the place uses the "towers" landmark model (it is graded, as a silhouette, even at amount 0). */
    public static boolean towers(Hints H) { return "towers".equals(H.heroModel) && H.towers != null; }

    public static void grade(int[] px, int pw, int ph, double s, Hints H, double w, int LD, double amount, Noise N) {
        if (amount <= 0 && !towers(H)) return;
        Preset P = Preset.mix(SUN, OVC, w);
        String[][] pal = "med".equals(H.pal) ? PAL_MED : "steel".equals(H.pal) ? PAL_STEEL : PAL_ALPINE;
        double alb = "med".equals(H.pal) ? 0.05 : 0;
        double[][] wallC = new double[3][], roofC = new double[3][];
        for (int i = 0; i < 3; i++) { wallC[i] = chroma(hex(pal[0][i])); roofC[i] = chroma(hex(pal[1][i])); }
        double[] heroC = chroma(hex(H.heroC != null ? H.heroC : pal[2][0])), heroTopC = chroma(hex(H.heroTopC != null ? H.heroTopC : pal[2][1]));
        double[] FOL = chroma(hex("#4d6e43"));
        int n = pw * ph;
        float[] Y = new float[n], A = new float[n], LIT = new float[n], SEP = new float[n];
        boolean[] heroRun = new boolean[n];
        for (int i = 0; i < n; i++) {
            int c = px[i];
            A[i] = (c >>> 24) / 255f;
            Y[i] = (float) ((0.299 * ((c >> 16) & 255) + 0.587 * ((c >> 8) & 255) + 0.114 * (c & 255)) / 255.0);
        }
        // the "towers" landmark: its pixels are graded by heroTowers() and kept out of the generic runs below
        boolean[] HM = towers(H) ? heroMask(px, A, pw, ph, s, H) : null;
        if (amount <= 0) { // dusk, before the night skyline: only the landmark, as its silhouette
            heroTowers(px, A, Y, HM, pw, ph, s, H.towers, w, LD, 0);
            return;
        }
        // pass 1: faces from horizontal runs
        for (int py = 0; py < ph; py++) {
            int x = 0;
            while (x < pw) {
                int i = py * pw + x;
                if (A[i] < 0.4f || (HM != null && HM[i])) { x++; continue; }
                int a = x;
                float Y0 = Y[i];
                double xd = (x + 0.5) / s;
                boolean soft = HM == null && H.softHero && xd >= H.hero[0] && xd <= H.hero[1];
                x++;
                while (x < pw) {
                    int j = py * pw + x;
                    if (A[j] < 0.4f || (HM != null && HM[j])) break;
                    float dy = Y[j] - Y0;
                    if (soft) {
                        if (dy > 0.10f) break;
                        // another tower of the landmark: a tone step that persists (anti-aliased window edges don't)
                        boolean step = dy < -0.012f || (dy > 0.012f && dy < 0.06f);
                        if (step && x + 2 < pw && A[j + 1] >= 0.4f && A[j + 2] >= 0.4f
                                && Math.abs(Y[j + 1] - Y[j]) < 0.008f && Math.abs(Y[j + 2] - Y[j]) < 0.008f) break;
                    } else if (dy > 0.10f || dy < -0.035f) break;
                    x++;
                }
                int b = x;
                double narrow = 1 - sm(P.wMin, P.wMax, (b - a) / s);
                if (soft) narrow = Math.max(narrow, 0.35); // the landmark's wider body keeps some modelling too
                for (int q = a; q < b; q++) {
                    double pos = LD < 0 ? (q - a + 0.5) / (b - a) : (b - q - 0.5) / (b - a);
                    double face;
                    if (soft) face = 1 - 0.8 * sm(0.05, 1.0, pos); // a tapered tower: lit side to soft shadow side
                    else face = 1 - sm(0.40, 0.60, pos);
                    LIT[py * pw + q] = (float) (P.facade + (face - P.facade) * narrow);
                    heroRun[py * pw + q] = soft;
                }
                // a slightly darker seam where two neighbouring forms meet (not against the sky)
                if (soft && b - a > 1) {
                    if (a > 0 && A[py * pw + a - 1] >= 0.4f) SEP[py * pw + a] = 1;
                    if (b < pw && A[py * pw + b] >= 0.4f) SEP[py * pw + b - 1] = 1;
                }
            }
        }
        float[] LITs = blur(blur(LIT, A, pw, ph, 1, 0, 2), A, pw, ph, 0, 1, 6);
        float[] SEPs = blur(SEP, A, pw, ph, 1, 0, 1);
        double[] steps = {0.4, 0.8, 1.25}, wts = {1, 0.62, 0.3}, tsteps = {0.5, 1.1, 1.8, 2.5}, twts = {1, 0.8, 0.55, 0.3};
        List<double[]> accCc = new ArrayList<>();
        for (int py = 0; py < ph; py++) for (int x = 0; x < pw; x++) {
            int i = py * pw + x;
            if (A[i] < 0.01f || (HM != null && HM[i])) continue;
            double xd = (x + 0.5) / s, yd = (py + 0.5) / s, Yi = Y[i];
            double f = Math.max(0, Math.min(1, (Yi - 0.22) / 0.38)), near = 1 - sm(0.3, 0.8, f);
            double rim = 0, top = 0;
            for (int k = 0; k < 3; k++) rim = Math.max(rim, farther(A, Y, pw, ph, x + LD * steps[k] * s, py, Yi) * wts[k]);
            for (int k = 0; k < 4; k++) top = Math.max(top, farther(A, Y, pw, ph, x, py - tsteps[k] * s, Yi) * twts[k]);
            double heroW = HM != null ? 0 : sm(H.hero[0] - 4, H.hero[0] + 4, xd) * (1 - sm(H.hero[1] - 4, H.hero[1] + 4, xd)) * (1 - sm(0.40, 0.58, f)) * (1 - sm(H.heroBottom - 3, H.heroBottom + 3, yd));
            double[] ch = mixv(pick(wallC, xd, 0, N), pick(roofC, xd, 3.7, N), top * 0.9);
            ch = mixv(ch, mixv(heroC, heroTopC, top * 0.6), heroW);
            double Ladd = 0;
            for (Accent ac : H.acc) {
                double aw = boxW(ac.b, xd, yd) * ac.k * near;
                if (aw > 0) { ch = mixv(ch, ac.cc, aw); Ladd += ac.l * aw; }
            }
            if (heroRun[i]) Ladd += 0.05 * top * heroW; // finials and pinnacles a touch lighter
            double Lm = Yi * P.gain + P.off + Ladd + P.h * sm(134, 40, yd) * near + alb * near * (1 - top * 0.8);
            double matK = (1 - sm(0.22, 0.7, f)) * P.sat;
            double face = P.facade + (LITs[i] - P.facade) * (heroW + (1 - heroW) * 0.45);
            double light = Math.min(1, Math.max(face, top * 0.9) + rim * P.rim) * near + (1 - near) * P.facade;
            double fol = (1 - sm(0.17, 0.215, Yi)) * near * (1 - heroW) * P.fol;
            if (fol > 0) ch = mixv(ch, FOL, fol);
            double[] o = new double[3];
            for (int cc = 0; cc < 3; cc++) {
                double M = Math.max(0, Lm * 255 + ch[cc] * matK * (0.45 + 0.55 * light));
                o[cc] = M * (P.shade[cc] + (P.lit[cc] - P.shade[cc]) * light);
            }
            if (heroRun[i]) {
                double mod = 1 + P.model * (LITs[i] - 0.5) * heroW;   // overcast: gentle modelling, no stripes
                double sep = 1 - 0.16 * Math.min(1, SEPs[i] * 1.6) * heroW; // neighbouring towers read as separate
                for (int cc = 0; cc < 3; cc++) o[cc] *= mod * sep;
            }
            o = mixv(o, P.aer, sm(0.25, 1.0, f) * P.aerK);
            if (H.water != null) {
                double ww = boxW(H.water, xd, yd) * sm(0.24, 0.34, Yi);
                if (ww > 0) {
                    double wl = 0.8 + 0.7 * (Yi - 0.4);
                    o = mixv(o, new double[]{P.water[0] * wl, P.water[1] * wl, P.water[2] * wl}, ww * 0.85);
                }
            }
            int c0 = px[i];
            double r0 = (c0 >> 16) & 255, g0 = (c0 >> 8) & 255, b0 = c0 & 255;
            int r = clamp(r0 + (o[0] - r0) * amount), g = clamp(g0 + (o[1] - g0) * amount), bb = clamp(b0 + (o[2] - b0) * amount);
            px[i] = (c0 & 0xff000000) | (r << 16) | (g << 8) | bb;
        }
        if (HM != null) heroTowers(px, A, Y, HM, pw, ph, s, H.towers, w, LD, amount);
    }

    /** Landmark pixels of the "towers" model: warm (R - B >= -2) inside the hero range, above its foot. */
    private static boolean[] heroMask(int[] px, float[] A, int pw, int ph, double s, Hints H) {
        boolean[] m = new boolean[pw * ph];
        int x0 = Math.max(0, (int) Math.floor(H.hero[0] * s)), x1 = Math.min(pw, (int) Math.ceil(H.hero[1] * s));
        int y1 = Math.min(ph, (int) Math.ceil((H.heroBottom + 6) * s));
        for (int py = 0; py < y1; py++) for (int x = x0; x < x1; x++) {
            int i = py * pw + x, c = px[i];
            if (A[i] >= 0.1f && ((c >> 16) & 255) - (c & 255) >= -2) m[i] = true;
        }
        return m;
    }

    /**
     * Grades the "towers" landmark. Every row is split into runs at the element tones, found on a morphological
     * closing of the row's luma (5 px, same material only), which erases the small darker openings but keeps the
     * steps between elements exact. The SVG's depth code (the nearer element is darker) tells which edge of a run is
     * hidden behind a nearer element: such a run is lit as the visible part of a round form, with a soft contact shadow
     * at the hidden edge, and a run free on both sides as a whole one. Sun from the LD side and a little from the
     * front with a soft terminator; overcast only darkens the edges a little. A vertical average within each element
     * settles the run ends; no horizontal blur, so the silhouettes stay crisp. The colour comes from the material (R - B: old / new
     * stone, mosaic; tones of 0.47 and more are white glazed parts) and the openings (darker than their run's tone).
     * At low sun the result fades into {@link Towers#silhouette} (amount 0: the silhouette alone).
     */
    static void heroTowers(int[] px, float[] A, float[] Y, boolean[] HM, int pw, int ph, double s, Towers T, double w,
                           int LD, double amount) {
        final double LX = 0.86, LZ = 0.51, WRAP = 0.16, TT = 0.013, VIS = 0.62, WALL = 0.6;
        final int R = 2;                                     // closing radius (px): openings up to 2R - 1 px wide
        int n = pw * ph;
        float[] LS = new float[n], LO = new float[n], YR = new float[n], YC = new float[n], AO = new float[n];
        int[] ra = new int[pw], rb = new int[pw], qm = new int[pw];
        double[] ry = new double[pw];
        float[] mx = new float[pw], yc = new float[pw];
        boolean[] ok = new boolean[pw];
        for (int py = 0; py < ph; py++) {
            int row = py * pw;
            for (int x = 0; x < pw; x++) {
                int c = px[row + x];
                ok[x] = HM[row + x] && A[row + x] >= 0.6f;
                qm[x] = ((c >> 16) & 255) - (c & 255);
            }
            // closing = min(max(Y)) over +-R opaque landmark pixels of the same material
            for (int pass = 0; pass < 2; pass++) {
                float[] src = pass == 0 ? Y : mx, dst = pass == 0 ? mx : yc;
                int off = pass == 0 ? row : 0;
                for (int x = 0; x < pw; x++) {
                    if (!ok[x]) { dst[x] = src[off + x]; continue; }
                    float v = src[off + x];
                    for (int k = -R; k <= R; k++) {
                        int xx = x + k;
                        if (k == 0 || xx < 0 || xx >= pw || !ok[xx] || Math.abs(qm[xx] - qm[x]) > 3) continue;
                        v = pass == 0 ? Math.max(v, src[off + xx]) : Math.min(v, src[off + xx]);
                    }
                    dst[x] = v;
                }
            }
            int x = 0, nr = 0;
            while (x < pw) {
                if (!HM[row + x]) { x++; continue; }
                int a = x;
                double yr = -1;
                while (x < pw && HM[row + x]) {
                    if (ok[x]) {
                        // a tone counts once two opaque pixels agree (anti-aliased edges never set or split a run)
                        boolean pair = x + 1 < pw && ok[x + 1] && Math.abs(yc[x + 1] - yc[x]) < 0.008;
                        if (yr < 0) { if (pair) yr = yc[x]; }
                        else if (Math.abs(yc[x] - yr) > TT && pair && x > a) break;   // the next element starts here
                    }
                    x++;
                }
                int b = Math.max(x, a + 1);
                if (yr < 0) { yr = 0; for (int q = a; q < b; q++) yr += yc[q]; yr /= (b - a); }
                ra[nr] = a; rb[nr] = b; ry[nr] = yr; nr++;
                for (int q = a; q < b; q++) YC[row + q] = yc[q];
                x = b;
            }
            for (int r = 0; r < nr; r++) {
                int a = ra[r], b = rb[r], wpx = b - a;
                // a neighbouring run that touches this one and is nearer (darker) hides that edge
                boolean hl = r > 0 && rb[r - 1] == a && ry[r - 1] < ry[r] - TT;
                boolean hr = r + 1 < nr && ra[r + 1] == b && ry[r + 1] < ry[r] - TT;
                double p0 = hl && hr ? (1 - VIS) / 2 : hl ? 1 - VIS : 0, p1 = hl && hr ? (1 + VIS) / 2 : hr ? VIS : 1;
                double narrow = 1 - sm(11, 22, wpx / s);   // wide runs are walls, not spindles: a flat wall light
                for (int q = a; q < b; q++) {
                    double t = wpx <= 2 ? 0.5 : (q - a + 0.5) / wpx;
                    double p = p0 + (p1 - p0) * t;
                    double nx = 2 * p - 1, nz = Math.sqrt(Math.max(0, 1 - nx * nx));
                    double d = Math.max(0, Math.min(1, (nx * LD * LX + nz * LZ + WRAP) / (1 + WRAP)));
                    int i = py * pw + q;
                    LS[i] = (float) (WALL + (d - WALL) * narrow);
                    LO[i] = (float) (0.85 + (nz - 0.85) * narrow);
                    YR[i] = (float) ry[r];
                    double ao = 0;
                    if (hl) ao = Math.max(ao, Math.exp(-(q - a + 0.5) / (0.55 * s)));
                    if (hr) ao = Math.max(ao, Math.exp(-(b - q - 0.5) / (0.55 * s)));
                    AO[i] = (float) ao;
                }
            }
        }
        // vertical smoothing within each element (same tone): a round form's light hardly changes along its height,
        // and the per-row guesses (hidden edges, run ends) settle without anything bleeding across element edges
        final int VR = (int) Math.round(2.2 * s);
        float[] LS2 = new float[n], LO2 = new float[n];
        for (int py = 0; py < ph; py++) for (int x = 0; x < pw; x++) {
            int i = py * pw + x;
            if (!HM[i]) continue;
            double ls = 0, lo = 0, wt = 0;
            for (int k = -VR; k <= VR; k++) {
                int yy = py + k;
                if (yy < 0 || yy >= ph) continue;
                int j = yy * pw + x;
                if (!HM[j] || Math.abs(YR[j] - YR[i]) > 0.006) continue;
                double wk = 1 - Math.abs(k) / (VR + 1.0);
                ls += LS[j] * wk; lo += LO[j] * wk; wt += wk;
            }
            LS2[i] = (float) (ls / wt); LO2[i] = (float) (lo / wt);
        }
        double[] lit = new double[3], sh = new double[3], ov = new double[3], o = new double[3];
        for (int py = 0; py < ph; py++) for (int x = 0; x < pw; x++) {
            int i = py * pw + x;
            if (!HM[i]) continue;
            double ls = LS2[i], lo = LO2[i];
            // tops facing the sky (roof ridges, ledges, the arms of the cross): a touch more light
            if (py > 0 && A[i - pw] < 0.3f && x > 0 && x + 1 < pw && HM[i - 1] && HM[i + 1]) ls = Math.min(1, ls + 0.12);
            int c0 = px[i];
            double r0 = (c0 >> 16) & 255, g0 = (c0 >> 8) & 255, b0 = c0 & 255;
            double q = r0 - b0, tNew = 1 - sm(1.5, 6.5, q), mos = sm(10, 16, q), tW = sm(0.455, 0.49, YR[i]);
            double open = A[i] < 0.95f ? 1 : Math.max(0.5, 1 - T.kY * Math.max(0, YC[i] - Y[i]));   // openings only
            double ao = 1 - 0.16 * AO[i];
            Noise.mix(lit, T.litOld, T.litNew, tNew);
            Noise.mix(sh, T.shadeOld, T.shadeNew, tNew);
            Noise.mix(ov, T.ovcOld, T.ovcNew, tNew);
            if (tW > 0) { Noise.mixTo(lit, T.litWhite, tW); Noise.mixTo(sh, T.shadeWhite, tW); Noise.mixTo(ov, T.ovcWhite, tW); }
            for (int c = 0; c < 3; c++) {
                double sun = (sh[c] + (lit[c] - sh[c]) * ls) * open * ao;
                double ovc = ov[c] * (0.80 + 0.20 * lo) * open * (1 - 0.08 * AO[i]);
                if (mos > 0) {
                    sun += (T.mosaic[c] * (0.42 + 0.62 * ls) * ao - sun) * mos * T.mosaicK;
                    ovc += (T.mosaic[c] * 0.78 * (0.80 + 0.20 * lo) - ovc) * mos * T.mosaicK * 0.7;
                }
                o[c] = sun + (ovc - sun) * w;
            }
            double[] base = {r0, g0, b0};
            if (T.silhouette != null) { // the fade-out target: one tone for the whole landmark, softly rounded
                double sl = (0.90 + 0.10 * lo) * (1 + 0.10 * tW)
                        * (A[i] < 0.95f ? 1 : Math.max(0.6, 1 - 1.4 * Math.max(0, YC[i] - Y[i])));
                for (int c = 0; c < 3; c++) base[c] = T.silhouette[c] * sl;
            }
            px[i] = (c0 & 0xff000000) | (clamp(base[0] + (o[0] - base[0]) * amount) << 16)
                    | (clamp(base[1] + (o[1] - base[1]) * amount) << 8) | clamp(base[2] + (o[2] - base[2]) * amount);
        }
    }

    private static int clamp(double v) { return v <= 0 ? 0 : v >= 255 ? 255 : (int) Math.round(v); }

    private static double[] pick(double[][] arr, double x, double o, Noise N) {
        double u = sm(-0.32, 0.32, N.noise(x / 7 + o, 0.37 + o));
        return u < 0.5 ? mixv(arr[0], arr[1], u * 2) : mixv(arr[1], arr[2], (u - 0.5) * 2);
    }

    private static double farther(float[] A, float[] Y, int pw, int ph, double px, double py, double Yi) {
        int x = (int) Math.max(0, Math.min(pw - 1, Math.round(px))), y = (int) Math.max(0, Math.min(ph - 1, Math.round(py)));
        int j = y * pw + x;
        if (A[j] < 0.4f) return 1;
        return Math.max(0, Math.min(1, (Y[j] - Yi - 0.015) / 0.07));
    }

    private static float[] blur(float[] src, float[] A, int pw, int ph, int dx, int dy, int r) {
        float[] out = new float[src.length];
        for (int py = 0; py < ph; py++) for (int x = 0; x < pw; x++) {
            int i = py * pw + x;
            if (A[i] < 0.4f) { out[i] = src[i]; continue; }
            float sum = 0; int cnt = 0;
            for (int k = -r; k <= r; k++) {
                int qx = x + dx * k, qy = py + dy * k;
                if (qx < 0 || qy < 0 || qx >= pw || qy >= ph) continue;
                int j = qy * pw + qx;
                if (A[j] < 0.4f) continue;
                sum += src[j]; cnt++;
            }
            out[i] = cnt > 0 ? sum / cnt : src[i];
        }
        return out;
    }

    /** The near-bank ground by day: dark olive-green instead of near-black, a sunlit canopy rim and slight texture. */
    public static void ground(int[] px, int pw, int ph, double s, double cover, double el, Noise N) {
        groundW(px, pw, ph, s, weight(cover), amount(el), N);
    }

    public static void groundW(int[] px, int pw, int ph, double s, double w, double amount, Noise N) {
        if (amount <= 0) return;
        double[][] sun = {hex("#33432f"), hex("#232d21"), hex("#161c15")}, ovc = {hex("#363e39"), hex("#252b28"), hex("#181b1a")};
        double[][] st = {mixv(sun[0], ovc[0], w), mixv(sun[1], ovc[1], w), mixv(sun[2], ovc[2], w)};
        double rimK = 0.18 * (1 - w * 0.45);
        double[] rimC = {170, 190, 110};
        for (int py = 0; py < ph; py++) for (int x = 0; x < pw; x++) {
            int i = py * pw + x;
            int c0 = px[i];
            if ((c0 >>> 24) < 3) continue;
            double xd = (x + 0.5) / s, yd = (py + 0.5) / s;
            double[] o = yd < 152 ? mixv(st[0], st[1], sm(136, 152, yd)) : mixv(st[1], st[2], sm(152, 192, yd));
            double rim = 0;
            for (double k : new double[]{0.6, 1.2, 1.9, 2.6}) {
                int yy = (int) Math.round(py - k * s);
                if (yy < 0 || (px[yy * pw + x] >>> 24) < 128) rim = Math.max(rim, 1 - k / 3.4);
            }
            double t = N.fbm(xd / 2.4, yd / 2.4, 2) * 12 * (1 - sm(146, 156, yd));
            for (int c = 0; c < 3; c++) o[c] += t;
            o = mixv(o, rimC, rim * rimK);
            double r0 = (c0 >> 16) & 255, g0 = (c0 >> 8) & 255, b0 = c0 & 255;
            px[i] = (c0 & 0xff000000) | (clamp(r0 + (o[0] - r0) * amount) << 16) | (clamp(g0 + (o[1] - g0) * amount) << 8) | clamp(b0 + (o[2] - b0) * amount);
        }
    }
}
