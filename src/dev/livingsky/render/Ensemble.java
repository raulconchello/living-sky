package dev.livingsky.render;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Hourly precipitation from an ensemble (Open-Meteo ensemble API, icon_seamless: 40 members), kept per hour as
 * P = share of members with >= 0.1 mm, R = mean amount over those wet members (mm/h: the rate if it precipitates) and
 * the snow share of their water. Open-Meteo's precipitation at T is the sum over the preceding hour: {@link #t} holds
 * those end times, so the hour that starts at h is the entry whose t is h + 1.
 */
public final class Ensemble {
    public double[] t = new double[0];     // end of each hour (hours since epoch)
    public double[] p = new double[0];     // 0..1
    public double[] r = new double[0];     // mm/h over the wet members (0 when none)
    public double[] snow = new double[0];  // 0..1 snow share of the wet members' water
    public String model = "";
    public int members;
    public long fetchedAtMs;
    public double lat, lon;

    public boolean covers(double time) { return t.length > 1 && time >= t[0] - 1 && time < t[t.length - 1]; }

    /** Index of the hour interval (t[i] - 1, t[i]] containing time, or -1. */
    public int idx(double time) {
        if (t.length == 0) return -1;
        int i = (int) Math.ceil(time - t[0] - 1e-9);
        return i >= 0 && i < t.length ? i : -1;
    }

    /** Parses an ensemble API response; null when it has too few members or hours. */
    public static Ensemble parse(String json, String model, double lat, double lon, long fetchedAt) throws Exception {
        Map<String, Object> o = Json.obj(Json.parse(json));
        if (o == null) throw new Exception("ensemble: not an object");
        if (Boolean.TRUE.equals(o.get("error"))) throw new Exception(Json.str(o, "reason", "error"));
        Map<String, Object> h = Json.obj(o, "hourly");
        double[] time = Json.nums(h, "time", 1 / 3600.0, 0);
        List<String> pk = new ArrayList<>();
        for (String k : h.keySet()) if (k.equals("precipitation") || k.startsWith("precipitation_member")) pk.add(k);
        int n = time.length;
        if (pk.isEmpty() || n < 2) return null;
        List<double[]> pr = new ArrayList<>(), sn = new ArrayList<>();
        for (String k : pk) {
            pr.add(Json.nums(h, k, 1, Double.NaN));
            String sk = "snowfall" + k.substring("precipitation".length());
            sn.add(h.containsKey(sk) ? Json.nums(h, sk, 1, 0) : new double[0]);
        }
        Ensemble e = new Ensemble();
        e.model = model; e.lat = lat; e.lon = lon; e.fetchedAtMs = fetchedAt;
        e.t = time; e.p = new double[n]; e.r = new double[n]; e.snow = new double[n];
        int any = 0, maxMembers = 0;
        for (int i = 0; i < n; i++) {
            int cnt = 0, wet = 0;
            double sum = 0, snowW = 0;
            for (int m = 0; m < pr.size(); m++) {
                double[] a = pr.get(m);
                if (i >= a.length || Double.isNaN(a[i])) continue;
                cnt++;
                if (a[i] >= WxClass.WET_MM) {
                    wet++;
                    sum += a[i];
                    double[] s = sn.get(m);
                    if (i < s.length) snowW += s[i] / 0.7;   // snowfall cm ~ 0.7 x water mm when it is all snow
                }
            }
            if (cnt > 0) {
                any++;
                e.p[i] = (double) wet / cnt;
                e.r[i] = wet > 0 ? sum / wet : 0;
                e.snow[i] = sum > 0 ? Math.min(1, snowW / sum) : 0;
            }
            maxMembers = Math.max(maxMembers, cnt);
        }
        e.members = maxMembers;
        return any > n / 3 && maxMembers >= 5 ? e : null;
    }

    // ------------------------------------------------------------------ cache
    public String toJson() {
        StringBuilder b = new StringBuilder("{");
        b.append("\"v\":2,\"model\":").append(Json.quote(model)).append(",\"members\":").append(members)
                .append(",\"fetchedAt\":").append(fetchedAtMs).append(",\"lat\":").append(lat).append(",\"lon\":").append(lon);
        arr(b, "t", t, 1e6); arr(b, "p", p, 1000); arr(b, "r", r, 1000); arr(b, "snow", snow, 1000);
        return b.append('}').toString();
    }

    private static void arr(StringBuilder b, String k, double[] v, double q) {
        b.append(",\"").append(k).append("\":[");
        for (int i = 0; i < v.length; i++) b.append(i > 0 ? "," : "").append(Math.round(v[i] * q) / q);
        b.append(']');
    }

    /** The cache written by {@link #toJson}, or null (also for older cache versions: refetched). */
    public static Ensemble fromJson(String s) {
        try {
            Map<String, Object> o = Json.obj(Json.parse(s));
            if (Json.num(o, "v", 0) != 2) return null;
            Ensemble e = new Ensemble();
            e.model = Json.str(o, "model", "");
            e.members = (int) Json.num(o, "members", 0);
            e.fetchedAtMs = (long) Json.num(o, "fetchedAt", 0);
            e.lat = Json.num(o, "lat", 0);
            e.lon = Json.num(o, "lon", 0);
            e.t = Json.nums(o, "t", 1, 0); e.p = Json.nums(o, "p", 1, 0); e.r = Json.nums(o, "r", 1, 0); e.snow = Json.nums(o, "snow", 1, 0);
            return e.t.length == e.p.length && e.t.length == e.r.length && e.t.length > 1 ? e : null;
        } catch (Exception ex) {
            return null;
        }
    }
}
