package dev.livingsky;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;

import com.caverock.androidsvg.RenderOptions;
import com.caverock.androidsvg.SVG;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import dev.livingsky.render.BandShader;
import dev.livingsky.render.Noise;
import dev.livingsky.render.Px;
import dev.livingsky.render.Scene;
import dev.livingsky.render.SkyShader;
import dev.livingsky.render.Sun;
import dev.livingsky.render.SvgGen;
import dev.livingsky.render.Timeline;
import dev.livingsky.render.Wx;
import dev.livingsky.render.WxClass;
import dev.livingsky.render.WxState;

/**
 * Renders the widget exactly like the approved HTML/JS reference (design/skylines/preview/ulm_*.html + sky8.js):
 * the card bitmap (sky, skyline SVG, near-bank ground, column shadow, all left text, day strip) and the tall
 * 48 h column bitmap (sky band, 15-min rain bars, hour labels, hourly temperatures). Layout numbers are the CSS
 * values in dp; Inter metrics give the same baselines as Chromium.
 */
final class Renderer {
    /** Two-letter day names for the column's midnight label, indexed by Calendar.DAY_OF_WEEK - 1. */
    static final String[] DAY2 = {"SU", "MO", "TU", "WE", "TH", "FR", "SA"};

    private Renderer() {}

    // card + column geometry (dp)
    static final float CARD_W = 379.4f, CARD_H = 192.4f;
    static final float COL_X0 = 270, COL_X1 = 372, COL_Y0 = 7, COL_Y1 = 185.4f;
    static final float VIEW_H = 8, TOTAL_H = 72;
    static final float PXH = (COL_Y1 - COL_Y0) / VIEW_H; // dp per hour in the column
    // Inter vertical metrics (per em)
    static final float ASC = 1984f / 2048, LH = (1984f + 494f) / 2048;
    static final String MINUS = "−";
    static final String FEAT = "tnum, cv11", FEAT_STRIP = "cv11";
    static final boolean CARD_HAIRLINE = true;

    static float baseline(float top, float size) { return top + ASC * size; }

    /**
     * The card's left text (location, clock, temperature, feels-like, UV / RH, day strip) has the column numbers' halo:
     * each glyph its own soft shadow, from the glyphs at full strength grown by a 0.6 dp round stroke, two black shadows
     * 4 dp + 1.2 dp at 70 % (Raul's choice "A", 24 Nov). No shared darkening behind the text block.
     * Debug (--es textfx): "old" = the previous look (CSS shadows with the 12 / 26 dp halos, plus the sky's text scrim),
     * "mask" = every glyph opaque magenta and no shadows (for contrast measurements).
     */
    static String textFx(Context ctx) { return ctx == null ? "" : new Prefs(ctx).textFx(); }

    static boolean oldText(Context ctx) { return "old".equals(textFx(ctx)); }

    /** A text batch for the card's left text. */
    static TextKit.Batch textBatch(Context ctx, int w, int h, float k) {
        TextKit.Batch b = new TextKit.Batch(w, h);
        String fx = textFx(ctx);
        if (fx.equals("mask")) b.force(0xffff00ff);
        else if (!fx.equals("old")) b.halo(0.6f * k);
        return b;
    }

    static TextKit.Shadow[] textShadows(Context ctx, float k, boolean day) {
        String fx = textFx(ctx);
        if (fx.equals("old")) return textShadows(k, day);
        if (fx.equals("mask")) return new TextKit.Shadow[0];
        int s = Color.argb(Math.round(0.7f * 255), 0, 0, 0);
        return new TextKit.Shadow[]{new TextKit.Shadow(0, 0, 4 * k, s), new TextKit.Shadow(0, 0, 1.2f * k, s)};
    }

    /** The previous CSS text-shadow (only --es textfx old): proposal B by day (bluish, plus wide halos), the original at night. */
    static TextKit.Shadow[] textShadows(float k, boolean day) {
        if (day) return new TextKit.Shadow[]{
                new TextKit.Shadow(0, 1 * k, 1.5f * k, Color.argb(Math.round(0.45f * 255), 8, 18, 40)),
                new TextKit.Shadow(0, 0, 12 * k, Color.argb(Math.round(0.50f * 255), 8, 22, 52)),
                new TextKit.Shadow(0, 0, 26 * k, Color.argb(Math.round(0.22f * 255), 8, 22, 52))};
        int sc = Color.argb(Math.round(0.35f * 255), 10, 12, 20);
        return new TextKit.Shadow[]{new TextKit.Shadow(0, 1 * k, 1 * k, sc), new TextKit.Shadow(0, 0, 10 * k, sc)};
    }                       // line-height: normal
    static float baseline(float top, float size, float lh) { return top + (lh - LH * size) / 2 + ASC * size; }

    static final class Input {
        Wx wx;
        Timeline tl;           // what everything on screen reads
        double now;            // hours since epoch
        double lat, lon;
        String place;          // "Weststadt, Ulm"
        Places.Skyline sky;
        int mode;              // Scene.MODE_*
        boolean mock;
        boolean noData;        // no forecast at all yet: placeholders
        boolean day;           // daylight look (proposal B) for the text shadows
        boolean colValues;     // timeline column in values mode (after a tap)
        double alarmAt = Double.NaN; // next alarm (hours since epoch), NaN if none
        double el;             // sun elevation now (condition words after sunset)
        TimeZone tz;
    }

    static final class Geo {
        float k;
        int cardW, cardH, colL, colT, colW, colH;

        static Geo of(float k) {
            Geo g = new Geo();
            g.k = k;
            g.cardW = Math.round(CARD_W * k);
            g.cardH = Math.round(CARD_H * k);
            g.colL = Math.round(COL_X0 * k);
            g.colT = Math.round(COL_Y0 * k);
            g.colW = Math.round((COL_X1 - COL_X0) * k);
            g.colH = Math.round((COL_Y1 - COL_Y0) * k);
            return g;
        }
    }

    static final class Out {
        Geo geo;
        Bitmap card, column;
        int[] rowEdges;      // px, ascending, first 0, last column height
        long[] rowIds;
        boolean night;
        double el;
        String clockText;    // fixed text in mock / time-travel mode, else null (live TextClock)
        boolean day;         // daylight text shadows for the clock bitmap
        boolean colValues;   // the column was rendered in values mode
        int alarmMode;       // ALARM_NONE / ALARM_PILL (in the column) / ALARM_TAB (static tab on the column's bottom edge)
        Bitmap alarmTab;     // the tab bitmap (with its shadow margins), null unless ALARM_TAB
        String alarmText;    // "8:45"
        float clockBaselineDp = baseline(22, 54, 54);
        String info;
    }

    // ------------------------------------------------------------------ helpers
    static String deg(double v) {
        long r = Math.round(v);
        if (r == 0) return "0°";
        return (r < 0 ? MINUS + (-r) : String.valueOf(r)) + "°";
    }

