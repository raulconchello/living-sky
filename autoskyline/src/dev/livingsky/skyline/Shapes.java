package dev.livingsky.skyline;

import dev.livingsky.skyline.Shape.Poly;

/**
 * Type-appropriate silhouette generators, all in metres (x right, y up, base at y = 0, main axis at x = 0).
 * Proportions follow typical real buildings so that true heights give believable shapes.
 */
final class Shapes {
    private Shapes() {}

    /** Parameters for one landmark drawing. */
    static final class Spec {
        double H;            // total height (m)
        double w;            // footprint width across the view (m), 0 = unknown
        double depth;        // footprint depth along the view (m)
        double len;          // visible length of a nave/wing (m), 0 = seen end-on
        int dir = 1;         // +1: nave extends to the right of the tower, -1: to the left
        String style = "";
        String region = "central";   // central, med, alpine, north, america
        int towers;
        boolean ruin, cathedral, notable;
        String roofShape = "", towerRoof = "", castleType = "", towerType = "", construction = "";
        double roofH = Double.NaN;   // building roof height when H includes a spire/antenna
        double dpPerM = 1;           // drawing scale, for details that must stay legible
        Rng r;
    }

    // ------------------------------------------------------------------ primitives

    static double[] rect(double x0, double y0, double x1, double y1) {
        return new double[]{x0, y0, x0, y1, x1, y1, x1, y0};
    }

    static double[] lancet(double cx, double y, double w, double h) {
        return new double[]{cx - w / 2, y, cx - w / 2, y + h, cx - w * 0.32, y + h + w * 0.42, cx, y + h + w * 0.7,
                cx + w * 0.32, y + h + w * 0.42, cx + w / 2, y + h, cx + w / 2, y};
    }

    static double[] arch(double cx, double y, double w, double h) {
        Poly p = new Poly().p(cx - w / 2, y).p(cx - w / 2, y + h);
        for (int i = 1; i < 6; i++) { double a = Math.PI * (1 - i / 6.0); p.p(cx + Math.cos(a) * w / 2, y + h + Math.sin(a) * w / 2); }
        p.p(cx + w / 2, y + h).p(cx + w / 2, y);
        return p.toArray();
    }

    static double[] spike(double cx, double y0, double h, double w) {
        return new double[]{cx - w / 2, y0, cx - w * 0.3, y0 + h * 0.55, cx, y0 + h, cx + w * 0.3, y0 + h * 0.55, cx + w / 2, y0};
    }

    static double[] circle(double cx, double cy, double r, int n) {
        double[] a = new double[n * 2];
        for (int i = 0; i < n; i++) { double t = 2 * Math.PI * i / n; a[2 * i] = cx + Math.cos(t) * r; a[2 * i + 1] = cy + Math.sin(t) * r; }
        return a;
    }

    /** Thin finial with a cross: from y0 up h. */
    static void cross(Shape s, int role, double cx, double y0, double h, double w) {
        double bw = Math.max(0.35, w * 0.16);
        s.add(role, rect(cx - bw / 2, y0, cx + bw / 2, y0 + h));
        s.add(role, rect(cx - w / 2, y0 + h * 0.62, cx + w / 2, y0 + h * 0.62 + bw));
    }

    /** Merlons along a wall top from x0 to x1 at height y. */
    static void crenels(Shape s, int role, double x0, double x1, double y, double mw, double mh) {
        int n = Math.max(2, (int) Math.round((x1 - x0) / (mw * 2)));
        double step = (x1 - x0) / n;
        for (int i = 0; i < n; i++) {
            double a = x0 + i * step + step * 0.25;
            s.add(role, rect(a, y - 0.2, a + step * 0.5, y + mh));
        }
    }

    /** Smooth onion bulb outline (closed polygon) from y0 to y1, max half-width W. */
    static double[] onion(double cx, double y0, double y1, double W) {
        double[] tc = {0, 0.1, 0.24, 0.38, 0.52, 0.64, 0.75, 0.85, 0.93, 1.0};
        double[] fc = {0.6, 0.88, 1.0, 0.97, 0.8, 0.56, 0.34, 0.19, 0.11, 0.06};
        Poly p = new Poly();
        int N = 18;
        double[] xs = new double[N + 1], ys = new double[N + 1];
        for (int i = 0; i <= N; i++) {
            double t = i / (double) N;
            xs[i] = W * interp(tc, fc, t);
            ys[i] = y0 + (y1 - y0) * t;
        }
        for (int i = 0; i <= N; i++) p.p(cx - xs[i], ys[i]);
        for (int i = N; i >= 0; i--) p.p(cx + xs[i], ys[i]);
        return p.toArray();
    }

    /** Dome (half ellipse, optionally slightly pointed) from y0, half-width R, height h. */
    static double[] dome(double cx, double y0, double R, double h, double point) {
        Poly p = new Poly();
        int N = 14;
        for (int i = 0; i <= N; i++) {
            double a = Math.PI * (1 - i / (double) N);
            double c = Math.cos(a), sn = Math.sin(a);
            double yy = sn * h + Math.pow(sn, 6) * point * h;
            p.p(cx + c * R, y0 + yy);
        }
        return p.toArray();
    }

    static double interp(double[] xs, double[] ys, double x) {
        if (x <= xs[0]) return ys[0];
        for (int i = 1; i < xs.length; i++) {
            if (x <= xs[i]) {
                double t = (x - xs[i - 1]) / (xs[i] - xs[i - 1]);
                double u = t * t * (3 - 2 * t);
                return ys[i - 1] + (ys[i] - ys[i - 1]) * (0.5 * t + 0.5 * u);
            }
        }
        return ys[ys.length - 1];
    }

    // ------------------------------------------------------------------ towers

    /** Gothic tower with pinnacles and a pointed (optionally openwork, crocketed) spire. */
    static void gothicTower(Shape s, int role, double cx, double H, double tw, boolean big, Rng r) {
        double hb = H * (big ? 0.58 : 0.56);
        double half = tw / 2;
        Poly p = new Poly();
        p.p(cx - half, 0).p(cx - half, hb * 0.34).p(cx - half * 0.93, hb * 0.35).p(cx - half * 0.93, hb * 0.68)
                .p(cx - half * 0.87, hb * 0.69).p(cx - half * 0.87, hb);
        // corner pinnacle
        double pw = tw * 0.12, ph = H * (big ? 0.075 : 0.05);
        p.p(cx - half * 0.87, hb).p(cx - half * 0.87 + pw * 0.2, hb + ph * 0.5).p(cx - half * 0.87 + pw * 0.5, hb + ph)
                .p(cx - half * 0.87 + pw * 0.8, hb + ph * 0.5).p(cx - half * 0.87 + pw, hb);
        double ys, sw;
        if (big) {
            // octagon stage with small gables
            double ow = half * 0.66, oh = H * 0.11;
            p.p(cx - ow, hb).p(cx - ow, hb + oh).p(cx - ow * 0.92, hb + oh + H * 0.012).p(cx - ow * 0.82, hb + oh + H * 0.05)
                    .p(cx - ow * 0.72, hb + oh + H * 0.012).p(cx - ow * 0.66, hb + oh);
            ys = hb + oh; sw = ow * 0.64;
        } else {
            ys = hb + H * 0.005; sw = half * 0.74;
            p.p(cx - sw, hb);
        }
        // spire with crockets
        double top = H * 0.965;
        int n = big ? 14 : 0;
        if (n > 0) {
            for (int i = 0; i <= n; i++) {
                double f = i / (double) n, y = ys + (top - ys) * f, w = sw * (1 - f) + sw * 0.06 * f;
                p.p(cx - w, y);
                if (i < n) p.p(cx - w - tw * 0.03, y + (top - ys) / n * 0.4);
            }
        } else {
            p.p(cx - sw, ys).p(cx - sw * 0.06, top);
        }
        // finial
        double fh = H - top;
        p.p(cx - tw * 0.02, top).p(cx - tw * 0.02, top + fh * 0.55).p(cx - tw * 0.09, top + fh * 0.55).p(cx - tw * 0.09, top + fh * 0.68)
                .p(cx - tw * 0.02, top + fh * 0.68).p(cx - tw * 0.012, H);
        p.mirror(cx);
        s.add(role, p);
        // openings
        double lw = tw * 0.13;
        s.add(Shape.HOLE, lancet(cx - tw * 0.2, hb - H * 0.13, lw, H * 0.08));
        s.add(Shape.HOLE, lancet(cx + tw * 0.2, hb - H * 0.13, lw, H * 0.08));
        if (big) {
            s.add(Shape.HOLE, lancet(cx - tw * 0.3, H * 0.25, tw * 0.1, H * 0.1));
            s.add(Shape.HOLE, lancet(cx, H * 0.25, tw * 0.1, H * 0.1));
            s.add(Shape.HOLE, lancet(cx + tw * 0.3, H * 0.25, tw * 0.1, H * 0.1));
            s.add(Shape.HOLE, lancet(cx - sw * 0.35, hb + 1, tw * 0.1, H * 0.08));
            s.add(Shape.HOLE, lancet(cx + sw * 0.35, hb + 1, tw * 0.1, H * 0.08));
            for (int i = 0; i < 8; i++) {
                double y = ys + (top - ys) * (0.08 + i * 0.1), w = sw * (1 - (y - ys) / (top - ys)) * 0.42;
                if (w > tw * 0.025) { s.add(Shape.HOLE, lancet(cx - w * 0.95, y, w * 0.9, (top - ys) * 0.04)); s.add(Shape.HOLE, lancet(cx + w * 0.95, y, w * 0.9, (top - ys) * 0.04)); }
            }
        } else {
            s.add(Shape.HOLE, circle(cx, hb - H * 0.2, tw * 0.13, 12));
            s.add(Shape.HOLE, lancet(cx, H * 0.12, tw * 0.14, H * 0.1));
        }
    }

