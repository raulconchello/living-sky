package dev.livingsky;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import dev.livingsky.render.Ensemble;
import dev.livingsky.render.Sources;

/**
 * Ensemble precipitation (Open-Meteo ensemble API, no key): icon_seamless (40 members), then gfs025, then
 * ecmwf_ifs025. Per hour only P, R over the wet members and the snow share are kept (small cache). Runs come every
 * 6 h, and ensemble calls weigh more on the daily limit, so it is refetched every 2 h.
 */
final class EnsembleClient {
    private EnsembleClient() {}

    static final long STALE_MS = 120 * 60_000L;

    /** Fetches (trying each model) and stores; throws when every model fails. */
    static Ensemble fetch(Context c, double lat, double lon) throws Exception {
        Exception last = null;
        for (String m : Sources.ENS_MODELS) {
            try {
                String body = Sources.get(Sources.ensembleUrl(lat, lon, m), 25000);
                Ensemble e = Ensemble.parse(body, m, lat, lon, System.currentTimeMillis());
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

    static void store(Context c, Ensemble e) throws Exception {
        File tmp = new File(c.getFilesDir(), "ensemble.json.tmp");
        try (FileOutputStream f = new FileOutputStream(tmp)) { f.write(e.toJson().getBytes(StandardCharsets.UTF_8)); }
        if (!tmp.renameTo(new File(c.getFilesDir(), "ensemble.json"))) throw new Exception("cannot store ensemble cache");
    }

    /** The last good ensemble, or null (also for an older cache format: refetched). */
    static Ensemble cached(Context c) {
        File f = new File(c.getFilesDir(), "ensemble.json");
        if (!f.exists()) return null;
        try {
            return Ensemble.fromJson(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
        } catch (Exception ex) {
            return null;
        }
    }
}
