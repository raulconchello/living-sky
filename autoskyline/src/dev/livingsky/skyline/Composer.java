package dev.livingsky.skyline;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Turns {@link PlaceData} into the day and night skyline SVGs.
 *
 * Model: a virtual camera stands Dv metres in front of the hero landmark, looking along azimuth theta. Landmark
 * positions are projected laterally (x = X0 + kx * u); heights use one vertical scale s (dp per metre) so landmarks
 * stay true relative to each other. Terrain behind the town is sampled along camera rays and becomes the far layers.
 */
final class Composer {
    static final double W = 379.4, HY = 134, HX0 = 168, HX1 = 268, LOW_TOP = 114, HERO_TOP = 28;
    static final double HERO_BASE = 138.5, HIDDEN_X = 272, HERO_CX = 218;
    static final double F = 300;                // focal length (dp) for terrain projection
    static final double LOW_MAX = HY - LOW_TOP - 1.2;   // max height above the horizon in the low zone (dp)
    static final double[] PD = {300, 900, 1800, 3000, 5000, 8000, 13000, 20000, 32000};
    static final int PX_N = 29;                 // profile azimuth samples
    static final double PX_0 = -10, PX_STEP = 14;
    static final int N_BASES = 12, N_FG = 27;

    final PlaceData d;
    final long seed;
    final Rng rng;

    String region = "central";
    boolean alpine, coastal, lakeside, riverside, metro;
    double urban = 0.5;
    double waterLevel = Double.NaN;
    int urbanHint = -1;

    final List<Landmark> cands = new ArrayList<>();
    Landmark hero;
    double theta;            // radians
    double s = 1, kx = 0.12, Dv = 2500, X0 = HERO_CX, f = F;
    /** Quantised camera used for terrain sampling, so small layout changes can reuse a cached profile. */
    double pDv = 2500, pX0 = HERO_CX, pF = F;
    double refElev = Double.NaN;
    final List<Placed> placed = new ArrayList<>();

    // terrain
    boolean haveProfile;
    double[] hFar, hNear;    // horizon heights (dp, before exaggeration) per profile x
    boolean[] backWater;
    boolean frontWater;

    static final class Placed {
        Landmark l;
        Shape shape;
        double x, baseY, lift, haze, w;
        boolean heroSet;
        Shapes.Spec spec;
    }

    Composer(PlaceData d) {
        this.d = d;
        long la = Math.round(d.lat * 1000), lo = Math.round(d.lon * 1000);
        this.seed = Rng.hash(la, lo, 7);
        this.rng = new Rng(seed);
    }

    // =================================================================== planning

    void plan() {
        classifyPlace();
        for (Landmark l : d.landmarks) {
            if (l.kind == Landmark.Kind.BRIDGE) continue;
            double[] en = Sources.en(d.lat, d.lon, l.lat, l.lon);
            l.e = en[0]; l.n = en[1];
            double dist = Math.hypot(l.e, l.n);
            l.h = l.estimateHeight();
            if (l.h < 9) continue;
            if (dist > maxDist(l)) continue;
            if (l.isTall() && l.h < 38) continue;
            double notab = Math.log(1 + l.sitelinks) / Math.log(2);
            double reach = 2200 * (1 + notab / 8);   // famous landmarks are worth showing from farther away
            double hs = Double.isNaN(l.estimateHeightRaw()) ? l.h * 0.8 : l.h;   // a guessed height counts less than a measured one
            double sc = l.kindWeight() * (1 + notab) * (0.5 + Math.min(hs, 260) / 45.0) / (1 + Math.pow(dist / reach, 2));
            if (!l.qid.isEmpty()) sc *= 1.15;
            if (l.kind == Landmark.Kind.CHAPEL && l.sitelinks < 3) sc *= 0.4;
            if (l.ruin && l.kind != Landmark.Kind.CASTLE) sc *= 0.5;
            l.score = sc;
            if (sc > 0) cands.add(l);
        }
        Collections.sort(cands, new Comparator<Landmark>() {
            @Override public int compare(Landmark a, Landmark b) { return Double.compare(b.score, a.score); }
        });
        while (cands.size() > 60) cands.remove(cands.size() - 1);
        hero = cands.isEmpty() ? null : cands.get(0);
        chooseView();
        layout();
        pDv = Math.round(Dv / 250) * 250.0;
        pX0 = Math.round(X0 / 4) * 4.0;
        pF = Math.round(f / 10) * 10.0;
    }

    private double maxDist(Landmark l) {
        switch (l.kind) {
            case CASTLE: case PALACE: case TV_TOWER: case LIGHTHOUSE: return 6500;
            case SKYSCRAPER: return 4000;
            case CATHEDRAL: return 4500;
            case HIGHRISE: case CHAPEL: return 2500;
            default: return 3600;
        }
    }

    private void classifyPlace() {
        double lat = d.lat, lon = d.lon;
        if (lon < -30) region = "america";
        else if (lat < 44.3 && lat > 30 && lon > -10 && lon < 42) region = "med";
        else if (lat > 56) region = "north";
        else if (lat >= 45.6 && lat <= 49.2 && lon >= 9.4 && lon <= 17.0) region = "central-south";
        else region = "central";
        // terrain roughness from the survey around the viewer
        double maxAng = surveyMaxAngle();
        alpine = maxAng > 4.5 && lat > 42 && lat < 49 && lon > 4 && lon < 17;
        // water
        for (PlaceData.Water w : d.water) {
            double dist = Sources.dist(d.lat, d.lon, w.lat, w.lon);
            if ("coast".equals(w.type)) coastal = true;
            else if ("lake".equals(w.type) && (Math.max(w.sizeE, w.sizeN) > 900)) lakeside = true;
            else if ("river".equals(w.type) && dist < 2200) riverside = true;
        }
        if (d.survey != null && !Double.isNaN(d.viewerElev)) {
            // water from the elevation survey: sea at ~0 m, or a lake = several near samples at the same lowest level
            double mn = Double.MAX_VALUE;
            int nd = PlaceData.SURVEY_D.length;
            for (int a = 0; a < PlaceData.SURVEY_AZ; a++) for (int k = 0; k < 3; k++) {
                double e = d.survey[a * nd + k];
                if (!Double.isNaN(e)) mn = Math.min(mn, e);
            }
            int flat = 0;
            for (int a = 0; a < PlaceData.SURVEY_AZ; a++) for (int k = 0; k < 3; k++) {
                double e = d.survey[a * nd + k];
                if (!Double.isNaN(e) && e <= mn + 1.0) flat++;
            }
            // lakes are perfectly flat in the DEM: the most common near elevation (within 0.35 m)
            double mode = Double.NaN; int modeN = 0;
            for (int a = 0; a < PlaceData.SURVEY_AZ; a++) for (int k = 0; k < 3; k++) {
                double v = d.survey[a * nd + k];
                if (Double.isNaN(v)) continue;
                int c = 0;
                for (int a2 = 0; a2 < PlaceData.SURVEY_AZ; a2++) for (int k2 = 0; k2 < 3; k2++) {
                    double e = d.survey[a2 * nd + k2];
                    if (!Double.isNaN(e) && Math.abs(e - v) <= 0.35) c++;
                }
                if (c > modeN || (c == modeN && v < mode)) { modeN = c; mode = v; }
            }
            int seaN = 0;
            for (int a = 0; a < PlaceData.SURVEY_AZ; a++) for (int k = 0; k < 3; k++) {
                double e = d.survey[a * nd + k];
                if (!Double.isNaN(e) && e <= 0.6) seaN++;
            }
            if (mn <= 0.6 && (coastal || seaN >= 2)) { coastal = true; waterLevel = 0.6; }
            else if (modeN >= 6 && mode <= mn + 6) { lakeside = true; waterLevel = mode + 0.6; }
            else if (lakeside && mn < d.viewerElev + 3) waterLevel = mn + 1.2;
        }
        if (d.population == 0 && d.placeType.isEmpty()) {
            // nothing known: guess from the number of notable structures around
            int n = 0;
            for (Landmark l : d.landmarks) if (l.sitelinks >= 2) n++;
            urbanHint = n;
        }
        // urbanity
        double u;
        switch (d.placeType) {
            case "city": u = 0.85; break;
            case "town": u = 0.62; break;
            case "suburb": case "borough": case "quarter": u = 0.72; break;
            case "village": u = 0.32; break;
            case "hamlet": u = 0.18; break;
            default: u = d.osmOk ? 0.45 : 0.55;
        }
        if (urbanHint >= 0) u = Rng.clamp(0.25 + urbanHint * 0.025, 0.25, 0.75);
        if (d.population > 0) u = Math.max(u * 0.6, Rng.clamp(Math.log10(d.population) / 6.5, 0.12, 1.0));
        int wdTall = 0;
        for (Landmark l : d.landmarks) if (l.isTall() && !l.fromOsm) wdTall++;
        if (d.tallCount >= 12 || wdTall >= 4) u = Math.max(u, 0.85);
        metro = d.tallCount >= 60 || wdTall >= 10;
        if (metro) u = 1;
        urban = u;
    }