    /** Baroque tower with onion dome, lantern and small onion. */
    static void onionTower(Shape s, int role, double cx, double H, double tw, Rng r) {
        double half = tw / 2, hb = H * 0.58, h2 = H * 0.69, h3 = H * 0.84, h4 = H * 0.885, h5 = H * 0.94;
        Poly p = new Poly();
        p.p(cx - half, 0).p(cx - half, hb).p(cx - half * 1.06, hb).p(cx - half * 1.06, hb + H * 0.012).p(cx - half * 0.88, hb + H * 0.012)
                .p(cx - half * 0.88, h2).p(cx - half * 0.94, h2).p(cx - half * 0.94, h2 + H * 0.01).p(cx - half * 0.6, h2 + H * 0.01);
        p.p(cx - tw * 0.03, H * 0.995).p(cx - tw * 0.03, H);
        p.mirror(cx);
        s.add(role, p);
        // urns on the cornice
        s.add(role, spike(cx - half * 0.95, hb + H * 0.012, H * 0.035, tw * 0.09));
        s.add(role, spike(cx + half * 0.95, hb + H * 0.012, H * 0.035, tw * 0.09));
        // onion, lantern, small onion, cross
        s.add(role, onion(cx, h2 + H * 0.008, h3, half * 1.02));
        s.add(role, rect(cx - tw * 0.15, h3 - H * 0.005, cx + tw * 0.15, h4));
        s.add(role, onion(cx, h4, h5, tw * 0.2));
        cross(s, role, cx, h5 - H * 0.005, H - h5 + H * 0.005, tw * 0.2);
        // openings: belfry arches, clock, lantern
        s.add(Shape.HOLE, arch(cx, hb + H * 0.025, tw * 0.3, (h2 - hb) * 0.5));
        s.add(Shape.HOLE, circle(cx, hb - H * 0.06, tw * 0.17, 12));
        s.add(Shape.HOLE, rect(cx - tw * 0.06, h3 + (h4 - h3) * 0.2, cx + tw * 0.06, h4 - (h4 - h3) * 0.25));
        s.add(Shape.HOLE, arch(cx, H * 0.2, tw * 0.16, H * 0.07));
    }

    /** Romanesque tower with a rhenish helm (gabled pyramid) roof. */
    static void helmTower(Shape s, int role, double cx, double H, double tw, Rng r) {
        double half = tw / 2, hb = H * 0.74, gh = H * 0.055;
        Poly p = new Poly();
        p.p(cx - half, 0).p(cx - half, hb).p(cx - half * 0.5, hb + gh).p(cx - tw * 0.02, H * 0.985).p(cx - tw * 0.02, H);
        p.mirror(cx);
        s.add(role, p);
        s.add(Shape.ROOF, new double[]{cx - half * 0.5, hb + gh, cx, hb, cx + half * 0.5, hb + gh, cx, H * 0.985});
        for (int k = 0; k < 2; k++) {
            double y = hb - H * (0.11 + k * 0.17);
            s.add(Shape.HOLE, arch(cx - tw * 0.15, y, tw * 0.13, H * 0.06));
            s.add(Shape.HOLE, arch(cx + tw * 0.15, y, tw * 0.13, H * 0.06));
        }
    }

    /** Square bell tower with an arcaded belfry; top is a low pyramid, an octagon with cupola, or a small spire. */
    static void campanile(Shape s, int role, double cx, double H, double tw, int variant, Rng r) {
        double half = tw / 2;
        double hb = variant == 1 ? H * 0.74 : H * 0.84;
        Poly p = new Poly();
        p.p(cx - half, 0).p(cx - half, hb - H * 0.02).p(cx - half * 1.07, hb - H * 0.02).p(cx - half * 1.07, hb);
        if (variant == 0) { // low pyramid + finial
            p.p(cx - half * 0.98, hb).p(cx - tw * 0.04, H * 0.97).p(cx - tw * 0.02, H);
        } else if (variant == 1) { // octagonal belfry + cupola + lantern (Iberian baroque)
            double ow = half * 0.72, oh = H * 0.1;
            p.p(cx - ow, hb).p(cx - ow, hb + oh).p(cx - ow * 1.06, hb + oh).p(cx - ow * 1.06, hb + oh + H * 0.01);
            p.p(cx - tw * 0.02, H);
            s.add(role, dome(cx, hb + oh + H * 0.008, ow * 0.98, H * 0.08, 0.25));
            s.add(role, rect(cx - tw * 0.09, hb + oh + H * 0.08, cx + tw * 0.09, hb + oh + H * 0.115));
            s.add(Shape.HOLE, arch(cx - ow * 0.45, hb + oh * 0.2, ow * 0.38, oh * 0.45));
            s.add(Shape.HOLE, arch(cx + ow * 0.45, hb + oh * 0.2, ow * 0.38, oh * 0.45));
        } else { // balustrade + slender pinnacle spire
            p.p(cx - half * 0.95, hb + H * 0.02).p(cx - half * 0.6, hb + H * 0.02).p(cx - half * 0.6, hb + H * 0.05)
                    .p(cx - tw * 0.03, H * 0.98).p(cx - tw * 0.02, H);
        }
        p.mirror(cx);
        s.add(role, p);
        // belfry arcade
        double by = hb - H * 0.15;
        s.add(Shape.HOLE, arch(cx - tw * 0.2, by, tw * 0.24, H * 0.08));
        s.add(Shape.HOLE, arch(cx + tw * 0.2, by, tw * 0.24, H * 0.08));
        s.add(Shape.HOLE, arch(cx, H * 0.35, tw * 0.12, H * 0.05));
    }

