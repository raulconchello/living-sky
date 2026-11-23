package dev.livingsky;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import dev.livingsky.render.Sources;
import dev.livingsky.render.Wx;

/**
 * Open-Meteo forecast (best_match, no key; URL and parser in {@link Sources} / {@link Wx#parse}, shared with the
 * desktop harness). Keeps the last good response on disk so the widget never goes blank offline.
 */
final class WeatherClient {
    private WeatherClient() {}

    static String httpGet(String url, int timeoutMs) throws Exception { return Sources.get(url, timeoutMs); }

    /** Fetches, validates (parses) and stores the forecast. Throws on any failure (the old cache stays). */
    static Wx fetch(Context ctx, double lat, double lon) throws Exception {
        String body = httpGet(Sources.forecastUrl(lat, lon), 20000);
        long at = System.currentTimeMillis();
        Wx w = Wx.parse(body, at);
        // the raw response plus the fetch time: {"_fetchedAt":..., <the response's own fields>}
        String stored = "{\"_fetchedAt\":" + at + "," + body.trim().substring(1);
        File tmp = new File(ctx.getFilesDir(), "weather.json.tmp");
        try (FileOutputStream fo = new FileOutputStream(tmp)) { fo.write(stored.getBytes(StandardCharsets.UTF_8)); }
        if (!tmp.renameTo(new File(ctx.getFilesDir(), "weather.json"))) throw new Exception("cannot store weather cache");
        return w;
    }

    /** Last good forecast, or null. */
    static Wx cached(Context ctx) {
        File f = new File(ctx.getFilesDir(), "weather.json");
        if (!f.exists()) return null;
        try {
            String s = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            java.util.Map<String, Object> o = dev.livingsky.render.Json.obj(dev.livingsky.render.Json.parse(s));
            return Wx.parse(s, (long) dev.livingsky.render.Json.num(o, "_fetchedAt", f.lastModified()));
        } catch (Exception e) {
            return null;
        }
    }
}