    private double surveyMaxAngle() {
        if (d.survey == null || Double.isNaN(d.viewerElev)) return 0;
        double best = 0;
        int nd = PlaceData.SURVEY_D.length;
        for (int a = 0; a < PlaceData.SURVEY_AZ; a++) best = Math.max(best, surveyAngle(a));
        return best;
    }

    /** Horizon elevation angle (deg) seen from the viewer along survey azimuth index a (rings beyond 1 km). */
    private double surveyAngle(int a) {
        if (d.survey == null || Double.isNaN(d.viewerElev)) return 0;
        int nd = PlaceData.SURVEY_D.length;
        double best = -90;
        for (int k = 1; k < nd; k++) {
            double e = d.survey[a * nd + k];
            if (Double.isNaN(e)) continue;
            double D = PlaceData.SURVEY_D[k];
            double ang = Math.toDegrees(Math.atan((e - d.viewerElev - 20 - D * D / (2 * 6371000.0) * 0.87) / D));
            best = Math.max(best, ang);
        }
        return best < -80 ? 0 : best;
    }

    /** Fraction of near survey samples within +-35 deg of azimuth (deg) that are water. */
    double waterToward(double azDeg) {
        if (d.survey == null || Double.isNaN(waterLevel)) return 0;
        int nd = PlaceData.SURVEY_D.length, hit = 0, tot = 0;
        for (int a = 0; a < PlaceData.SURVEY_AZ; a++) {
            double az = a * 360.0 / PlaceData.SURVEY_AZ;
            double diff = Math.abs(((az - azDeg) % 360 + 540) % 360 - 180);
            if (diff > 35) continue;
            for (int k = 0; k < 3; k++) {
                double e = d.survey[a * nd + k];
                if (Double.isNaN(e)) continue;
                tot++;
                if (e <= waterLevel) hit++;
            }
        }
        return tot == 0 ? 0 : hit / (double) tot;
    }

    private void chooseView() {
        double heroScore = hero == null ? 1 : hero.score;
        double best = -1e18, bestT = 0;
        double dV = hero == null ? 0 : Math.hypot(hero.e, hero.n);
        double bearing = hero == null ? 0 : Math.atan2(hero.e, hero.n);
        int nTop = Math.min(cands.size(), 16);
        for (int ti = 0; ti < 72; ti++) {
            double th = Math.toRadians(ti * 5);
            double fe = Math.sin(th), fn = Math.cos(th), re = Math.cos(th), rn = -Math.sin(th);
            double sc = 0;
            if (hero != null) {
                double Dc = 2500;
                List<double[]> hz = new ArrayList<>();
                for (int i = 0; i < nTop; i++) {
                    Landmark l = cands.get(i);
                    double de = l.e - hero.e, dn = l.n - hero.n;
                    double u = de * re + dn * rn, w = de * fe + dn * fn;
                    if (Dc + w < 400) continue;
                    double alpha = Math.toDegrees(Math.atan2(u, Dc + w));
                    double depthK = 1 / (1 + Math.max(0, w) / 1500.0);   // far behind the hero: small and hazy
                    if (Math.abs(alpha) < 10) hz.add(new double[]{alpha, l.score * (l == hero ? 1 : depthK), l.h});
                    else if (Math.abs(alpha) < 30) sc += 0.12 * l.score * depthK;
                }
                Collections.sort(hz, new Comparator<double[]>() {
                    @Override public int compare(double[] a, double[] b) { return Double.compare(a[0], b[0]); }
                });
                for (int i = 0; i < hz.size(); i++) {
                    double v = hz.get(i)[1];
                    for (int j = 0; j < hz.size(); j++) {
                        if (j == i) continue;
                        double sep = Math.abs(hz.get(i)[0] - hz.get(j)[0]);
                        if (sep < 1.6 && hz.get(j)[1] > v) v *= 0.3 + 0.4 * sep / 1.6;
                    }
                    sc += v;
                }
            }
            // a mountain backdrop is worth a lot
            double tb = 0; int tn = 0;
            for (int a = 0; a < PlaceData.SURVEY_AZ; a++) {
                double az = a * 360.0 / PlaceData.SURVEY_AZ;
                double diff = Math.abs(((az - ti * 5) % 360 + 540) % 360 - 180);
                if (diff <= 30) { tb += Math.max(0, surveyAngle(a)); tn++; }
            }
            if (tn > 0) sc += heroScore * (alpine ? 0.14 : 0.07) * Math.min(tb / tn, 12);
            // water between us and the town (waterfront view), or behind it (sea horizon)
            sc += heroScore * 0.35 * waterToward(ti * 5 + 180);
            sc += heroScore * 0.08 * waterToward(ti * 5);
            // look the way the user would look from where they are
            if (hero != null && dV > 250) sc += heroScore * (dV > 1500 ? 0.9 : 0.55) * Math.max(0, Math.cos(th - bearing)) * Math.min(1, (dV - 250) / 900);
            // prefer light from the south (northern hemisphere): facades seen lit; mild
            sc += heroScore * 0.04 * (0.5 - 0.5 * Math.cos(th)) * (d.lat >= 0 ? 1 : -1);
            sc += heroScore * 0.03 * new Rng(seed + ti).next();
            if (sc > best) { best = sc; bestT = th; }
        }
        theta = bestT;
    }

    // =================================================================== layout

    private Shapes.Spec spec(Landmark l, Rng r) {
        Shapes.Spec sp = new Shapes.Spec();
        sp.H = l.h;
        sp.style = l.style == null ? "" : l.style;
        sp.region = alpine && !"med".equals(region) ? ("central-south".equals(region) ? "central-south" : "alpine") : region;
        sp.towers = l.towers;
        sp.ruin = l.ruin;
        sp.cathedral = l.kind == Landmark.Kind.CATHEDRAL;
        sp.notable = l.sitelinks >= 8;
        sp.roofShape = l.roofShape;
        sp.towerRoof = l.towerRoof;
        if (sp.towerRoof.isEmpty() && l.towers > 0 && l.isChurchLike() && !l.roofShape.isEmpty()) { sp.towerRoof = l.roofShape; sp.roofShape = ""; }  // older caches
        sp.castleType = l.castleType; sp.towerType = l.towerType; sp.construction = l.construction;
        sp.roofH = l.roofHeight;
        sp.dpPerM = s;
        sp.r = r;
        double ce = Math.cos(theta), sn = Math.sin(theta);
        // footprint across / along the view
        double sE = l.sizeE, sN = l.sizeN;
        if (sE <= 0 && sN <= 0) {
            double L = l.isChurchLike() ? l.h * (sp.cathedral ? 1.25 : 0.95) : 0;
            sE = L; sN = L * 0.38;
        }
        sp.w = sE * Math.abs(ce) + sN * Math.abs(sn);
        sp.depth = sE * Math.abs(sn) + sN * Math.abs(ce);
        if (l.isChurchLike()) {
            boolean ew = sE >= sN;
            double L = Math.max(sE, sN), Wd = Math.min(sE, sN);
            double comp = ew ? ce : -sn;          // lateral component of the church axis (east or north)
            sp.len = Math.max(0, L * Math.abs(comp) - Wd * 0.2);
            if (sp.len < L * 0.35) sp.len = 0;
            sp.depth = Wd;
            int dir = comp >= 0 ? 1 : -1;     // nave extends east of a west tower
            if (!Double.isNaN(l.towerDE)) {
                double tu = l.towerDE * ce + l.towerDN * -sn;
                if (Math.abs(tu) > 3) dir = tu > 0 ? -1 : 1;
            }
            sp.dir = dir;
            if (l.towers == 0 && sp.cathedral && !"med".equals(region) && Rng.hash01(l.lat * 1000, l.lon * 1000) < 0.5) sp.towers = 2;
        }
        return sp;
    }

    /** Build the silhouette and normalise it to exactly the landmark's height (true relative heights). */
    static Shape build(Landmark l, Shapes.Spec sp) {
        Shape sh = buildRaw(l, sp);
        double my = sh.maxY();
        if (my > sp.H * 1.001 && my > 0) {
            double k = sp.H / my;
            for (int r = 0; r < Shape.NROLES; r++) for (double[] p : sh.parts[r]) for (int i = 1; i < p.length; i += 2) p[i] *= k;
            for (double[] v : sh.reds) v[1] *= k;
            for (double[] v : sh.wins) v[1] *= k;
            for (double[] v : sh.glows) v[1] *= k;
        }
        return sh;
    }