    /** Drum, dome and lantern centred at cx, sitting on y0. Returns the top height. */
    static double domeDrum(Shape s, int role, double cx, double y0, double R, double drumH, double domeH, double lantH, boolean pointed) {
        s.add(role, rect(cx - R * 1.04, y0, cx + R * 1.04, y0 + drumH * 0.12));
        s.add(role, rect(cx - R, y0, cx + R, y0 + drumH));
        s.add(role, rect(cx - R * 1.05, y0 + drumH - drumH * 0.08, cx + R * 1.05, y0 + drumH));
        s.add(role, dome(cx, y0 + drumH - 0.1, R * 0.98, domeH, pointed ? 0.35 : 0.05));
        double ly = y0 + drumH + domeH * 0.94;
        s.add(role, rect(cx - R * 0.17, ly, cx + R * 0.17, ly + lantH * 0.62));
        s.add(role, dome(cx, ly + lantH * 0.6, R * 0.21, lantH * 0.2, 0.5));
        s.add(role, rect(cx - R * 0.025, ly + lantH * 0.75, cx + R * 0.025, ly + lantH));
        int nw = 4;
        for (int i = 0; i < nw; i++) {
            double x = cx - R * 0.66 + i * (R * 1.32 / (nw - 1));
            s.add(Shape.HOLE, arch(x, y0 + drumH * 0.25, R * 0.14, drumH * 0.38));
        }
        s.add(Shape.HOLE, rect(cx - R * 0.06, ly + lantH * 0.12, cx + R * 0.06, ly + lantH * 0.45));
        return ly + lantH;
    }

    // ------------------------------------------------------------------ churches

    static Shape church(Spec sp) {
        Shape s = new Shape();
        s.flood = true;
        Rng r = sp.r;
        double H = sp.H;
        String st = sp.style;
        boolean big = H >= 85 || (sp.cathedral && H >= 65);
        boolean med = "med".equals(sp.region);
        boolean twin = sp.towers >= 2;
        double naveW = sp.depth > 0 ? Rng.clamp(Math.min(sp.depth, sp.w > 0 ? Math.max(sp.w, sp.depth) : sp.depth), 10, 45) : Rng.clamp(H * 0.32, 11, 36);
        double tw = Rng.clamp(H * (big ? 0.19 : 0.2), 5, 30);
        if (twin) tw *= 0.85;

        int type; // 0 gothic spire, 1 onion, 2 romanesque helm, 3 campanile, 4 dome basilica
        if (sp.roofShape.contains("onion") || (st.contains("baroque") && !med && !st.contains("gothic"))) type = 1;
        else if (sp.roofShape.contains("dome") || st.contains("byzantine") || st.contains("orthodox")) type = 4;
        else if (st.contains("romanesque") && !st.contains("gothic")) type = 2;
        else if (med) type = (st.contains("baroque") || st.contains("neoclassical") || st.contains("renaissance")) && !st.contains("gothic")
                && (sp.cathedral || st.contains("basilica")) ? 4 : 3;
        else if (st.contains("neoclassical") || st.contains("renaissance")) type = (sp.cathedral || big) ? 4 : 0;
        else if (st.contains("gothic") || st.contains("modern")) type = 0;
        else if ("alpine".equals(sp.region) || "central-south".equals(sp.region)) type = r.chance(0.45) ? 1 : 0;
        else type = 0;
        // the mapped roof of the tower element refines the tower cap (but a domed basilica stays one)
        String tr = sp.towerRoof;
        if (type != 4) {
            if (tr.contains("onion") || tr.contains("dome")) type = med ? 3 : 1;   // bulbous cap ("Welsche Haube")
            else if (tr.contains("pyramid") || tr.contains("hipped")) type = med ? 3 : 2;
            else if (tr.contains("conical") || tr.contains("spire") || tr.contains("cone")) type = 0;
        }
        if ("central-south".equals(sp.region) && type == 0 && st.isEmpty() && r.chance(0.35)) type = 1;

        // ---- nave / body (behind the tower)
        double eave = Rng.clamp(H * (big ? 0.22 : 0.3), 7, 30);
        if (type == 4) eave = Rng.clamp(H * 0.24, 9, 30);
        double pitch = (type == 3 || type == 4) ? 0.32 : 0.62;
        double ridge = Math.min(eave + naveW * pitch, H * (type == 4 ? 0.5 : 0.55));   // wide naves: hipped/double roofs stay below the towers
        double len = sp.len;
        int dir = sp.dir;
        double towerHalf = tw / 2;
        if (len > 4) {
            double x0 = dir * towerHalf * 0.6, x1 = x0 + dir * len;
            double hip = Math.min(len * 0.18, (ridge - eave) * 0.9);
            // roof (lighter), clerestory wall, aisle and buttresses (darker)
            s.add(Shape.ROOF, new double[]{x0, eave - 0.5, x0, ridge, x1 - dir * hip, ridge, x1, eave - 0.5});
            s.add(Shape.WALL, rect(Math.min(x0, x1), 0, Math.max(x0, x1), eave));
            boolean gothicNave = type == 0 || (st.contains("gothic"));
            if (big || sp.cathedral) {
                double ah = eave * 0.55;
                s.add(Shape.DARK, new double[]{x0, 0, x0, ah, x1 - dir * 2, ah, x1 - dir * 2, 0});
                s.add(Shape.DARK, new double[]{x0, ah, x0, ah + eave * 0.12, x1 - dir * 2, ah + eave * 0.12, x1 - dir * 2, ah});
            }
            double bayW = gothicNave ? Rng.clamp(eave * 0.55, 5, 9) : Rng.clamp(eave * 0.6, 5, 10);
            int bays = Math.max(2, (int) Math.floor((len - 4) / bayW));
            for (int i = 0; i < bays; i++) {
                double bx = x0 + dir * (3 + (i + 0.5) * (len - 6) / bays);
                double wh = eave * 0.42;
                if (gothicNave) {
                    s.add(Shape.HOLE, lancet(bx, eave * 0.32, Math.min(2.2, bayW * 0.3), wh));
                    double px = bx + dir * (len - 6) / bays * 0.5;
                    if (i < bays - 1 || bays == 1) {
                        s.add(Shape.DARK, rect(px - 0.8, 0, px + 0.8, eave * (big ? 0.78 : 0.62)));
                        if (big) s.add(Shape.DARK, spike(px, eave * 0.78 - 0.2, eave * 0.22, 1.8));
                    }
                } else {
                    s.add(Shape.HOLE, arch(bx, eave * 0.36, Math.min(2.4, bayW * 0.32), wh * 0.8));
                }
            }
            if (big && gothicNave) for (double m = x0 + dir * 4; dir * (x1 - m) > 3; m += dir * bayW) s.add(Shape.WALL, spike(m, eave - 0.3, 5.5, 1.4));
            // dome at the crossing for domed basilicas
            if (type == 4) {
                double R = Rng.clamp(naveW * 0.42, 6, 22);
                double cxd = x0 + dir * len * 0.58;
                double domeTop = twin ? H * 0.92 : H;
                double drumH = Math.max(3, (domeTop - ridge) * 0.25), lantH = Math.max(4, (domeTop - ridge) * 0.22);
                double domeH = Math.max(R * 0.7, domeTop - ridge - drumH - lantH + R * 0.05);
                domeDrum(s, Shape.WALL, cxd, ridge - 1, R, drumH, domeH, lantH, false);
                if (len > 70) { // smaller domes along the roof (e.g. El Pilar)
                    for (double f : new double[]{0.22, 0.82}) {
                        double cx2 = x0 + dir * len * f, R2 = R * 0.5;
                        domeDrum(s, Shape.ROOF, cx2, ridge - 1, R2, R2 * 0.5, R2 * 0.9, R2 * 0.8, false);
                    }
                }
            }
            // chapel turret on small churches without a real tower handled by caller
        } else {
            // seen end-on: gable front behind the tower
            double gw = naveW;
            s.add(Shape.WALL, new double[]{-gw / 2, 0, -gw / 2, eave, 0, ridge, gw / 2, eave, gw / 2, 0});
            if (type == 4) {
                double R = Rng.clamp(naveW * 0.42, 6, 22);
                double domeTop = twin ? H * 0.92 : H;
                double drumH = Math.max(3, (domeTop - ridge) * 0.25), lantH = Math.max(4, (domeTop - ridge) * 0.22);
                domeDrum(s, Shape.ROOF, 0, ridge - 1, R, drumH, Math.max(R * 0.7, domeTop - ridge - drumH - lantH), lantH, false);
            }
        }

        // ---- tower(s)
        if (type == 4 && !twin) {
            // domed church: the dome is the landmark; a bell tower at the front, and in the south a second one at the
            // far end of a long nave (El Pilar style)
            double th = H * 0.8;
            campanile(s, Shape.TOWER, 0, th, Rng.clamp(th * 0.16, 5, 13), med ? 1 : 0, r);
            if (med && len > 40) campanile(s, Shape.TOWER, dir * (len + towerHalf * 0.2), th * 0.98, Rng.clamp(th * 0.16, 5, 13), 1, r);
            return s;
        }
        double[] txs;
        if (twin) {
            double sep = Math.max(tw * 1.25, naveW * 0.82);
            double vis = len > 4 ? 0.35 : 1.0;  // seen from the side the two towers overlap
            txs = new double[]{-sep * vis / 2, sep * vis / 2};
        } else txs = new double[]{0};
        for (int i = 0; i < txs.length; i++) {
            int role = (twin && i == 0 && len > 4) ? Shape.WALL : Shape.TOWER;   // the rear twin a shade lighter
            double th = twin && txs.length == 2 && i == 0 && len > 4 ? H * 0.985 : H;
            if (twin && type != 1) {
                // twin west front: gable between the towers
                if (i == 0) s.add(Shape.WALL, new double[]{txs[0], 0, txs[0], eave * 1.2, (txs[0] + txs[1]) / 2, ridge * 1.05, txs[1], eave * 1.2, txs[1], 0});
            }
            switch (type) {
                case 1: onionTower(s, role, txs[i], th, tw, r); break;
                case 2: helmTower(s, role, txs[i], th, tw, r); break;
                case 3: campanile(s, role, txs[i], th, tw, st.contains("baroque") || r.chance(0.5) ? 1 : (r.chance(0.5) ? 0 : 2), r); break;
                case 4: campanile(s, role, txs[i], th, tw, 1, r); break;
                default: gothicTower(s, role, txs[i], th, tw, big, r);
            }
        }
        if (twin && type == 4) {
            // twin towers flanking a domed body seen end-on: dome already drawn behind
        }
        return s;
    }

