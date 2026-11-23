package dev.livingsky.render;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * Where the weather comes from (all keyless), in plain Java so the phone and the desktop harness use the same URLs:
 *   forecast  Open-Meteo best_match: current, hourly, 24 h of 15-min precipitation, sunrise / sunset;
 *   ensemble  Open-Meteo icon_seamless (40 members), then gfs025, then ecmwf_ifs025: precipitation and snowfall;
 *   now (DE)  DWD radar (RADOLAN RV, 1 km, 5 min, with a 2 h nowcast) and the nearest DWD station, via Bright Sky.
 */
public final class Sources {
    private Sources() {}

    public static final String UA = "LivingSkyWidget/1.0";
    public static final String[] ENS_MODELS = {"icon_seamless", "gfs025", "ecmwf_ifs025"};
    /** Days of forecast and ensemble: today + the 4 strip days, plus the hour that ends strip day 4 at midnight. */
    public static final int DAYS = 6;

    public static final String F_CURRENT = "temperature_2m,apparent_temperature,relative_humidity_2m,uv_index,weather_code,cloud_cover,is_day,wind_speed_10m,wind_gusts_10m";
    public static final String F_HOURLY = "temperature_2m,weather_code,cloud_cover,uv_index,wind_gusts_10m,relative_humidity_2m,precipitation,snowfall";
    public static final String F_MIN15 = "precipitation,rain,snowfall,weather_code";
    public static final String F_DAILY = "sunrise,sunset";
    public static final String E_HOURLY = "precipitation,snowfall";

    public static String forecastUrl(double lat, double lon) {
        return String.format(Locale.US, "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f", lat, lon)
                + "&current=" + F_CURRENT + "&hourly=" + F_HOURLY + "&minutely_15=" + F_MIN15 + "&daily=" + F_DAILY
                + "&timezone=auto&forecast_days=" + DAYS + "&forecast_minutely_15=96&timeformat=unixtime";
    }

    public static String ensembleUrl(double lat, double lon, String model) {
        return String.format(Locale.US, "https://ensemble-api.open-meteo.com/v1/ensemble?latitude=%.4f&longitude=%.4f", lat, lon)
                + "&hourly=" + E_HOURLY + "&models=" + model + "&timezone=auto&forecast_days=" + DAYS + "&timeformat=unixtime";
    }

    // ---- Germany: DWD radar + station via Bright Sky
    /** Germany and its borders (the DWD radar composite): 47.2-55.1 N, 5.8-15.1 E. */
    public static boolean inGermany(double lat, double lon) { return lat >= 47.2 && lat <= 55.1 && lon >= 5.8 && lon <= 15.1; }

    /** Radar frames from 20 min ago to 2 h ahead (the RV nowcast) in a 3 x 3 km box around the point. */
    public static String radarUrl(double lat, double lon, long nowMs) {
        return String.format(Locale.US, "https://api.brightsky.dev/radar?lat=%.4f&lon=%.4f&distance=1000&format=plain", lat, lon)
                + "&date=" + iso(nowMs - 20 * 60_000L) + "&last_date=" + iso(nowMs + 125 * 60_000L);
    }

    /** The nearest DWD station's latest observation (SYNOP), at most 15 km away. */
    public static String currentUrl(double lat, double lon) {
        return String.format(Locale.US, "https://api.brightsky.dev/current_weather?lat=%.4f&lon=%.4f&max_dist=15000", lat, lon);
    }

    static String iso(long ms) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(ms));
    }

    /** Parses an ISO 8601 timestamp with offset ("2026-11-23T20:00:00+00:00") to epoch ms, or -1. */
    public static long parseIso(String s) {
        if (s == null) return -1;
        try {
            String t = s.replace("Z", "+00:00");
            int p = Math.max(t.lastIndexOf('+'), t.lastIndexOf('-'));
            if (p > 10) t = t.substring(0, p) + t.substring(p).replace(":", "");
            return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).parse(t).getTime();
        } catch (Exception e) {
            return -1;
        }
    }

    // ---- HTTP (gzip or deflate, which Bright Sky requires; done by hand so it is the same on the phone and the PC)
    public static String get(String url, int timeoutMs) throws Exception {
        HttpURLConnection c = (HttpURLConnection) java.net.URI.create(url).toURL().openConnection();
        c.setConnectTimeout(timeoutMs);
        c.setReadTimeout(timeoutMs);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("Accept-Encoding", "gzip");
        try {
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String enc = c.getContentEncoding();
            if (in != null && "gzip".equalsIgnoreCase(enc)) in = new GZIPInputStream(in);
            else if (in != null && "deflate".equalsIgnoreCase(enc)) in = new InflaterInputStream(in);
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

    // ---- budget (Open-Meteo: weight = max(1, vars / 10) x max(1, days / 14), ensembles x members)
    public static int vars(String list) { return list.isEmpty() ? 0 : list.split(",").length; }

    public static double forecastWeight() {
        int v = vars(F_CURRENT) + vars(F_HOURLY) + vars(F_MIN15) + vars(F_DAILY);
        return Math.max(1, v / 10.0) * Math.max(1, DAYS / 14.0);
    }

    public static double ensembleWeight(int members) {
        return Math.max(1, vars(E_HOURLY) * members / 10.0) * Math.max(1, DAYS / 14.0);
    }
}