    /** Renders an SVG whose viewBox is the card (0 0 379.4 192.4) in card coordinates (1 user unit = 1 dp). */
    static void svg(Canvas c, String doc, float k) {
        try {
            SVG s = SVG.getFromString(doc);
            s.setDocumentWidth(CARD_W);
            s.setDocumentHeight(CARD_H);
            s.setDocumentViewBox(0, 0, CARD_W, CARD_H);
            c.save();
            c.scale(k, k);
            s.renderToCanvas(c, new RenderOptions().viewPort(0, 0, CARD_W, CARD_H));
            c.restore();
        } catch (Exception e) {
            android.util.Log.w(Locator.TAG, "svg render failed", e);
        }
    }

    static String asset(Context c, String path) {
        try {
            return Places.read(c, path);
        } catch (Exception e) {
            return null;
        }
    }

    /** Draws an icon SVG (viewBox 24) into a size x size dp box with CSS drop-shadow(0 0 3px rgba(0,0,0,.35)). */
    static void icon(Context ctx, Canvas c, String key, float x, float y, float size, float k) {
        String doc = asset(ctx, "icons/" + key + ".svg");
        if (doc == null) return;
        try {
            SVG s = SVG.getFromString(doc);
            int pad = (int) Math.ceil(12 * k);
            int bw = (int) Math.ceil(size * k) + 2 * pad;
            Bitmap ib = Bitmap.createBitmap(bw, bw, Bitmap.Config.ARGB_8888);
            Canvas ic = new Canvas(ib);
            float fx = x * k - pad, fy = y * k - pad;
            float ox = fx - (float) Math.floor(fx), oy = fy - (float) Math.floor(fy);
            s.setDocumentWidth(24);
            s.setDocumentHeight(24);
            s.setDocumentViewBox(0, 0, 24, 24);
            ic.save();
            ic.translate(pad + ox, pad + oy);
            ic.scale(size * k / 24, size * k / 24);
            s.renderToCanvas(ic, new RenderOptions().viewPort(0, 0, 24, 24));
            ic.restore();
            // filter: drop-shadow's length is the Gaussian standard deviation
            Paint bp = new Paint();
            bp.setMaskFilter(new BlurMaskFilter(TextKit.blurRadius(3 * k), BlurMaskFilter.Blur.NORMAL));
            int[] off = new int[2];
            Bitmap sh = ib.extractAlpha(bp, off);
            Paint sp = new Paint(Paint.FILTER_BITMAP_FLAG);
            sp.setColor(Color.argb(89, 0, 0, 0));
            float bx = (float) Math.floor(fx), by = (float) Math.floor(fy);
            c.drawBitmap(sh, bx + off[0], by + off[1], sp);
            c.drawBitmap(ib, bx, by, null);
            sh.recycle();
            ib.recycle();
        } catch (Exception e) {
            android.util.Log.w(Locator.TAG, "icon " + key, e);
        }
    }

    // ------------------------------------------------------------------ card
    static Out render(Context ctx, Input in, float k) {
        Out o = new Out();
        Geo g = Geo.of(k);
        o.geo = g;
        SkyShader.Params sp = Scene.sky(in.tl, in.now, in.lat, in.lon, in.mock, in.mode, in.sky.heroGlow);
        in.day = !sp.night && sp.daylight;
        in.el = Sun.elevation(in.now, in.lat, in.lon);
        o.night = sp.night;
        o.day = in.day;
        o.el = sp.el;
        if (!in.mock && !WeatherFx.oldClouds && sp.state == WxState.SHOWERS) sp.cover = Math.max(sp.cover, 0.84); // grey wet sky
        o.card = card(ctx, in, g, sp);
        // twilight: the day and night looks (sky, skyline, lights, stars) cross-fade between sun -2 and -6 deg,
        // so dusk and dawn have no step where the night look used to switch on at -4
        double nightness = Noise.sm(-2, -6, sp.el);
        if (!in.mock && !WeatherFx.oldClouds && in.mode == Scene.MODE_AUTO && nightness > 0.004 && nightness < 0.996) {
            SkyShader.Params sp2 = Scene.sky(in.tl, in.now, in.lat, in.lon, in.mock, in.mode, in.sky.heroGlow);
            sp2.night = !sp.night;
            sp2.cover = sp.cover;
            Bitmap other = card(ctx, in, g, sp2);
            Paint bp = new Paint(Paint.FILTER_BITMAP_FLAG);
            bp.setAlpha((int) Math.round(255 * (sp.night ? 1 - nightness : nightness)));
            new Canvas(o.card).drawBitmap(other, 0, 0, bp);
            other.recycle();
        }
        column(ctx, in, g, o);
        return o;
    }

    /** The column band's cloud cover at now (0..1): the timeline's cloud curve, averaged over +-60 min like BandShader.smooth. */
    static double colCover(Timeline tl, double now) {
        double s = 0, ws = 0;
        for (double off = -1; off <= 1.0001; off += 0.25) {
            double wt = 1 - Math.abs(off) / 1.25;
            s += wt * tl.cloudAt(now + off) / 100;
            ws += wt;
        }
        return s / ws;
    }