    /** Small chapel: nave with a ridge turret. */
    static Shape chapel(Spec sp) {
        Shape s = new Shape();
        s.flood = true;
        double H = sp.H, L = Rng.clamp(sp.len > 0 ? sp.len : H * 1.1, 8, 26), eave = H * 0.38, ridge = H * 0.62;
        s.add(Shape.WALL, rect(-L / 2, 0, L / 2, eave));
        s.add(Shape.ROOF, new double[]{-L / 2, eave - 0.3, -L / 2 + 1, ridge, L / 2 - 1.5, ridge, L / 2, eave - 0.3});
        double tx = -L / 2 + 2.5;
        s.add(Shape.TOWER, rect(tx - 1.3, ridge - 1, tx + 1.3, ridge + H * 0.12));
        s.add(Shape.TOWER, new double[]{tx - 1.5, ridge + H * 0.12, tx, H, tx + 1.5, ridge + H * 0.12});
        s.add(Shape.HOLE, arch(0, eave * 0.3, 1.4, eave * 0.35));
        return s;
    }

    static Shape monastery(Spec sp) {
        Spec c = copy(sp);
        c.len = Math.max(sp.len, 30);
        Shape s = church(c);
        // long convent wing on the far side
        double W = Rng.clamp(Math.max(sp.w, sp.depth) * 0.9, 40, 140);
        double eave = Rng.clamp(sp.H * 0.28, 9, 16), ridge = eave + 6;
        int dir = -sp.dir;
        double x0 = dir * sp.H * 0.12, x1 = x0 + dir * W * 0.6;
        s.add(Shape.ROOF, new double[]{x0, eave - 0.3, x0, ridge, x1 - dir * 3, ridge, x1, eave - 0.3});
        s.add(Shape.DARK, rect(Math.min(x0, x1), 0, Math.max(x0, x1), eave));
        int nw = (int) Math.max(3, Math.abs(x1 - x0) / 5);
        for (int i = 0; i < nw; i++) {
            double x = x0 + dir * (2.5 + i * (Math.abs(x1 - x0) - 5) / Math.max(1, nw - 1));
            for (int k = 0; k < 2; k++) s.wins.add(new double[]{x, eave * (0.3 + k * 0.35)});
        }
        return s;
    }

    static Spec copy(Spec a) {
        Spec b = new Spec();
        b.H = a.H; b.w = a.w; b.depth = a.depth; b.len = a.len; b.dir = a.dir; b.style = a.style; b.region = a.region; b.towers = a.towers;
        b.ruin = a.ruin; b.cathedral = a.cathedral; b.notable = a.notable; b.roofShape = a.roofShape; b.towerRoof = a.towerRoof; b.castleType = a.castleType;
        b.towerType = a.towerType; b.construction = a.construction; b.roofH = a.roofH; b.r = a.r; b.dpPerM = a.dpPerM;
        return b;
    }

    static Shape mosque(Spec sp) {
        Shape s = new Shape();
        s.flood = true;
        double H = sp.H;
        double W = Rng.clamp(sp.w > 0 ? sp.w : H * 1.2, 20, 90);
        double bodyH = Rng.clamp(H * 0.3, 8, 25);
        s.add(Shape.WALL, rect(-W / 2, 0, W / 2, bodyH));
        double R = W * 0.26;
        s.add(Shape.ROOF, rect(-R * 1.05, bodyH - 0.2, R * 1.05, bodyH + R * 0.25));
        s.add(Shape.TOWER, dome(0, bodyH + R * 0.25 - 0.1, R, R * 0.95, 0.25));
        s.add(Shape.TOWER, rect(-0.25, bodyH + R * 1.2, 0.25, bodyH + R * 1.55));
        for (double x : new double[]{-W * 0.38, W * 0.38}) s.add(Shape.ROOF, dome(x, bodyH - 0.2, W * 0.1, W * 0.09, 0.2));
        for (int k = -1; k <= 1; k += 2) {
            double mx = k * (W / 2 + 2.5), mw = Rng.clamp(H * 0.05, 2, 4.5);
            s.add(Shape.TOWER, new double[]{mx - mw / 2, 0, mx - mw / 2, H * 0.78, mx - mw * 0.8, H * 0.79, mx - mw * 0.8, H * 0.8,
                    mx - mw / 2, H * 0.8, mx - mw / 2, H * 0.88, mx, H, mx + mw / 2, H * 0.88, mx + mw / 2, H * 0.8, mx + mw * 0.8, H * 0.8,
                    mx + mw * 0.8, H * 0.79, mx + mw / 2, H * 0.78, mx + mw / 2, 0});
        }
        for (int i = 0; i < 5; i++) s.add(Shape.HOLE, arch(-W * 0.3 + i * W * 0.15, bodyH * 0.25, 1.8, bodyH * 0.35));
        return s;
    }

    /** Synagogue / temple / generic domed civic building. */
    static Shape domedHall(Spec sp) {
        Shape s = new Shape();
        s.flood = true;
        double H = sp.H, W = Rng.clamp(sp.w > 0 ? sp.w : H * 1.3, 16, 70), bodyH = H * 0.5;
        s.add(Shape.WALL, rect(-W / 2, 0, W / 2, bodyH));
        s.add(Shape.ROOF, new double[]{-W / 2 - 0.5, bodyH, -W / 2 + 2, bodyH + 2.5, W / 2 - 2, bodyH + 2.5, W / 2 + 0.5, bodyH});
        double R = W * 0.22;
        s.add(Shape.TOWER, rect(-R, bodyH, R, bodyH + H * 0.12));
        s.add(Shape.TOWER, dome(0, bodyH + H * 0.12 - 0.1, R, H - bodyH - H * 0.12 - 1.5, 0.15));
        s.add(Shape.TOWER, rect(-0.3, H - 2, 0.3, H));
        for (int i = 0; i < 4; i++) s.add(Shape.HOLE, arch(-W * 0.3 + i * W * 0.2, bodyH * 0.25, 1.8, bodyH * 0.35));
        return s;
    }

