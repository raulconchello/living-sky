package dev.livingsky.render;

import java.util.Map;

/**
 * The deterministic forecast (Open-Meteo best_match) as parsed: the raw input of {@link Timeline}, which is what the
 * renderers read. All times are hours since the Unix epoch (UTC), as doubles.
 */
public final class Wx {
    public static final int DRY = 0, RAIN = 1, SLEET = 2, SNOW = 3;

    public double lat, lon;
    public String tz = "UTC";          // IANA zone of the forecast location
    public long fetchedAtMs;

    // current
    public double temp, feels, rh, uv, cloud; // cloud 0..100
    public double wind, gust;                  // km/h
    public int code;
    public boolean isDay = true;

    // hourly (instant values at hT, except precipitation / snowfall / gusts: the hour that ends at hT)
    public double[] hT = new double[0], hTemp = new double[0], hCloud = new double[0], hUV = new double[0];
    public double[] hGust = new double[0];
    public double[] hRH = new double[0], hPrecip = new double[0], hSnow = new double[0]; // RH %, mm/h, cm/h
    public int[] hCode = new int[0];

    // minutely_15 (slot start; amounts over the 15 min)
    public double[] mT = new double[0], mPrecip = new double[0], mRain = new double[0], mSnow = new double[0];
    public int[] mCode = new int[0];

    // daily (start of local day)
    public double[] dT = new double[0], dRise = new double[0], dSet = new double[0];

    public boolean hasHourly() { return hT.length >= 2; }

    /** Linear interpolation of an hourly series at t (clamped). */
    public double hourly(double[] v, double t) {
        int n = Math.min(hT.length, v.length);
        if (n == 0) return 0;
        if (n == 1) return v[0];
        int i = (int) Math.floor(t - hT[0]);
        i = Math.max(0, Math.min(n - 2, i));
        double f = Math.max(0, Math.min(1, t - hT[i]));
        return v[i] + (v[i + 1] - v[i]) * f;
    }

    /** Index of the hourly entry at exactly time t (a whole hour of the grid), or -1. */
    public int at(double t) {
        if (hT.length == 0) return -1;
        int i = (int) Math.round(t - hT[0]);
        return i >= 0 && i < hT.length && Math.abs(hT[i] - t) < 0.01 ? i : -1;
    }

    /** Index of the daily row whose local day contains t, or -1. */
    public int dayIndex(double t) {
        for (int i = dT.length - 1; i >= 0; i--) if (t >= dT[i] - 1e-6) return i;
        return -1;
    }

    public static boolean isFog(int code) { return code == 45 || code == 48; }
    public static boolean isHail(int code) { return code == 96 || code == 99 || (code >= 87 && code <= 90); }
    public static boolean isThunder(int code) { return code >= 95 && code <= 99; }
    public static boolean isShowers(int code) { return code >= 80 && code <= 86; }
    public static boolean isFreezing(int code) { return code == 56 || code == 57 || code == 66 || code == 67; }
    public static boolean isSnowCode(int code) { return (code >= 71 && code <= 77) || code == 85 || code == 86; }

    // ------------------------------------------------------------------ parse (Open-Meteo forecast JSON)
    private static double[] fill(double[] v, int n, double def) {
        if (v.length == n) return v;
        double[] r = new double[n];
        for (int i = 0; i < n; i++) r[i] = i < v.length ? v[i] : def;
        return r;
    }

    private static int[] codes(Map<String, Object> m, String k, int n) {
        double[] v = fill(Json.nums(m, k, 1, 0), n, 0);
        int[] r = new int[n];
        for (int i = 0; i < n; i++) r[i] = (int) Math.round(v[i]);
        return r;
    }

    public static Wx parse(String json, long fetchedAt) throws Exception {
        Map<String, Object> o = Json.obj(Json.parse(json));
        if (o == null) throw new Exception("forecast: not an object");
        if (Boolean.TRUE.equals(o.get("error"))) throw new Exception(Json.str(o, "reason", "error"));
        Wx w = new Wx();
        w.lat = Json.num(o, "latitude", 0);
        w.lon = Json.num(o, "longitude", 0);
        w.tz = Json.str(o, "timezone", "UTC");
        w.fetchedAtMs = fetchedAt;
        Map<String, Object> cur = Json.obj(o, "current");
        if (cur == null || !(cur.get("temperature_2m") instanceof Double)) throw new Exception("forecast has no current data");
        w.temp = Json.num(cur, "temperature_2m", 0);
        w.feels = Json.num(cur, "apparent_temperature", w.temp);
        w.rh = Json.num(cur, "relative_humidity_2m", 0);
        w.uv = Json.num(cur, "uv_index", 0);
        w.code = (int) Json.num(cur, "weather_code", 3);
        w.cloud = Json.num(cur, "cloud_cover", 100);
        w.isDay = Json.num(cur, "is_day", 1) == 1;
        w.wind = Json.num(cur, "wind_speed_10m", 0);
        w.gust = Json.num(cur, "wind_gusts_10m", w.wind);

        Map<String, Object> h = Json.obj(o, "hourly");
        w.hT = Json.nums(h, "time", 1 / 3600.0, 0);
        int n = w.hT.length;
        if (n < 2) throw new Exception("forecast has no hourly data");
        w.hTemp = fill(Json.nums(h, "temperature_2m", 1, w.temp), n, w.temp);
        w.hCode = codes(h, "weather_code", n);
        w.hCloud = fill(Json.nums(h, "cloud_cover", 1, 100), n, 100);
        w.hUV = fill(Json.nums(h, "uv_index", 1, 0), n, 0);
        w.hGust = fill(Json.nums(h, "wind_gusts_10m", 1, 0), n, 0);
        w.hRH = h.containsKey("relative_humidity_2m") ? fill(Json.nums(h, "relative_humidity_2m", 1, -1), n, -1) : new double[0];
        w.hPrecip = fill(Json.nums(h, "precipitation", 1, 0), n, 0);
        w.hSnow = fill(Json.nums(h, "snowfall", 1, 0), n, 0);

        Map<String, Object> m = Json.obj(o, "minutely_15");
        if (m != null) {
            w.mT = Json.nums(m, "time", 1 / 3600.0, 0);
            int k = w.mT.length;
            w.mPrecip = fill(Json.nums(m, "precipitation", 1, 0), k, 0);
            w.mRain = fill(Json.nums(m, "rain", 1, 0), k, 0);
            w.mSnow = fill(Json.nums(m, "snowfall", 1, 0), k, 0);
            w.mCode = codes(m, "weather_code", k);
        }
        Map<String, Object> d = Json.obj(o, "daily");
        w.dT = Json.nums(d, "time", 1 / 3600.0, 0);
        int nd = w.dT.length;
        w.dRise = fill(Json.nums(d, "sunrise", 1 / 3600.0, 0), nd, 0);
        w.dSet = fill(Json.nums(d, "sunset", 1 / 3600.0, 0), nd, 0);
        return w;
    }
}
