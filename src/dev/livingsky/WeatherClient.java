package dev.livingsky;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;

import dev.livingsky.render.Wx;

/** Open-Meteo forecast (no key). Keeps the last good response on disk so the widget never goes blank offline. */
final class WeatherClient {
    private WeatherClient() {}

    static final String UA = "LivingSkyWidget/1.0";

    static String url(double lat, double lon) {
        return String.format(Locale.US, "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f", lat, lon)
                + "&current=temperature_2m,apparent_temperature,relative_humidity_2m,uv_index,weather_code,cloud_cover,is_day,wind_speed_10m,wind_gusts_10m"
                + "&hourly=temperature_2m,weather_code,cloud_cover,precipitation_probability,uv_index,wind_gusts_10m,relative_humidity_2m,precipitation,snowfall"
                + "&minutely_15=precipitation,rain,snowfall,weather_code"
                + "&daily=weather_code,temperature_2m_max,temperature_2m_min,sunrise,sunset,wind_gusts_10m_max"
                + "&timezone=auto&forecast_days=5&timeformat=unixtime";
    }

    static String httpGet(String url, int timeoutMs) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(timeoutMs);
        c.setReadTimeout(timeoutMs);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Accept", "application/json");
        try {
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while (in != null && (n = in.read(buf)) > 0) bo.write(buf, 0, n);
            String body = new String(bo.toByteArray(), StandardCharsets.UTF_8);
            if (code != 200) throw new Exception("HTTP " + code + ": " + body.substring(0, Math.min(160, body.length())));
            return body;
        } finally {
            c.disconnect();
        }
    }

    /** Fetches, validates (parses) and stores the forecast. Throws on any failure (the old cache stays). */
    static Wx fetch(Context ctx, double lat, double lon) throws Exception {
        String body = httpGet(url(lat, lon), 20000);
        Wx w = parse(body, System.currentTimeMillis());
        File tmp = new File(ctx.getFilesDir(), "weather.json.tmp");
        try (FileOutputStream fo = new FileOutputStream(tmp)) {
            JSONObject o = new JSONObject(body);
            o.put("_fetchedAt", w.fetchedAtMs);
            fo.write(o.toString().getBytes(StandardCharsets.UTF_8));
        }
        if (!tmp.renameTo(new File(ctx.getFilesDir(), "weather.json"))) throw new Exception("cannot store weather cache");
        return w;
    }

    /** Last good forecast, or null. */
    static Wx cached(Context ctx) {
        File f = new File(ctx.getFilesDir(), "weather.json");
        if (!f.exists()) return null;
        try {
            String s = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            JSONObject o = new JSONObject(s);
            return parse(s, o.optLong("_fetchedAt", f.lastModified()));
        } catch (Exception e) {
            return null;
        }
    }

    private static double[] dArr(JSONObject o, String k, double scale, double def) {
        JSONArray a = o.optJSONArray(k);
        if (a == null) return new double[0];
        double[] r = new double[a.length()];
        for (int i = 0; i < r.length; i++) r[i] = a.isNull(i) ? def : a.optDouble(i, def) * scale;
        return r;
    }

    private static int[] iArr(JSONObject o, String k) {
        JSONArray a = o.optJSONArray(k);
        if (a == null) return new int[0];
        int[] r = new int[a.length()];
        for (int i = 0; i < r.length; i++) r[i] = a.isNull(i) ? 0 : a.optInt(i, 0);
        return r;
    }

    private static double[] fill(double[] v, int n, double def) {
        if (v.length == n) return v;
        double[] r = new double[n];
        for (int i = 0; i < n; i++) r[i] = i < v.length ? v[i] : def;
        return r;
    }

    private static int[] fill(int[] v, int n) {
        if (v.length == n) return v;
        int[] r = new int[n];
        for (int i = 0; i < n; i++) r[i] = i < v.length ? v[i] : 0;
        return r;
    }

    static Wx parse(String json, long fetchedAt) throws Exception {
        JSONObject o = new JSONObject(json);
        Wx w = new Wx();
        w.lat = o.getDouble("latitude");
        w.lon = o.getDouble("longitude");
        w.tz = o.optString("timezone", "UTC");
        w.fetchedAtMs = fetchedAt;
        JSONObject cur = o.getJSONObject("current");
        w.temp = cur.getDouble("temperature_2m");
        w.feels = cur.optDouble("apparent_temperature", w.temp);
        w.rh = cur.optDouble("relative_humidity_2m", 0);
        w.uv = cur.isNull("uv_index") ? 0 : cur.optDouble("uv_index", 0);
        w.code = cur.optInt("weather_code", 3);
        w.cloud = cur.optDouble("cloud_cover", 100);
        w.isDay = cur.optInt("is_day", 1) == 1;
        w.wind = cur.optDouble("wind_speed_10m", 0);
        w.gust = cur.optDouble("wind_gusts_10m", w.wind);

        JSONObject h = o.getJSONObject("hourly");
        w.hT = dArr(h, "time", 1 / 3600.0, 0);
        int n = w.hT.length;
        w.hTemp = fill(dArr(h, "temperature_2m", 1, w.temp), n, w.temp);
        w.hCode = fill(iArr(h, "weather_code"), n);
        w.hCloud = fill(dArr(h, "cloud_cover", 1, 100), n, 100);
        w.hPP = fill(dArr(h, "precipitation_probability", 1, 0), n, 0);
        w.hUV = fill(dArr(h, "uv_index", 1, 0), n, 0);
        w.hGust = fill(dArr(h, "wind_gusts_10m", 1, 0), n, 0);
        w.hRH = h.has("relative_humidity_2m") ? fill(dArr(h, "relative_humidity_2m", 1, -1), n, -1) : new double[0];
        w.hPrecip = h.has("precipitation") ? fill(dArr(h, "precipitation", 1, 0), n, 0) : new double[0];
        w.hSnow = h.has("snowfall") ? fill(dArr(h, "snowfall", 1, 0), n, 0) : new double[0];
        if (n < 2) throw new Exception("forecast has no hourly data");

        JSONObject m = o.optJSONObject("minutely_15");
        if (m != null) {
            w.mT = dArr(m, "time", 1 / 3600.0, 0);
            int k = w.mT.length;
            w.mPrecip = fill(dArr(m, "precipitation", 1, 0), k, 0);
            w.mRain = fill(dArr(m, "rain", 1, 0), k, 0);
            w.mSnow = fill(dArr(m, "snowfall", 1, 0), k, 0);
            w.mCode = fill(iArr(m, "weather_code"), k);
        }
        JSONObject d = o.getJSONObject("daily");
        w.dT = dArr(d, "time", 1 / 3600.0, 0);
        int nd = w.dT.length;
        w.dCode = fill(iArr(d, "weather_code"), nd);
        w.dMax = fill(dArr(d, "temperature_2m_max", 1, 0), nd, 0);
        w.dMin = fill(dArr(d, "temperature_2m_min", 1, 0), nd, 0);
        w.dRise = fill(dArr(d, "sunrise", 1 / 3600.0, 0), nd, 0);
        w.dSet = fill(dArr(d, "sunset", 1 / 3600.0, 0), nd, 0);
        w.dGust = fill(dArr(d, "wind_gusts_10m_max", 1, 0), nd, 0);
        return w;
    }
}
