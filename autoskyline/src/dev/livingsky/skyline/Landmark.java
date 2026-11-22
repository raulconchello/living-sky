package dev.livingsky.skyline;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** A prominent structure near the place, merged from OpenStreetMap and Wikidata. */
final class Landmark {
    enum Kind {
        CATHEDRAL, CHURCH, CHAPEL, MONASTERY, MOSQUE, SYNAGOGUE, TEMPLE,
        CASTLE, PALACE, FORT, CITY_GATE, TOWN_HALL,
        BELL_TOWER, TOWER, TV_TOWER, LATTICE_TOWER, CHIMNEY, WATER_TOWER, LIGHTHOUSE,
        SKYSCRAPER, HIGHRISE, BRIDGE
    }

    String name = "";
    String qid = "";
    Kind kind = Kind.TOWER;
    double lat, lon;
    /** Total height to the top (spire, antenna), metres; NaN when unknown. */
    double height = Double.NaN;
    /** Roof height for buildings when known (without spire/antenna). */
    double roofHeight = Double.NaN;
    double levels = Double.NaN;
    /** Footprint extents (metres, east-west and north-south), 0 when unknown (node). */
    double sizeE, sizeN;
    int sitelinks;
    /** Lower-case style keywords: gothic, baroque, romanesque, renaissance, neoclassical, artdeco, modern, byzantine, moorish. */
    String style = "";
    String roofShape = "";
    /** roof:shape of the separate tower element (onion, dome, pyramidal, conical...), "" if unknown. */
    String towerRoof = "";
    String construction = "";
    String towerType = "";
    String castleType = "";
    boolean ruin;
    /** Number of towers found (twin-tower churches), 0 = unknown. */
    int towers;
    /** True when {@link #height} was measured on a tower element (not on the church building). */
    boolean towerHeight;
    /** Offset of the (main) tower from the footprint centre, metres east/north; NaN if unknown. */
    double towerDE = Double.NaN, towerDN = Double.NaN;
    /** Ground elevation at the landmark (m), NaN until sampled. */
    double baseElev = Double.NaN;
    boolean fromOsm, fromWd;

    // ---- derived during composition (not serialised)
    double e, n;          // metres east/north of the viewer
    double score;
    double h;             // height used for drawing (m)

    boolean isChurchLike() {
        return kind == Kind.CATHEDRAL || kind == Kind.CHURCH || kind == Kind.CHAPEL || kind == Kind.MONASTERY;
    }

    boolean isTall() { return kind == Kind.SKYSCRAPER || kind == Kind.HIGHRISE; }

    /** Floodlit at night (most historic landmarks are). */
    boolean floodlit() {
        switch (kind) {
            case CATHEDRAL: case CHURCH: case MONASTERY: case MOSQUE: case SYNAGOGUE: case TEMPLE:
            case CASTLE: case PALACE: case FORT: case CITY_GATE: case TOWN_HALL: case BELL_TOWER:
                return true;
            case TOWER:
                return !"communication".equals(towerType) && !"observation".equals(towerType) || sitelinks > 8;
            default:
                return false;
        }
    }

    /** Height from tags only (height, else levels), NaN if neither is known. */
    double estimateHeightRaw() {
        if (!Double.isNaN(height) && height > 3) return height;
        if (!Double.isNaN(levels) && levels > 0) return levels * 3.4 + 2;
        return Double.NaN;
    }

    /** Best height estimate in metres. */
    double estimateHeight() {
        if (!Double.isNaN(height) && height > 3) {
            // a "height" on a church without a separate tower element is often the nave/roof, not the tower
            boolean cat = kind == Kind.CATHEDRAL;
            if (isChurchLike() && !towerHeight && height < (cat ? 45 : 22)) return Math.max(height * 1.8, cat ? 55 : 28);
            return height;
        }
        double big = Math.max(sizeE, sizeN), small = Math.min(sizeE, sizeN);
        if (!Double.isNaN(levels) && levels > 0) {
            double hh = levels * 3.4 + 2;
            if (kind == Kind.SKYSCRAPER || kind == Kind.HIGHRISE) return hh;
            return Math.max(hh, defaultHeight(big, small));
        }
        return defaultHeight(big, small);
    }

    private double defaultHeight(double big, double small) {
        // notable buildings tend to be bigger; a small stable jitter keeps unknown towers from looking cloned
        double nb = Math.log(1 + sitelinks) / Math.log(2);
        double jit = 0.9 + 0.2 * Rng.hash01(lat * 7919.0, lon * 104729.0);
        switch (kind) {
            case CATHEDRAL: return jit * (big > 0 ? Rng.clamp(big * 0.75, 55, 105) : Rng.clamp(58 + 4 * nb, 60, 95));
            case CHURCH: return jit * (big > 0 ? Rng.clamp(big * 0.85 + 8, 28, 72) : Rng.clamp(34 + 4 * nb, 34, 62));
            case MONASTERY: return big > 0 ? Rng.clamp(big * 0.45, 28, 60) : 40;
            case CHAPEL: return 16;
            case MOSQUE: return 32;
            case SYNAGOGUE: return 24;
            case TEMPLE: return 22;
            case CASTLE: return ruin ? 22 : jit * (big > 0 ? Rng.clamp(big * 0.35 + 12, 22, 55) : Rng.clamp(26 + 4 * nb, 26, 62));
            case PALACE: return jit * (big > 0 ? Rng.clamp(big * 0.2 + 14, 18, 40) : Rng.clamp(20 + 2.5 * nb, 20, 40));
            case FORT: return 16;
            case CITY_GATE: return 30;
            case TOWN_HALL: return jit * Rng.clamp(36 + 3 * nb, 36, 58);
            case BELL_TOWER: return jit * Rng.clamp(34 + 3 * nb, 34, 60);
            case TOWER: return "observation".equals(towerType) ? 35 : 30;
            case TV_TOWER: return Rng.clamp(70 + 30 * nb, 70, 220);
            case LATTICE_TOWER: return Rng.clamp(60 + 20 * nb, 60, 160);
            case CHIMNEY: return 80;
            case WATER_TOWER: return 34;
            case LIGHTHOUSE: return 26;
            case SKYSCRAPER: return 120;
            case HIGHRISE: return 45;
            case BRIDGE: return 0;
            default: return 25;
        }
    }

