package dev.livingsky;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

import dev.livingsky.render.Ensemble;

/**
 * Ensemble precipitation for the timeline column (Open-Meteo ensemble API, no key): icon_seamless (40 members), then
 * gfs025, then ecmwf_ifs025. Only the per-hour probability / mean / snowfall / mean cloud cover are kept (small cache). The ensemble
 * updates every 6 h, and ensemble calls weigh more on the daily limit, so it is refetched about hourly.
 */
final class EnsembleClient {
    private EnsembleClient() {}

    static final String[] MODELS = {"icon_seamless", "gfs025", "ecmwf_ifs025"};
    static final long STALE_MS = 60 * 60_000L;

    static String url(double lat, double lon, String model) {
        return String.format(Locale.US, "https://ensemble-api.open-meteo.com/v1/ensemble?latitude=%.4f&longitude=%.4f", lat, lon)
                + "&hourly=precipitation,snowfall,cloud_cover&models=" + model + "&timezone=auto&forecast_days=5&timeformat=unixtime";
    }

    /** Fetches (trying each model) and stores; throws when every model fails. */
    static Ensemble fetch(Context c, double lat, double lon) throws Exception {
        Exception last = null;
        for (String m : MODELS) {
            try {
                String body = WeatherClient.httpGet(url(lat, lon, m), 25000);
                Ensemble e = parse(new JSONObject(body), m, lat, lon);
                if (e == null) { last = new Exception(m + ": no members"); continue; }
                store(c, e);
                Log.i(Locator.TAG, "ensemble: " + m + ", " + e.members + " members, " + e.t.length + " h");
                return e;
            } catch (Exception ex) {
                last = ex;
                Log.w(Locator.TAG, "ensemble " + m + " failed: " + ex.getMessage());
            }
        }
        throw last != null ? last : new Exception("ensemble: no model");
    }

    static Ensemble parse(JSONObject o, String model, double lat, double lon) throws Exception {
        if (o.optBoolean("error", false)) throw new Exception(o.optString("reason", "error"));
        JSONObject h = o.getJSONObject("hourly");
        JSONArray time = h.getJSONArray("time");
        List<JSONArray> pr = new ArrayList<>(), sn = new ArrayList<>(), cl = new ArrayList<>();
        for (Iterator<String> it = h.keys(); it.hasNext(); ) {
            String k = it.next();
            if (k.equals("precipitation") || k.startsWith("precipitation_member")) pr.add(h.getJSONArray(k));
            else if (k.equals("snowfall") || k.startsWith("snowfall_member")) sn.add(h.getJSONArray(k));
            else if (k.equals("cloud_cover") || k.startsWith("cloud_cover_member")) cl.add(h.getJSONArray(k));
        }
        int n = time.length();
        if (pr.isEmpty() || n < 2) return null;
        Ensemble e = new Ensemble();
        e.model = model; e.lat = lat; e.lon = lon; e.fetchedAtMs = System.currentTimeMillis();
        e.t = new double[n]; e.prob = new double[n]; e.mean = new double[n]; e.snow = new double[n];
        e.cloud = new double[cl.isEmpty() ? 0 : n];
        int any = 0, maxMembers = 0;
        for (int i = 0; i < n; i++) {
            e.t[i] = time.getLong(i) / 3600.0;
            int cnt = 0, wet = 0; double sum = 0;
            for (JSONArray a : pr) {
                if (i >= a.length() || a.isNull(i)) continue;
                double v = a.optDouble(i, 0);
                cnt++; sum += v; if (v >= 0.1) wet++;
            }
            int sc = 0; double ss = 0;
            for (JSONArray a : sn) { if (i < a.length() && !a.isNull(i)) { sc++; ss += a.optDouble(i, 0); } }
            if (cnt > 0) { any++; e.prob[i] = 100.0 * wet / cnt; e.mean[i] = sum / cnt; }
            e.snow[i] = sc > 0 ? ss / sc : 0;
            if (!cl.isEmpty()) {
                int cc = 0; double cs = 0;
                for (JSONArray a : cl) { if (i < a.length() && !a.isNull(i)) { cc++; cs += a.optDouble(i, 0); } }
                e.cloud[i] = cc > 0 ? cs / cc : -1;
            }
            maxMembers = Math.max(maxMembers, cnt);
        }
        e.members = maxMembers;
        return any > n / 3 && maxMembers >= 5 ? e : null;
    }

    static void store(Context c, Ensemble e) throws Exception {
        JSONObject o = new JSONObject();
        o.put("model", e.model).put("members", e.members).put("fetchedAt", e.fetchedAtMs).put("lat", e.lat).put("lon", e.lon);
        o.put("t", arr(e.t)).put("prob", arr(e.prob)).put("mean", arr(e.mean)).put("snow", arr(e.snow)).put("cloud", arr(e.cloud));
        File tmp = new File(c.getFilesDir(), "ensemble.json.tmp");
        try (FileOutputStream f = new FileOutputStream(tmp)) { f.write(o.toString().getBytes(StandardCharsets.UTF_8)); }
        if (!tmp.renameTo(new File(c.getFilesDir(), "ensemble.json"))) throw new Exception("cannot store ensemble cache");
    }

    /** The last good ensemble, or null. */
    static Ensemble cached(Context c) {
        File f = new File(c.getFilesDir(), "ensemble.json");
        if (!f.exists()) return null;
        try {
            JSONObject o = new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            Ensemble e = new Ensemble();
            e.model = o.optString("model"); e.members = o.optInt("members"); e.fetchedAtMs = o.optLong("fetchedAt");
            e.lat = o.optDouble("lat"); e.lon = o.optDouble("lon");
            e.t = dl(o.getJSONArray("t")); e.prob = dl(o.getJSONArray("prob")); e.mean = dl(o.getJSONArray("mean")); e.snow = dl(o.getJSONArray("snow"));
            e.cloud = o.has("cloud") ? dl(o.getJSONArray("cloud")) : new double[0];
            return e;
        } catch (Exception ex) {
            return null;
        }
    }

    private static JSONArray arr(double[] v) throws Exception {
        JSONArray a = new JSONArray();
        for (double x : v) a.put(Math.round(x * 1000) / 1000.0);
        return a;
    }

    private static double[] dl(JSONArray a) {
        double[] r = new double[a.length()];
        for (int i = 0; i < r.length; i++) r[i] = a.optDouble(i, 0);
        return r;
    }
}