    // ------------------------------------------------------------------ castles and palaces

    static Shape castle(Spec sp) {
        Shape s = new Shape();
        s.flood = true;
        Rng r = sp.r;
        double H = sp.H;
        boolean romantic = sp.style.contains("romanesque") || sp.style.contains("gothic") || sp.style.contains("historic") || sp.notable && sp.H > 50;
        double W = romantic ? Rng.clamp(sp.w > 0 ? sp.w * 0.6 : H * 1.2, H * 0.9, H * 1.4)
                : Rng.clamp(sp.w > 0 ? sp.w : H * 2.2, H * 1.2, Math.max(H * 1.3, Math.min(140, H * 3.5)));
        double wallH = H * (romantic ? 0.17 : 0.3);
        // curtain wall with crenellations (front, darkest)
        s.add(Shape.DARK, rect(-W / 2, 0, W / 2, wallH));
        if (!sp.ruin) crenels(s, Shape.DARK, -W / 2, W / 2, wallH, Math.max(1.0, W / 40), Math.max(1.0, H * 0.035));
        else for (double x = -W / 2; x < W / 2; x += W / 9) s.add(Shape.DARK, rect(x, wallH - 1, x + W / 18, wallH + r.range(-wallH * 0.4, wallH * 0.2)));
        // palas: long hall with a steep roof (lighter, behind)
        double px0 = -W * 0.42, px1 = W * 0.18, pe = H * (romantic ? 0.56 : 0.5), pr = pe + (romantic ? H * 0.2 : H * 0.16);
        s.add(Shape.WALL, rect(px0, 0, px1, pe));
        if (!sp.ruin) s.add(Shape.ROOF, new double[]{px0 - 0.5, pe - 0.3, px0 + 2, pr, px1 - 2, pr, px1 + 0.5, pe - 0.3});
        else crenels(s, Shape.WALL, px0, px1, pe, 2, 1.5);
        int nw = (int) Math.max(2, (px1 - px0) / 6);
        for (int i = 0; i < nw; i++) {
            double x = px0 + 3 + i * (px1 - px0 - 6) / Math.max(1, nw - 1);
            s.add(Shape.HOLE, rect(x - 0.7, pe * 0.55, x + 0.7, pe * 0.55 + 2.2));
            s.wins.add(new double[]{x, pe * 0.75});
        }
        // keep (Bergfried): tallest
        double kw = Rng.clamp(W * 0.13, 7, 15), kx = W * 0.3;
        double kTop = H * (sp.ruin ? 0.85 : (romantic ? 0.8 : 0.86));
        s.add(Shape.TOWER, rect(kx - kw / 2, 0, kx + kw / 2, kTop));
        if (sp.ruin) {
            s.add(Shape.TOWER, new double[]{kx - kw / 2, kTop - 0.1, kx - kw / 2, kTop + 2, kx - kw * 0.1, kTop + 0.6, kx + kw * 0.2, kTop + 2.5, kx + kw / 2, kTop + 1, kx + kw / 2, kTop - 0.1});
        } else if (romantic || r.chance(0.5)) {
            s.add(Shape.TOWER, rect(kx - kw * 0.56, kTop - H * 0.02, kx + kw * 0.56, kTop));
            s.add(Shape.ROOF, new double[]{kx - kw * 0.56, kTop - 0.1, kx, H, kx + kw * 0.56, kTop - 0.1});
        } else {
            s.add(Shape.TOWER, rect(kx - kw * 0.58, kTop - H * 0.03, kx + kw * 0.58, kTop));
            crenels(s, Shape.TOWER, kx - kw * 0.58, kx + kw * 0.58, kTop, kw * 0.12, H * 0.04);
        }
        s.add(Shape.HOLE, rect(kx - 0.6, kTop * 0.7, kx + 0.6, kTop * 0.7 + 2.4));
        // round corner towers with conical roofs
        double[] cxs = romantic ? new double[]{-W / 2, -W * 0.08, W / 2} : new double[]{-W / 2, W / 2};
        for (int i = 0; i < cxs.length; i++) {
            double cw = Rng.clamp(W * (romantic ? 0.075 : 0.1), 4, 10), th = H * (romantic ? 0.5 + 0.12 * i : 0.45);
            s.add(Shape.WALL, rect(cxs[i] - cw / 2, 0, cxs[i] + cw / 2, th));
            if (!sp.ruin) {
                double rh = romantic ? cw * 2.2 : cw * 1.2;
                s.add(Shape.ROOF, new double[]{cxs[i] - cw * 0.62, th - 0.2, cxs[i] - cw * 0.06, th + rh, cxs[i] + cw * 0.06, th + rh, cxs[i] + cw * 0.62, th - 0.2});
            }
            s.add(Shape.HOLE, rect(cxs[i] - 0.5, th * 0.6, cxs[i] + 0.5, th * 0.6 + 1.8));
        }
        if (romantic) {
            // slender stair turret
            double sx = -W * 0.22, sw = Rng.clamp(W * 0.05, 2.5, 5);
            s.add(Shape.WALL, rect(sx - sw / 2, pe - 2, sx + sw / 2, H * 0.78));
            s.add(Shape.ROOF, new double[]{sx - sw * 0.6, H * 0.78 - 0.2, sx, H * 0.95, sx + sw * 0.6, H * 0.78 - 0.2});
        }
        return s;
    }

    static Shape palace(Spec sp) {
        Shape s = new Shape();
        s.flood = true;
        Rng r = sp.r;
        double H = sp.H, W = Rng.clamp(sp.w > 0 ? sp.w : H * 3, H * 1.5, Math.min(180, H * 5));
        double eave = H * 0.5, ridge = H * 0.68;
        s.add(Shape.WALL, rect(-W / 2, 0, W / 2, eave));
        s.add(Shape.ROOF, new double[]{-W / 2, eave - 0.3, -W / 2 + 3, ridge, W / 2 - 3, ridge, W / 2, eave - 0.3});
        // corner pavilions
        for (int k = -1; k <= 1; k += 2) {
            double cx = k * (W / 2 - W * 0.06), pw = W * 0.12;
            s.add(Shape.DARK, rect(cx - pw / 2, 0, cx + pw / 2, eave * 1.08));
            s.add(Shape.ROOF, new double[]{cx - pw / 2 - 0.3, eave * 1.08 - 0.2, cx - pw * 0.2, ridge * 1.08, cx + pw * 0.2, ridge * 1.08, cx + pw / 2 + 0.3, eave * 1.08 - 0.2});
        }
        // central risalit with a pediment, and a roof turret (dome or onion) rising from the ridge
        double tw = Rng.clamp(W * 0.08, 6, 14), rw = tw * 2.2;
        s.add(Shape.DARK, rect(-rw / 2, 0, rw / 2, eave * 1.1));
        s.add(Shape.DARK, new double[]{-rw / 2 - 0.4, eave * 1.1 - 0.1, 0, eave * 1.1 + rw * 0.22, rw / 2 + 0.4, eave * 1.1 - 0.1});
        double t0 = ridge - 1, t1 = H * 0.8;
        s.add(Shape.TOWER, rect(-tw / 2, t0, tw / 2, t1));
        s.add(Shape.TOWER, rect(-tw * 0.58, t1 - H * 0.02, tw * 0.58, t1));
        if (r.chance(0.5) && !"med".equals(sp.region)) s.add(Shape.TOWER, onion(0, t1 - 0.1, H * 0.95, tw * 0.55));
        else s.add(Shape.TOWER, dome(0, t1 - 0.1, tw * 0.55, H * 0.14, 0.2));
        s.add(Shape.TOWER, rect(-0.25, H * 0.93, 0.25, H));
        s.add(Shape.HOLE, arch(0, t0 + (t1 - t0) * 0.3, tw * 0.3, (t1 - t0) * 0.35));
        int nw = (int) (W / 5);
        for (int i = 0; i < nw; i++) {
            double x = -W / 2 + 2.5 + i * (W - 5) / Math.max(1, nw - 1);
            if (Math.abs(x) < rw * 0.6) continue;
            s.add(Shape.HOLE, rect(x - 0.6, eave * 0.25, x + 0.6, eave * 0.25 + 2.2));
            s.add(Shape.HOLE, rect(x - 0.6, eave * 0.62, x + 0.6, eave * 0.62 + 2.2));
            s.wins.add(new double[]{x, eave * 0.4});
            s.wins.add(new double[]{x, eave * 0.75});
        }
        return s;
    }