    private static Shape buildRaw(Landmark l, Shapes.Spec sp) {
        switch (l.kind) {
            case CATHEDRAL: case CHURCH: return Shapes.church(sp);
            case CHAPEL: return sp.H > 24 ? Shapes.church(sp) : Shapes.chapel(sp);
            case MONASTERY: return Shapes.monastery(sp);
            case MOSQUE: return Shapes.mosque(sp);
            case SYNAGOGUE: case TEMPLE: return Shapes.domedHall(sp);
            case CASTLE: return Shapes.castle(sp);
            case PALACE: return sp.style.contains("gothic") || sp.style.contains("romanesque") ? Shapes.castle(sp) : Shapes.palace(sp);
            case FORT: return Shapes.fort(sp);
            case CITY_GATE: return Shapes.cityGate(sp);
            case TOWN_HALL: return Shapes.townHall(sp);
            case BELL_TOWER: return Shapes.bellTower(sp);
            case TV_TOWER: return Shapes.tvTower(sp);
            case LATTICE_TOWER: return Shapes.latticeTower(sp);
            case CHIMNEY: return Shapes.chimney(sp);
            case WATER_TOWER: return Shapes.waterTower(sp);
            case LIGHTHOUSE: return Shapes.lighthouse(sp);
            case SKYSCRAPER: return Shapes.skyscraper(sp);
            case HIGHRISE: return Shapes.highrise(sp);
            default: return Shapes.tower(sp);
        }
    }

    private double liftOf(Landmark l) {
        if (Double.isNaN(l.baseElev) || Double.isNaN(refElev)) return 0;
        double de = l.baseElev - refElev - 12;
        if (de <= 0) return 0;
        return 30 * Math.tanh(de * Math.min(s, 0.9) / 55.0);
    }

    private void layout() {
        placed.clear();
        // town level = median ground under the main candidates (and the viewer): only landmarks clearly above it get a hill
        List<Double> bases = new ArrayList<>();
        if (!Double.isNaN(d.viewerElev)) bases.add(d.viewerElev);
        for (int i = 0; i < Math.min(N_BASES, cands.size()); i++) if (!Double.isNaN(cands.get(i).baseElev)) bases.add(cands.get(i).baseElev);
        Collections.sort(bases);
        refElev = bases.isEmpty() ? Double.NaN : bases.get(bases.size() / 2);
        if (hero == null) { Dv = 2500; kx = F / Dv; X0 = HERO_CX; f = F; return; }
        double fe = Math.sin(theta), fn = Math.cos(theta), re = Math.cos(theta), rn = -Math.sin(theta);
        // hero set: landmarks within a narrow cone of the hero as seen from 2.5 km
        List<Landmark> set = new ArrayList<>();
        List<double[]> uw = new ArrayList<>();
        set.add(hero); uw.add(new double[]{0, 0});
        int maxSet = metro ? 12 : 4;
        for (int i = 1; i < cands.size() && set.size() < maxSet; i++) {
            Landmark l = cands.get(i);
            double de = l.e - hero.e, dn = l.n - hero.n;
            double u = de * re + dn * rn, w = de * fe + dn * fn;
            if (2500 + w < 400) continue;
            double alpha = Math.toDegrees(Math.atan2(u, 2500 + w));
            if (Math.abs(alpha) > 10 || l.score < hero.score * (metro ? 0.08 : 0.16)) continue;
            // the hero must read as the main landmark: a much taller but less notable neighbour stays out of the set
            if (!metro && l.h > hero.h * 1.15 && l.score < hero.score * 0.7) continue;
            set.add(l); uw.add(new double[]{u, w});
        }
        // vertical scale: hero ~82 dp, every set member must fit under the hero zone ceiling
        double maxH = 0;
        for (Landmark l : set) maxH = Math.max(maxH, l.h);
        s = Rng.clamp(82 / hero.h, 0.1, 2.0);
        s = Math.min(s, 100 / maxH);
        for (int it = 0; it < 4; it++) {   // landmarks on hills must still fit under the hero-zone ceiling
            double worst = 0;
            for (Landmark l : set) worst = Math.max(worst, l.h * s + liftOf(l) + (HERO_BASE + 3 - HERO_BASE));
            if (worst <= 103) break;
            s *= 103 / worst;
        }
        // drop set members that become too small to read next to a much taller hero
        for (int i = set.size() - 1; i >= 1; i--) if (set.get(i).h * s < 9) { set.remove(i); uw.remove(i); }

        // build shapes
        List<Placed> ps = new ArrayList<>();
        for (int i = 0; i < set.size(); i++) {
            Landmark l = set.get(i);
            Placed p = new Placed();
            p.l = l; p.heroSet = true; p.w = uw.get(i)[1];
            Rng r = new Rng(seed ^ Rng.hashString(l.qid + l.name + l.kind));
            p.spec = spec(l, r);
            p.shape = build(l, p.spec);
            ps.add(p);
        }
        // lateral scale: fit the tall parts of the set into the hero zone; when the shapes alone are too wide even at
        // the smallest lateral scale, drop the weakest member and try again
        while (true) {
            kx = 0.12;
            double ext = 0;
            for (int iter = 0; iter < 40; iter++) {
                double a = 1e9, b = -1e9;
                for (int i = 0; i < ps.size(); i++) {
                    double x = kx * uw.get(i)[0];
                    a = Math.min(a, x + s * leftOfTall(ps.get(i).shape));
                    b = Math.max(b, x + s * rightOfTall(ps.get(i).shape));
                }
                ext = b - a;
                if (ext <= 92 || kx <= 0.025) break;
                kx = Math.max(0.025, kx * 0.88);
            }
            if (ps.size() == 1) { kx = 0.12; break; }
            if (ext <= 96) break;
            int worst = -1;
            for (int i = 1; i < ps.size(); i++) if (worst < 0 || ps.get(i).l.score < ps.get(worst).l.score) worst = i;
            ps.remove(worst); uw.remove(worst); set.remove(worst);
        }
        // the hero must stay readable: members whose towers overlap the hero's towers leave the set
        for (int i = ps.size() - 1; i >= 1; i--) {
            double hl = s * leftOfTall(ps.get(0).shape), hr = s * rightOfTall(ps.get(0).shape);
            double x = kx * uw.get(i)[0];
            double ml = x + s * leftOfTall(ps.get(i).shape), mr = x + s * rightOfTall(ps.get(i).shape);
            double ov = Math.min(hr, mr) - Math.max(hl, ml);
            if (ov > 0.2 * Math.min(hr - hl, mr - ml) && !(metro && ps.get(i).l.h < hero.h * 0.8)) {
                // keep it if it clearly rises above the hero where they overlap (a castle on the hill behind a church)
                double ox0 = Math.max(hl, ml), ox1 = Math.min(hr, mr);
                double heroTop = s * ps.get(0).shape.topOver(ox0 / s, ox1 / s) + liftOf(hero);
                double memTop = s * ps.get(i).shape.topOver((ox0 - x) / s, (ox1 - x) / s) + liftOf(ps.get(i).l);
                if (memTop < heroTop + 12) { ps.remove(i); uw.remove(i); set.remove(i); }
            }
        }
        Dv = Rng.clamp(F / kx, 1500, 12000);
        f = Dv * kx;
        // horizontal placement: centre the set's silhouette extents in the hero zone
        double a = 1e9, b = -1e9;
        for (int i = 0; i < ps.size(); i++) {
            double x = kx * uw.get(i)[0];
            Shape sh = ps.get(i).shape;
            // weight by height so long low naves can run into the hidden zone instead of pushing the tower off-centre
            a = Math.min(a, x + s * leftOfTall(sh));
            b = Math.max(b, x + s * rightOfTall(sh));
        }
        X0 = HERO_CX - (a + b) / 2;
        // a nave running left into the low zone is drawn on the other side of its tower instead (readability wins)
        for (int i = 0; i < ps.size(); i++) {
            Placed p = ps.get(i);
            double x = X0 + kx * uw.get(i)[0];
            if (p.spec.len > 4 && p.spec.dir < 0 && x + s * p.shape.minX() < HX0) {
                double yTop = baseFor(uw.get(i)[1]) - liftOf(p.l) - s * p.shape.topOver(p.shape.minX() - 1, (HX0 - x) / s);
                if (yTop < LOW_TOP + 0.6) {
                    p.spec.dir = 1;
                    p.spec.r = new Rng(seed ^ Rng.hashString(p.l.qid + p.l.name + p.l.kind));
                    p.shape = build(p.l, p.spec);
                }
            }
        }
        // re-centre after any flips
        a = 1e9; b = -1e9;
        for (int i = 0; i < ps.size(); i++) {
            double x = kx * uw.get(i)[0];
            Shape sh = ps.get(i).shape;
            // weight by height so long low naves can run into the hidden zone instead of pushing the tower off-centre
            a = Math.min(a, x + s * leftOfTall(sh));
            b = Math.max(b, x + s * rightOfTall(sh));
        }
        X0 = HERO_CX - (a + b) / 2;
        // the low zone left of x=168 must stay below y=114: shift the set right (the hero's towers stay inside the hero
        // zone); if that is not enough, drop the member that intrudes
        for (int iter = 0; iter < 200; iter++) {
            int bad = -1;
            for (int i = 0; i < ps.size() && bad < 0; i++) {
                Placed p = ps.get(i);
                double x = X0 + kx * uw.get(i)[0];
                if (x + s * p.shape.minX() >= HX0) continue;
                double xl = (HX0 - x) / s;   // metres left of the axis that fall in the low zone
                double yTop = baseFor(uw.get(i)[1]) - liftOf(p.l) - s * p.shape.topOver(p.shape.minX() - 1, xl);
                if (yTop < LOW_TOP + 0.6) bad = i;
            }
            if (bad < 0) break;
            if (X0 + s * rightOfTall(ps.get(0).shape) + 2 <= HX1 - 2) { X0 += 2; continue; }
            if (bad == 0) s *= 0.95;   // the hero itself is too wide: draw everything a little smaller
            else { ps.remove(bad); uw.remove(bad); set.remove(bad); }
            // re-centre the smaller set
            a = 1e9; b = -1e9;
            for (int i = 0; i < ps.size(); i++) {
                double x = kx * uw.get(i)[0];
                a = Math.min(a, x + s * leftOfTall(ps.get(i).shape));
                b = Math.max(b, x + s * rightOfTall(ps.get(i).shape));
            }
            X0 = HERO_CX - (a + b) / 2;
        }
        List<Double> ws = new ArrayList<>();
        for (double[] v : uw) ws.add(v[1]);
        Collections.sort(ws);
        for (int i = 0; i < ps.size(); i++) {
            Placed p = ps.get(i);
            p.x = X0 + kx * uw.get(i)[0];
            p.lift = liftOf(p.l);
            p.baseY = baseFor(p.w);
            p.haze = p.l == hero ? 0 : Rng.clamp(0.06 + 0.12 * Math.max(0, p.w) / 1000.0, 0.04, 0.3);
            if (metro && p.l != hero && ws.size() > 1) p.haze = 0.04 + 0.3 * ws.indexOf(p.w) / (ws.size() - 1.0);
            placed.add(p);
        }
        // secondary landmarks outside the hero set: small ones may decorate the low town, others go to the hidden side
        int nLow = 0, nHidden = 0, nHeroExtra = 0;
        for (int i = 1; i < cands.size(); i++) {
            Landmark l = cands.get(i);
            if (set.contains(l)) continue;
            double de = l.e - hero.e, dn = l.n - hero.n;
            double u = de * re + dn * rn, w = de * fe + dn * fn;
            if (Dv + w < 300) continue;
            double x = X0 + kx * u * Dv / Math.max(300, Dv + w);
            if (x < -20 || x > W + 20) continue;
            Rng r = new Rng(seed ^ Rng.hashString(l.qid + l.name + l.kind));
            Shape sh = build(l, spec(l, r));
            double lift = liftOf(l);
            double top = sh.maxY() * s + lift;
            double left = x + s * sh.minX(), right = x + s * sh.maxX();
            Placed p = new Placed();
            p.l = l; p.shape = sh; p.x = x; p.lift = lift; p.w = w; p.baseY = baseFor(w);
            p.haze = Rng.clamp(0.12 + 0.15 * Math.max(0, w) / 1500.0, 0.1, 0.35);
            double ceil = (p.baseY - HY);
            if (right < HX0 - 1) {
                if (top + ceil > LOW_MAX || top < 5 || nLow >= 5) continue;
                if (overlapsPlaced(left, right, 0.5)) continue;
                placed.add(p); nLow++;
            } else if (left > HIDDEN_X) {
                if (nHidden >= 4 || top > 100) continue;
                placed.add(p); nHidden++;
            } else if (left >= HX0 && right <= HX1 + 30) {
                if (nHeroExtra >= (metro ? 10 : 2) || top > 102 || top < 8) continue;
                if (overlapsPlaced(x + s * leftOfTall(sh), x + s * rightOfTall(sh), metro ? 0.85 : 0.15)) continue;
                placed.add(p); nHeroExtra++;
            }
        }
        // draw far things first; the hero always on top of its neighbours
        final Landmark h0 = hero;
        Collections.sort(placed, new Comparator<Placed>() {
            @Override public int compare(Placed a, Placed b) {
                if (a.l == h0) return 1;
                if (b.l == h0) return -1;
                return Double.compare(b.w, a.w);
            }
        });
    }

