package dev.livingsky.render;

import java.util.List;
import java.util.Map;

/**
 * What is measured right now in Germany (Bright Sky, DWD): the radar (RADOLAN RV, 5-min frames over a 3 x 3 km box,
 * including the 2 h nowcast) and the nearest station's latest observation (SYNOP). Plain Java, so the desktop harness
 * parses the same way. The raw responses are cached together (see {@link #cacheJson}).
 */
public final class NowObs {
    public double lat, lon;
    public long fetchedAtMs;
    public String radarBody, currentBody;   // raw responses (for the cache)

    // radar: frame end times (ms) and the box mean rate (mm/h); the latest observed frame's time
    public long[] frameMs = new long[0];
    public double[] frameR = new double[0];
    public long radarIssueMs = -1;

    // station
    public long obsMs = -1;
    public String condition;                  // dry, fog, rain, sleet, snow, hail, thunderstorm (or null)
    public double precip10 = Double.NaN, visibilityM = Double.NaN, obsTemp = Double.NaN, distKm = Double.NaN;
    public String station = "";

    /** Radar usable for "now": its latest observed frame is at most 15 min old. */
    public boolean radarFresh(long nowMs) {
        long age = nowMs - radarIssueMs;   // not for a "now" before the frame either (debug time travel)
        return radarIssueMs > 0 && frameMs.length > 0 && age <= WxClass.RADAR_MAX_MIN * 60_000L && age >= -5 * 60_000L;
    }

    /** A station observation at most maxMin old (and not from after now) and within 15 km. */
    public boolean obsFresh(long nowMs, double maxMin) {
        return obsMs > 0 && nowMs - obsMs <= maxMin * 60_000L && nowMs - obsMs > -10 * 60_000L && !(distKm > 15);
    }

    /** The latest observed frame's rate (mm/h), or NaN. */
    public double radarNow() {
        for (int i = frameMs.length - 1; i >= 0; i--) if (frameMs[i] <= radarIssueMs) return frameR[i];
        return Double.NaN;
    }

    /** Mean radar rate (mm/h) over the frames ending in (t0, t1] (hours since epoch), or NaN when there are none. */
    public double radarRate(double t0, double t1) {
        double s = 0;
        int n = 0;
        for (int i = 0; i < frameMs.length; i++) {
            double ft = frameMs[i] / 3600000.0;
            if (ft > t0 + 1e-6 && ft <= t1 + 1e-6 && !Double.isNaN(frameR[i])) { s += frameR[i]; n++; }
        }
        return n > 0 ? s / n : Double.NaN;
    }

    /** Last radar frame time (hours since epoch), or -inf. */
    public double radarEnd() { return frameMs.length > 0 ? frameMs[frameMs.length - 1] / 3600000.0 : Double.NEGATIVE_INFINITY; }

    public boolean obsWet() {
        return (precip10 > 0) || "rain".equals(condition) || "sleet".equals(condition) || "snow".equals(condition)
                || "hail".equals(condition) || "thunderstorm".equals(condition);
    }

    // ------------------------------------------------------------------ parse
    public static NowObs parse(String radarJson, String currentJson, double lat, double lon, long fetchedAt) {
        NowObs o = new NowObs();
        o.lat = lat; o.lon = lon; o.fetchedAtMs = fetchedAt;
        if (radarJson != null) try { o.parseRadar(radarJson); } catch (Exception e) { o.radarBody = null; o.frameMs = new long[0]; }
        if (currentJson != null) try { o.parseCurrent(currentJson); } catch (Exception e) { o.currentBody = null; o.obsMs = -1; }
        return o;
    }

