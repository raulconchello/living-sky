package dev.livingsky;

import android.content.Context;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Hand-drawn skyline registry (assets/skylines/<id>.json + <id>_day.svg + <id>_night.svg) and the selection rule:
 * the nearest place whose distance is within its radius_km; otherwise the automatic skyline.
 */
final class Places {
    private Places() {}

    static final class Place {
        String id, name, label;
        double lat, lon, radiusKm;
        dev.livingsky.render.DaylightGrade.Hints hints;
        @Override public String toString() { return id; }
    }

    /** What to draw for a location. */
    static final class Skyline {
        String day, night;       // SVG documents (viewBox 0 0 379.4 192.4)
        String source;           // "place:<id>", "auto:osm", "auto:procedural"
        Place place;             // when hand-drawn
        double distanceKm = -1;
        double heroGlow = 1;
        dev.livingsky.render.DaylightGrade.Hints hints; // daylight grade hints (null: generic by region)
    }

    /** The optional "daylight" block of <id>.json (palette, hero range + colours, accent and water boxes in dp). */
    static dev.livingsky.render.DaylightGrade.Hints hints(JSONObject o) {
        dev.livingsky.render.DaylightGrade.Hints h = dev.livingsky.render.DaylightGrade.Hints.generic(o.optString("country", ""));
        JSONObject d = o.optJSONObject("daylight");
        if (d == null) return h;
        h.pal = d.optString("palette", h.pal);
        org.json.JSONArray hr = d.optJSONArray("hero");
        if (hr != null && hr.length() == 2) h.hero = new double[]{hr.optDouble(0), hr.optDouble(1)};
        if (d.has("heroColor")) h.heroC = d.optString("heroColor");
        if (d.has("heroTopColor")) h.heroTopC = d.optString("heroTopColor");
        org.json.JSONArray w = d.optJSONArray("water");
        if (w != null && w.length() == 4) h.water = new double[]{w.optDouble(0), w.optDouble(1), w.optDouble(2), w.optDouble(3)};
        h.softHero = d.optBoolean("softHero", true);
        h.heroModel = d.has("heroModel") ? d.optString("heroModel") : null;
        JSONObject t = d.optJSONObject("towers");
        if (t != null) {
            dev.livingsky.render.DaylightGrade.Towers T = new dev.livingsky.render.DaylightGrade.Towers();
            double[][] lit = colours(t, "lit"), sh = colours(t, "shade"), ov = colours(t, "overcast");
            if (lit != null && sh != null && ov != null) {
                T.litOld = lit[0]; T.litNew = lit[1]; T.litWhite = lit[2];
                T.shadeOld = sh[0]; T.shadeNew = sh[1]; T.shadeWhite = sh[2];
                T.ovcOld = ov[0]; T.ovcNew = ov[1]; T.ovcWhite = ov[2];
                T.mosaic = dev.livingsky.render.DaylightGrade.hex(t.optString("mosaic", "#d9a95c"));
                T.mosaicK = t.optDouble("mosaicK", T.mosaicK);
                T.kY = t.optDouble("openings", T.kY);
                if (t.has("silhouette")) T.silhouette = dev.livingsky.render.DaylightGrade.hex(t.optString("silhouette"));
                h.towers = T;
            }
        }
        h.heroBottom = d.optDouble("heroBottom", h.heroBottom);
        org.json.JSONArray ac = d.optJSONArray("accents");
        if (ac != null) for (int i = 0; i < ac.length(); i++) {
            JSONObject a = ac.optJSONObject(i);
            if (a == null) continue;
            org.json.JSONArray b = a.optJSONArray("box");
            if (b == null || b.length() != 4) continue;
            dev.livingsky.render.DaylightGrade.Accent x = new dev.livingsky.render.DaylightGrade.Accent();
            x.b = new double[]{b.optDouble(0), b.optDouble(1), b.optDouble(2), b.optDouble(3)};
            x.cc = dev.livingsky.render.DaylightGrade.chroma(dev.livingsky.render.DaylightGrade.hex(a.optString("color", "#808080")));
            x.k = a.optDouble("k", 1);
            x.l = a.optDouble("l", 0);
            h.acc.add(x);
        }
        return h;
    }

    /** "key": ["#old", "#new", "#white"] -> three colours, or null. */
    private static double[][] colours(JSONObject o, String key) {
        org.json.JSONArray a = o.optJSONArray(key);
        if (a == null || a.length() != 3) return null;
        double[][] r = new double[3][];
        for (int i = 0; i < 3; i++) r[i] = dev.livingsky.render.DaylightGrade.hex(a.optString(i, "#808080"));
        return r;
    }

    private static List<Place> cache;

    static synchronized List<Place> all(Context c) {
        if (cache != null) return cache;
        List<Place> r = new ArrayList<>();
        try {
            String[] files = c.getAssets().list("skylines");
            if (files != null) for (String f : files) {
                if (!f.endsWith(".json")) continue;
                String id = f.substring(0, f.length() - 5);
                if (!exists(c, "skylines/" + id + "_day.svg") || !exists(c, "skylines/" + id + "_night.svg")) continue;
                try {
                    JSONObject o = new JSONObject(read(c, "skylines/" + f));
                    Place p = new Place();
                    p.id = o.optString("id", id);
                    if (!p.id.equals(id)) p.id = id;
                    p.name = o.optString("name", id);
                    p.label = o.optString("label", p.name);
                    p.lat = o.getDouble("lat");
                    p.lon = o.getDouble("lon");
                    p.radiusKm = o.optDouble("radius_km", 10);
                    try { p.hints = hints(o); } catch (Exception ignored) { }
                    r.add(p);
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
        Collections.sort(r, (a, b) -> a.id.compareTo(b.id));
        cache = r;
        return r;
    }

    static Place byId(Context c, String id) {
        for (Place p : all(c)) if (p.id.equals(id)) return p;
        return null;
    }

    /** Nearest hand-drawn place containing (lat, lon) within its radius, or null. */
    static Place nearest(Context c, double lat, double lon, double[] distOut) {
        Place best = null;
        double bd = Double.MAX_VALUE;
        for (Place p : all(c)) {
            double d = Locator.distanceKm(lat, lon, p.lat, p.lon);
            if (d <= p.radiusKm && d < bd) { bd = d; best = p; }
        }
        if (distOut != null) distOut[0] = best == null ? -1 : bd;
        return best;
    }

    static Skyline handDrawn(Context c, Place p, double dist) throws Exception {
        Skyline s = new Skyline();
        s.day = read(c, "skylines/" + p.id + "_day.svg");
        s.night = read(c, "skylines/" + p.id + "_night.svg");
        s.source = "place:" + p.id;
        s.place = p;
        s.distanceKm = dist;
        s.heroGlow = 1;
        s.hints = p.hints;
        return s;
    }

    /** The selection rule. */
    static Skyline select(Context c, double lat, double lon, String placeName, boolean network) {
        double[] d = new double[1];
        Place p = nearest(c, lat, lon, d);
        if (p != null) {
            try {
                return handDrawn(c, p, d[0]);
            } catch (Exception ignored) {
            }
        }
        return AutoSkylines.get(c, lat, lon, placeName, network);
    }

    static boolean exists(Context c, String path) {
        try (InputStream in = c.getAssets().open(path)) {
            return in != null;
        } catch (Exception e) {
            return false;
        }
    }

    static String read(Context c, String path) throws Exception {
        try (InputStream in = c.getAssets().open(path)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return new String(bo.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