    static Shape fort(Spec sp) {
        Shape s = new Shape();
        s.flood = true;
        double H = Math.max(10, sp.H), W = Rng.clamp(sp.w > 0 ? sp.w : 80, 40, 200);
        s.add(Shape.DARK, new double[]{-W / 2 - H * 0.5, 0, -W / 2, H * 0.55, W / 2, H * 0.55, W / 2 + H * 0.5, 0});
        crenels(s, Shape.DARK, -W / 2, W / 2, H * 0.55, 1.6, 1.2);
        s.add(Shape.TOWER, rect(-W * 0.1, 0, W * 0.05, H));
        crenels(s, Shape.TOWER, -W * 0.1, W * 0.05, H, 1.2, 1.4);
        return s;
    }

    // ------------------------------------------------------------------ civic towers

    static Shape townHall(Spec sp) {
        Shape s = new Shape();
        s.flood = true;
        Rng r = sp.r;
        double H = sp.H, W = Rng.clamp(sp.w > 0 ? sp.w : H * 1.0, 18, 70);
        double eave = Rng.clamp(H * 0.36, 10, 22), ridge = eave + Rng.clamp(W * 0.25, 6, 14);
        s.add(Shape.WALL, rect(-W / 2, 0, W / 2, eave));
        if (r.chance(0.5)) { // stepped gable ends
            s.add(Shape.ROOF, new double[]{-W / 2, eave - 0.2, -W / 2 + 4, ridge, W / 2 - 4, ridge, W / 2, eave - 0.2});
        } else {
            s.add(Shape.ROOF, new double[]{-W / 2, eave - 0.2, -W / 2 + 1, ridge, W / 2 - 1, ridge, W / 2, eave - 0.2});
        }
        double tw = Rng.clamp(H * 0.15, 5, 10);
        double tx = W * 0.18 * (r.chance(0.5) ? 1 : -1);
        if (r.chance(0.5) && !"med".equals(sp.region)) onionTower(s, Shape.TOWER, tx, H, tw, r);
        else campanile(s, Shape.TOWER, tx, H, tw, "med".equals(sp.region) ? 0 : 2, r);
        int nw = (int) (W / 4.5);
        for (int i = 0; i < nw; i++) {
            double x = -W / 2 + 2.2 + i * (W - 4.4) / Math.max(1, nw - 1);
            if (Math.abs(x - tx) < tw * 0.7) continue;
            s.add(Shape.HOLE, rect(x - 0.6, eave * 0.55, x + 0.6, eave * 0.55 + 2));
            s.wins.add(new double[]{x, eave * 0.6});
        }
        return s;
    }

    static Shape cityGate(Spec sp) {
        Shape s = new Shape();
        s.flood = true;
        double H = sp.H, tw = Rng.clamp(H * 0.32, 7, 13), hb = H * 0.66;
        s.add(Shape.TOWER, rect(-tw / 2, 0, tw / 2, hb));
        s.add(Shape.ROOF, new double[]{-tw / 2 - 0.6, hb - 0.2, -tw * 0.04, H, tw * 0.04, H, tw / 2 + 0.6, hb - 0.2});
        s.add(Shape.ROOF, rect(-tw * 0.12, hb + (H - hb) * 0.35, tw * 0.12, hb + (H - hb) * 0.48));
        s.add(Shape.HOLE, arch(0, 0, tw * 0.42, H * 0.12));
        s.add(Shape.HOLE, rect(-tw * 0.22, hb * 0.6, -tw * 0.12, hb * 0.6 + 2.2));
        s.add(Shape.HOLE, rect(tw * 0.12, hb * 0.6, tw * 0.22, hb * 0.6 + 2.2));
        s.wins.add(new double[]{0, hb * 0.8});
        return s;
    }

    static Shape bellTower(Spec sp) {
        Shape s = new Shape();
        s.flood = true;
        double tw = Rng.clamp(sp.H * 0.2, 4.5, 14);
        if ("med".equals(sp.region)) campanile(s, Shape.TOWER, 0, sp.H, tw, sp.r.chance(0.5) ? 1 : 0, sp.r);
        else if (sp.roofShape.contains("onion") || sp.style.contains("baroque")) onionTower(s, Shape.TOWER, 0, sp.H, tw, sp.r);
        else if (sp.style.contains("romanesque")) helmTower(s, Shape.TOWER, 0, sp.H, tw, sp.r);
        else gothicTower(s, Shape.TOWER, 0, sp.H, tw, false, sp.r);
        return s;
    }

    /** Generic tower: defensive (crenellated), observation (platform), or with a pyramid roof. */
    static Shape tower(Spec sp) {
        Shape s = new Shape();
        double H = sp.H;
        Rng r = sp.r;
        if ("observation".equals(sp.towerType) || sp.construction.contains("steel") || sp.construction.contains("concrete")) {
            double bw = Rng.clamp(H * 0.12, 3, 9);
            s.add(Shape.TOWER, new double[]{-bw / 2, 0, -bw * 0.35, H * 0.86, bw * 0.35, H * 0.86, bw / 2, 0});
            s.add(Shape.TOWER, rect(-bw * 0.85, H * 0.85, bw * 0.85, H * 0.9));
            s.add(Shape.TOWER, rect(-bw * 0.7, H * 0.9, -bw * 0.62, H * 0.95));
            s.add(Shape.TOWER, rect(bw * 0.62, H * 0.9, bw * 0.7, H * 0.95));
            s.add(Shape.TOWER, rect(-bw * 0.75, H * 0.95, bw * 0.75, H * 0.965));
            s.add(Shape.TOWER, rect(-0.2, H * 0.965, 0.2, H));
            return s;
        }
        s.flood = true;
        double tw = Rng.clamp(H * 0.28, 5, 14);
        if ("defensive".equals(sp.towerType) || r.chance(0.35)) {
            s.add(Shape.TOWER, rect(-tw / 2, 0, tw / 2, H * 0.93));
            s.add(Shape.TOWER, rect(-tw * 0.56, H * 0.88, tw * 0.56, H * 0.93));
            crenels(s, Shape.TOWER, -tw * 0.56, tw * 0.56, H * 0.93, tw * 0.1, H * 0.07);
        } else {
            double hb = H * 0.72;
            s.add(Shape.TOWER, rect(-tw / 2, 0, tw / 2, hb));
            s.add(Shape.ROOF, new double[]{-tw / 2 - 0.5, hb - 0.2, -tw * 0.03, H, tw * 0.03, H, tw / 2 + 0.5, hb - 0.2});
        }
        s.add(Shape.HOLE, arch(0, H * 0.55, tw * 0.18, H * 0.08));
        s.wins.add(new double[]{0, H * 0.3});
        return s;
    }

    // ------------------------------------------------------------------ modern structures

