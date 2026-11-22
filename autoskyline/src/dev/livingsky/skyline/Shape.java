package dev.livingsky.skyline;

import java.util.ArrayList;
import java.util.List;

/**
 * A landmark silhouette in metres: x to the right of the anchor (main tower axis), y up from the ground.
 * Polygons are grouped by tonal role, like the hand-drawn Ulm Minster (roof lighter, walls mid, fronts darker).
 */
final class Shape {
    static final int ROOF = 0;   // receding roofs and back masses: lightest
    static final int WALL = 1;   // walls, clerestory: mid
    static final int DARK = 2;   // closest parts (aisles, buttresses, shaded faces): darkest
    static final int TOWER = 3;  // main tower / main body
    static final int HOLE = 4;   // openings: sky seen through by day, dark by night
    static final int GLASS = 5;  // glazing that is lit at night (lantern, TV pod)
    static final int SHADE = 6;  // faint light facets/mullions by day (not drawn at night)
    static final int NROLES = 7;

    @SuppressWarnings("unchecked")
    final List<double[]>[] parts = new List[NROLES];
    /** Red aviation lights (x, y) in metres. */
    final List<double[]> reds = new ArrayList<>();
    /** Candidate lit windows (x, y) in metres. */
    final List<double[]> wins = new ArrayList<>();
    /** Warm glows (x, y, radius dp). */
    final List<double[]> glows = new ArrayList<>();
    boolean flood;
    String label = "";

    Shape() { for (int i = 0; i < NROLES; i++) parts[i] = new ArrayList<>(); }

    void add(int role, double[] poly) { if (poly != null && poly.length >= 6) parts[role].add(poly); }

    void add(int role, Poly p) { add(role, p.toArray()); }

    void addAll(Shape o, double dx) {
        for (int r = 0; r < NROLES; r++) for (double[] p : o.parts[r]) add(r, shift(p, dx));
        for (double[] v : o.reds) reds.add(new double[]{v[0] + dx, v[1]});
        for (double[] v : o.wins) wins.add(new double[]{v[0] + dx, v[1]});
        for (double[] v : o.glows) glows.add(new double[]{v[0] + dx, v[1], v[2]});
    }

    static double[] shift(double[] p, double dx) {
        double[] q = p.clone();
        for (int i = 0; i < q.length; i += 2) q[i] += dx;
        return q;
    }

    double minX() {
        double m = Double.MAX_VALUE;
        for (int r = 0; r < NROLES; r++) if (r != HOLE && r != GLASS && r != SHADE) for (double[] p : parts[r]) for (int i = 0; i < p.length; i += 2) m = Math.min(m, p[i]);
        return m == Double.MAX_VALUE ? 0 : m;
    }

    double maxX() {
        double m = -Double.MAX_VALUE;
        for (int r = 0; r < NROLES; r++) if (r != HOLE && r != GLASS && r != SHADE) for (double[] p : parts[r]) for (int i = 0; i < p.length; i += 2) m = Math.max(m, p[i]);
        return m == -Double.MAX_VALUE ? 0 : m;
    }

    double maxY() {
        double m = 0;
        for (int r = 0; r < NROLES; r++) for (double[] p : parts[r]) for (int i = 1; i < p.length; i += 2) m = Math.max(m, p[i]);
        return m;
    }

    /** Highest point (m) of the solid silhouette over the x-interval [x0, x1] (metres). */
    double topOver(double x0, double x1) {
        double best = 0;
        for (int r = 0; r < NROLES; r++) {
            if (r == HOLE || r == GLASS || r == SHADE) continue;
            for (double[] p : parts[r]) {
                int n = p.length / 2;
                for (int i = 0; i < n; i++) {
                    double ax = p[2 * i], ay = p[2 * i + 1], bx = p[(2 * i + 2) % p.length], by = p[(2 * i + 3) % p.length];
                    if (ax >= x0 && ax <= x1) best = Math.max(best, ay);
                    // edge crossing the interval ends
                    for (double xx : new double[]{x0, x1}) {
                        if ((ax - xx) * (bx - xx) < 0) {
                            double t = (xx - ax) / (bx - ax);
                            best = Math.max(best, ay + (by - ay) * t);
                        }
                    }
                }
            }
        }
        return best;
    }

    /** Growable polygon point list. */
    static final class Poly {
        private double[] a = new double[32];
        private int n;

        Poly p(double x, double y) {
            if (n + 2 > a.length) a = java.util.Arrays.copyOf(a, a.length * 2);
            a[n++] = x; a[n++] = y;
            return this;
        }

        int size() { return n / 2; }

        double[] toArray() { return java.util.Arrays.copyOf(a, n); }

        /** Append mirrored copy of the points (x -> 2*cx - x) in reverse order, to build symmetric outlines. */
        Poly mirror(double cx) {
            int m = n;
            for (int i = m - 2; i >= 0; i -= 2) p(2 * cx - a[i], a[i + 1]);
            return this;
        }
    }
}