    private boolean overlapsPlaced(double left, double right, double maxFrac) {
        for (Placed q : placed) {
            double ql = q.x + s * leftOfTall(q.shape), qr = q.x + s * rightOfTall(q.shape);
            double ov = Math.min(right, qr) - Math.max(left, ql);
            if (ov > 0 && ov > maxFrac * Math.min(right - left, qr - ql)) return true;
        }
        return false;
    }

    /** Leftmost x (m) of the parts taller than half the shape (towers, not naves). */
    private static double leftOfTall(Shape sh) {
        double H = sh.maxY(), x0 = sh.minX(), x1 = sh.maxX();
        for (double x = x0; x < x1; x += Math.max(0.5, (x1 - x0) / 80)) if (sh.topOver(x, x + 0.5) > H * 0.6) return x;
        return x0;
    }

    private static double rightOfTall(Shape sh) {
        double H = sh.maxY(), x0 = sh.minX(), x1 = sh.maxX();
        for (double x = x1; x > x0; x -= Math.max(0.5, (x1 - x0) / 80)) if (sh.topOver(x - 0.5, x) > H * 0.6) return x;
        return x1;
    }

    private double baseFor(double w) {
        return Rng.clamp(HY + (HERO_BASE - HY) * Dv / Math.max(300, Dv + w), HY + 1.5, HERO_BASE + 3);
    }

    // =================================================================== terrain profile (camera rays)

    String profileKey() {
        return String.format(Locale.ROOT, "%.0f/%.0f/%.1f/%.1f/%d", Math.toDegrees(theta), pDv, pX0, pF, hero == null ? 0 : (int) (Math.abs(hero.lat * 1e4) + Math.abs(hero.lon * 1e4)));
    }

    double[] cam() {
        double he = hero == null ? 0 : hero.e, hn = hero == null ? 0 : hero.n;
        return new double[]{he - pDv * Math.sin(theta), hn - pDv * Math.cos(theta)};
    }

    /**
     * Points to sample, most important first (so a partial answer still helps): N_BASES landmark bases, N_FG
     * foreground points (9 azimuths x 3 distances between camera and town), then the PD rings near to far, each ring
     * covering all PX_N azimuths.
     */
    double[][] profileRequest() {
        double[] c = cam();
        List<double[]> pts = new ArrayList<>();
        for (int i = 0; i < N_BASES; i++) {
            Landmark l = i < cands.size() ? cands.get(i) : null;
            pts.add(l == null ? new double[]{d.lat, d.lon} : new double[]{l.lat, l.lon});
        }
        for (int i = 0; i < 9; i++) {
            double x = 20 + i * 30;
            double az = theta + Math.atan((x - pX0) / pF);
            for (double fr : new double[]{0.35, 0.65, 0.85}) {
                double D = pDv * fr;
                pts.add(Sources.offset(d.lat, d.lon, c[0] + D * Math.sin(az), c[1] + D * Math.cos(az)));
            }
        }
        for (double dd : PD) {
            for (int i = 0; i < PX_N; i++) {
                double x = PX_0 + i * PX_STEP;
                double az = theta + Math.atan((x - pX0) / pF);
                double D = pDv + dd;
                pts.add(Sources.offset(d.lat, d.lon, c[0] + D * Math.sin(az), c[1] + D * Math.cos(az)));
            }
        }
        double[] la = new double[pts.size()], lo = new double[pts.size()];
        for (int i = 0; i < la.length; i++) { la[i] = pts.get(i)[0]; lo[i] = pts.get(i)[1]; }
        return new double[][]{la, lo};
    }

    /** Apply elevations sampled at profileRequest() points. Re-runs the layout when landmark bases change. */
    void applyProfile(double[] ev) {
        int nRay = PX_N * PD.length;
        if (ev == null || ev.length < N_BASES + N_FG + nRay) return;
        boolean any = false;
        for (int i = 0; i < Math.min(N_BASES, cands.size()); i++) {
            double e = ev[i];
            if (!Double.isNaN(e)) { cands.get(i).baseElev = e; any = true; }
        }
        if (any) layout();   // lifts depend on base elevations; theta/hero unchanged
        double eye = (Double.isNaN(refElev) ? (Double.isNaN(d.viewerElev) ? 0 : d.viewerElev) : refElev) + 25;
        hFar = new double[PX_N]; hNear = new double[PX_N]; backWater = new boolean[PX_N];
        int valid = 0;
        for (int i = 0; i < PX_N; i++) {
            double bf = -1e9, bn = -1e9;
            for (int k = 0; k < PD.length; k++) {
                double e = ev[N_BASES + N_FG + k * PX_N + i];
                if (Double.isNaN(e)) continue;
                valid++;
                double D = pDv + PD[k];
                double tanA = (e - eye - D * D / (2 * 6371000.0) * 0.87) / D;
                double hdp = pF * tanA;
                bf = Math.max(bf, hdp);
                if (PD[k] <= 3000) bn = Math.max(bn, hdp);
                if (k <= 1 && !Double.isNaN(waterLevel) && e <= waterLevel) backWater[i] = true;
            }
            hFar[i] = bf < -1e8 ? 0 : bf;
            hNear[i] = bn < -1e8 ? 0 : bn;
        }
        int fw = 0, ft = 0;
        for (int j = 0; j < N_FG; j++) {
            double e = ev[N_BASES + j];
            if (Double.isNaN(e)) continue;
            ft++;
            if (!Double.isNaN(waterLevel) && e <= waterLevel) fw++;
        }
        frontWater = ft > 0 && fw >= Math.max(3, ft * 0.25);
        haveProfile = valid > nRay / 3;
    }

