package dev.livingsky.skyline;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Automatic "Living Sky" skyline for places without a hand-drawn one.
 *
 * <p>{@link #generate} is blocking (network + rendering, about 10 s worst case on a new place, a few ms from cache):
 * call it on a background thread. Results are cached per ~1 km grid cell in {@code cacheDir}, so each new place is
 * fetched once. It never throws for network or data problems: it falls back to a generic low town with hills.
 *
 * <p>Output SVGs follow design/skylines/SPEC.md (viewBox 0 0 379.4 192.4, horizon y=134, hero zone x 168-268).
 */
public final class AutoSkyline {
    private AutoSkyline() {}

    /** Generated skyline. */
    public static final class Result {
        public final String daySvg;
        public final String nightSvg;
        /** Human-readable summary: view direction, landmarks, terrain, data sources. For logs / the debug screen. */
        public final String description;
        /** True when every data source answered; false for partial data or the generic fallback. */
        public final boolean complete;
        /** True when served from the on-disk cache without network. */
        public final boolean fromCache;

        Result(String day, String night, String description, boolean complete, boolean fromCache) {
            this.daySvg = day; this.nightSvg = night; this.description = description;
            this.complete = complete; this.fromCache = fromCache;
        }
    }

    /** Bump when the rendering changes so cached SVGs are re-rendered from cached data (no refetch). */
    static final int RENDER_VERSION = 16;
    /** Tests: ignore rendered SVGs in the cache (data is still reused). */
    static volatile boolean ignoreSvgCache;
    /** Total time budget for a new place, milliseconds. */
    public static volatile long budgetMillis = 10_000;
    /** Retry a place whose data was partial (one source failed) after this long. */
    public static volatile long retryPartialMillis = 6 * 3600_000L;
    /** After a total failure, don't hit the network again for this long (serve the generic town). */
    public static volatile long retryFailedMillis = 20 * 60_000L;
    /** Grid cell size in degrees of latitude (~1 km). */
    static final double CELL_DEG = 0.009;

    private static final ConcurrentHashMap<String, Object> LOCKS = new ConcurrentHashMap<>();

    /**
     * Generate (or load from cache) the automatic skyline for a place.
     *
     * @param lat       latitude of the phone
     * @param lon       longitude of the phone
     * @param placeName display name (e.g. "Weststadt, Ulm"); used only in the description, may be null
     * @param cacheDir  writable directory (e.g. context.getCacheDir() or getFilesDir()); may be null to disable caching
     */
    public static Result generate(double lat, double lon, String placeName, File cacheDir) throws IOException {
        long t0 = System.currentTimeMillis();
        if (Double.isNaN(lat) || Double.isNaN(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180) return generic(0, 0, placeName);
        double[] cc = cellCentre(lat, lon);
        String key = cellKey(lat, lon);
        File dir = cacheDir == null ? null : new File(cacheDir, "autoskyline");
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) dir = null;
        Object lock = LOCKS.putIfAbsent(key, new Object());
        if (lock == null) lock = LOCKS.get(key);
        synchronized (lock) {
            try {
                return generateLocked(cc[0], cc[1], placeName, dir, key, t0);
            } catch (RuntimeException e) {
                // never fail hard
                return generic(lat, lon, placeName);
            }
        }
    }

    /** Cached result for this place, or null (no network, fast; safe on the main thread for small files). */
    public static Result cached(double lat, double lon, File cacheDir) {
        if (cacheDir == null) return null;
        File f = new File(new File(cacheDir, "autoskyline"), cellKey(lat, lon) + ".svg.json");
        try {
            JSONObject o = new JSONObject(read(f));
            if (o.optInt("rv") != RENDER_VERSION) return null;
            return new Result(o.getString("day"), o.getString("night"), o.optString("desc"), o.optBoolean("complete"), true);
        } catch (IOException | JSONException e) {
            return null;
        }
    }

    /** The generic low town with hills (no network). Deterministic for the coordinates. */
    public static Result generic(double lat, double lon, String placeName) {
        double[] cc = cellCentre(lat, lon);
        PlaceData d = new PlaceData();
        d.lat = cc[0]; d.lon = cc[1]; d.placeName = placeName == null ? "" : placeName;
        Composer c = new Composer(d);
        c.plan();
        return new Result(c.render(false, placeName), c.render(true, placeName), c.describe(placeName) + " (generic)", false, false);
    }

    // ------------------------------------------------------------------ internals

    static String cellKey(double lat, double lon) {
        long li = (long) Math.floor(lat / CELL_DEG);
        double latC = (li + 0.5) * CELL_DEG;
        double lonStep = CELL_DEG / Math.max(0.05, Math.cos(Math.toRadians(latC)));
        long lo = (long) Math.floor(lon / lonStep);
        return "c" + li + "_" + lo;
    }

    static double[] cellCentre(double lat, double lon) {
        long li = (long) Math.floor(lat / CELL_DEG);
        double latC = (li + 0.5) * CELL_DEG;
        double lonStep = CELL_DEG / Math.max(0.05, Math.cos(Math.toRadians(latC)));
        long lo = (long) Math.floor(lon / lonStep);
        return new double[]{latC, (lo + 0.5) * lonStep};
    }

    private static Result generateLocked(double lat, double lon, String placeName, File dir, String key, long t0) {
        long now = System.currentTimeMillis();
        File svgF = dir == null ? null : new File(dir, key + ".svg.json");
        File dataF = dir == null ? null : new File(dir, key + ".data.json");
        File failF = dir == null ? null : new File(dir, key + ".fail");

        PlaceData d = null;
        if (dataF != null && dataF.isFile()) {
            try { d = PlaceData.fromJson(new JSONObject(read(dataF))); } catch (IOException | JSONException e) { d = null; }
        }
        boolean stalePartial = d != null && !d.complete() && now - d.fetchedAt > retryPartialMillis;
        // 1) fresh rendered cache
        if (d != null && svgF != null && svgF.isFile() && !stalePartial && !ignoreSvgCache) {
            Result r = cached(lat, lon, dir.getParentFile());
            if (r != null) return r;
        }
        // 2) recent total failure: don't hammer the network
        if (d == null && failF != null && failF.isFile() && now - failF.lastModified() < retryFailedMillis) {
            return generic(lat, lon, placeName);
        }
        long deadline = t0 + budgetMillis;
        boolean fetched = false;
        if (d == null || stalePartial) {
            PlaceData nd = fetch(lat, lon, placeName, d, deadline);
            fetched = true;
            if (nd == null || (!nd.osmOk && !nd.wdOk && !nd.terrainOk)) {
                touch(failF);
                if (d == null) return generic(lat, lon, placeName);
            } else d = nd;
        }
        if (placeName != null && !placeName.isEmpty()) d.placeName = placeName;
        // 3) compose; the terrain profile depends on the chosen view, fetch it if not cached
        Composer c = new Composer(d);
        c.plan();
        String pk = c.profileKey();
        boolean profileOk = false;
        if (pk.equals(d.profileKey) && d.profile != null) {
            c.applyProfile(d.profile);
            profileOk = true;
        } else {
            long pdl = Math.max(deadline, System.currentTimeMillis() + 2500);
            try {
                double[][] req = c.profileRequest();
                double[] ev = elevationsParallel(req[0], req[1], pdl);
                // landmark bases may move the layout (lifts); the key stays the hero/theta based one
                d.profile = ev; d.profileKey = pk;
                c.applyProfile(ev);
                profileOk = true;
            } catch (IOException e) {
                d.errors = (d.errors + " profile: " + e.getMessage()).trim();
            }
        }
        String day = c.render(false, d.placeName), night = c.render(true, d.placeName);
        String desc = c.describe(d.placeName);
        boolean complete = d.complete() && profileOk;
        if (dir != null) {
            try {
                write(dataF, d.toJson().toString());
                JSONObject o = new JSONObject();
                o.put("rv", RENDER_VERSION); o.put("day", day); o.put("night", night); o.put("desc", desc); o.put("complete", complete);
                o.put("at", System.currentTimeMillis());
                write(svgF, o.toString());
                if (failF != null && failF.isFile()) failF.delete();
            } catch (IOException | JSONException ignored) { }
        }
        return new Result(day, night, desc, complete, !fetched && profileOk && pk.equals(d.profileKey));
    }

    private static final ThreadFactory DAEMON = new ThreadFactory() {
        @Override public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "autoskyline-net");
            t.setDaemon(true);
            return t;
        }
    };

    /** Fetch all sources in parallel within the deadline. Keeps sources that already succeeded in {@code old}. */
    static PlaceData fetch(final double lat, final double lon, String placeName, PlaceData old, final long deadline) {
        final PlaceData osm = new PlaceData(), wd = new PlaceData(), ter = new PlaceData();
        for (PlaceData p : new PlaceData[]{osm, wd, ter}) { p.lat = lat; p.lon = lon; }
        final boolean needOsm = old == null || !old.osmOk, needWd = old == null || !old.wdOk, needTer = old == null || !old.terrainOk;
        ExecutorService ex = Executors.newFixedThreadPool(3, DAEMON);
        final long landmarkDeadline = deadline - Math.min(2500, budgetMillis / 4);
        StringBuilder errs = new StringBuilder();
        try {
            Future<?> fo = !needOsm ? null : ex.submit(new Callable<Void>() {
                @Override public Void call() throws Exception { Sources.fetchOverpass(osm, landmarkDeadline); return null; }
            });
            Future<?> fw = !needWd ? null : ex.submit(new Callable<Void>() {
                @Override public Void call() throws Exception { Sources.fetchWikidata(wd, landmarkDeadline); return null; }
            });
            Future<?> ft = !needTer ? null : ex.submit(new Callable<Void>() {
                @Override public Void call() throws Exception { survey(ter, landmarkDeadline); return null; }
            });
            for (Future<?> fu : new Future<?>[]{fo, fw, ft}) {
                if (fu == null) continue;
                long left = landmarkDeadline + 300 - System.currentTimeMillis();
                try { fu.get(Math.max(1, left), TimeUnit.MILLISECONDS); }
                catch (Exception e) {
                    fu.cancel(true);
                    Throwable c = e.getCause() != null ? e.getCause() : e;
                    errs.append(fu == fo ? "osm: " : fu == fw ? "wikidata: " : "terrain: ").append(c.getClass().getSimpleName()).append(' ')
                            .append(c.getMessage() == null ? "" : c.getMessage()).append("; ");
                }
            }
        } finally {
            ex.shutdownNow();
        }
        PlaceData d = new PlaceData();
        d.lat = lat; d.lon = lon; d.placeName = placeName == null ? "" : placeName;
        d.fetchedAt = System.currentTimeMillis();
        // OSM first (geometry), then Wikidata merged into it
        PlaceData src = needOsm ? osm : old;
        if (src.osmOk) {
            d.osmOk = true;
            for (Landmark l : src.landmarks) if (l.fromOsm) { Landmark c = copyOsm(l); d.landmarks.add(c); }
            d.water.addAll(src.water);
            d.placeType = src.placeType; d.population = src.population; d.placeDist = src.placeDist;
            d.tallCount = src.tallCount; d.osmCount = src.osmCount;
        }
        PlaceData wsrc = needWd ? wd : old;
        if (wsrc.wdOk) {
            d.wdOk = true;
            for (Landmark l : wsrc.landmarks) if (l.fromWd && !l.fromOsm) Sources.mergeWd(d, l);
            if (d.population == 0) d.population = wsrc.population;
            for (PlaceData.Water w : wsrc.water) {
                boolean dup = false;
                for (PlaceData.Water o : d.water) if (o.type.equals(w.type) && o.lat == w.lat && o.lon == w.lon) dup = true;
                if (!dup) d.water.add(w);
            }
            if (!needWd) for (Landmark l : old.landmarks) if (l.fromWd && l.fromOsm) Sources.mergeWd(d, wdPart(l));
        }
        PlaceData tsrc = needTer ? ter : old;
        if (tsrc.terrainOk) { d.terrainOk = true; d.survey = tsrc.survey; d.viewerElev = tsrc.viewerElev; }
        d.errors = errs.toString().trim();
        prune(d);
        return d;
    }

    /** Keep the cache small in dense cities: the most notable/tallest landmarks and the largest water features. */
    static void prune(PlaceData d) {
        if (d.landmarks.size() > 350) {
            java.util.Collections.sort(d.landmarks, new java.util.Comparator<Landmark>() {
                @Override public int compare(Landmark a, Landmark b) {
                    double ka = a.sitelinks * 10 + a.estimateHeight() + (a.isTall() ? 0 : 40);
                    double kb = b.sitelinks * 10 + b.estimateHeight() + (b.isTall() ? 0 : 40);
                    return Double.compare(kb, ka);
                }
            });
            d.landmarks.subList(350, d.landmarks.size()).clear();
        }
        if (d.water.size() > 40) {
            java.util.Collections.sort(d.water, new java.util.Comparator<PlaceData.Water>() {
                @Override public int compare(PlaceData.Water a, PlaceData.Water b) {
                    return Double.compare(Math.max(b.sizeE, b.sizeN), Math.max(a.sizeE, a.sizeN));
                }
            });
            d.water.subList(40, d.water.size()).clear();
        }
    }

    private static Landmark copyOsm(Landmark l) {
        return Landmark.fromJson(safeJson(l));
    }

    private static Landmark wdPart(Landmark l) {
        Landmark w = Landmark.fromJson(safeJson(l));
        w.fromOsm = false;
        return w;
    }

    private static JSONObject safeJson(Landmark l) {
        try { return l.toJson(); } catch (JSONException e) { return new JSONObject(); }
    }

    /** Polar terrain survey around the viewer plus the viewer's own elevation. */
    static void survey(PlaceData d, long deadline) throws IOException {
        int na = PlaceData.SURVEY_AZ, nd = PlaceData.SURVEY_D.length;
        double[] la = new double[na * nd + 1], lo = new double[na * nd + 1];
        for (int a = 0; a < na; a++) {
            double az = Math.toRadians(a * 360.0 / na);
            for (int k = 0; k < nd; k++) {
                double[] p = Sources.offset(d.lat, d.lon, PlaceData.SURVEY_D[k] * Math.sin(az), PlaceData.SURVEY_D[k] * Math.cos(az));
                la[a * nd + k] = p[0]; lo[a * nd + k] = p[1];
            }
        }
        la[na * nd] = d.lat; lo[na * nd] = d.lon;
        double[] ev = Sources.elevations(la, lo, deadline);
        d.survey = java.util.Arrays.copyOf(ev, na * nd);
        d.viewerElev = ev[na * nd];
        int ok = 0;
        for (double e : ev) if (!Double.isNaN(e)) ok++;
        d.terrainOk = ok > ev.length / 2 && !Double.isNaN(d.viewerElev);
        if (!d.terrainOk) throw new IOException("elevation: too few samples");
    }

    /** Elevations in batches of 100, fetched concurrently. */
    static double[] elevationsParallel(double[] la, double[] lo, final long deadline) throws IOException {
        final int n = la.length;
        final double[] out = new double[n];
        java.util.Arrays.fill(out, Double.NaN);
        int batches = (n + 99) / 100;
        ExecutorService ex = Executors.newFixedThreadPool(Math.min(4, batches), DAEMON);
        try {
            @SuppressWarnings("unchecked") Future<double[]>[] fs = new Future[batches];
            for (int b = 0; b < batches; b++) {
                final int i0 = b * 100, i1 = Math.min(n, i0 + 100);
                final double[] bl = java.util.Arrays.copyOfRange(la, i0, i1), bo = java.util.Arrays.copyOfRange(lo, i0, i1);
                fs[b] = ex.submit(new Callable<double[]>() {
                    @Override public double[] call() throws Exception { return Sources.elevations(bl, bo, deadline); }
                });
            }
            int okB = 0;
            for (int b = 0; b < batches; b++) {
                try {
                    double[] v = fs[b].get(Math.max(1, deadline + 200 - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
                    System.arraycopy(v, 0, out, b * 100, v.length);
                    okB++;
                } catch (Exception e) { fs[b].cancel(true); }
            }
            if (okB == 0) throw new IOException("elevation profile failed");
        } finally {
            ex.shutdownNow();
        }
        return out;
    }

    // ------------------------------------------------------------------ file helpers

    static String read(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return new String(bo.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    static void write(File f, String s) throws IOException {
        File tmp = new File(f.getPath() + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) { out.write(s.getBytes(StandardCharsets.UTF_8)); }
        if (!tmp.renameTo(f)) {
            f.delete();
            if (!tmp.renameTo(f)) throw new IOException("rename failed " + f);
        }
    }

    private static void touch(File f) {
        if (f == null) return;
        try { write(f, String.format(Locale.ROOT, "%d", System.currentTimeMillis())); } catch (IOException ignored) { }
    }
}