    static Shape tvTower(Spec sp) {
        Shape s = new Shape();
        double H = sp.H;
        double wb = Rng.clamp(H * 0.075, 8, 24), wt = wb * 0.5;
        double podY = H * 0.6, podH = Rng.clamp(H * 0.07, 6, 26), podW = Rng.clamp(H * 0.15, 14, 44);
        double mastY = podY + podH * 1.9;
        Poly p = new Poly();
        p.p(-wb * 0.85, 0).p(-wb * 0.55, H * 0.04).p(-wb / 2, H * 0.08).p(-wt / 2, podY);
        p.p(-wt / 2, mastY).p(-wt * 0.32, mastY).p(-wt * 0.32, mastY + H * 0.05).p(-0.9, mastY + H * 0.05).p(-0.55, H * 0.97).p(-0.25, H);
        p.mirror(0);
        s.add(Shape.TOWER, p);
        // pod: lower cone, glazed band, upper deck
        s.add(Shape.TOWER, new double[]{-wt * 0.5, podY - podH * 0.4, -podW / 2, podY + podH * 0.25, podW / 2, podY + podH * 0.25, wt * 0.5, podY - podH * 0.4});
        s.add(Shape.TOWER, rect(-podW / 2, podY + podH * 0.25, podW / 2, podY + podH * 0.62));
        s.add(Shape.TOWER, new double[]{-podW / 2 - 0.5, podY + podH * 0.62, -podW * 0.42, podY + podH * 0.95, podW * 0.42, podY + podH * 0.95, podW / 2 + 0.5, podY + podH * 0.62});
        s.add(Shape.TOWER, rect(-podW * 0.3, podY + podH * 0.95, podW * 0.3, podY + podH * 1.35));
        s.add(Shape.GLASS, rect(-podW / 2 + 0.8, podY + podH * 0.33, podW / 2 - 0.8, podY + podH * 0.54));
        // a second smaller deck lower down on big towers
        if (H > 200) s.add(Shape.TOWER, rect(-wt * 0.9, podY - podH * 2.2, wt * 0.9, podY - podH * 1.9));
        s.reds.add(new double[]{0, H});
        s.reds.add(new double[]{0, mastY + H * 0.05});
        s.reds.add(new double[]{-podW / 2, podY + podH * 0.95});
        s.reds.add(new double[]{podW / 2, podY + podH * 0.95});
        s.label = "tv";
        return s;
    }

    static Shape latticeTower(Spec sp) {
        Shape s = new Shape();
        double H = sp.H;
        boolean wide = H > 150 && sp.notable;
        double wb = H * (wide ? 0.38 : 0.14), wt = Math.max(1.5, H * 0.02), top = H * 0.9;
        // legs as a trapezoid with truss openings
        Poly p = new Poly();
        if (wide) p.p(-wb / 2, 0).p(-wb * 0.28, H * 0.12).p(-wb * 0.16, H * 0.3).p(-wt * 1.4, H * 0.62).p(-wt / 2, top).p(-0.3, H);
        else p.p(-wb / 2, 0).p(-wt / 2, top).p(-0.3, H);
        p.mirror(0);
        s.add(Shape.TOWER, p);
        if (wide) s.add(Shape.HOLE, new double[]{-wb * 0.36, 0, -wb * 0.2, H * 0.1, wb * 0.2, H * 0.1, wb * 0.36, 0, wb * 0.15, 0, 0, H * 0.06, -wb * 0.15, 0});
        int n = 9;
        for (int i = 0; i < n; i++) {
            double y0 = top * i / n, y1 = top * (i + 1) / n;
            double w0 = halfAt(p, y0, wb, wt, top, wide) * 0.72, w1 = halfAt(p, y1, wb, wt, top, wide) * 0.72;
            if (w1 < 0.8) continue;
            s.add(Shape.HOLE, new double[]{-w0 * 0.85, y0 + (y1 - y0) * 0.12, 0, y1 - (y1 - y0) * 0.1, w0 * 0.85, y0 + (y1 - y0) * 0.12});
        }
        s.reds.add(new double[]{0, H});
        s.reds.add(new double[]{0, top * 0.6});
        return s;
    }

    private static double halfAt(Poly p, double y, double wb, double wt, double top, boolean wide) {
        double f = y / top;
        if (!wide) return (wb / 2) * (1 - f) + (wt / 2) * f;
        return Math.max(wt / 2, (wb / 2) * Math.pow(1 - f, 2.2));
    }

    static Shape chimney(Spec sp) {
        Shape s = new Shape();
        double H = sp.H, wb = Rng.clamp(H * 0.075, 3, 14), wt = wb * 0.5;
        s.add(Shape.TOWER, new double[]{-wb / 2, 0, -wt / 2, H * 0.95, -wt * 0.56, H * 0.955, -wt * 0.56, H, wt * 0.56, H, wt * 0.56, H * 0.955, wt / 2, H * 0.95, wb / 2, 0});
        s.add(Shape.ROOF, rect(-wt * 0.55, H * 0.9, wt * 0.55, H * 0.915));
        if (H > 60) s.reds.add(new double[]{0, H * 0.985});
        if (H > 120) s.reds.add(new double[]{0, H * 0.6});
        // a low boiler house at the foot
        double bw = Math.max(wb * 2.6, 14);
        s.add(Shape.DARK, rect(wb * 0.2, 0, wb * 0.2 + bw, Math.min(14, H * 0.14)));
        s.wins.add(new double[]{wb * 0.2 + bw * 0.5, Math.min(14, H * 0.14) * 0.5});
        return s;
    }

    static Shape waterTower(Spec sp) {
        Shape s = new Shape();
        Rng r = sp.r;
        double H = sp.H;
        if (sp.notable || r.chance(0.5)) {
            // historic: masonry shaft, wide tank storey, steep roof with lantern
            double sw = Rng.clamp(H * 0.3, 6, 14), tw = sw * 1.45;
            s.add(Shape.TOWER, rect(-sw / 2, 0, sw / 2, H * 0.62));
            s.add(Shape.TOWER, new double[]{-sw / 2, H * 0.6, -tw / 2, H * 0.66, -tw / 2, H * 0.8, tw / 2, H * 0.8, tw / 2, H * 0.66, sw / 2, H * 0.6});
            s.add(Shape.ROOF, new double[]{-tw / 2 - 0.5, H * 0.8, -tw * 0.12, H * 0.94, tw * 0.12, H * 0.94, tw / 2 + 0.5, H * 0.8});
            s.add(Shape.ROOF, rect(-tw * 0.08, H * 0.94, tw * 0.08, H * 0.985));
            s.add(Shape.ROOF, new double[]{-tw * 0.1, H * 0.985, 0, H, tw * 0.1, H * 0.985});
            for (int i = 0; i < 3; i++) s.add(Shape.HOLE, arch(-tw * 0.3 + i * tw * 0.3, H * 0.7, tw * 0.09, H * 0.05));
            s.add(Shape.HOLE, arch(0, H * 0.3, sw * 0.18, H * 0.08));
            s.flood = sp.notable;
        } else {
            // modern: slim shaft and a goblet tank
            double sw = Rng.clamp(H * 0.16, 3, 8), tw = Rng.clamp(H * 0.55, 10, 30);
            s.add(Shape.TOWER, rect(-sw / 2, 0, sw / 2, H * 0.7));
            s.add(Shape.TOWER, new double[]{-sw / 2, H * 0.66, -tw / 2, H * 0.84, -tw / 2, H * 0.94, -tw * 0.3, H, tw * 0.3, H, tw / 2, H * 0.94, tw / 2, H * 0.84, sw / 2, H * 0.66});
            s.reds.add(new double[]{0, H});
        }
        return s;
    }