    static Bitmap card(Context ctx, Input in, Geo g, SkyShader.Params sp) {
        float k = g.k;
        int W = g.cardW, H = g.cardH;
        // 1. sky: painted at half resolution, upscaled (bicubic) and grained, like sky8.js paint(sky, W, H, 0.5, ..., 7)
        sp.layerClouds = !in.mock && WeatherFx.layerClouds(sp.state, 1);
        sp.noScrim = !oldText(ctx);   // the text carries its own halo: no shared darkening behind it
        int pw = (int) Math.round(CARD_W * k * 0.5), ph = (int) Math.round(CARD_H * k * 0.5);
        int[] lo = new SkyShader(sp).render(pw, ph, k * 0.5);
        int[] full = Px.upscale(lo, pw, ph, W, H);
        Px.grain(full, 7, System.nanoTime());
        Bitmap bm = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        bm.setPixels(full, 0, W, 0, 0, W, H);
        Canvas c = new Canvas(bm);

        // 2. skyline SVG, then the near-bank ground band over it, with the weather layered around them
        WeatherFx fx = in.mock ? null : new WeatherFx(Scene.state(in.tl), sp, k, W, H, in.now, in.lat, in.lon);
        if (fx != null) { fx.colCover = colCover(in.tl, in.now); fx.sky(c, bm); }
        Bitmap sk = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        svg(new Canvas(sk), sp.night ? in.sky.night : in.sky.day, k);
        boolean graded = !sp.night && sp.daylight;
        if (graded) Grade.skyline(sk, in, sp, k);
        Paint skp = null;
        if (graded && sp.storm > 0.15) { // the brighter daylight skyline goes dark under rain and storms
            float f = (float) (1 - 0.5 * Math.min(1, sp.storm));
            skp = new Paint();
            skp.setColorFilter(new android.graphics.ColorMatrixColorFilter(new float[]{f, 0, 0, 0, 0, 0, f, 0, 0, 0, 0, 0, f * 1.02f, 0, 0, 0, 0, 0, 1, 0}));
        }
        c.drawBitmap(sk, 0, 0, skp);
        if (fx != null) fx.afterSkyline(c, sk);
        Bitmap gr = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        svg(new Canvas(gr), SvgGen.ground(sp.night, new Noise(20261121)), k);
        if (graded) Grade.ground(gr, sp, k);
        c.drawBitmap(gr, 0, 0, null);
        if (fx != null) fx.afterGround(c, gr, sk);
        sk.recycle();
        gr.recycle();

        // 3. column: soft shadow to the left (-6px 0 18px rgba(10,12,20,.35)) and a .6 px white hairline (28 %)
        RectF col = new RectF(g.colL, g.colT, g.colL + g.colW, g.colT + g.colH);
        Paint sh = new Paint(Paint.ANTI_ALIAS_FLAG);
        sh.setColor(Color.argb(Math.round(0.35f * 255), 10, 12, 20));
        sh.setMaskFilter(new BlurMaskFilter(TextKit.blurRadius(9 * k), BlurMaskFilter.Blur.NORMAL));
        RectF shr = new RectF(col);
        shr.offset(-6 * k, 0);
        c.drawRoundRect(shr, 14 * k, 14 * k, sh);
        Path ring = new Path();
        ring.setFillType(Path.FillType.EVEN_ODD);
        RectF outer = new RectF(col);
        outer.inset(-0.6f * k, -0.6f * k);
        ring.addRoundRect(outer, 14.6f * k, 14.6f * k, Path.Direction.CW);
        ring.addRoundRect(col, 14 * k, 14 * k, Path.Direction.CW);
        Paint rp = new Paint(Paint.ANTI_ALIAS_FLAG);
        rp.setColor(Color.argb(Math.round(0.28f * 255), 255, 255, 255));
        c.drawPath(ring, rp);

        // 4. text (everything but the clock, which is a live TextClock)
        text(ctx, c, in, g);

        // 5. rounded card (26 dp) + inset hairline
        Bitmap out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas oc = new Canvas(out);
        Paint cp = new Paint(Paint.ANTI_ALIAS_FLAG);
        cp.setShader(new BitmapShader(bm, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        RectF card = new RectF(0, 0, CARD_W * k, CARD_H * k);
        oc.drawRoundRect(card, 26 * k, 26 * k, cp);
        if (CARD_HAIRLINE) {
            Path hl = new Path();
            hl.setFillType(Path.FillType.EVEN_ODD);
            hl.addRoundRect(card, 26 * k, 26 * k, Path.Direction.CW);
            RectF inner = new RectF(card);
            inner.inset(0.6f * k, 0.6f * k);
            hl.addRoundRect(inner, 25.4f * k, 25.4f * k, Path.Direction.CW);
            Paint hp = new Paint(Paint.ANTI_ALIAS_FLAG);
            hp.setColor(Color.argb(Math.round(0.22f * 255), 255, 255, 255));
            oc.drawPath(hl, hp);
        }
        bm.recycle();
        return out;
    }

    static int white(float a) { return Color.argb(Math.round(a * 255), 255, 255, 255); }

    static void text(Context ctx, Canvas c, Input in, Geo g) {
        float k = g.k;
        Timeline tl = in.tl;
        Timeline.Sample now = tl.now;
        TextKit.Batch b = textBatch(ctx, g.cardW, g.cardH, k);

        // --- location line (17, 11): arrow, "Sublocality, City" (12 dp, 500), " · Sun, 22 Nov" (400, 85 %); opacity .95
        float size = 12, ls = 0.2f * k, top = 11;
        float by = baseline(top, size) * k;
        Paint pMain = TextKit.paint(ctx, 500, size * k, white(0.95f), FEAT);
        Paint pDate = TextKit.paint(ctx, 400, size * k, white(0.95f * 0.85f), FEAT);
        // layout option 1: the current condition instead of the date (the date's style; the reference mock keeps the date)
        String place = in.place == null || in.place.isEmpty() ? "" : in.place;
        float x0 = 17 + 10 + 4;
        float maxX = 260 * k;
        String[] cands;
        if (in.mock || in.noData) {
            SimpleDateFormat df = new SimpleDateFormat("EEE, d MMM", Locale.ENGLISH);
            df.setTimeZone(in.tz);
            cands = new String[]{df.format(new Date((long) (in.now * 3600000L)))};
        } else {
            // the same state the card's sky shows (one classifier, the timeline's "now")
            WxState st = Scene.state(tl);
            String[] cw = WxClass.word(st, in.el < -0.83);
            st.windy = false;
            String[] plain = WxClass.word(st, in.el < -0.83);
            // fit at x <= 260 dp: full word, short word, without ", windy", then ellipsize the location
            cands = new String[]{cw[0], cw[1], cw[1].equals("Windy") ? cw[1] : plain[1]};
        }
        String date = "\u00a0·\u00a0" + cands[0];
        float mainW = TextKit.width(pMain, place, ls);
        for (String cnd : cands) {
            date = "\u00a0·\u00a0" + cnd;
            if (x0 * k + mainW + 4 * k + TextKit.width(pDate, date, ls) <= maxX) break;
        }
        float dateW = TextKit.width(pDate, date, ls);
        while (place.length() > 3 && x0 * k + TextKit.width(pMain, place, ls) + 4 * k + dateW > maxX) {
            place = place.substring(0, place.length() - 2).trim() + "…";
        }
        float x = x0 * k;
        if (!place.isEmpty()) {
            x = b.text(pMain, place, x, by, ls) + 4 * k;
        }
        b.text(pDate, date, x, by, ls);
        // arrow glyph (an SVG, so no text-shadow), vertically centred in the 14.52 dp line box
        Path arrow = new Path();
        float ax = 17 * k, ay = (top + (LH * size - 10) / 2) * k, as = 10f / 24 * k;
        arrow.moveTo(ax + 3 * as, ay + 11 * as);
        arrow.lineTo(ax + 21 * as, ay + 3 * as);
        arrow.lineTo(ax + 13 * as, ay + 21 * as);
        arrow.lineTo(ax + 11 * as, ay + 13 * as);
        arrow.close();
        Paint ap = new Paint(Paint.ANTI_ALIAS_FLAG);
        ap.setColor(white(0.95f));

        // --- temperature (14, 78.9): 33.3 dp, 300, letter-spacing -1, whole degrees
        String tempS = in.noData ? "–°" : deg(now.temp);
        Paint pT = TextKit.paint(ctx, 300, 33.3f * k, Color.WHITE, FEAT);
        float tls = -1 * k;
        b.text(pT, tempS, 14 * k, baseline(78.9f, 33.3f, 33.3f) * k, tls);
        // The approved sketch measured the temperature before Inter had loaded, which left a fixed 8.7 dp between
        // the ink of the last glyph and "Feels like"; keep that visual gap for any temperature.
        float right = TextKit.inkRight(pT, tempS, 14 * k, tls) + 8.7f * k;

        // --- "Feels like 1°" (13 dp, 500) at y 80
        Paint pC = TextKit.paint(ctx, 500, 13 * k, Color.WHITE, FEAT);
        b.text(pC, in.noData ? "Waiting for weather…" : "Feels like " + deg(now.feels), right, baseline(80, 13) * k, 0);

        // --- "UV 1   RH 93%" at y 97.5 (11.5 dp, labels #dbe9ff, values 600 white), line-height 14
        if (!in.noData) {
            float sb = baseline(97.5f, 11.5f, 14) * k;
            Paint pL = TextKit.paint(ctx, 400, 11.5f * k, 0xffdbe9ff, FEAT);
            Paint pV = TextKit.paint(ctx, 600, 11.5f * k, Color.WHITE, FEAT);
            float sx = right;
            sx = b.text(pL, "UV ", sx, sb, 0);
            sx = b.text(pV, String.valueOf(Math.max(0, Math.round(now.uv))), sx, sb, 0);
            sx += 10 * k;
            sx = b.text(pL, "RH ", sx, sb, 0);
            b.text(pV, Math.round(Math.max(0, now.rh)) + "%", sx, sb, 0);
        }

        // --- day strip (15, 150), width 246: today + 4 days, aggregated from the column's own hourly samples
        List<float[]> iconPos = new ArrayList<>();
        List<String> iconKeys = new ArrayList<>();
        if (!in.noData) {
            Wx w = tl.wx;
            int d0 = w.dayIndex(in.now);
            SimpleDateFormat dn = new SimpleDateFormat("EEE", Locale.ENGLISH);
            dn.setTimeZone(in.tz);
            // today + the next 4 days (Raul, 24 Nov): today is the whole calendar day (00-24, the hours already past
            // included), labelled with its weekday like the others and marked by a faint tint over its column.
            // Five slots: type and icons a little smaller (name 11.5, max 11.5, min 10.5, icon 17.5 dp) to fit 246 dp.
            // Debug (--es strip old): the previous strip, the next 4 days at the full sizes.
            String mode = new Prefs(ctx).strip();
            boolean old = mode.equals("old");
            int slots = old ? 4 : 5;
            float sName = old ? 12.5f : 11.5f, sHi = old ? 12 : 11.5f, sLo = old ? 11 : 10.5f, sIcon = old ? 19.5f : 17.5f;
            Paint pD = TextKit.paint(ctx, 600, sName * k, Color.WHITE, FEAT_STRIP);
            // today's mark (Raul, 24 Nov, sketch "4i"): a faint tint over today's whole column of the strip, from the
            // top of the strip to the card's bottom edge; the name keeps the others' weight.
            // Debug (--es todaymark old): the previous mark, today's name a touch bolder (0.45 dp stroke on Inter 600).
            boolean oldMark = !old && new Prefs(ctx).todayMarkOld();
            Paint pToday = new Paint(pD);
            if (oldMark) { pToday.setStyle(Paint.Style.FILL_AND_STROKE); pToday.setStrokeWidth(0.45f * k); pToday.setStrokeJoin(Paint.Join.ROUND); }
            Paint pH = TextKit.paint(ctx, 600, sHi * k, Color.WHITE, FEAT_STRIP);
            Paint pLo = TextKit.paint(ctx, 400, sLo * k, white(0.62f), FEAT_STRIP);
            float sls = -0.4f * k;
            List<Object[]> days = new ArrayList<>();
            float total = 0;
            int first = old ? d0 + 1 : d0;
            for (int i = first; i < first + slots && i >= 0 && i < w.dT.length; i++) {
                boolean today = i == d0;
                WxClass.Day day = today ? tl.todayFull() : tl.dayAt(i);
                if (day == null) continue;
                String name = dn.format(new Date((long) ((w.dT[i] + 12) * 3600000L)));
                String hi = deg(day.max), lo = deg(day.min);
                float nameW = TextKit.width(today ? pToday : pD, name, 0);
                float hiW = numW(pH, hi, sls, k), loW = numW(pLo, lo, sls, k);
                float dayW = Math.max(nameW + 3 * k + sIcon * k, hiW + 2.5f * k + loW);
                total += dayW;
                days.add(new Object[]{name, hi, lo, nameW, hiW, dayW, day.icon, today});
            }
            int n = days.size();
            float gap = n > 1 ? Math.max(0, (246 * k - total) / (n - 1)) : 0;
            float dx = 15 * k;
            float yName = (150 + (15.5f - LH * sName) / 2 + ASC * sName) * k;
            float yNum = (150 + 15.5f + ASC * sHi) * k;
            for (Object[] d : days) {
                float nameW = (Float) d[3], hiW = (Float) d[4], dayW = (Float) d[5];
                boolean today = (Boolean) d[7];
                b.text(today ? pToday : pD, (String) d[0], dx, yName, 0);
                if (today && !old && !oldMark) todayTint(c, dx, dayW, k);
                iconPos.add(new float[]{dx / k + nameW / k + 3, 148 + (19.5f - sIcon) / 2, sIcon});
                iconKeys.add((String) d[6]);
                numText(b, pH, (String) d[1], dx, yNum, sls, k);
                numText(b, pLo, (String) d[2], dx + hiW + 2.5f * k, yNum, sls, k);
                dx += dayW + gap;
            }
            if (total > 246 * k) android.util.Log.w(Locator.TAG, "strip: " + Math.round(total / k) + " dp > 246 dp");
        }

        b.flush(c, textShadows(ctx, k, in.day));
        c.drawPath(arrow, ap);
        for (int i = 0; i < iconPos.size(); i++) icon(ctx, c, iconKeys.get(i), iconPos.get(i)[0], iconPos.get(i)[1], iconPos.get(i)[2], k);
    }

    /** Today's tint in the strip: a white 11 % tint over the slot, from the strip's top to the card's bottom edge. */
    static void todayTint(Canvas c, float dx, float dayW, float k) {
        RectF r = new RectF(dx - 5 * k, 145 * k, dx + dayW + 4 * k, CARD_H * k);
        float rad = 10 * k;
        Path p = new Path();
        p.addRoundRect(r, new float[]{rad, rad, rad, rad, 0, 0, 0, 0}, Path.Direction.CW);   // the card's corner clips the bottom
        Paint f = new Paint(Paint.ANTI_ALIAS_FLAG);
        f.setColor(Color.argb(Math.round(0.11f * 255), 255, 255, 255));
        c.drawPath(p, f);
    }

    /**
     * A column bar's rate at t. In the 15-min range it is the slot's own R. Beyond it R is interpolated between hour
     * centres (a neighbour hour without a bar counts as 0), so the bars keep a smooth envelope instead of hourly steps.
     * Whether a bar is drawn, and whether it is solid, still comes from the hour itself (the strip invariant).
     */
    static double barRate(Timeline tl, double t, Timeline.Sample sm) {
        if (tl.slot.length > 0 && t >= tl.slot0 && Math.floor((t - tl.slot0) * 4 + 1e-6) < tl.slot.length) return sm.R;
        Timeline.Sample[] H = tl.hour;
        if (H.length == 0) return sm.R;
        int i = (int) Math.floor(t - H[0].t);
        if (i < 0 || i >= H.length) return sm.R;
        double f = t - (H[i].t + 0.5);
        int j = f < 0 ? i - 1 : i + 1;
        double rj = j >= 0 && j < H.length && WxClass.bar(H[j]) ? H[j].R : 0;
        return sm.R + (rj - sm.R) * Math.min(0.5, Math.abs(f));
    }

    /** Width of a strip number; a leading minus is a span with margin 0 -1px 0 -.3px. */
    static float numW(Paint p, String s, float ls, float k) {
        if (s.startsWith(MINUS)) return TextKit.width(p, s, ls) - 1.3f * k;
        return TextKit.width(p, s, ls);
    }

    static void numText(TextKit.Batch b, Paint p, String s, float x, float y, float ls, float k) {
        if (s.startsWith(MINUS)) {
            float e = b.text(p, MINUS, x - 0.3f * k, y, ls);
            b.text(p, s.substring(1), e - 1 * k, y, ls);
        } else {
            b.text(p, s, x, y, ls);
        }
    }

    // ------------------------------------------------------------------ column
    static void column(Context ctx, Input in, Geo g, Out o) {
        float k = g.k;
        float CW = COL_X1 - COL_X0;
        double now = in.now;
        Timeline tl = in.tl;
        float CHdp = TOTAL_H * PXH;
        int Wc = (int) Math.round(CW * k), Hc = (int) Math.round(CHdp * k);
        // sky band at 0.6 x resolution, upscaled, grain 6
        // the sky band is the expensive part: cached, so a tap (bars <-> values) only redraws the overlay
        String bandKey = tl.key + "|" + Math.round(now * 3600) + "|" + in.lat + "|" + in.lon + "|" + Wc + "x" + Hc + "|" + in.mock;
        int[] full;
        synchronized (Renderer.class) {
            full = bandKey.equals(bandCacheKey) ? bandCache : null;
        }
        if (full == null) {
            int pw = (int) Math.round(CW * k * 0.6), ph = (int) Math.round(CHdp * k * 0.6);
            BandShader bs = new BandShader(tl, now, PXH, in.lat, in.lon, 20261121);
            bs.daylight = !in.mock;
            int[] lo = bs.render(pw, ph, k * 0.6);
            full = Px.upscale(lo, pw, ph, Wc, Hc);
            bs.overlay(full, Wc, Hc, k);   // crisp streaks / flakes / pellets at full resolution (live look)
            Px.grain(full, 6, System.nanoTime() + 1);
            synchronized (Renderer.class) { bandCacheKey = bandKey; bandCache = full; }
        }
        boolean values = in.colValues;
        o.colValues = values;
        Bitmap bm = Bitmap.createBitmap(Wc, Hc, Bitmap.Config.ARGB_8888);
        bm.setPixels(full, 0, Wc, 0, 0, Wc, Hc);
        Canvas c = new Canvas(bm);
        double T1 = now + TOTAL_H;

        // 15-min bars: x 27, length = the rate if it falls (26 dp per mm/h, capped); a hairline of air between slots.
        // "Likely" bars are solid as before; "possible" bars are translucent with a crisp edge, so they still read
        // over bright cloud. Beyond the 15-min data the length follows a smooth envelope between hour centres.
        float bx = 27, bmax = CW - bx - 16 - 8;
        Paint fp = new Paint(Paint.ANTI_ALIAS_FLAG);
        Paint wp = new Paint(Paint.ANTI_ALIAS_FLAG);
        wp.setColor(0xfff2f5fb);
        Paint ep = new Paint(Paint.ANTI_ALIAS_FLAG);
        ep.setStyle(Paint.Style.STROKE);
        ep.setStrokeWidth(0.9f * k);
        for (double s0 = Math.floor(now * 4) / 4; s0 < T1 && !values; s0 += 0.25) {
            Timeline.Sample sm = tl.at(s0 + 0.125);
            if (!WxClass.bar(sm)) continue;
            double v = barRate(tl, s0 + 0.125, sm);
            boolean solid = WxClass.solid(sm);
            int pt = WxClass.ptype(sm);
            double ya = (Math.max(s0, now) - now) * PXH, yb = (s0 + 0.25 - now) * PXH;
            float bw = (float) Math.min(bmax, v * 26);
            RectF r = new RectF(bx * k, (float) (ya + 0.4) * k, (bx + bw) * k, (float) (ya + 0.4 + Math.max(0.6, yb - ya - 0.8)) * k);
            int col = WxClass.barColor(pt, v);
            int save = c.saveLayerAlpha(r.left - 2, r.top - 2, r.right + 2, r.bottom + 2, Math.round((solid ? 0.95f : 0.45f) * 255));
            fp.setColor(col);
            c.drawRoundRect(r, 1.3f * k, 1.3f * k, fp);
            if (pt == Wx.SLEET) { // hatched #8cc0ff / #f2f5fb: 3.2 dp pattern rotated 40 deg (userSpaceOnUse)
                Path clip = new Path();
                clip.addRoundRect(r, 1.3f * k, 1.3f * k, Path.Direction.CW);
                c.save();
                c.clipPath(clip);
                c.rotate(40);
                // cover the bar's bounding box in the rotated frame
                double cs = Math.cos(Math.toRadians(-40)), sn = Math.sin(Math.toRadians(-40));
                double minU = 1e9, maxU = -1e9, minV = 1e9, maxV = -1e9;
                float[][] pts = {{r.left, r.top}, {r.right, r.top}, {r.left, r.bottom}, {r.right, r.bottom}};
                for (float[] p : pts) {
                    double u = p[0] * cs - p[1] * sn, vv = p[0] * sn + p[1] * cs;
                    minU = Math.min(minU, u); maxU = Math.max(maxU, u); minV = Math.min(minV, vv); maxV = Math.max(maxV, vv);
                }
                float per = 3.2f * k;
                for (double u = Math.floor(minU / per) * per; u < maxU; u += per)
                    c.drawRect((float) u, (float) minV, (float) (u + per / 2), (float) maxV, wp);
                c.restore();
            }
            c.restoreToCount(save);
            if (!solid && r.height() > 2.2f * k) { // the crisp edge of a "possible" bar
                ep.setColor(col);
                ep.setAlpha(235);
                float in2 = 0.45f * k;
                c.drawRoundRect(new RectF(r.left + in2, r.top + in2, r.right - in2, r.bottom - in2), 1.1f * k, 1.1f * k, ep);
            }
        }

        // hour scale (left), hourly temperatures (right), day name at midnight
        // live look (vivid realistic sky behind the labels): halo "B · Muy suave" and slightly brighter
        // hours; contrast with the halo is measured in tools/DesktopColumn2; the mock reference is unchanged
        boolean live = !in.mock && BandShader.REAL;
        TextKit.Batch b = new TextKit.Batch(Wc, Hc);
        if (live) b.halo(0.6f * k);
        Paint pHour = TextKit.paint(ctx, 500, 11 * k, white(live ? 0.75f : 0.62f), FEAT);
        Paint pTemp = TextKit.paint(ctx, 500, 11 * k, white(0.8f), FEAT);
        Paint pDay = TextKit.paint(ctx, 600, 11 * k, white(0.85f), FEAT);
        // Two-letter day names ("MO", "TU", ...) as wide as the "00".."23" hour numbers, so nothing overlaps the bars.
        // One size for all days: slightly condensed, shrunk until the widest label fits about 1.08x the width of "00".
        pDay.setTextScaleX(0.92f);
        {
            float w00 = TextKit.width(pHour, "00", 0), maxW = 0;
            for (String d : DAY2) maxW = Math.max(maxW, TextKit.width(pDay, d, 0));
            if (maxW > w00 * 1.08f) pDay.setTextSize(pDay.getTextSize() * w00 * 1.08f / maxW);
        }
        // values mode (after a tap): rain probability (blue), RH, UV per hour, no % signs, zeros dimmed
        Paint pVR = TextKit.paint(ctx, 500, 11 * k, Color.argb(Math.round(0.95f * 255), 0xa6, 0xcf, 0xff), FEAT);
        Paint pVRd = TextKit.paint(ctx, 500, 11 * k, Color.argb(Math.round(0.38f * 255), 0xa6, 0xcf, 0xff), FEAT);
        Paint pVH = TextKit.paint(ctx, 500, 11 * k, white(0.8f), FEAT);
        Paint pVU = TextKit.paint(ctx, 500, 11 * k, white(0.8f), FEAT);
        Paint pVUd = TextKit.paint(ctx, 500, 11 * k, white(0.38f), FEAT);
        Calendar cal = Calendar.getInstance(in.tz, Locale.ENGLISH);
        cal.setTimeInMillis((long) (now * 3600000L));
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        cal.add(Calendar.HOUR_OF_DAY, 1);
        List<Integer> edges = new ArrayList<>();
        List<Long> ids = new ArrayList<>();
        edges.add(0);
        ids.add((long) Math.floor(now));
        while (true) {
            double h = cal.getTimeInMillis() / 3600000.0;
            if (h >= T1) break;
            float y = (float) ((h - now) * PXH);
            int ey = Math.round(y * k);
            if (ey >= 3 && ey < Hc - 3) { edges.add(ey); ids.add((long) Math.round(h)); }
            if (h >= now + 0.25) {
                float lb = (y - 7 + ASC * 11) * k;
                // midnight: the two-letter day name ("MO") replaces the 00 label; the row keeps its temperature
                if (cal.get(Calendar.HOUR_OF_DAY) == 0) {
                    String d = DAY2[cal.get(Calendar.DAY_OF_WEEK) - 1];
                    b.text(pDay, d, 7 * k, lb, 0);
                } else {
                    b.text(pHour, String.format(Locale.US, "%02d", cal.get(Calendar.HOUR_OF_DAY)), 7 * k, lb, 0);
                }
                float rx = (CW - 7) * k;
                Timeline.Sample hs = tl.hourAt(h);   // the strip's max / min are these same numbers
                if (hs != null && (!values || VALUES_KEEP_TEMP)) {
                    String t = deg(hs.temp);
                    b.text(pTemp, t, rx - TextKit.width(pTemp, t, 0), lb, 0);
                }
                if (values && hs != null) valuesRow(b, hs, lb, k, pVR, pVRd, pVH, pVU, pVUd);
            }
            cal.add(Calendar.HOUR_OF_DAY, 1);
        }
        edges.add(Hc);
        if (live) b.flush(c, new TextKit.Shadow(0, 0, 4 * k, Color.argb(Math.round(0.7f * 255), 0, 0, 0)),
                new TextKit.Shadow(0, 0, 1.2f * k, Color.argb(Math.round(0.7f * 255), 0, 0, 0)));
        else b.flush(c, new TextKit.Shadow(0, 0, 5 * k, Color.argb(Math.round(0.65f * 255), 0, 0, 0)),
                new TextKit.Shadow(0, 0, 1 * k, Color.argb(Math.round(0.5f * 255), 0, 0, 0)));
        // the next alarm: the white pill at its exact minute when it is readable in the column at rest or
        // more than 12 h away; between, a static tab on the column's bottom edge instead (no pill); neither in values mode
        o.alarmMode = alarmMode(in, now, T1, values);
        o.alarmText = Double.isNaN(in.alarmAt) ? null : alarmText(in);
        if (o.alarmMode == ALARM_PILL) alarmPill(ctx, c, in, (float) ((in.alarmAt - now) * PXH), CW, k);
        o.alarmTab = o.alarmMode == ALARM_TAB ? alarmTab(ctx, in, k) : null;
        o.column = bm;
        o.rowEdges = new int[edges.size()];
        for (int i = 0; i < edges.size(); i++) o.rowEdges[i] = edges.get(i);
        o.rowIds = new long[ids.size()];
        for (int i = 0; i < ids.size(); i++) o.rowIds[i] = ids.get(i);
    }

    // bitmap clock region (dp, card coordinates): glyphs + the 10 px glow
    static final float CLOCK_X = 0, CLOCK_Y = 10, CLOCK_W = 196, CLOCK_H = 80;

    /** The clock as a transparent bitmap: Inter ExtraLight 54 dp, letter-spacing -1.8, both CSS text shadows. */
    static Bitmap clock(Context ctx, String text, float k) { return clock(ctx, text, k, false); }

    static Bitmap clock(Context ctx, String text, float k, boolean day) {
        int w = Math.round(CLOCK_W * k), h = Math.round(CLOCK_H * k);
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.translate(-CLOCK_X * k, -CLOCK_Y * k);
        TextKit.Batch t = textBatch(ctx, Math.round(CARD_W * k), Math.round(CARD_H * k), k);
        Paint pc = TextKit.paint(ctx, 200, 54 * k, Color.WHITE, FEAT);
        t.text(pc, text, 13 * k, baseline(22, 54, 54) * k, -1.8f * k);
        t.flush(c, textShadows(ctx, k, day));
        return b;
    }

    // values mode: the approved sketch (design/column, panels 4-5) puts rain / RH / UV where the temperature sits
    // (right edges 52, 74, 95) and hides the temperature, so nothing collides with the day name at midnight.
    // VALUES_KEEP_TEMP = true keeps the temperature and squeezes the three numbers in front of it instead.
    static final boolean VALUES_KEEP_TEMP = false;
    static final float VX_RAIN = VALUES_KEEP_TEMP ? 40 : 52, VX_RH = VALUES_KEEP_TEMP ? 58 : 74, VX_UV = VALUES_KEEP_TEMP ? 71 : 95;
    static String bandCacheKey;
    static int[] bandCache;

    static int uvColor(double uv) {
        int u = (int) Math.round(uv);
        if (u < 3) return 0;
        return u < 6 ? 0xffffd54a : u < 8 ? 0xffff9b45 : u < 11 ? 0xffff6257 : 0xffc78bff; // WHO bands
    }

    static void valuesRow(TextKit.Batch b, Timeline.Sample h, float lb, float k, Paint pr, Paint prDim, Paint ph, Paint pu, Paint puDim) {
        int pop = WxClass.pct(h.P);   // the hour's highest slot P: always matches its bars (dimmed = no bar)
        String r = String.valueOf(pop);
        Paint rp = h.P < WxClass.P_POSSIBLE ? prDim : pr;
        b.text(rp, r, VX_RAIN * k - TextKit.width(rp, r, 0), lb, 0);
        double rh = h.rh;
        String hs = rh < 0 ? "–" : String.valueOf(Math.round(rh));
        b.text(ph, hs, VX_RH * k - TextKit.width(ph, hs, 0), lb, 0);
        double uvv = h.uv;
        int uv = (int) Math.round(uvv);
        String us = String.valueOf(Math.max(0, uv));
        Paint up = uv <= 0 ? puDim : pu;
        int uc = uvColor(uvv);
        if (uc != 0) { up = new Paint(pu); up.setColor(uc); }
        b.text(up, us, VX_UV * k - TextKit.width(up, us, 0), lb, 0);
    }

    /** Alarm-clock line glyph (24-unit box): ring r 7.4 at (12, 13.6), hands, two ear strokes; stroke 2.7, round caps. */
    static Path alarmGlyph(float x, float y, float size) {
        float s = size / 24f;
        Path p = new Path();
        p.addCircle(x + 12 * s, y + 13.6f * s, 7.4f * s, Path.Direction.CW);
        p.moveTo(x + 12 * s, y + 10.1f * s);
        p.lineTo(x + 12 * s, y + 13.8f * s);
        p.lineTo(x + 14.5f * s, y + 15.3f * s);
        p.moveTo(x + 3.3f * s, y + 6.4f * s);
        p.lineTo(x + 6.7f * s, y + 3.4f * s);
        p.moveTo(x + 20.7f * s, y + 6.4f * s);
        p.lineTo(x + 17.3f * s, y + 3.4f * s);
        return p;
    }

    // ------------------------------------------------------------------ next alarm: pill / tab
    static final int ALARM_NONE = 0, ALARM_PILL = 1, ALARM_TAB = 2;
    /** Both elements at 85 % of the original 16 dp pill. */
    static final float AL_K = 0.85f;
    static final float AL_FONT = 11 * AL_K, AL_ICON = 9.5f * AL_K, AL_GAP = 2.5f * AL_K, AL_R = 8 * AL_K;
    static final float PILL_H = 16 * AL_K, PILL_PADL = 5 * AL_K, PILL_PADR = 7 * AL_K;
    static final float TAB_H = 15.5f * AL_K, TAB_PADL = 5.5f * AL_K, TAB_PADR = 5 * AL_K;
    /** The pill is readable in the column at rest when alarm - (current full hour) <= 7.5 h; the tab covers up to 12 h. */
    static final double AL_REST_H = 7.5, AL_TAB_H = 12;
    static final int AL_BG = 0xfff3f5f9, AL_INK = 0xff1a1e2b;

    static int alarmMode(Input in, double now, double T1, boolean values) {
        if (values || Double.isNaN(in.alarmAt) || in.alarmAt < now) return ALARM_NONE;
        double off = in.tz.getOffset((long) (now * 3600000L)) / 3600000.0;
        double hour0 = Math.floor(now + off) - off;                  // the current full hour (local)
        if (in.alarmAt - hour0 > AL_REST_H && in.alarmAt - now <= AL_TAB_H) return ALARM_TAB;
        return in.alarmAt < T1 ? ALARM_PILL : ALARM_NONE;
    }

    static String alarmText(Input in) {
        SimpleDateFormat hm = new SimpleDateFormat("H:mm", Locale.US);
        hm.setTimeZone(in.tz);
        return hm.format(new Date((long) (in.alarmAt * 3600000L)));
    }

    /** "⏰ 8:45" pill in the column (column px): #f3f5f9, ink #1a1e2b, Inter 600 9.35 dp, 13.6 dp tall, radius 6.8. */
    static void alarmPill(Context ctx, Canvas c, Input in, float yDp, float cwDp, float k) {
        String t = alarmText(in);
        Paint tp = TextKit.paint(ctx, 600, AL_FONT * k, AL_INK, FEAT);
        float w = PILL_PADL + AL_ICON + AL_GAP + TextKit.width(tp, t, 0) / k + PILL_PADR;
        // whole-pixel edges, so the box is crisp and the ink can be centred to the pixel
        int left = Math.round((cwDp - w) / 2 * k), right = left + Math.round(w * k);
        int H = Math.round(PILL_H * k), top = Math.round(yDp * k - H / 2f);
        RectF r = new RectF(left, top, right, top + H);
        float rr = AL_R * k;
        Paint sh = new Paint(Paint.ANTI_ALIAS_FLAG); // box-shadow 0 1px 3px rgba(0,0,0,.35), at 85 %
        sh.setColor(Color.argb(Math.round(0.35f * 255), 0, 0, 0));
        sh.setMaskFilter(new BlurMaskFilter(TextKit.blurRadius(1.5f * AL_K * k), BlurMaskFilter.Blur.NORMAL));
        RectF rs = new RectF(r);
        rs.offset(0, AL_K * k);
        c.drawRoundRect(rs, rr, rr, sh);
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setColor(AL_BG);
        c.drawRoundRect(r, rr, rr, bg);
        alarmContent(c, tp, t, left + PILL_PADL * k, top, top + H, k);
    }

    /**
     * The tab on the column's bottom edge (its own bitmap, shadow margins included; flat bottom flush with the
     * bitmap's bottom): rounded top corners 6.8 dp, 13.2 dp tall, small upward shadow, same colours as the pill.
     */
    static Bitmap alarmTab(Context ctx, Input in, float k) {
        String t = alarmText(in);
        Paint tp = TextKit.paint(ctx, 600, AL_FONT * k, AL_INK, FEAT);
        float w = TAB_PADL + AL_ICON + AL_GAP + TextKit.width(tp, t, 0) / k + TAB_PADR;
        int mx = Math.round(5.5f * k), mt = Math.round(6 * k);       // room for the shadow
        int tw = Math.round(w * k), H = Math.round(TAB_H * k);
        Bitmap bm = Bitmap.createBitmap(tw + 2 * mx, H + mt, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bm);
        RectF r = new RectF(mx, mt, mx + tw, mt + H);
        float rr = AL_R * k;
        Path shape = new Path();
        shape.addRoundRect(r, new float[]{rr, rr, rr, rr, 0, 0, 0, 0}, Path.Direction.CW);
        Paint sh = new Paint(Paint.ANTI_ALIAS_FLAG); // box-shadow 0 -1px 4px rgba(0,0,0,.35), at 85 %
        sh.setColor(Color.argb(Math.round(0.35f * 255), 0, 0, 0));
        sh.setMaskFilter(new BlurMaskFilter(TextKit.blurRadius(2f * AL_K * k), BlurMaskFilter.Blur.NORMAL));
        Path ps = new Path(shape);
        ps.offset(0, -AL_K * k);
        c.drawPath(ps, sh);
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setColor(AL_BG);
        c.drawPath(shape, bg);
        alarmContent(c, tp, t, mx + TAB_PADL * k, mt, mt + H, k);
        return bm;
    }

    /**
     * Icon and time, each centred vertically by its own ink (the icon's stroked outline with its bells; the digits'
     * glyph outlines) between the whole-pixel rows top and bottom, so the space above and below is equal.
     */
    static void alarmContent(Canvas c, Paint tp, String t, float xIcon, int top, int bottom, float k) {
        Paint ip = new Paint(Paint.ANTI_ALIAS_FLAG);
        ip.setStyle(Paint.Style.STROKE);
        ip.setStrokeWidth(2.7f / 24f * AL_ICON * k);
        ip.setStrokeCap(Paint.Cap.ROUND);
        ip.setStrokeJoin(Paint.Join.ROUND);
        Path icon = new Path();
        ip.getFillPath(alarmGlyph(xIcon, 0, AL_ICON * k), icon);
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setColor(AL_INK);
        icon.offset(0, inkShift(icon, top, bottom));
        c.drawPath(icon, fill);
        Path text = new Path();
        tp.getTextPath(t, 0, t.length(), xIcon + (AL_ICON + AL_GAP) * k, 0, text);
        text.offset(0, inkShift(text, top, bottom));
        c.drawPath(text, fill);
    }

    /**
     * Vertical offset that centres a path's ink between pixel rows [top, bottom): first the exact geometric centre of
     * its bounds, then refined in 1/16 px steps so the rows that are at least half covered leave the same number of
     * empty rows above and below (what the eye and a pixel count see).
     */
    static float inkShift(Path p, int top, int bottom) {
        RectF b = new RectF();
        p.computeBounds(b, true);
        float dy0 = (top + bottom) / 2f - (b.top + b.bottom) / 2f;
        int pad = 4, w = (int) Math.ceil(b.right) - (int) Math.floor(b.left) + 2 * pad, h = bottom - top + 2 * pad;
        Bitmap a = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8);
        Canvas ac = new Canvas(a);
        Paint fp = new Paint(Paint.ANTI_ALIAS_FLAG);
        byte[] px = new byte[a.getRowBytes() * h];
        float best = dy0;
        int bestErr = Integer.MAX_VALUE, bestAbs = Integer.MAX_VALUE;
        for (int s = -8; s <= 8; s++) {
            float dy = dy0 + s / 16f;
            a.eraseColor(0);
            ac.save();
            ac.translate(pad - (float) Math.floor(b.left), pad - top + dy);  // whole-pixel moves keep the raster
            ac.drawPath(p, fp);
            ac.restore();
            a.copyPixelsToBuffer(java.nio.ByteBuffer.wrap(px));
            int first = -1, last = -1;
            for (int y = 0; y < h; y++) {
                boolean ink = false;
                for (int x = 0; x < w && !ink; x++) ink = (px[y * a.getRowBytes() + x] & 0xff) >= 128;
                if (ink) { if (first < 0) first = y; last = y; }
            }
            if (first < 0) continue;
            int gapTop = first - pad, gapBottom = (h - pad - 1) - last;
            int err = Math.abs(gapTop - gapBottom);
            if (err < bestErr || (err == bestErr && Math.abs(s) < bestAbs)) { bestErr = err; bestAbs = Math.abs(s); best = dy; }
        }
        a.recycle();
        return best;
    }

    /** The static "Rain · RH · UV" legend in the bottom fade (values mode only; never scrolls). */
    static Bitmap legend(Context ctx, Geo g) {
        float k = g.k;
        int W = g.colW, H = Math.round(22 * k);
        Bitmap bm = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bm);
        Paint bg = new Paint();
        bg.setShader(new android.graphics.LinearGradient(0, 0, 0, H, new int[]{0x000e1018, 0xdb0e1018, 0xdb0e1018}, new float[]{0, 0.5f, 1}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, W, H, bg);
        TextKit.Batch b = new TextKit.Batch(W, H);
        Paint lr = TextKit.paint(ctx, 600, 11 * k, 0xffa6cfff, FEAT);
        Paint lw = TextKit.paint(ctx, 600, 11 * k, white(0.75f), FEAT);
        float base = (6 + ASC * 11) * k;
        b.text(lr, "Rain", VX_RAIN * k - TextKit.width(lr, "Rain", 0), base, 0);
        b.text(lw, "RH", VX_RH * k - TextKit.width(lw, "RH", 0), base, 0);
        b.text(lw, "UV", VX_UV * k - TextKit.width(lw, "UV", 0), base, 0);
        b.flush(c, new TextKit.Shadow(0, 0, 5 * k, Color.argb(Math.round(0.7f * 255), 0, 0, 0)));
        return bm;
    }

    /** Debug preview: card + column + clock composited, as it should look on the home screen. */
    static Bitmap preview(Context ctx, Out o, String clockText) { return preview(ctx, o, clockText, 0); }

    /** @param scrollDp column scroll offset (debug dumps of a scrolled column) */
    static Bitmap preview(Context ctx, Out o, String clockText, float scrollDp) {
        Geo g = o.geo;
        float k = g.k;
        Bitmap p = o.card.copy(Bitmap.Config.ARGB_8888, true);
        Canvas c = new Canvas(p);
        c.save();
        Path clip = new Path();
        clip.addRoundRect(new RectF(g.colL, g.colT, g.colL + g.colW, g.colT + g.colH), 14 * k, 14 * k, Path.Direction.CW);
        c.clipPath(clip);
        float sc = Math.max(0, Math.min(o.column.getHeight() - g.colH, scrollDp * k));
        c.drawBitmap(o.column, g.colL, g.colT - sc, null);
        Paint fade = new Paint();
        fade.setShader(new android.graphics.LinearGradient(0, g.colT + g.colH - 22 * k, 0, g.colT + g.colH, 0x000e1018, 0x9e0e1018, Shader.TileMode.CLAMP));
        c.drawRect(g.colL, g.colT + g.colH - 22 * k, g.colL + g.colW, g.colT + g.colH, fade);
        if (o.colValues) c.drawBitmap(legend(ctx, g), g.colL, g.colT + g.colH - Math.round(22 * k), null);
        // the alarm tab: bottom|center_horizontal in the column, like the widget's FrameLayout places it
        if (o.alarmTab != null)
            c.drawBitmap(o.alarmTab, g.colL + (g.colW - o.alarmTab.getWidth()) / 2, g.colT + g.colH - o.alarmTab.getHeight(), null);
        c.restore();
        TextKit.Batch b = textBatch(ctx, g.cardW, g.cardH, k);
        Paint pc = TextKit.paint(ctx, 200, 54 * k, Color.WHITE, FEAT);
        b.text(pc, clockText, 13 * k, baseline(22, 54, 54) * k, -1.8f * k);
        b.flush(c, textShadows(ctx, k, o.day));
        return p;
    }
}
