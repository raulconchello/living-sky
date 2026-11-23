package dev.livingsky;

import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import dev.livingsky.render.Moon;
import dev.livingsky.render.CloudLayer;
import dev.livingsky.render.SkyShader;
import dev.livingsky.render.WxState;

/**
 * The foreground weather pass: what makes each state readable at a glance on the home screen. The sky shader alone
 * paints behind the skyline, so here the weather is layered around it, stylised and calm:
 *   sky()          behind the skyline: sun bloom, crisp stars, the moon, broken cloud (CloudLayer), the bolt and its flash;
 *   afterSkyline() over the skyline: fog bank + mist + light halos, rain curtains, rim light, snow on the roofs;
 *   afterGround()  over everything: near fog, wet sheen, snow / hail on the ground, rain, snow, hail, wind.
 * All coordinates in dp (card space), drawn in px (x k). Deterministic per state and day.
 */
final class WeatherFx {
    private final WxState st;
    private final SkyShader.Params sp;
    private final float k;
    private final int W, H;
    private final boolean night;
    private final long seed;
    private final double now, lat, lon;
    // the bolt (dp), kept for the rim light
    private float boltX0, boltY0, boltX1, boltY1;

    WeatherFx(WxState st, SkyShader.Params sp, float k, int W, int H, double now, double lat, double lon) {
        this.st = st;
        this.sp = sp;
        this.k = k;
        this.W = W;
        this.H = H;
        this.night = sp.night;
        this.now = now;
        this.lat = lat;
        this.lon = lon;
        this.seed = st.kind * 7919L + (long) Math.floor(now / 24) * 31L;
    }

    // text blocks (dp): location line, clock, temperature + info, day strip
    private static final float[][] TEXT = {{10, 8, 215, 24}, {10, 22, 160, 76}, {10, 78, 168, 113}, {10, 148, 262, 186}};

    private static boolean inText(float x, float y) {
        for (float[] r : TEXT) if (x >= r[0] && x <= r[2] && y >= r[1] && y <= r[3]) return true;
        return false;
    }

    private Paint paint() { return new Paint(Paint.ANTI_ALIAS_FLAG); }

    private static int argb(double a, int r, int g, int b) {
        return Color.argb((int) Math.max(0, Math.min(255, Math.round(a * 255))), r, g, b);
    }

    private float blurR(float sigmaDp) { return TextKit.blurRadius(sigmaDp * k); }

    // ================================================================= 1. behind the skyline
    void sky(Canvas c, Bitmap skyPx) {
        int kind = st.kind;
        boolean sunUp = sp.el > -1 && sp.sunY < 134;
        if (!night && sunUp && (kind == WxState.CLEAR || kind == WxState.PARTLY)) sunBloom(c);
        if (night && sp.cover < 0.9 && !st.precip() && kind != WxState.FOG) stars(c, skyPx);
        if (night && sp.cover < 0.8 && !st.precip() && kind != WxState.FOG) moon(c, skyPx);
        if (layerClouds(st.kind, sp.cover)) cloudLayer(c, kind == WxState.SHOWERS, night && sp.cover < 0.8 && !st.precip());
        else if (kind == WxState.PARTLY || kind == WxState.SHOWERS) cumulus(c, kind == WxState.SHOWERS); // old style (debug)
        if (st.lightning) bolt(c);
    }