    static Shape lighthouse(Spec sp) {
        Shape s = new Shape();
        double H = sp.H, wb = Rng.clamp(H * 0.24, 4, 12), wt = wb * 0.62, g = H * 0.76;
        s.add(Shape.TOWER, new double[]{-wb / 2, 0, -wt / 2, g, wt / 2, g, wb / 2, 0});
        s.add(Shape.TOWER, rect(-wt * 0.78, g - 0.2, wt * 0.78, g + H * 0.025));
        s.add(Shape.TOWER, rect(-wt * 0.72, g + H * 0.025, -wt * 0.66, g + H * 0.06));
        s.add(Shape.TOWER, rect(wt * 0.66, g + H * 0.025, wt * 0.72, g + H * 0.06));
        s.add(Shape.TOWER, rect(-wt * 0.72, g + H * 0.055, wt * 0.72, g + H * 0.065));
        double lw = wt * 0.46;
        s.add(Shape.TOWER, rect(-lw, g + H * 0.025, lw, g + H * 0.15));
        s.add(Shape.GLASS, rect(-lw * 0.8, g + H * 0.04, lw * 0.8, g + H * 0.135));
        s.add(Shape.TOWER, dome(0, g + H * 0.148, lw * 1.08, H * 0.06, 0.3));
        s.add(Shape.TOWER, rect(-0.2, g + H * 0.2, 0.2, H));
        s.add(Shape.HOLE, rect(-0.5, H * 0.35, 0.5, H * 0.35 + 1.6));
        s.add(Shape.HOLE, rect(-0.5, H * 0.58, 0.5, H * 0.58 + 1.6));
        s.glows.add(new double[]{0, g + H * 0.09, 9});
        s.label = "lighthouse";
        return s;
    }

    /** Skyscraper: setbacks and a stepped crown for art deco towers, otherwise one of several modern tops. */
    static Shape skyscraper(Spec sp) {
        Shape s = new Shape();
        Rng r = sp.r;
        double H = sp.H;
        double roof = !Double.isNaN(sp.roofH) && sp.roofH > H * 0.6 && sp.roofH < H ? sp.roofH : H;
        double W = sp.w > 0 ? sp.w : H * r.range(0.13, 0.22);
        W = Rng.clamp(W, Math.max(16, H * 0.09), Math.max(20, Math.min(80, H * 0.3)));
        boolean deco = sp.style.contains("artdeco") || sp.style.contains("neo") || sp.style.contains("gothic");
        boolean spire = roof < H - 3;
        double[][] tiers;
        int v = r.nextInt(6);
        if (deco) tiers = new double[][]{{1.0, 0.2}, {0.84, 0.62}, {0.7, 0.8}, {0.54, 0.9}, {0.4, 0.96}, {0.28, 1.0}};
        else if (v == 2) tiers = new double[][]{{1.0, 0.58}, {0.78, 1.0}};
        else if (v == 4) tiers = new double[][]{{1.0, 0.3}, {0.86, 0.86}, {0.7, 1.0}};
        else tiers = new double[][]{{1.0, 1.0}};
        double y = 0;
        double mull = Math.max(4.5, 2.4 / Math.max(0.05, sp.dpPerM));
        for (int i = 0; i < tiers.length; i++) {
            double w = W * tiers[i][0], y1 = roof * tiers[i][1];
            boolean last = i == tiers.length - 1;
            double split = -w / 2 + w * 0.6;
            if (last && !deco && v == 1) { // chamfered top
                double c = w * 0.18;
                s.add(Shape.WALL, new double[]{-w / 2, y, -w / 2, y1 - c, -w / 2 + c, y1, split, y1, split, y});
                s.add(Shape.DARK, new double[]{split - 0.05, y, split - 0.05, y1, w / 2 - c, y1, w / 2, y1 - c, w / 2, y});
            } else if (last && !deco && v == 3) { // slanted top
                double c = w * 0.45;
                s.add(Shape.WALL, new double[]{-w / 2, y, -w / 2, y1, split, y1 - c * 0.6, split, y});
                s.add(Shape.DARK, new double[]{split - 0.05, y, split - 0.05, y1 - c * 0.6, w / 2, y1 - c, w / 2, y});
            } else if (last && !deco && v == 5) { // pyramidal cap
                double c = Math.min(w * 0.9, roof * 0.12);
                s.add(Shape.WALL, rect(-w / 2, y, split, y1 - c));
                s.add(Shape.DARK, rect(split - 0.05, y, w / 2, y1 - c));
                s.add(Shape.WALL, new double[]{-w / 2, y1 - c - 0.05, 0, y1, 0, y1 - c - 0.05});
                s.add(Shape.DARK, new double[]{0, y1 - c - 0.05, 0, y1, w / 2, y1 - c - 0.05});
            } else {
                s.add(Shape.WALL, rect(-w / 2, y, split, y1));
                s.add(Shape.DARK, rect(split - 0.05, y, w / 2, y1));
            }
            // faint mullions on the lit face
            for (double mx = -w / 2 + mull * 0.6; mx < split - mull * 0.4; mx += mull)
                s.add(Shape.SHADE, rect(mx, y + 2, mx + Math.max(0.35, 0.45 / Math.max(0.05, sp.dpPerM)), y1 - 2));
            y = y1 - 0.05;
        }
        double topW = W * tiers[tiers.length - 1][0];
        if (deco) {
            // stepped crown of arches above the last setback
            double cy = roof;
            for (int k = 0; k < 4; k++) {
                double w = topW * (0.9 - k * 0.2), h = (H - roof) > 3 ? (H - roof) * 0.09 : roof * 0.018;
                s.add(Shape.WALL, dome(0, cy - 0.1, w / 2, h, 0.6));
                s.add(Shape.HOLE, rect(-w * 0.25, cy + h * 0.2, -w * 0.15, cy + h * 0.6));
                s.add(Shape.HOLE, rect(w * 0.15, cy + h * 0.2, w * 0.25, cy + h * 0.6));
                cy += h * 0.8;
            }
            if (spire) s.add(Shape.WALL, new double[]{-topW * 0.08, cy - 0.1, -0.35, H, 0.35, H, topW * 0.08, cy - 0.1});
            else s.add(Shape.WALL, rect(-0.6, cy - 0.1, 0.6, cy + roof * 0.04));
            s.reds.add(new double[]{0, spire ? H : cy + roof * 0.04});
        } else {
            if (v == 0 || v == 2 || v == 4) {
                // mechanical penthouse, maybe antennas
                s.add(Shape.DARK, rect(-topW * 0.3, roof - 0.1, topW * 0.2, roof + Math.min(6, H * 0.02)));
                if (!spire && r.chance(0.5)) {
                    s.add(Shape.DARK, rect(-topW * 0.18, roof, -topW * 0.18 + 0.8, roof + H * 0.06));
                    s.reds.add(new double[]{-topW * 0.18 + 0.4, roof + H * 0.06});
                }
            }
            if (spire) {
                double sw = Math.max(1.4, topW * 0.1);
                s.add(Shape.WALL, new double[]{-sw / 2, roof - 0.1, -sw * 0.18, H, sw * 0.18, H, sw / 2, roof - 0.1});
                s.reds.add(new double[]{0, H});
            } else s.reds.add(new double[]{0, roof + Math.min(6, H * 0.02)});
        }
        s.label = "tall";
        return s;
    }

    static Shape highrise(Spec sp) {
        Shape s = new Shape();
        double H = sp.H, W = Rng.clamp(sp.w > 0 ? sp.w : H * 0.6, 14, 60);
        double split = -W / 2 + W * 0.58;
        s.add(Shape.WALL, rect(-W / 2, 0, split, H));
        s.add(Shape.DARK, rect(split - 0.05, 0, W / 2, H));
        s.add(Shape.DARK, rect(-W * 0.2, H - 0.1, W * 0.1, H + 2.5));
        for (double yy = 3; yy < H - 2; yy += 3.2)
            for (double xx = -W / 2 + 1.5; xx < W / 2 - 1; xx += 3.2) s.wins.add(new double[]{xx, yy});
        s.label = "tall";
        return s;
    }
}