    // =================================================================== rendering

    String render(boolean night, String placeName) {
        Svg g = new Svg();
        Rng r = new Rng(seed ^ 0x5eed);
        String farC0 = night ? "#3a2f33" : "#8e8c97", farC1 = night ? "#43363a" : "#9c97a0";
        String gFar = g.linear("gFar", 0, 0, 0, 1, new Object[][]{{0, farC0}, {1, farC1}});
        String gMid = g.linear("gMid", 0, 0, 0, 1, new Object[][]{{0, night ? "#1d1b22" : "#4d505c"}, {1, night ? "#221f25" : "#5a5a64"}});
        String frontC = night ? "#17161c" : "#3a3c46";

        // ---- far terrain (one or two layers)
        double[] far = terrainHeights(true), near = terrainHeights(false);
        boolean twoLayers = false;
        for (int i = 0; i < far.length; i++) if (far[i] > near[i] + 3) { twoLayers = true; break; }
        double tMax = 0;
        for (int i = 0; i < far.length; i++) tMax = Math.max(tMax, far[i]);
        boolean mountains = tMax > 22;
        if (twoLayers) {
            // distant range lighter, nearer ridge a little darker: aerial perspective
            String gFar2 = g.linear("gFarB", 0, 0, 0, 1, new Object[][]{{0, night ? "#30282d" : (mountains ? "#858390" : "#9896a1")}, {1, night ? "#3b3135" : "#9d99a3"}});
            g.path(ridgePath(far), gFar2, night ? 0.85 : (mountains ? 0.88 : 0.62));
            String gNearR = mountains && !night ? g.linear("gRidge", 0, 0, 0, 1, new Object[][]{{0, "#6f6e7a"}, {1, "#83818c"}}) : gFar;
            g.path(ridgePath(near), gNearR, night ? 0.9 : (mountains ? 0.93 : 0.75));
        } else {
            String gm = mountains && !night ? g.linear("gMtn", 0, 0, 0, 1, new Object[][]{{0, "#7a7985"}, {1, "#908d98"}}) : gFar;
            g.path(ridgePath(far), gm, night ? 0.9 : (mountains ? 0.92 : 0.75));
        }
        // sea or lake horizon behind the town
        if (haveProfile && backWater != null) {
            int run0 = -1;
            for (int i = 0; i <= PX_N; i++) {
                boolean wv = i < PX_N && backWater[i];
                if (wv && run0 < 0) run0 = i;
                if (!wv && run0 >= 0) {
                    double x0 = PX_0 + run0 * PX_STEP - 4, x1 = PX_0 + (i - 1) * PX_STEP + 4;
                    if (x1 - x0 > 12) {
                        String gw = g.linear("gSea", 0, 0, 1, 0, night
                                ? new Object[][]{{0, "#e2a26a", 0.0}, {0.5, "#c98d5e", 0.32}, {1, "#e2a26a", 0.0}}
                                : new Object[][]{{0, "#cfc6c2", 0.0}, {0.5, "#d9d0cb", 0.5}, {1, "#cfc6c2", 0.0}});
                        g.rect(Math.max(0, x0), HY - 0.6, Math.min(W, x1) - Math.max(0, x0), 2.2, gw, 1);
                    }
                    run0 = -1;
                }
            }
        }
        // ---- far city haze for big cities
        if (urban > 0.8) {
            Fabric fc = blocks(-4, W + 4, HY + 3, 3, metro ? 16 : 9, 4, 12, seed ^ 0xfa);
            g.path(fc.d, gFar, night ? 0.95 : 0.85);
            if (night) windows(g, fc.wins, 0.25, "#ffcf86", 0.35, 0.7, 11);
        }
        // ---- background towers for skyscraper cities
        if (metro) {
            String gT = night ? "#1f1d24" : g.linear("gTow", 0, 0, 0, 1, new Object[][]{{0, "#6c6c78"}, {1, "#7a7984"}});
            Fabric ft = towers(-4, W + 4, HY + 5, seed ^ 0x707);
            g.path(ft.d, gT, night ? 1 : 0.92);
            if (night) windows(g, ft.wins, 0.3, "#ffcf86", 0.4, 0.8, 5);
        }
        // ---- mid town
        Fabric mid = houses(-4, W + 4, HY + 6, 3 + urban * 1.5, 7 + urban * 3.5, 5, 13, seed ^ 0x4242, false);
        g.path(mid.d, gMid, 1);
        // ---- landmarks (and hills under lifted ones)
        for (Placed p : placed) {
            if (p.lift > 2) drawHill(g, p, night ? "#1d1b22" : "#4f525e", r);
            drawLandmark(g, p, night);
        }
        if (night) windows(g, mid.wins, 0.42, "#ffcf86", 0.55, 0.95, 1);
        // ---- front roofs and trees, or open water in front (lake / sea waterfront view)
        boolean waterFront = frontWater || ((lakeside || coastal) && !Double.isNaN(waterLevel) && waterToward(Math.toDegrees(theta) + 180) > 0.25);
        if (waterFront) {
            String gw = g.linear("gWater", 0, 0, 1, 0, night
                    ? new Object[][]{{0, "#2a2226", 0.5}, {0.5, "#4a3a33", 0.75}, {1, "#2a2226", 0.5}}
                    : new Object[][]{{0, "#9f9ca6", 0.35}, {0.55, "#b9b4b8", 0.6}, {1, "#9f9ca6", 0.35}});
            g.rect(-2, HY + 6.6, W + 4, 12, gw, 1);
            String gl = g.linear("gShore", 0, 0, 1, 0, night
                    ? new Object[][]{{0, "#e2a26a", 0.0}, {0.5, "#e8a970", 0.65}, {1, "#e2a26a", 0.0}}
                    : new Object[][]{{0, "#d6cbc4", 0.0}, {0.5, "#e2d9d2", 0.7}, {1, "#d6cbc4", 0.0}});
            g.rect(0, HY + 7.2, HIDDEN_X, 0.8, gl, 1);
            g.rect(30, HY + 10.2, HIDDEN_X - 60, 1.2, gl, 1);
            if (night) {
                // reflections of the lights in the water
                StringBuilder rf = new StringBuilder();
                Rng rr = new Rng(seed ^ 0xbeef);
                for (int i = 0; i < 40; i++) {
                    double x = rr.range(4, HIDDEN_X - 4), y = HY + 8 + rr.range(0, 3.5);
                    rf.append('M').append(Svg.f(x)).append(',').append(Svg.f(y)).append('h').append(Svg.f(rr.range(1.2, 3.5))).append("v0.5h-").append("0.5Z");
                }
                g.path(rf, "#ffc978", 0.45);
            }
        } else {
            Fabric front = houses(-6, W + 6, HY + 11, 2.5, 6.5 + urban * 1.5, 6, 15, seed ^ 0x777, true);
            g.path(front.d, frontC, 1);
            if (night) windows(g, front.wins, 0.38, "#ffc978", 0.5, 0.95, 2);
            // ---- water glint in front (river)
            if (riverside || lakeside || coastal) {
                String gr = g.linear("gRiver", 0, 0, 1, 0, night
                        ? new Object[][]{{0, "#e2a26a", 0.0}, {0.5, "#e8a970", 0.5}, {1, "#e2a26a", 0.0}}
                        : new Object[][]{{0, "#cfc6c2", 0.0}, {0.5, "#d6cbc4", 0.55}, {1, "#cfc6c2", 0.0}});
                g.rect(0, HY + 10.5, HIDDEN_X, 2.0, gr, 1);
            }
        }
        String comment = "Auto skyline (" + (night ? "night" : "day") + "): " + describe(placeName);
        return g.toSvg(comment);
    }

    // ---- terrain helpers