    private void sunBloom(Canvas c) {
        float x = (float) sp.sunX * k, y = (float) sp.sunY * k, r = 62 * k;
        Paint p = paint();
        p.setShader(new RadialGradient(x, y, r, new int[]{argb(0.42, 255, 250, 232), argb(0.16, 255, 240, 214), 0},
                new float[]{0, 0.35f, 1}, Shader.TileMode.CLAMP));
        p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SCREEN));
        c.drawCircle(x, y, r, p);
    }

    /** Crisp stars at full resolution, only where the sky pixel is dark (clear gaps between clouds). */
    private void stars(Canvas c, Bitmap skyPx) {
        Random r = new Random(seed ^ 0x5eed);
        Paint p = paint();
        for (int i = 0; i < 340; i++) {
            float x = r.nextFloat() * 268, y = 4 + r.nextFloat() * 116;
            int px = Math.min(W - 1, (int) (x * k)), py = Math.min(H - 1, (int) (y * k));
            int c0 = skyPx.getPixel(px, py);
            double lum = (Color.red(c0) * 0.3 + Color.green(c0) * 0.55 + Color.blue(c0) * 0.15);
            double dark = 1 - Math.max(0, Math.min(1, (lum - 22) / 34.0));
            double horizon = 1 - Math.max(0, Math.min(1, (y - 70) / 50.0));
            double a = Math.min(1, dark * horizon * (0.5 + 0.7 * Math.pow(r.nextFloat(), 1.3)));
            if (a < 0.08) continue;
            float rad = (0.55f + 0.85f * (float) Math.pow(r.nextFloat(), 3)) * k * 0.6f;
            int tint = r.nextFloat() < 0.2 ? Color.rgb(255, 236, 214) : r.nextFloat() < 0.3 ? Color.rgb(214, 226, 255) : Color.rgb(246, 248, 255);
            if (rad > 0.85f * k * 0.55f) { // the brighter ones get a small glow
                p.setShader(new RadialGradient(x * k, y * k, rad * 4, new int[]{argb(a * 0.35, 220, 230, 255), 0}, null, Shader.TileMode.CLAMP));
                c.drawCircle(x * k, y * k, rad * 4, p);
                p.setShader(null);
            }
            p.setColor(tint);
            p.setAlpha((int) (a * 255));
            c.drawCircle(x * k, y * k, rad, p);
        }
    }

    /** Where the moon sits on the card (dp) and how much light it gives: {x, y, light 0..1}, or null below the horizon. */
    private double[] moonPlace() {
        double[] m = Moon.at(now, lat, lon);
        if (m[0] < 2 || m[2] < 0.04) return null;
        double x = Math.max(178, Math.min(258, 200 + (m[1] - 180) * 0.6));
        double y = Math.max(16, Math.min(64, 118 - m[0] * 1.5));
        return new double[]{x, y, Math.pow(m[2], 1.3) * Math.min(1, (m[0] - 2) / 8), m[2], m[4]};
    }

    /**
     * The moon at its real phase, placed in the right part of the sky by its real azimuth / altitude, its lit side
     * turned toward the Sun as seen from here (waxing: lit on the right, tilted by the real geometry).
     */
    private void moon(Canvas c, Bitmap skyPx) {
        double[] mp0 = moonPlace();
        if (mp0 == null) return;
        float x = (float) mp0[0], y = (float) mp0[1];
        double f = mp0[3];
        int c0 = skyPx.getPixel((int) (x * k), (int) (y * k));
        double lum = Color.red(c0) * 0.3 + Color.green(c0) * 0.55 + Color.blue(c0) * 0.15;
        double vis = 1 - Math.max(0, Math.min(0.85, (lum - 30) / 60.0)); // behind cloud: dimmer
        float R = 5.4f * k, cx = x * k, cy = y * k;
        Paint g = paint();
        g.setShader(new RadialGradient(cx, cy, R * 4.5f, new int[]{argb(0.30 * vis * f, 230, 232, 245), 0}, null, Shader.TileMode.CLAMP));
        g.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SCREEN));
        c.drawCircle(cx, cy, R * 4.5f, g);
        // earthshine: the dark part only as a very faint ashen disc (strongest for a thin crescent), never a grey plate
        Paint earth = paint();
        earth.setColor(argb(0.10 * vis * (1 - f), 92, 98, 122));
        c.drawCircle(cx, cy, R, earth);
        // lit part, drawn with the bright limb on the right, then turned to the real bright-limb direction:
        // limb half-disc + terminator half-ellipse (bulging toward the limb for a crescent, away for a gibbous moon)
        Path lit = new Path();
        RectF disc = new RectF(cx - R, cy - R, cx + R, cy + R);
        float rx = (float) (R * Math.abs(1 - 2 * f));
        RectF ell = new RectF(cx - rx, cy - R, cx + rx, cy + R);
        lit.arcTo(disc, -90, 180, true);
        lit.arcTo(ell, 90, f < 0.5 ? -180 : 180, false);
        lit.close();
        Paint mp = paint();
        mp.setColor(argb(0.95 * vis, 246, 242, 228));
        mp.setMaskFilter(new BlurMaskFilter(Math.max(0.5f, 0.35f * k), BlurMaskFilter.Blur.NORMAL));
        c.save();
        c.rotate((float) mp0[4], cx, cy);
        c.drawPath(lit, mp);
        c.restore();
    }

    /** Debug (--es clouds old): the previous cartoon cumulus, the sky shader's own puffs and the -4 deg day/night step. */
    static volatile boolean oldClouds;

    /** Broken cloud drawn by {@link CloudLayer} (fair-weather wisps, partly cloudy, showers); the decks stay in the shader. */
    static boolean layerClouds(int kind, double cover) {
        return !oldClouds && (kind == WxState.PARTLY || kind == WxState.SHOWERS || (kind == WxState.CLEAR && cover > 0.08));
    }

    /**
     * Broken cloud at every hour ({@link CloudLayer}): soft fbm clouds in perspective, see-through thin parts, lit by
     * the sun by day (golden / pink when low), the afterglow at dusk, dark bodies with moonlit rims and a faint city
     * glow at night. One field, lit continuously by the sun's elevation, so dawn and dusk have no seam.
     */
    /** The column's cloud cover for this hour (0..1, ensemble mean, smoothed as the band), or NaN: the shader's cover. */
    double colCover = Double.NaN;

    private void cloudLayer(Canvas c, boolean showers, boolean moonUp) {
        CloudLayer cl = new CloudLayer(seed ^ 0xC10D5L);
        // the same amount of cloud as the column next to it, kept within what the state means
        double cc = Double.isNaN(colCover) ? sp.cover : colCover;
        if (st.kind == WxState.CLEAR) cc = Math.min(cc, Math.max(sp.cover, 0.25));
        else if (showers) cc = Math.max(0.62, Math.min(0.9, cc));
        else cc = Math.max(0.3, Math.min(0.86, cc));
        cl.cover = cc;
        cl.showers = showers;
        cl.windy = st.windy;
        cl.HY = sp.HY;
        cl.focal = sp.focal;
        cl.el = sp.el;
        cl.sunX = sp.sunX;
        cl.sunY = sp.sunY;
        double[] m = moonUp ? moonPlace() : null;
        if (m != null) { cl.moonX = m[0]; cl.moonY = m[1]; cl.moonLight = m[2]; }
        double s = k * 0.6;
        int pw = (int) Math.ceil(272 * s), ph = (int) Math.ceil((sp.HY + 2) * s);
        cl.render(pw, ph, s);
        RectF dst = new RectF(0, 0, (float) (pw / s * k), (float) (ph / s * k));
        Paint fp = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        Bitmap lb = Bitmap.createBitmap(cl.layer, pw, ph, Bitmap.Config.ARGB_8888);
        c.drawBitmap(lb, null, dst, fp);
        lb.recycle();
        Bitmap gb = Bitmap.createBitmap(cl.glow, pw, ph, Bitmap.Config.ARGB_8888);
        Paint sp2 = new Paint(Paint.FILTER_BITMAP_FLAG);
        sp2.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SCREEN));
        c.drawBitmap(gb, null, dst, sp2);
        gb.recycle();
    }

    /** Fair-weather cumulus: separate puffy clouds with flat bases, smaller toward the horizon. */
    private void cumulus(Canvas c, boolean showers) {
        Random r = new Random(seed ^ 0xC0FFEE);
        int n = showers ? 8 : (int) Math.round(6 + st.cover * 7);
        List<float[]> clouds = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            float depth = r.nextFloat(); // 0 near (high, big) .. 1 far (low, small)
            float y = 24 + depth * 88 + r.nextFloat() * 6;
            float w = (96 - depth * 70) * (0.75f + r.nextFloat() * 0.5f) * (showers ? 1.2f : 1f);
            float x = -20 + r.nextFloat() * 300;
            // keep big bright clouds out from behind the clock and temperature: there only far, small ones
            if (x + w * 0.5f < 175 && y < 112 && depth < 0.55f) { depth = 0.6f + r.nextFloat() * 0.4f; y = 98 + depth * 14; w = 26 + r.nextFloat() * 16; }
            clouds.add(new float[]{x, y, w, depth});
        }
        clouds.sort((a, b) -> Float.compare(b[3], a[3])); // far first
        for (float[] cl : clouds) drawCumulus(c, r, cl[0], cl[1], cl[2], cl[3], showers);
    }

    private void drawCumulus(Canvas c, Random r, float x, float base, float w, float depth, boolean showers) {
        float sxw = st.windy ? 1.7f : 1f, syh = st.windy ? 0.55f : 1f; // wind: torn, stretched clouds
        w *= sxw;
        Path p = new Path();
        int bumps = 4 + r.nextInt(4);
        float h = w / sxw * (0.36f + r.nextFloat() * 0.14f) * syh;
        for (int i = 0; i < bumps; i++) {
            float t = (i + 0.5f) / bumps;
            float bx = x + t * w;
            float arch = (float) Math.sin(Math.PI * Math.pow(t, 0.85));
            float rad = w / sxw * (0.11f + 0.13f * arch + r.nextFloat() * 0.05f);
            float by = base - rad * 0.3f * syh - h * 0.6f * arch;
            if (st.windy) p.addOval(new RectF((bx - rad * 1.6f) * k, (by - rad * syh) * k, (bx + rad * 1.6f) * k, (by + rad * syh) * k), Path.Direction.CW);
            else p.addCircle(bx * k, by * k, rad * k, Path.Direction.CW);
        }
        p.addRect(x * k + w * 0.07f * k, (base - h * 0.3f) * k, (x + w * 0.93f) * k, base * k, Path.Direction.CW);
        c.save();
        c.clipRect(0, 0, W, (base + 0.6f) * k);
        float top = (base - h * 1.3f) * k, bot = base * k;
        int cTop, cMid, cBase;
        double haze = depth * depth * 0.55;
        double dusk = Math.max(0, Math.min(1, (10 - sp.el) / 12.0)); // low sun: warm, dimmer
        if (!night) {
            cTop = mixc(showers ? Color.rgb(222, 224, 232) : Color.rgb(253, 253, 254), Color.rgb(214, 222, 230), haze);
            cMid = mixc(showers ? Color.rgb(156, 160, 174) : Color.rgb(232, 235, 242), Color.rgb(206, 214, 224), haze);
            cBase = mixc(showers ? Color.rgb(84, 90, 108) : Color.rgb(160, 170, 190), Color.rgb(190, 198, 212), haze);
            if (dusk > 0) {
                cTop = mixc(cTop, Color.rgb(246, 214, 196), dusk * 0.7);
                cMid = mixc(cMid, Color.rgb(196, 160, 160), dusk * 0.7);
                cBase = mixc(cBase, Color.rgb(112, 92, 112), dusk * 0.7);
            }
        } else {
            cTop = Color.rgb(44, 46, 60);
            cMid = Color.rgb(56, 54, 66);
            cBase = Color.rgb(98, 80, 76); // lit from below by the town
        }
        Paint f = paint();
        f.setShader(new LinearGradient(0, top, 0, bot, new int[]{cTop, cTop, cMid, cBase}, new float[]{0, 0.3f, 0.62f, 1}, Shader.TileMode.CLAMP));
        f.setAlpha(night ? 225 : 250);
        f.setMaskFilter(new BlurMaskFilter(Math.max(0.6f, (0.9f + depth * 0.9f) * k), BlurMaskFilter.Blur.NORMAL));
        c.drawPath(p, f);
        // shade on the side away from the sun, so the puffs read as volumes
        if (!night) {
            float sun = (float) sp.sunX;
            float shadeX = (x + w / 2) < sun ? x * k : (x + w) * k, litX = (x + w / 2) < sun ? (x + w) * k : x * k;
            Paint sh = paint();
            sh.setShader(new LinearGradient(shadeX, 0, litX, 0, argb(showers ? 0.3 : 0.2, 60, 70, 96), argb(0, 60, 70, 96), Shader.TileMode.CLAMP));
            sh.setMaskFilter(new BlurMaskFilter(Math.max(0.6f, 1.2f * k), BlurMaskFilter.Blur.NORMAL));
            c.drawPath(p, sh);
        }
        c.restore();
    }

    private static int mixc(int a, int b, double t) {
        return Color.rgb((int) (Color.red(a) + (Color.red(b) - Color.red(a)) * t), (int) (Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                (int) (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }

    /** A forked, branching bolt from the cloud to the ground just behind the landmark, and the flash on the cloud. */
    private void bolt(Canvas c) {
        Random r = new Random(seed ^ 0xB017);
        boltX0 = 214 + r.nextFloat() * 14;
        boltY0 = 6;
        boltX1 = 232 + r.nextFloat() * 22;
        boltY1 = 140;
        // flash on the cloud base
        Paint fl = paint();
        fl.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SCREEN));
        float fx = boltX0 * k, fy = 34 * k, fr = (night ? 165 : 140) * k;
        fl.setShader(new RadialGradient(fx, fy, fr, new int[]{argb(night ? 0.62 : 0.5, 214, 218, 255), argb(night ? 0.26 : 0.2, 150, 150, 220), 0},
                new float[]{0, 0.4f, 1}, Shader.TileMode.CLAMP));
        c.drawCircle(fx, fy, fr, fl);
        List<float[]> main = jag(r, boltX0, boltY0, boltX1, boltY1, 7, 9f);
        List<List<float[]>> branches = new ArrayList<>();
        for (int b = 0; b < 4; b++) {
            float[] o = main.get(8 + r.nextInt(Math.max(1, main.size() / 2)));
            float dir = r.nextBoolean() ? 1 : -1;
            float len = 24 + r.nextFloat() * 26;
            branches.add(jag(r, o[0], o[1], o[0] + dir * len * 0.7f, o[1] + len, 5, 5f));
        }
        strokeBolt(c, main, 1f);
        for (List<float[]> br : branches) strokeBolt(c, br, 0.55f);
    }

    private List<float[]> jag(Random r, float x0, float y0, float x1, float y1, int levels, float amp) {
        List<float[]> pts = new ArrayList<>();
        pts.add(new float[]{x0, y0});
        pts.add(new float[]{x1, y1});
        for (int l = 0; l < levels; l++) {
            List<float[]> n = new ArrayList<>();
            for (int i = 0; i + 1 < pts.size(); i++) {
                float[] a = pts.get(i), b = pts.get(i + 1);
                n.add(a);
                float mx = (a[0] + b[0]) / 2 + (r.nextFloat() - 0.5f) * amp, my = (a[1] + b[1]) / 2 + (r.nextFloat() - 0.5f) * amp * 0.25f;
                n.add(new float[]{mx, my});
            }
            n.add(pts.get(pts.size() - 1));
            pts = n;
            amp *= 0.56f;
        }
        return pts;
    }

    private void strokeBolt(Canvas c, List<float[]> pts, float s) {
        Path p = new Path();
        p.moveTo(pts.get(0)[0] * k, pts.get(0)[1] * k);
        for (int i = 1; i < pts.size(); i++) p.lineTo(pts.get(i)[0] * k, pts.get(i)[1] * k);
        Paint g = paint();
        g.setStyle(Paint.Style.STROKE);
        g.setStrokeCap(Paint.Cap.ROUND);
        g.setStrokeJoin(Paint.Join.ROUND);
        g.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SCREEN));
        g.setColor(argb((night ? 0.75 : 0.78) * s, 170, 180, 255));
        g.setStrokeWidth(7 * k * s);
        g.setMaskFilter(new BlurMaskFilter(blurR(3.2f * s), BlurMaskFilter.Blur.NORMAL));
        c.drawPath(p, g);
        g.setColor(argb(0.9 * s + 0.1, 210, 218, 255));
        g.setStrokeWidth(2.4f * k * s);
        g.setMaskFilter(new BlurMaskFilter(blurR(0.6f), BlurMaskFilter.Blur.NORMAL));
        c.drawPath(p, g);
        g.setMaskFilter(null);
        g.setXfermode(null);
        g.setColor(argb(Math.min(1, 0.7 + 0.3 * s), 255, 255, 255));
        g.setStrokeWidth(Math.max(1.4f, (night ? 1.0f : 1.3f) * k * s));
        c.drawPath(p, g);
    }

    // ================================================================= 2. over the skyline (before the near ground)
    void afterSkyline(Canvas c, Bitmap sk) {
        int kind = st.kind;
        if (st.lightning) rimLight(c, sk);
        if (kind == WxState.SNOW || kind == WxState.HEAVYSNOW || kind == WxState.HAIL)
            dusting(c, sk, 1.5f, kind == WxState.HAIL ? 0.45 : kind == WxState.HEAVYSNOW ? 0.85 : 0.7);
        switch (kind) {
            case WxState.FOG: fogBank(c, sk); break;
            case WxState.DRIZZLE: veil(c, night ? Color.rgb(70, 72, 84) : Color.rgb(170, 174, 184), 0.10, 0.24); break;
            case WxState.RAIN: case WxState.SLEET: veil(c, night ? Color.rgb(62, 66, 80) : Color.rgb(150, 156, 170), 0.12, 0.28); break;
            case WxState.SHOWERS: shafts(c); break;
            case WxState.HEAVY: case WxState.THUNDER:
                veil(c, night ? Color.rgb(56, 58, 74) : Color.rgb(128, 134, 150), 0.18, 0.46);
                sheets(c);
                break;
            case WxState.HAIL: veil(c, night ? Color.rgb(60, 70, 64) : Color.rgb(150, 164, 152), 0.15, 0.4); break;
            case WxState.HEAVYSNOW: veil(c, night ? Color.rgb(110, 116, 132) : Color.rgb(226, 230, 236), 0.28, 0.6); break;
            case WxState.SNOW: veil(c, night ? Color.rgb(96, 102, 118) : Color.rgb(220, 224, 232), 0.08, 0.3); break;
            default: break;
        }
    }

    /** Vertical veil (aerial perspective): thin at the top, densest at the horizon. */
    private void veil(Canvas c, int col, double aTop, double aHorizon) {
        Paint p = paint();
        int r = Color.red(col), g = Color.green(col), b = Color.blue(col);
        p.setShader(new LinearGradient(0, 0, 0, 150 * k, new int[]{argb(aTop * 0.6, r, g, b), argb(aTop, r, g, b), argb(aHorizon, r, g, b), argb(aHorizon, r, g, b)},
                new float[]{0, 0.3f, 0.88f, 1}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, W, H, p);
    }

    private void fogBank(Canvas c, Bitmap sk) {
        // the bank: the landmark a faint ghost, the town nearly gone
        int fr, fg, fb;
        if (night) { fr = 70; fg = 62; fb = 60; } else { fr = 166; fg = 168; fb = 172; }
        Paint p = paint();
        p.setShader(new LinearGradient(0, 0, 0, 152 * k,
                new int[]{argb(0.42, fr, fg, fb), argb(0.62, fr, fg, fb), argb(0.86, fr, fg, fb), argb(0.93, fr, fg, fb)},
                new float[]{0, 0.35f, 0.75f, 1}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, W, H, p);
        // soft horizontal mist bands drifting across
        Random r = new Random(seed ^ 0xF06);
        Paint m = paint();
        for (int i = 0; i < 9; i++) {
            float y = 58 + r.nextFloat() * 92, h = 4 + r.nextFloat() * 9, x = -60 + r.nextFloat() * 260, w = 120 + r.nextFloat() * 220;
            double a = 0.18 + r.nextFloat() * 0.22;
            m.setColor(night ? argb(a, 104, 92, 86) : argb(a, 198, 200, 204));
            m.setMaskFilter(new BlurMaskFilter(blurR(h * 0.6f), BlurMaskFilter.Blur.NORMAL));
            c.drawRoundRect(new RectF(x * k, y * k, (x + w) * k, (y + h) * k), h * k, h * k, m);
        }
        if (night) halos(c, sk);
    }

    /** Night fog: glowing halos around lit windows and floodlights (found as warm bright pixels of the skyline). */
    private void halos(Canvas c, Bitmap sk) {
        Bitmap lights = brightMask(sk, 150);
        if (lights == null) return;
        int[] off = new int[2];
        Paint add = paint();
        add.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SCREEN));
        Paint bp = new Paint();
        bp.setMaskFilter(new BlurMaskFilter(blurR(7f), BlurMaskFilter.Blur.NORMAL));
        Bitmap big = lights.extractAlpha(bp, off);
        add.setColor(argb(0.7, 255, 170, 100));
        c.drawBitmap(big, off[0], off[1], add);
        bp.setMaskFilter(new BlurMaskFilter(blurR(2.2f), BlurMaskFilter.Blur.NORMAL));
        Bitmap small = lights.extractAlpha(bp, off);
        add.setColor(argb(0.5, 255, 200, 146));
        c.drawBitmap(small, off[0], off[1], add);
        add.setColor(argb(0.4, 255, 222, 176));
        c.drawBitmap(lights, 0, 0, add);
        big.recycle();
        small.recycle();
        lights.recycle();
    }

    /** ALPHA_8 mask of the warm, bright pixels (lit windows, floodlit stone) of the skyline layer. */
    private Bitmap brightMask(Bitmap sk, int thr) {
        int w = sk.getWidth(), h = sk.getHeight();
        int[] px = new int[w * h];
        sk.getPixels(px, 0, w, 0, 0, w, h);
        boolean any = false;
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            int a = p >>> 24, r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
            int v = 0;
            if (a > 40 && r > thr && r > b + 25) { v = Math.min(255, (r - thr) * 3 + 60); any = true; }
            px[i] = v << 24;
        }
        if (!any) return null;
        Bitmap m = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        m.setPixels(px, 0, w, 0, 0, w, h);
        Bitmap a8 = m.extractAlpha();
        m.recycle();
        return a8;
    }

    /** Edges of a mask facing (dx, dy) (dp): mask minus itself shifted away from that side. */
    private Bitmap edges(Bitmap layer, float dx, float dy) {
        Bitmap a = layer.extractAlpha();
        Bitmap e = Bitmap.createBitmap(layer.getWidth(), layer.getHeight(), Bitmap.Config.ALPHA_8);
        Canvas ec = new Canvas(e);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        ec.drawBitmap(a, 0, 0, p);
        p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
        ec.drawBitmap(a, -dx * k, -dy * k, p);
        a.recycle();
        return e;
    }

    private void dusting(Canvas c, Bitmap layer, float depthDp, double alpha) {
        Bitmap e = edges(layer, 0, -depthDp);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        p.setMaskFilter(new BlurMaskFilter(Math.max(0.5f, 0.3f * k), BlurMaskFilter.Blur.NORMAL));
        p.setColor(night ? argb(alpha * 0.5, 196, 206, 228) : argb(alpha * 0.85, 246, 248, 252));
        c.drawBitmap(e, 0, 0, p);
        e.recycle();
    }

    /** The flash rim-lights the skyline on the side facing the bolt. */
    private void rimLight(Canvas c, Bitmap sk) {
        float bx = (boltX0 + boltX1) / 2;
        Bitmap e = edges(sk, -0.9f, -1.3f);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        p.setShader(new RadialGradient(bx * k, 90 * k, 150 * k, new int[]{argb(night ? 0.95 : 0.8, 214, 222, 255), argb(0.3, 170, 180, 240), 0},
                new float[]{0, 0.5f, 1}, Shader.TileMode.CLAMP));
        c.drawBitmap(e, 0, 0, p);
        e.recycle();
        // facades catch some of the flash
        Bitmap a = sk.extractAlpha();
        Paint f = new Paint(Paint.FILTER_BITMAP_FLAG);
        f.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SCREEN));
        f.setShader(new RadialGradient(bx * k, 100 * k, 110 * k, new int[]{argb(night ? 0.3 : 0.22, 150, 160, 220), 0}, null, Shader.TileMode.CLAMP));
        c.drawBitmap(a, 0, 0, f);
        a.recycle();
    }

    /** Heavy rain: broad slanted sheets veiling the skyline. */
    private void sheets(Canvas c) {
        Random r = new Random(seed ^ 0x5EE7);
        float slant = (float) Math.tan(Math.toRadians(angle()));
        Paint p = paint();
        for (int i = 0; i < 7; i++) {
            float x = -40 + r.nextFloat() * 300, w = 14 + r.nextFloat() * 30;
            double a = 0.07 + r.nextFloat() * 0.1;
            Path s = new Path();
            s.moveTo(x * k, 0);
            s.lineTo((x + w) * k, 0);
            s.lineTo((x + w + slant * 150) * k, 150 * k);
            s.lineTo((x + slant * 150) * k, 150 * k);
            s.close();
            p.setColor(night ? argb(a, 120, 128, 150) : argb(a, 196, 202, 214));
            p.setMaskFilter(new BlurMaskFilter(blurR(5f), BlurMaskFilter.Blur.NORMAL));
            c.drawPath(s, p);
        }
    }

    /** Showers: a couple of rain shafts hanging from the darker clouds. */
    private void shafts(Canvas c) {
        Random r = new Random(seed ^ 0x5AF7);
        Paint p = paint();
        for (int i = 0; i < 3; i++) {
            float x = 20 + r.nextFloat() * 230, w = 26 + r.nextFloat() * 34, top = 44 + r.nextFloat() * 24;
            Path s = new Path();
            s.moveTo(x * k, top * k);
            s.lineTo((x + w) * k, top * k);
            s.lineTo((x + w + 10) * k, 140 * k);
            s.lineTo((x + 4) * k, 140 * k);
            s.close();
            int col = night ? Color.rgb(78, 84, 100) : Color.rgb(128, 136, 152);
            p.setShader(new LinearGradient(0, top * k, 0, 140 * k, new int[]{argb(0.0, Color.red(col), Color.green(col), Color.blue(col)),
                    argb(0.42, Color.red(col), Color.green(col), Color.blue(col)), argb(0.3, Color.red(col), Color.green(col), Color.blue(col))},
                    new float[]{0, 0.35f, 1}, Shader.TileMode.CLAMP));
            p.setMaskFilter(new BlurMaskFilter(blurR(4f), BlurMaskFilter.Blur.NORMAL));
            c.drawPath(s, p);
        }
    }

    // ================================================================= 3. in front of everything (after the ground band)
    void afterGround(Canvas c, Bitmap ground, Bitmap sk) {
        int kind = st.kind;
        if (kind == WxState.FOG) nearFog(c);
        if (st.rainy() || kind == WxState.HAIL) wetSheen(c, ground, sk);
        if (kind == WxState.SNOW || kind == WxState.HEAVYSNOW) dusting(c, ground, 2.2f, kind == WxState.HEAVYSNOW ? 0.95 : 0.75);
        switch (kind) {
            case WxState.DRIZZLE: rain(2300, 2.4f, 4.4f, 1.05f, 0.30, 0.46, 6, 0, c); break;
            case WxState.RAIN: rainLayers(c, 0.7); break;
            case WxState.SHOWERS: rainLayers(c, 0.55); break;
            case WxState.HEAVY: rainLayers(c, 1.25); break;
            case WxState.THUNDER: rainLayers(c, 0.95); break;
            case WxState.SLEET: rainLayers(c, 0.5); snow(c, 0.0, 360, 0); break;
            case WxState.SNOW: snow(c, 1.0, 0, 1); break;
            case WxState.HEAVYSNOW: snow(c, 2.3, 0, 1); break;
            case WxState.HAIL: hail(c, ground); rainLayers(c, 0.35); break;
            default: break;
        }
        if (st.windy) wind(c);
        // keep the white text legible over busy weather: a soft darkening behind the text block
        double prot;
        switch (kind) {
            case WxState.FOG: prot = 0.30; break;
            case WxState.HEAVYSNOW: prot = 0.28; break;
            case WxState.SNOW: case WxState.HEAVY: case WxState.HAIL: prot = 0.18; break;
            case WxState.PARTLY: prot = night ? 0 : 0.14; break;
            default: prot = st.precip() ? 0.1 : 0;
        }
        if (prot > 0) protectText(c, prot);
    }

    private double angle() { // rain slant from vertical, degrees
        return st.windy ? 34 : 11;
    }

    private void rainLayers(Canvas c, double amount) {
        double ang = angle();
        // far: many short, thin, faint; near: fewer, longer, brighter
        rain((int) (900 * amount), 5f, 9f, 1.0f, 0.18, 0.30, ang, 0, c);
        rain((int) (150 * amount), 13f, 24f, 1.7f, 0.32, 0.55, ang, 1, c);
    }

    /** Slanted rain streaks over the whole card (alpha reduced over the text). */
    private void rain(int n, float lmin, float lmax, float widthPx, double amin, double amax, double angDeg, int near, Canvas c) {
        Random r = new Random(seed ^ (0x7A1 + n + near * 13L));
        double a = Math.toRadians(angDeg + (near == 1 ? 2 : 0));
        float sx = (float) Math.sin(a), sy = (float) Math.cos(a);
        Paint p = paint();
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        int cr = night ? 168 : 226, cg = night ? 182 : 232, cb = night ? 212 : 244;
        for (int i = 0; i < n; i++) {
            float len = lmin + r.nextFloat() * (lmax - lmin);
            float x = -20 + r.nextFloat() * 300, y = -20 + r.nextFloat() * 215;
            double al = amin + r.nextFloat() * (amax - amin);
            if (inText(x, y)) al *= 0.5;
            float x2 = x + sx * len, y2 = y + sy * len;
            p.setStrokeWidth(widthPx * (k / 2.625f) * (0.8f + r.nextFloat() * 0.4f));
            if (near == 1) {
                p.setShader(new LinearGradient(x * k, y * k, x2 * k, y2 * k, argb(0, cr, cg, cb), argb(al, cr, cg, cb), Shader.TileMode.CLAMP));
            } else {
                p.setShader(null);
                p.setColor(argb(al, cr, cg, cb));
            }
            c.drawLine(x * k, y * k, x2 * k, y2 * k, p);
        }
    }

    /** Snow: small sharp flakes behind, a few big soft ones in front. dots > 0: sleet pellets only. */
    private void snow(Canvas c, double amount, int dots, int big) {
        Random r = new Random(seed ^ 0x5A0);
        Paint p = paint();
        int col = night ? Color.rgb(218, 224, 240) : Color.WHITE;
        double drift = st.windy ? 0.6 : 0.15;
        if (dots > 0) { // sleet: small icy dots
            for (int i = 0; i < dots; i++) {
                float x = r.nextFloat() * 280, y = r.nextFloat() * 192;
                float rad = (0.5f + r.nextFloat() * 0.45f) * k;
                double a = (0.65 + r.nextFloat() * 0.3) * (inText(x, y) ? 0.6 : 1);
                p.setColor(col);
                p.setAlpha((int) (a * 255));
                c.drawCircle(x * k, y * k, rad, p);
            }
            return;
        }
        int nFar = (int) (520 * amount), nMid = (int) (150 * amount), nNear = (int) (16 * amount);
        for (int i = 0; i < nFar; i++) {
            float x = r.nextFloat() * 280, y = r.nextFloat() * 192;
            p.setColor(col);
            p.setAlpha((int) ((0.55 + r.nextFloat() * 0.35) * (inText(x, y) ? 0.6 : 1) * 255));
            c.drawCircle(x * k, y * k, (0.45f + r.nextFloat() * 0.35f) * k, p);
        }
        Paint m = paint();
        m.setMaskFilter(new BlurMaskFilter(Math.max(0.5f, 0.35f * k), BlurMaskFilter.Blur.NORMAL));
        for (int i = 0; i < nMid; i++) {
            float x = r.nextFloat() * 280, y = r.nextFloat() * 192;
            m.setColor(col);
            m.setAlpha((int) ((0.75 + r.nextFloat() * 0.25) * (inText(x, y) ? 0.6 : 1) * 255));
            float rad = (0.95f + r.nextFloat() * 0.75f) * k;
            c.drawCircle(x * k, y * k, rad, m);
            if (st.windy) { m.setAlpha(m.getAlpha() / 3); c.drawLine(x * k, y * k, (x - 5) * k, (y - (float) (5 * drift)) * k, m); }
        }
        if (big > 0) {
            Paint b = paint();
            for (int i = 0; i < nNear; i++) {
                float x = r.nextFloat() * 280, y = r.nextFloat() * 192;
                if (inText(x, y) && r.nextFloat() < 0.8f) continue; // keep the big soft ones off the text
                float rad = (2.2f + r.nextFloat() * 1.8f) * k;
                b.setMaskFilter(new BlurMaskFilter(rad * 0.7f, BlurMaskFilter.Blur.NORMAL));
                b.setColor(col);
                b.setAlpha((int) ((0.5 + r.nextFloat() * 0.3) * 255));
                c.drawCircle(x * k, y * k, rad, b);
            }
        }
    }

    /** Hail: dense white ice pellets with short motion tails, and some lying on the ground band. */
    private void hail(Canvas c, Bitmap ground) {
        Random r = new Random(seed ^ 0x4A11);
        Paint p = paint();
        Paint t = paint();
        t.setStyle(Paint.Style.STROKE);
        t.setStrokeCap(Paint.Cap.ROUND);
        double a = Math.toRadians(angle() * 0.6);
        float sx = (float) Math.sin(a), sy = (float) Math.cos(a);
        int col = night ? Color.rgb(222, 230, 236) : Color.rgb(250, 252, 252);
        int n = (int) (300 * Math.max(0.7, st.intensity));
        for (int i = 0; i < n; i++) {
            float x = r.nextFloat() * 280, y = r.nextFloat() * 196;
            float rad = (0.8f + r.nextFloat() * 0.9f) * k;
            double al = (0.7 + r.nextFloat() * 0.3) * (inText(x, y) ? 0.55 : 1);
            float tl = 4 + r.nextFloat() * 4;
            t.setStrokeWidth(rad * 1.1f);
            t.setShader(new LinearGradient((x - sx * tl) * k, (y - sy * tl) * k, x * k, y * k, argb(0, 255, 255, 255), argb(al * 0.45, 255, 255, 255), Shader.TileMode.CLAMP));
            c.drawLine((x - sx * tl) * k, (y - sy * tl) * k, x * k, y * k, t);
            p.setColor(col);
            p.setAlpha((int) (al * 255));
            c.drawCircle(x * k, y * k, rad, p);
        }
        // pellets lying along the top of the near bank (above the day strip text)
        Bitmap e = edges(ground, 0, -2.5f);
        Paint ep = new Paint(Paint.FILTER_BITMAP_FLAG);
        ep.setColor(night ? argb(0.35, 200, 210, 220) : argb(0.55, 236, 240, 242));
        c.drawBitmap(e, 0, 0, ep);
        e.recycle();
        for (int i = 0; i < 90; i++) {
            float x = r.nextFloat() * 270, y = 140 + r.nextFloat() * 8;
            int gy = Math.min(H - 1, (int) (y * k)), gx = Math.min(W - 1, (int) (x * k));
            if ((ground.getPixel(gx, gy) >>> 24) < 200) continue; // only on the ground
            p.setColor(col);
            p.setAlpha(200);
            c.drawCircle(x * k, y * k, (0.6f + r.nextFloat() * 0.5f) * k, p);
        }
    }

    /** Rain: the wet near bank catches the sky along its edge; at night lights reflect in streaks. */
    private void wetSheen(Canvas c, Bitmap ground, Bitmap sk) {
        Bitmap e = edges(ground, 0, -1.4f);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        p.setColor(night ? argb(0.28, 150, 160, 190) : argb(0.45, 200, 208, 222));
        c.drawBitmap(e, 0, 0, p);
        e.recycle();
        if (!night) return;
        Bitmap lights = brightMask(sk, 150);
        if (lights == null) return;
        // reflections: bright points just above the near bank mirrored into short vertical streaks
        Random r = new Random(seed ^ 0xEF1);
        Paint s = paint();
        s.setStyle(Paint.Style.STROKE);
        int y0 = (int) (118 * k), y1 = (int) (144 * k);
        for (int x = 0; x < (int) (268 * k); x += 2) {
            int best = 0;
            for (int y = y0; y < y1; y += 2) best = Math.max(best, Color.alpha(lights.getPixel(x, y)));
            if (best < 120 || r.nextFloat() < 0.5f) continue;
            float len = (5 + r.nextFloat() * 9) * k;
            s.setStrokeWidth(0.9f * k);
            s.setShader(new LinearGradient(0, 146 * k, 0, 146 * k + len, argb(0.42 * best / 255.0, 255, 196, 130), argb(0, 255, 196, 130), Shader.TileMode.CLAMP));
            c.drawLine(x, 146 * k, x, 146 * k + len, s);
        }
        lights.recycle();
    }

    private void nearFog(Canvas c) {
        Paint p = paint();
        int fr = night ? 70 : 166, fg = night ? 62 : 168, fb = night ? 60 : 172;
        p.setShader(new LinearGradient(0, 134 * k, 0, H, new int[]{argb(0.42, fr, fg, fb), argb(0.24, fr, fg, fb), argb(0.16, fr, fg, fb)},
                new float[]{0, 0.4f, 1}, Shader.TileMode.CLAMP));
        c.drawRect(0, 130 * k, W, H, p);
        // a low mist band in front of the near bank's top edge
        Paint m = paint();
        m.setColor(night ? argb(0.3, 100, 90, 84) : argb(0.32, 190, 192, 196));
        m.setMaskFilter(new BlurMaskFilter(blurR(4f), BlurMaskFilter.Blur.NORMAL));
        c.drawRect(-10 * k, 136 * k, 290 * k, 146 * k, m);
    }

    /** Strong wind: fast streaks across the sky and leaves blowing past. */
    private void wind(Canvas c) {
        Random r = new Random(seed ^ 0x3E3D);
        Paint p = paint();
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        int cr = night ? 170 : 236, cg = night ? 180 : 240, cb = night ? 205 : 248;
        for (int i = 0; i < 44; i++) {
            float x = -30 + r.nextFloat() * 280, y = 8 + r.nextFloat() * 128, len = 50 + r.nextFloat() * 80;
            float dy = -len * 0.06f;
            double a = 0.12 + r.nextFloat() * 0.16;
            if (inText(x + len / 2, y)) a *= 0.5;
            p.setStrokeWidth((0.8f + r.nextFloat() * 0.8f) * k * 0.55f);
            p.setShader(new LinearGradient(x * k, y * k, (x + len) * k, (y + dy) * k, new int[]{argb(0, cr, cg, cb), argb(a, cr, cg, cb), argb(0, cr, cg, cb)},
                    new float[]{0, 0.6f, 1}, Shader.TileMode.CLAMP));
            c.drawLine(x * k, y * k, (x + len) * k, (y + dy) * k, p);
        }
        Paint leaf = paint();
        for (int i = 0; i < 14; i++) {
            float x = r.nextFloat() * 265, y = 84 + r.nextFloat() * 70;
            if (inText(x, y)) continue;
            c.save();
            c.translate(x * k, y * k);
            c.rotate(r.nextFloat() * 360);
            leaf.setColor(night ? Color.rgb(44, 40, 38) : (r.nextBoolean() ? Color.rgb(122, 86, 50) : Color.rgb(96, 74, 50)));
            float lw = (1.8f + r.nextFloat() * 1.2f) * k, lh = lw * 0.5f;
            c.drawOval(new RectF(-lw, -lh, lw, lh), leaf);
            c.restore();
        }
    }

    private void protectText(Canvas c, double a) {
        Paint p = paint();
        float cx = 80 * k, cy = 62 * k;
        p.setShader(new RadialGradient(cx, cy, 120 * k, new int[]{argb(a, 18, 20, 28), argb(a * 0.6, 18, 20, 28), 0}, new float[]{0, 0.55f, 1}, Shader.TileMode.CLAMP));
        c.save();
        c.scale(1.45f, 0.75f, cx, cy); // elliptical, wider than tall
        c.drawCircle(cx, cy, 120 * k, p);
        c.restore();
    }
}