    private void parseRadar(String json) {
        List<Object> frames = Json.arr(Json.obj(Json.parse(json)), "radar");
        if (frames == null) return;
        long[] ft = new long[frames.size()];
        double[] fr = new double[frames.size()];
        int n = 0;
        long issue = -1;
        for (Object fo : frames) {
            Map<String, Object> f = Json.obj(fo);
            long t = Sources.parseIso(Json.str(f, "timestamp", null));
            String src = Json.str(f, "source", "");
            int sep = src.lastIndexOf("::");                                       // "RADARCOMP::RV::<issue time>"
            long st = Sources.parseIso(sep >= 0 ? src.substring(sep + 2) : src);
            List<Object> rows = Json.arr(f, "precipitation_5");
            if (t < 0 || rows == null) continue;
            double s = 0;
            int c = 0;
            for (Object ro : rows) {
                List<Object> row = Json.arr(ro);
                if (row == null) continue;
                for (Object v : row) if (v instanceof Double) { s += (Double) v; c++; }
            }
            if (c == 0) continue;
            ft[n] = t;
            fr[n] = s / c * 0.12;            // 0.01 mm per 5 min -> mm/h
            n++;
            if (st > 0) issue = Math.max(issue, st);
        }
        frameMs = java.util.Arrays.copyOf(ft, n);
        frameR = java.util.Arrays.copyOf(fr, n);
        radarIssueMs = issue;
        radarBody = json;
    }

    private void parseCurrent(String json) {
        Map<String, Object> o = Json.obj(Json.parse(json));
        Map<String, Object> w = Json.obj(o, "weather");
        if (w == null) return;
        obsMs = Sources.parseIso(Json.str(w, "timestamp", null));
        condition = Json.str(w, "condition", null);
        precip10 = Json.num(w, "precipitation_10", Double.NaN);
        visibilityM = Json.num(w, "visibility", Double.NaN);
        obsTemp = Json.num(w, "temperature", Double.NaN);
        List<Object> srcs = Json.arr(o, "sources");
        Map<String, Object> s0 = srcs != null && !srcs.isEmpty() ? Json.obj(srcs.get(0)) : null;
        if (s0 != null) {
            distKm = Json.num(s0, "distance", Double.NaN) / 1000;
            station = Json.str(s0, "station_name", "");
        }
        currentBody = json;
    }

    // ------------------------------------------------------------------ cache
    public String cacheJson() {
        return "{\"fetchedAt\":" + fetchedAtMs + ",\"lat\":" + lat + ",\"lon\":" + lon
                + ",\"radar\":" + (radarBody != null ? radarBody : "null") + ",\"current\":" + (currentBody != null ? currentBody : "null") + "}";
    }

    public static NowObs fromCache(String s) {
        try {
            Map<String, Object> o = Json.obj(Json.parse(s));
            String r = o.get("radar") != null ? reJson(o.get("radar")) : null;
            String c = o.get("current") != null ? reJson(o.get("current")) : null;
            return parse(r, c, Json.num(o, "lat", 0), Json.num(o, "lon", 0), (long) Json.num(o, "fetchedAt", 0));
        } catch (Exception e) {
            return null;
        }
    }

    /** Writes a parsed JSON value back to text (the cache keeps the raw responses nested). */
    static String reJson(Object v) {
        StringBuilder b = new StringBuilder();
        write(b, v);
        return b.toString();
    }

    private static void write(StringBuilder b, Object v) {
        if (v == null) b.append("null");
        else if (v instanceof String) b.append(Json.quote((String) v));
        else if (v instanceof Double) {
            double d = (Double) v;
            if (d == Math.rint(d) && Math.abs(d) < 1e15) b.append((long) d); else b.append(d);
        } else if (v instanceof Boolean) b.append(v);
        else if (v instanceof Map) {
            b.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : Json.obj(v).entrySet()) {
                if (!first) b.append(',');
                first = false;
                b.append(Json.quote(e.getKey())).append(':');
                write(b, e.getValue());
            }
            b.append('}');
        } else if (v instanceof List) {
            b.append('[');
            List<Object> l = Json.arr(v);
            for (int i = 0; i < l.size(); i++) { if (i > 0) b.append(','); write(b, l.get(i)); }
            b.append(']');
        }
    }
}