    /** Terrain height above the horizon (dp) for x samples every 2 dp from -6 to W+6. */
    double[] terrainHeights(boolean farLayer) {
        int n = (int) ((W + 12) / 2) + 1;
        double[] out = new double[n];
        long ts = seed ^ (farLayer ? 0x11 : 0x22);
        if (!haveProfile) {
            double ph = (seed & 1023) / 100.0;
            for (int i = 0; i < n; i++) {
                double x = -6 + i * 2;
                double h = 2 + 3 * (0.5 + 0.5 * Math.sin(x / 47 + ph)) + 1.8 * Math.sin(x / 13 + 1 + ph) * 0.5 + 6 * Math.max(0, Rng.fbm(x / 40, ts, 3) + 0.25);
                if (alpine) h *= 1.8;
                out[i] = farLayer ? h : h * 0.6;
            }
            return capTerrain(out);
        }
        double[] src = farLayer ? hFar : hNear;
        // vertical exaggeration so gentle hills read and mountains fit: the low zone (text above it) gets its own,
        // smaller scale; the hero zone may show mountains up to ~half the hero height
        int iLow = (int) ((HX0 - PX_0) / PX_STEP), iHero0 = (int) ((HX0 - PX_0) / PX_STEP), iHero1 = (int) ((HIDDEN_X - PX_0) / PX_STEP);
        double p90 = percentile(hFar, 0.9, 0, iLow);
        double p90h = percentile(hFar, 0.9, iHero0, iHero1);
        double vLow = 1.8, vHero = 1.8;
        if (p90 > 0.1) vLow = Math.min(vLow, 15 / p90);
        if (p90h > 0.1) vHero = Math.min(vHero, 44 / p90h);
        vLow = Math.max(vLow, 0.05); vHero = Math.max(vHero, vLow);
        for (int i = 0; i < n; i++) {
            double x = -6 + i * 2;
            double fi = (x - PX_0) / PX_STEP;
            int i0 = (int) Math.floor(fi);
            double t = fi - i0;
            double a = src[Math.max(0, Math.min(PX_N - 1, i0 - 1))], b = src[Math.max(0, Math.min(PX_N - 1, i0))],
                    c = src[Math.max(0, Math.min(PX_N - 1, i0 + 1))], e = src[Math.max(0, Math.min(PX_N - 1, i0 + 2))];
            double v = vLow + (vHero - vLow) * Rng.smooth(HX0 - 20, HX0 + 30, x);
            double h = catmull(a, b, c, e, t) * v;
            // ridge detail scaled with the terrain height
            double rough = Math.max(0.4, Math.min(5, Math.abs(h) * 0.18));
            h += rough * Rng.fbm(x / 7.0, ts, 4) + 0.5 * Rng.fbm(x / 2.5, ts + 5, 2) * Math.min(1, Math.abs(h) / 6);
            out[i] = h;
        }
        // flat country: keep a faint distant ridge so the horizon is not empty
        double mx = 0;
        for (double h : out) mx = Math.max(mx, h);
        if (mx < 3) for (int i = 0; i < n; i++) {
            double x = -6 + i * 2;
            out[i] = Math.max(out[i], (farLayer ? 1.6 : 0.8) + 2.2 * (0.5 + 0.5 * Rng.fbm(x / 30.0, ts, 3)));
        }
        if (!farLayer) for (int i = 0; i < n; i++) out[i] = Math.max(-2, out[i]);
        return capTerrain(out);
    }

    private double[] capTerrain(double[] h) {
        for (int i = 0; i < h.length; i++) {
            double x = -6 + i * 2;
            double cap = LOW_MAX - 0.8 + (52 - LOW_MAX) * Rng.smooth(HX0 + 2, HX0 + 34, x);
            if (h[i] > cap * 0.6) h[i] = cap * 0.6 + (cap * 0.4) * Math.tanh((h[i] - cap * 0.6) / (cap * 0.4));
            h[i] = Math.min(h[i], cap);
        }
        return h;
    }

    private static double catmull(double p0, double p1, double p2, double p3, double t) {
        double t2 = t * t, t3 = t2 * t;
        return 0.5 * ((2 * p1) + (-p0 + p2) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2 + (-p0 + 3 * p1 - 3 * p2 + p3) * t3);
    }

    private static double percentile(double[] v, double q, int i0, int i1) {
        if (v == null) return 0;
        i0 = Math.max(0, i0); i1 = Math.min(v.length, i1);
        if (i1 <= i0) return 0;
        double[] c = java.util.Arrays.copyOfRange(v, i0, i1);
        java.util.Arrays.sort(c);
        return c[Math.min(c.length - 1, (int) (q * (c.length - 1)))];
    }

    private String ridgePath(double[] h) {
        StringBuilder sb = new StringBuilder();
        sb.append("M-6,160L-6,").append(Svg.f(HY - h[0]));
        for (int i = 0; i < h.length; i++) sb.append('L').append(Svg.f(-6 + i * 2)).append(',').append(Svg.f(HY - h[i]));
        sb.append("L").append(Svg.f(W + 6)).append(",160Z");
        return sb.toString();
    }

    // ---- landmarks

    /** A wooded hill under a landmark standing on higher ground: short flat top under the building, long slopes. */
    private void drawHill(Svg g, Placed p, String fill, Rng r) {
        double w0 = p.x + s * p.shape.minX(), w1 = p.x + s * p.shape.maxX();
        double c = (w0 + w1) / 2, half = (w1 - w0) * 0.42 + 1;
        double l0 = c - half, r0 = c + half;
        double top = p.baseY - p.lift, foot = HY + 9;
        double spreadL = 8 + p.lift * 2.6, spreadR = 8 + p.lift * 3.4;
        long hs = Rng.hashString(p.l.name + p.l.qid);
        boolean conifers = alpine || "north".equals(region);
        StringBuilder sb = new StringBuilder();
        sb.append("M").append(Svg.f(l0 - spreadL)).append(",").append(Svg.f(foot + 6));
        for (double x = l0 - spreadL; x <= r0 + spreadR + 0.01; x += 1.5) {
            double t = x < l0 ? (l0 - x) / spreadL : (x > r0 ? (x - r0) / spreadR : 0);
            double prof = 1 - Math.pow(Math.min(1, t), 0.8) * (3 - 2 * Math.min(1, t)) / (1 + 0.0 * t);
            double y = foot - (foot - top - 0.4) * Rng.clamp(prof, 0, 1);
            if (t > 0 && t < 1) {
                y += 1.4 * Rng.fbm(x / 5.0, hs, 3) * Math.sin(Math.PI * t);
                // trees on the slopes
                double tb = Rng.hash01(Math.floor(x / 3), hs % 1000);
                if (tb < 0.55) y -= (conifers ? 2.2 : 1.6) * (0.6 + tb) * (0.5 + 0.5 * Math.sin(Math.PI * ((x / 3) % 1)));
            }
            if (x < HX0) y = Math.max(y, LOW_TOP + 1);
            sb.append("L").append(Svg.f(x)).append(",").append(Svg.f(y));
        }
        sb.append("L").append(Svg.f(r0 + spreadR)).append(",").append(Svg.f(foot + 6)).append("Z");
        g.path(sb, fill, 1);
    }

