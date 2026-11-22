package dev.livingsky;

import android.content.Context;

import java.io.File;
import java.lang.reflect.Method;

/**
 * Adapter to the automatic OSM skyline generator (third_party/autoskyline: dev.livingsky.skyline.AutoSkyline).
 * build.sh compiles the generator's sources into the APK when they exist and compile; this class reaches it by
 * reflection, so the app builds and runs (procedural-town fallback) with or without it.
 *
 * Generator API (its README): AutoSkyline.generate(lat, lon, placeName, cacheDir) -> Result{daySvg, nightSvg,
 * description, complete, fromCache}, blocking up to ~10 s for a new place, cached per ~1 km cell in
 * cacheDir/autoskyline; AutoSkyline.cached(lat, lon, cacheDir) -> Result or null (no network);
 * AutoSkyline.generic(lat, lon, placeName) -> the generator's own low town (no network).
 */
final class AutoSkylineBridge {
    private AutoSkylineBridge() {}

    static final String CLASS = "dev.livingsky.skyline.AutoSkyline";
    private static Class<?> cls;
    private static boolean probed;

    static synchronized boolean available() {
        if (!probed) {
            probed = true;
            try {
                cls = Class.forName(CLASS);
            } catch (Throwable t) {
                cls = null;
            }
        }
        return cls != null;
    }

    /** {day, night, description} or null. network=false: cache only, then the generator's generic town. */
    static String[] generate(Context c, double lat, double lon, String placeName, boolean network) throws Exception {
        if (!available()) return null;
        File dir = c.getFilesDir();
        Object r;
        if (network) {
            Method m = cls.getMethod("generate", double.class, double.class, String.class, File.class);
            r = m.invoke(null, lat, lon, placeName, dir);
        } else {
            r = cls.getMethod("cached", double.class, double.class, File.class).invoke(null, lat, lon, dir);
            if (r == null) return null;
        }
        if (r == null) return null;
        Class<?> rc = r.getClass();
        String day = (String) rc.getField("daySvg").get(r);
        String night = (String) rc.getField("nightSvg").get(r);
        String desc = String.valueOf(rc.getField("description").get(r));
        boolean cached = rc.getField("fromCache").getBoolean(r);
        boolean complete = rc.getField("complete").getBoolean(r);
        return new String[]{day, night, desc + (cached ? " [cached]" : "") + (complete ? "" : " [partial/generic]")};
    }
}
