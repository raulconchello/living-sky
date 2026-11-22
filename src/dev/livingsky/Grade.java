package dev.livingsky;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.security.MessageDigest;

import dev.livingsky.render.DaylightGrade;
import dev.livingsky.render.Noise;
import dev.livingsky.render.SkyShader;

/**
 * Applies the daylight grade (proposal B) to the rendered skyline and ground layers. The result depends only on the
 * skyline, the hints and a few quantised inputs (sunny / overcast blend, light side, dusk fade), so it is cached in
 * memory and on disk: the steady-state cost of a re-render is a bitmap copy.
 */
final class Grade {
    private Grade() {}

    static volatile long lastMs;          // last uncached grade time (skyline + ground), for the debug info
    static volatile boolean lastCached;
    private static String memKey;
    private static int[] memPx;
    private static String memKeyG;
    private static int[] memPxG;
    private static Context app;

    static void init(Context c) { app = c.getApplicationContext(); }

    private static String sha(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes("UTF-8"));
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < 8; i++) b.append(String.format("%02x", d[i]));
            return b.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    static void skyline(Bitmap sk, Renderer.Input in, SkyShader.Params sp, float k) {
        DaylightGrade.Hints h = in.sky.hints != null ? in.sky.hints : DaylightGrade.Hints.generic("DE");
        double w = Math.round(DaylightGrade.weight(sp.cover) * 4) / 4.0;
        int side = DaylightGrade.side(sp.sunX);
        double amount = Math.round(DaylightGrade.amount(sp.el) * 10) / 10.0;
        if (amount <= 0 && !DaylightGrade.towers(h)) return;   // the towers landmark keeps its dusk silhouette
        String doc = in.sky.day;
        String key = "v5|" + sha(doc) + "|" + hintsKey(h) + "|" + w + "|" + side + "|" + amount + "|" + sk.getWidth() + "x" + sk.getHeight();
        apply(sk, key, true, () -> {
            int W = sk.getWidth(), H = sk.getHeight();
            int[] px = new int[W * H];
            sk.getPixels(px, 0, W, 0, 0, W, H);
            DaylightGrade.grade(px, W, H, k, h, w, side, amount, new Noise(20261121));
            return px;
        });
    }

    static void ground(Bitmap gr, SkyShader.Params sp, float k) {
        double w = Math.round(DaylightGrade.weight(sp.cover) * 4) / 4.0;
        double amount = Math.round(DaylightGrade.amount(sp.el) * 10) / 10.0;
        if (amount <= 0) return;
        String key = "g2|" + w + "|" + amount + "|" + gr.getWidth() + "x" + gr.getHeight();
        apply(gr, key, false, () -> {
            int W = gr.getWidth(), H = gr.getHeight();
            int[] px = new int[W * H];
            gr.getPixels(px, 0, W, 0, 0, W, H);
            DaylightGrade.groundW(px, W, H, k, w, amount, new Noise(20261121));
            return px;
        });
    }

    private interface Job { int[] run(); }

    private static synchronized void apply(Bitmap b, String key, boolean skyline, Job job) {
        int W = b.getWidth(), H = b.getHeight();
        int[] px = skyline ? (key.equals(memKey) ? memPx : null) : (key.equals(memKeyG) ? memPxG : null);
        File f = app == null ? null : new File(new File(app.getCacheDir(), "grade"), sha(key) + ".png");
        if (px == null && f != null && f.exists()) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPremultiplied = false;
            Bitmap c = BitmapFactory.decodeFile(f.getPath(), o);
            if (c != null && c.getWidth() == W && c.getHeight() == H) {
                px = new int[W * H];
                c.getPixels(px, 0, W, 0, 0, W, H);
            }
            if (c != null) c.recycle();
        }
        if (px == null) {
            long t0 = System.nanoTime();
            px = job.run();
            long ms = (System.nanoTime() - t0) / 1000000;
            lastMs = skyline ? ms : lastMs + ms;
            lastCached = false;
            Log.i(Locator.TAG, (skyline ? "skyline" : "ground") + " grade " + ms + " ms (" + W + "x" + H + ")");
            if (f != null) {
                try {
                    //noinspection ResultOfMethodCallIgnored
                    f.getParentFile().mkdirs();
                    Bitmap c = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
                    c.setPremultiplied(false);
                    c.setPixels(px, 0, W, 0, 0, W, H);
                    try (FileOutputStream fo = new FileOutputStream(f)) { c.compress(Bitmap.CompressFormat.PNG, 100, fo); }
                    c.recycle();
                    pruneCache(f.getParentFile());
                } catch (Exception e) {
                    Log.w(Locator.TAG, "grade cache write failed", e);
                }
            }
        } else if (skyline) {
            lastCached = true;
        }
        if (skyline) { memKey = key; memPx = px; } else { memKeyG = key; memPxG = px; }
        b.setPixels(px, 0, W, 0, 0, W, H);
    }

    /** Keep the disk cache small: the 40 most recent graded layers (~0.3 MB each). */
    private static void pruneCache(File dir) {
        File[] fs = dir.listFiles();
        if (fs == null || fs.length <= 40) return;
        java.util.Arrays.sort(fs, (a, c) -> Long.compare(c.lastModified(), a.lastModified()));
        for (int i = 40; i < fs.length; i++) //noinspection ResultOfMethodCallIgnored
            fs[i].delete();
    }

    private static String hintsKey(DaylightGrade.Hints h) {
        StringBuilder b = new StringBuilder(h.pal).append(h.hero[0]).append(h.hero[1]).append(h.heroBottom).append(h.heroC).append(h.heroTopC).append(h.softHero);
        if (h.water != null) for (double v : h.water) b.append(',').append(v);
        for (DaylightGrade.Accent a : h.acc) { for (double v : a.b) b.append(',').append(v); b.append(a.k).append(a.l).append(a.cc[0]).append(a.cc[1]); }
        b.append('|').append(h.heroModel);
        DaylightGrade.Towers t = h.towers;
        if (t != null) {
            for (double[] c : new double[][]{t.litOld, t.litNew, t.litWhite, t.shadeOld, t.shadeNew, t.shadeWhite, t.ovcOld, t.ovcNew, t.ovcWhite, t.mosaic})
                for (double v : c) b.append(',').append(v);
            b.append(',').append(t.mosaicK).append(',').append(t.kY);
            if (t.silhouette != null) for (double v : t.silhouette) b.append(',').append(v);
        }
        return sha(b.toString());
    }
}