    private void drawLandmark(Svg g, Placed p, boolean night) {
        Shape sh = p.shape;
        double baseY = p.baseY - p.lift;
        double haze = p.haze;
        boolean flood = night && sh.flood && (p.heroSet || p.l.sitelinks >= 3 || p.l.kind == Landmark.Kind.CATHEDRAL);
        String hazeTo = night ? "#3a2f33" : "#8e8c97";
        String[] fill = new String[Shape.NROLES];
        double[] op = {1, 1, 1, 1, 1, 1, 1};
        if (!night) {
            fill[Shape.ROOF] = Svg.mix("#5c5e69", hazeTo, haze);
            fill[Shape.WALL] = Svg.mix("#4a4c57", hazeTo, haze);
            fill[Shape.DARK] = Svg.mix("#3d3f49", hazeTo, haze);
            fill[Shape.TOWER] = Svg.mix("#41434e", hazeTo, haze);
            fill[Shape.HOLE] = "#8d8c96"; op[Shape.HOLE] = 0.38;
            fill[Shape.GLASS] = "#8d8c96"; op[Shape.GLASS] = 0.3;
            fill[Shape.SHADE] = "#a3a2ad"; op[Shape.SHADE] = 0.13;
        } else if (flood) {
            boolean primary = p.heroSet && (p.l == hero || p.l.sitelinks >= 5);
            Object[][] main = primary
                    ? new Object[][]{{0, "#4a3a33"}, {0.45, "#8a6748"}, {0.8, "#c39360"}, {1, "#e0ac6c"}}
                    : new Object[][]{{0, "#3a2f2d"}, {0.5, "#6a5140"}, {0.85, "#93704f"}, {1, "#a98058"}};
            String gm = g.linear("gMin", 0, 0, 0, 1, main);
            String gf = g.linear("gMinF", 0, 0, 0, 1, primary ? new Object[][]{{0, "#5e4636"}, {1, "#a77a4e"}} : new Object[][]{{0, "#4a3a31"}, {1, "#7d5f43"}});
            fill[Shape.ROOF] = "#2a2124";
            fill[Shape.WALL] = gm;
            fill[Shape.DARK] = gf;
            fill[Shape.TOWER] = gm;
            fill[Shape.HOLE] = "#2b2025"; op[Shape.HOLE] = 0.9;
            fill[Shape.GLASS] = "#ffd890"; op[Shape.GLASS] = 0.9;
        } else {
            fill[Shape.ROOF] = "#1f1d24";
            fill[Shape.WALL] = "#24222a";
            fill[Shape.DARK] = "#1c1b21";
            fill[Shape.TOWER] = "#222027";
            fill[Shape.HOLE] = "#17161c"; op[Shape.HOLE] = 0.9;
            fill[Shape.GLASS] = "#ffd28a"; op[Shape.GLASS] = 0.85;
        }
        int[] order = {Shape.ROOF, Shape.WALL, Shape.DARK, Shape.TOWER, Shape.SHADE, Shape.HOLE, Shape.GLASS};
        for (int role : order) {
            if (sh.parts[role].isEmpty() || fill[role] == null) continue;
            StringBuilder sb = new StringBuilder();
            for (double[] poly : sh.parts[role]) Svg.poly(sb, place(poly, p.x, baseY));
            g.path(sb, fill[role], op[role]);
        }
        if (night) {
            // lit windows
            if ("tall".equals(sh.label)) tallWindows(g, p, baseY);
            else if (!sh.wins.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (double[] wv : sh.wins) {
                    if (Rng.hash01(wv[0] + p.x, wv[1] + 3) > 0.55) continue;
                    double x = p.x + wv[0] * s, y = baseY - wv[1] * s;
                    sb.append('M').append(Svg.f(x - 0.7)).append(',').append(Svg.f(y - 0.9)).append("h1.4v1.8h-1.4Z");
                }
                g.path(sb, "#ffcf86", 0.8);
            }
            for (double[] gl : sh.glows) {
                String rg = g.radial("gGlow", new Object[][]{{0, "#ffd890", 0.75}, {0.35, "#f0b070", 0.25}, {1, "#e2a26a", 0.0}});
                double gx = p.x + gl[0] * s, gy = baseY - gl[1] * s, gr = gl[2];
                if (gx < HX0 + gr) gr = Math.max(1.5, Math.min(gr, gy - LOW_TOP - 0.5));
                g.circle(gx, gy, gr, rg, 1);
            }
            for (double[] rd : sh.reds) {
                g.circle(p.x + rd[0] * s, baseY - rd[1] * s - 0.3, 0.75, "#ff5a40", 0.95);
            }
        }
    }

    private double[] place(double[] poly, double x0, double baseY) {
        double[] out = new double[poly.length];
        for (int i = 0; i < poly.length; i += 2) {
            out[i] = x0 + poly[i] * s;
            out[i + 1] = baseY - poly[i + 1] * s;
        }
        return out;
    }

    /** Window grid in dp on the rectangular wall parts of a tall building. */
    private void tallWindows(Svg g, Placed p, double baseY) {
        StringBuilder[] buckets = {new StringBuilder(), new StringBuilder(), new StringBuilder()};
        long hs = Rng.hashString(p.l.qid + p.l.name) ^ seed;
        Rng r = new Rng(hs);
        double litP = 0.2 + 0.15 * r.next();
        for (int role : new int[]{Shape.WALL, Shape.DARK}) {
            for (double[] poly : p.shape.parts[role]) {
                if (poly.length != 8) continue;
                double[] q = place(poly, p.x, baseY);
                double x0 = Math.min(q[0], q[4]), x1 = Math.max(q[0], q[4]), y0 = Math.min(q[1], q[3]), y1 = Math.max(q[1], q[3]);
                if (x1 - x0 < 2.4 || y1 - y0 < 4) continue;
                for (double y = y0 + 1.6; y < y1 - 2.4; y += 2.8) {
                    if (y > HY + 6) break;
                    for (double x = x0 + 0.8; x < x1 - 1.6; x += 2.4) {
                        double h = r.next();
                        if (h > litP) continue;
                        int b = (int) (r.next() * 3);
                        buckets[b].append('M').append(Svg.f(x)).append(',').append(Svg.f(y)).append("h1.2v1.6h-1.2Z");
                    }
                }
            }
        }
        double[] ops = {0.55, 0.75, 0.92};
        for (int b = 0; b < 3; b++) g.path(buckets[b], b == 2 ? "#ffd690" : "#ffcf86", ops[b]);
    }

    // ---- fabric

    static final class Fabric {
        final StringBuilder d = new StringBuilder();
        final List<double[]> wins = new ArrayList<>();
    }

    /** Height scale for the front layer: very low in front of the hero landmark. */
    private double frontScale(double x) {
        return 1 - 0.72 * Rng.smooth(HX0 - 18, HX0 + 2, x) * (1 - Rng.smooth(HX1 + 2, HX1 + 18, x));
    }

    private double capY(double x) {
        return x < HX0 + 1 ? LOW_TOP + 1.2 : HERO_TOP + 2;
    }

    /** Port of sky8.js houses(): one continuous outline of roofs and trees, plus window candidates. */
    Fabric houses(double x0, double x1, double by, double hmin, double hmax, double wmin, double wmax, long sd, boolean front) {
        Fabric fb = new Fabric();
        Rng r = new Rng(sd);
        StringBuilder d = fb.d;
        d.append('M').append(Svg.f(x0)).append(',').append(Svg.f(by + 30)).append('L').append(Svg.f(x0)).append(',').append(Svg.f(by));
        double x = x0;
        boolean med = "med".equals(region), am = "america".equals(region);
        boolean conifers = alpine || "north".equals(region);
        double treeP = (0.1 + 0.18 * (1 - urban)) * (front ? 1.25 : 1);
        double flatP = metro ? 1.0 : med ? 0.42 : (am ? (urban > 0.8 ? 0.85 : 0.55) : 0.06 + 0.1 * urban);
        while (x < x1) {
            double sc = front ? frontScale(x) : 1;
            double w = wmin + r.next() * (wmax - wmin);
            double eave = (hmin + r.next() * (hmax - hmin)) * sc;
            double roof = w * (0.55 + r.next() * 0.45) * (med ? 0.45 : 1) * (alpine ? 0.7 : 1) * sc;
            double kind = r.next();
            boolean heroZone = x > HX0 - 6 && x < HX1 + 6;
            if (kind < treeP && !(front && heroZone)) {
                double th = (eave + roof * 0.6) * (front ? 1 : 1.05);
                if (conifers && r.chance(0.65)) {
                    // conifer: layered spire
                    double cx = x + w / 2, top = by - th * 1.25;
                    pt(d, x + w * 0.12, by - th * 0.15);
                    for (int k = 0; k < 4; k++) {
                        double f0 = k / 4.0, f1 = (k + 1) / 4.0;
                        double wy = by - th * 0.15 - (th * 1.1) * f1;
                        double ww = (w * 0.42) * (1 - f0 * 0.85);
                        pt(d, cx - ww, by - th * 0.15 - (th * 1.1) * f0 - th * 0.05);
                        pt(d, cx - ww * 0.45, wy + th * 0.06);
                    }
                    pt(d, cx, top);
                    for (int k = 3; k >= 0; k--) {
                        double f0 = k / 4.0, f1 = (k + 1) / 4.0;
                        double wy = by - th * 0.15 - (th * 1.1) * f1;
                        double ww = (w * 0.42) * (1 - f0 * 0.85);
                        pt(d, cx + ww * 0.45, wy + th * 0.06);
                        pt(d, cx + ww, by - th * 0.15 - (th * 1.1) * f0 - th * 0.05);
                    }
                    pt(d, x + w * 0.88, by - th * 0.15);
                } else if (med && r.chance(0.5)) {
                    // cypress
                    double cx = x + w / 2, cw = Math.min(w * 0.22, 2.2), top = by - th * 1.35;
                    pt(d, cx - cw, by - th * 0.1);
                    pt(d, cx - cw * 1.1, by - th * 0.6);
                    pt(d, cx - cw * 0.7, by - th * 1.05);
                    pt(d, cx, top);
                    pt(d, cx + cw * 0.7, by - th * 1.05);
                    pt(d, cx + cw * 1.1, by - th * 0.6);
                    pt(d, cx + cw, by - th * 0.1);
                    w = Math.max(cw * 2.4, w * 0.4);
                } else {
                    for (int k = 0; k <= 6; k++) {
                        double a = Math.PI * (1 - k / 6.0);
                        pt(d, x + w / 2 + Math.cos(a) * w / 2 * (0.85 + r.next() * 0.3), by - th * 0.55 - Math.sin(a) * th * 0.5 * (0.8 + r.next() * 0.4));
                    }
                }
            } else if (kind < treeP + flatP) {
                // flat-roofed block (taller in cities); occasional rooftop tank in America
                double hb = eave * (med || am ? 1.1 + urban * 0.9 : 1.1) ;
                if (am && !front) hb *= 1.2 + r.next() * 0.6;
                pt(d, x, by - hb);
                if (am && !front && r.chance(0.3) && w > 6) {
                    double tx = x + 1 + r.next() * (w - 4);
                    pt(d, tx, by - hb); pt(d, tx, by - hb - 2.2); pt(d, tx + 0.5, by - hb - 2.9); pt(d, tx + 1.3, by - hb - 2.9); pt(d, tx + 1.8, by - hb - 2.2); pt(d, tx + 1.8, by - hb);
                } else if (med && r.chance(0.35)) {
                    double tx = x + 0.8 + r.next() * (w - 3);
                    pt(d, tx, by - hb); pt(d, tx, by - hb - 1.6); pt(d, tx + 2, by - hb - 1.6); pt(d, tx + 2, by - hb);
                }
                pt(d, x + w, by - hb);
                int nw = med || am ? 4 : 3;
                for (int k = 0; k < nw; k++) fb.wins.add(new double[]{x + 1.5 + r.next() * (w - 3.5), by - hb + 2.5 + r.next() * Math.max(0.5, hb - 4.5)});
            } else if (kind < treeP + flatP + (1 - treeP - flatP) * 0.52) {
                // eave-on house: long ridge, hipped or straight ends, maybe a chimney
                double rh = roof * 0.55, inset = Math.min(w * 0.3, rh * 0.7);
                pt(d, x, by - eave); pt(d, x + inset, by - eave - rh);
                if (r.chance(0.4)) {
                    double cxh = x + inset + r.next() * Math.max(0.1, w - 2 * inset - 1.6);
                    pt(d, cxh, by - eave - rh); pt(d, cxh, by - eave - rh - 2.2); pt(d, cxh + 1.4, by - eave - rh - 2.2); pt(d, cxh + 1.4, by - eave - rh);
                }
                pt(d, x + w - inset, by - eave - rh); pt(d, x + w, by - eave);
                for (int k = 0; k < 3; k++) fb.wins.add(new double[]{x + 2 + r.next() * (w - 4), by - eave + 2.5 + r.next() * Math.max(0.5, eave - 5)});
            } else {
                // gable-on, steep (or low-pitched in the south / chalets)
                double rp = med || alpine ? 0.45 : 0.85;
                pt(d, x, by - eave); pt(d, x + w / 2, by - eave - roof * rp); pt(d, x + w, by - eave);
                for (int k = 0; k < 2; k++) fb.wins.add(new double[]{x + 2 + r.next() * (w - 4), by - eave + 2.5 + r.next() * Math.max(0.5, eave - 5)});
            }
            x += w;
        }
        pt(d, x, by);
        d.append('L').append(Svg.f(x)).append(',').append(Svg.f(by + 30)).append('Z');
        return fb;
    }

