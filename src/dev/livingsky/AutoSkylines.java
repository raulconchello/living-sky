package dev.livingsky;

import android.content.Context;
import android.util.Log;

import java.io.File;

import dev.livingsky.render.SvgGen;

/**
 * The automatic skyline, used when no hand-drawn place is in range:
 *  1. the OSM generator (third_party/autoskyline, via {@link AutoSkylineBridge}); it caches per ~1 km cell itself.
 *     Without network permission (quick re-renders) only its cache is used;
 *  2. fallback: the legacy procedural town from sky8.js (hills + houses, seeded by the place name, no landmark).
 */
final class AutoSkylines {
    private AutoSkylines() {}

    static Places.Skyline get(Context c, double lat, double lon, String placeName, boolean network) {
        Places.Skyline s = new Places.Skyline();
        s.heroGlow = 0.45;
        // daylight grade: untuned generic palette by region
        s.hints = dev.livingsky.render.DaylightGrade.Hints.generic(lon < -50 ? "US" : lat < 44.5 && lon > -10 && lon < 36 ? "ES" : "DE");
        if (AutoSkylineBridge.available()) {
            try {
                String[] r = AutoSkylineBridge.generate(c, lat, lon, placeName, network);
                if (r != null && r[0] != null && r[1] != null) {
                    s.day = r[0];
                    s.night = r[1];
                    s.source = "auto:osm " + r[2];
                    return s;
                }
            } catch (Throwable t) {
                Log.w(Locator.TAG, "auto skyline generator failed", t);
                new Prefs(c).setError("auto skyline: " + t);
            }
        }
        String name = placeName == null || placeName.isEmpty() ? String.format(java.util.Locale.US, "%.2f,%.2f", lat, lon) : placeName;
        s.day = SvgGen.town(name, false);
        s.night = SvgGen.town(name, true);
        s.source = "auto:procedural (" + name + ")";
        return s;
    }

    /** Forget cached automatic skylines (debug screen). */
    static void clearCache(Context c) {
        File[] fs = new File(c.getFilesDir(), "autoskyline").listFiles();
        if (fs != null) for (File f : fs) //noinspection ResultOfMethodCallIgnored
            f.delete();
    }
}
