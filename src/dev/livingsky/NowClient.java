package dev.livingsky;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import dev.livingsky.render.NowObs;
import dev.livingsky.render.Sources;

/**
 * "Now" in Germany: the DWD radar (with its 2 h nowcast) and the nearest DWD station, via Bright Sky (no key, gzip).
 * Fetched at a render when the radar's latest frame is more than 10 min old (ticks only fire while the phone is in
 * use, so this adds no wake-ups); cached in now.json. A part that fails keeps its last response.
 */
final class NowClient {
    private NowClient() {}

    static final long REFRESH_MS = 10 * 60_000L, RETRY_MS = 3 * 60_000L;

    /** Is a refetch due (no data, moved, or the radar's newest frame is older than 10 min)? */
    static boolean due(NowObs o, double lat, double lon, long nowMs) {
        if (o == null || Locator.distanceKm(o.lat, o.lon, lat, lon) > 1) return true;
        long attempt = nowMs - o.fetchedAtMs;            // every fetch rewrites the cache, so this is the last try
        if (attempt < RETRY_MS) return false;
        if (o.radarIssueMs <= 0) return attempt > REFRESH_MS;
        return nowMs - o.radarIssueMs > REFRESH_MS;
    }

    static NowObs fetch(Context c, double lat, double lon, NowObs old) {
        long now = System.currentTimeMillis();
        boolean same = old != null && Locator.distanceKm(old.lat, old.lon, lat, lon) <= 1;
        String radar = same ? old.radarBody : null, cur = same ? old.currentBody : null;
        try { radar = Sources.get(Sources.radarUrl(lat, lon, now), 8000); } catch (Exception e) { Log.w(Locator.TAG, "radar fetch failed: " + e.getMessage()); }
        try { cur = Sources.get(Sources.currentUrl(lat, lon), 8000); } catch (Exception e) { Log.w(Locator.TAG, "station fetch failed: " + e.getMessage()); }
        NowObs o = NowObs.parse(radar, cur, lat, lon, now);
        try {
            File tmp = new File(c.getFilesDir(), "now.json.tmp");
            try (FileOutputStream f = new FileOutputStream(tmp)) { f.write(o.cacheJson().getBytes(StandardCharsets.UTF_8)); }
            if (!tmp.renameTo(new File(c.getFilesDir(), "now.json"))) Log.w(Locator.TAG, "cannot store now.json");
        } catch (Exception e) {
            Log.w(Locator.TAG, "now cache", e);
        }
        return o;
    }

    static NowObs cached(Context c) {
        File f = new File(c.getFilesDir(), "now.json");
        if (!f.exists()) return null;
        try {
            return NowObs.fromCache(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }
}