    /** Procedural background towers (setbacks, antennas) for skyscraper cities; kept low in the low zone. */
    Fabric towers(double x0, double x1, double by, long sd) {
        Fabric fb = new Fabric();
        Rng r = new Rng(sd);
        StringBuilder d = fb.d;
        d.append('M').append(Svg.f(x0)).append(',').append(Svg.f(by + 30)).append('L').append(Svg.f(x0)).append(',').append(Svg.f(by));
        double x = x0;
        double heroTop = hero == null ? 40 : hero.h * s;
        while (x < x1) {
            double w = r.range(5, 12);
            boolean low = x + w < HX0 + 4;
            double hmax = low ? LOW_MAX + (by - HY) - 1 : Math.min(heroTop * 0.55, 62);
            double h = low ? r.range(7, hmax) : r.range(14, Math.max(16, hmax));
            if (r.chance(0.18)) h *= 0.5;
            double top = by - h;
            if (!low && h > 24 && r.chance(0.45)) {
                // setback: narrower top section
                double inset = w * r.range(0.15, 0.3), sh = h * r.range(0.15, 0.3);
                pt(d, x, top + sh); pt(d, x + inset, top + sh); pt(d, x + inset, top); pt(d, x + w - inset, top);
                if (r.chance(0.4)) { double ax = x + w / 2; pt(d, ax - 0.3, top); pt(d, ax - 0.3, top - h * 0.12); pt(d, ax + 0.3, top - h * 0.12); pt(d, ax + 0.3, top); }
                pt(d, x + w - inset, top + sh); pt(d, x + w, top + sh);
            } else {
                pt(d, x, top); pt(d, x + w, top);
            }
            for (int k = 0; k < (int) (h / 3); k++) fb.wins.add(new double[]{x + 1 + r.next() * (w - 2.5), top + 2 + r.next() * Math.max(0.5, h - 4)});
            x += w;
            if (r.chance(0.25)) { double gap = r.range(1, 4); pt(d, x, by - 4); pt(d, x + gap, by - 4); x += gap; }
        }
        pt(d, x, by);
        d.append('L').append(Svg.f(x)).append(',').append(Svg.f(by + 30)).append('Z');
        return fb;
    }

    /** Flat-topped blocks (distant city). */
    Fabric blocks(double x0, double x1, double by, double hmin, double hmax, double wmin, double wmax, long sd) {
        Fabric fb = new Fabric();
        Rng r = new Rng(sd);
        StringBuilder d = fb.d;
        d.append('M').append(Svg.f(x0)).append(',').append(Svg.f(by + 30)).append('L').append(Svg.f(x0)).append(',').append(Svg.f(by));
        double x = x0;
        while (x < x1) {
            double w = wmin + r.next() * (wmax - wmin);
            double h = hmin + Math.pow(r.next(), 1.6) * (hmax - hmin);
            pt(d, x, by - h); pt(d, x + w, by - h);
            for (int k = 0; k < 2; k++) fb.wins.add(new double[]{x + 1 + r.next() * (w - 2), by - h + 2 + r.next() * Math.max(0.5, h - 3)});
            x += w;
        }
        pt(d, x, by);
        d.append('L').append(Svg.f(x)).append(',').append(Svg.f(by + 30)).append('Z');
        return fb;
    }

    private void pt(StringBuilder d, double x, double y) {
        y = Math.max(y, capY(x));
        d.append('L').append(Svg.f(x)).append(',').append(Svg.f(y));
    }

    private void windows(Svg g, List<double[]> wins, double p, String color, double op0, double op1, int salt) {
        StringBuilder[] b = {new StringBuilder(), new StringBuilder(), new StringBuilder()};
        for (double[] w : wins) {
            if (Rng.hash01(w[0] + salt, w[1]) >= p) continue;
            if (w[1] < capY(w[0]) + 1) continue;
            int k = (int) Math.min(2, Rng.hash01(w[1], w[0] + salt) * 3);
            b[k].append('M').append(Svg.f(w[0])).append(',').append(Svg.f(w[1])).append("h1.4v1.8h-1.4Z");
        }
        for (int k = 0; k < 3; k++) g.path(b[k], color, op0 + (op1 - op0) * k / 2.0);
    }

    // =================================================================== description

    String describe(String placeName) {
        StringBuilder b = new StringBuilder();
        String name = placeName == null || placeName.isEmpty() ? String.format(Locale.ROOT, "%.4f,%.4f", d.lat, d.lon) : placeName;
        b.append(name).append(": ");
        if (hero == null) b.append("generic low town");
        else {
            b.append("view ").append(compass(Math.toDegrees(theta))).append(" toward ");
            int k = 0;
            List<Placed> byScore = new ArrayList<>(placed);
            Collections.sort(byScore, new Comparator<Placed>() {
                @Override public int compare(Placed a, Placed c) { return Double.compare(c.l.score, a.l.score); }
            });
            for (Placed p : byScore) {
                if (!p.heroSet && k >= 1) continue;
                if (k++ > 0) b.append(", ");
                b.append(p.l.name.isEmpty() ? p.l.kind.name().toLowerCase(Locale.ROOT).replace('_', ' ') : p.l.name)
                        .append(" (").append(Math.round(p.l.h)).append(" m)");
                if (k >= 4) break;
            }
        }
        double mx = 0;
        if (hFar != null) for (double h : hFar) mx = Math.max(mx, h);
        if (mx > 25) b.append("; mountains");
        else if (mx > 4) b.append("; hills");
        if (frontWater || lakeside) b.append(lakeside ? "; lake" : "; water");
        else if (coastal) b.append("; sea");
        else if (riverside) b.append("; river");
        b.append(String.format(Locale.ROOT, "; scale %.2f dp/m", s));
        if (!d.osmOk) b.append("; no OSM");
        if (!d.wdOk) b.append("; no Wikidata");
        if (!haveProfile) b.append("; synthetic hills");
        return b.toString();
    }

    static String compass(double deg) {
        String[] c = {"N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"};
        return c[(int) Math.round(((deg % 360) + 360) % 360 / 22.5) % 16];
    }
}