    double kindWeight() {
        switch (kind) {
            case CATHEDRAL: return 1.35;
            case CASTLE: return 1.3;
            case CHURCH: return 1.0;
            case MONASTERY: return 0.9;
            case MOSQUE: return 1.0;
            case TV_TOWER: return 1.15;
            case LIGHTHOUSE: return 1.1;
            case PALACE: return style.contains("gothic") || style.contains("romanesque") ? 1.3 : 0.8;   // romantic "Schloss"
            case BELL_TOWER: return 1.0;
            case CITY_GATE: return 0.85;
            case TOWN_HALL: return 0.9;
            case TOWER: return 0.8;
            case SKYSCRAPER: return 0.95;
            case HIGHRISE: return 0.5;
            case WATER_TOWER: return 0.6;
            case CHIMNEY: return 0.45;
            case LATTICE_TOWER: return 0.5;
            case SYNAGOGUE: case TEMPLE: return 0.7;
            case CHAPEL: return 0.3;
            case FORT: return 0.5;
            case BRIDGE: return 0.0;   // not drawn as a standalone silhouette
            default: return 0.5;
        }
    }

    JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("name", name); o.put("qid", qid); o.put("kind", kind.name());
        o.put("lat", lat); o.put("lon", lon);
        putNum(o, "height", height); putNum(o, "roofHeight", roofHeight); putNum(o, "levels", levels);
        o.put("sizeE", sizeE); o.put("sizeN", sizeN); o.put("sitelinks", sitelinks);
        o.put("style", style); o.put("roofShape", roofShape); o.put("towerRoof", towerRoof); o.put("construction", construction);
        o.put("towerType", towerType); o.put("castleType", castleType); o.put("ruin", ruin);
        o.put("towers", towers); o.put("towerHeight", towerHeight); putNum(o, "towerDE", towerDE); putNum(o, "towerDN", towerDN);
        putNum(o, "baseElev", baseElev);
        o.put("osm", fromOsm); o.put("wd", fromWd);
        return o;
    }

    static Landmark fromJson(JSONObject o) {
        Landmark l = new Landmark();
        l.name = o.optString("name", ""); l.qid = o.optString("qid", "");
        try { l.kind = Kind.valueOf(o.optString("kind", "TOWER")); } catch (IllegalArgumentException e) { l.kind = Kind.TOWER; }
        l.lat = o.optDouble("lat", 0); l.lon = o.optDouble("lon", 0);
        l.height = o.optDouble("height", Double.NaN); l.roofHeight = o.optDouble("roofHeight", Double.NaN);
        l.levels = o.optDouble("levels", Double.NaN);
        l.sizeE = o.optDouble("sizeE", 0); l.sizeN = o.optDouble("sizeN", 0);
        l.sitelinks = o.optInt("sitelinks", 0);
        l.style = o.optString("style", ""); l.roofShape = o.optString("roofShape", ""); l.towerRoof = o.optString("towerRoof", "");
        l.construction = o.optString("construction", ""); l.towerType = o.optString("towerType", "");
        l.castleType = o.optString("castleType", ""); l.ruin = o.optBoolean("ruin", false);
        l.towers = o.optInt("towers", 0);
        l.towerHeight = o.optBoolean("towerHeight", false);
        l.towerDE = o.optDouble("towerDE", Double.NaN); l.towerDN = o.optDouble("towerDN", Double.NaN);
        l.baseElev = o.optDouble("baseElev", Double.NaN);
        l.fromOsm = o.optBoolean("osm", false); l.fromWd = o.optBoolean("wd", false);
        return l;
    }

    static void putNum(JSONObject o, String k, double v) throws JSONException {
        if (!Double.isNaN(v) && !Double.isInfinite(v)) o.put(k, v);
    }

    static JSONArray toJsonArray(java.util.List<Landmark> ls) throws JSONException {
        JSONArray a = new JSONArray();
        for (Landmark l : ls) a.put(l.toJson());
        return a;
    }

    @Override public String toString() {
        return kind + " '" + name + "' h=" + (Double.isNaN(height) ? "?" : Math.round(height)) + " sl=" + sitelinks
                + " style=" + style + (qid.isEmpty() ? "" : " " + qid) + (fromOsm ? " osm" : "") + (fromWd ? " wd" : "");
    }
}
