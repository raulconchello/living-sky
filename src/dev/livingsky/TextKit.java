package dev.livingsky;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;

import java.util.ArrayList;
import java.util.List;

/**
 * CSS-like text on a Canvas: bundled Inter weights, CSS letter-spacing (added after every character, trailing one
 * included in the width), and CSS text-shadow (blur radius r -> Gaussian sigma r/2) computed on the combined alpha
 * mask of a block of text, like Chromium does.
 */
final class TextKit {
    private TextKit() {}

    private static final Typeface[] FACES = new Typeface[10];

    static synchronized Typeface face(Context c, int weight) {
        int i = weight / 100;
        if (FACES[i] == null) {
            int res;
            switch (weight) {
                case 200: res = R.font.inter_extralight; break;
                case 300: res = R.font.inter_light; break;
                case 400: res = R.font.inter_regular; break;
                case 500: res = R.font.inter_medium; break;
                default: res = R.font.inter_semibold; break;
            }
            FACES[i] = c.getResources().getFont(res);
        }
        return FACES[i];
    }

    static Paint paint(Context c, int weight, float sizePx, int color, String features) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG | Paint.LINEAR_TEXT_FLAG); // fractional (unhinted) advances, like Chromium
        p.setTypeface(face(c, weight));
        p.setTextSize(sizePx);
        p.setColor(color);
        if (features != null) p.setFontFeatureSettings(features);
        return p;
    }

    /** Exact advance (Paint.measureText rounds up to whole pixels, which drifts against Chromium). */
    static float width(Paint p, String s, float ls) {
        if (s.isEmpty()) return 0;
        char[] ch = s.toCharArray();
        float[] adv = new float[ch.length];
        float tot = p.getTextRunAdvances(ch, 0, ch.length, 0, ch.length, false, adv, 0);
        return tot + ls * ch.length;
    }

    /** Draws s with its pen origin at (x, baseline y). @return the CSS end x (after the trailing letter-spacing). */
    static float draw(Canvas c, Paint p, String s, float x, float y, float ls) {
        if (s.isEmpty()) return x;
        if (ls == 0) {
            c.drawText(s, x, y, p);
            return x + width(p, s, 0);
        }
        char[] ch = s.toCharArray();
        float[] adv = new float[ch.length];
        p.getTextRunAdvances(ch, 0, ch.length, 0, ch.length, false, adv, 0);
        float cx = x;
        for (int i = 0; i < ch.length; i++) {
            c.drawText(ch, i, 1, cx, y, p);
            cx += adv[i] + ls;
        }
        return cx;
    }

    /** Right edge of the ink of the last character of s drawn at pen x (letter-spacing ls). */
    static float inkRight(Paint p, String s, float x, float ls) {
        if (s.isEmpty()) return x;
        char[] ch = s.toCharArray();
        float[] adv = new float[ch.length];
        p.getTextRunAdvances(ch, 0, ch.length, 0, ch.length, false, adv, 0);
        float cx = x;
        for (int i = 0; i < ch.length - 1; i++) cx += adv[i] + ls;
        android.graphics.Path path = new android.graphics.Path();
        p.getTextPath(ch, ch.length - 1, 1, cx, 0, path);
        android.graphics.RectF b = new android.graphics.RectF();
        path.computeBounds(b, true);
        return b.isEmpty() ? cx + adv[ch.length - 1] : b.right;
    }

    /** Android BlurMaskFilter radius for a Gaussian sigma (Skia: sigma = 0.57735 r + 0.5). */
    static float blurRadius(float sigmaPx) {
        return Math.max(0.01f, (sigmaPx - 0.5f) / 0.57735f);
    }

    /** One CSS shadow: offset and blur radius in px, colour ARGB. */
    static final class Shadow {
        final float dx, dy, blur;
        final int color;
        Shadow(float dx, float dy, float blur, int color) { this.dx = dx; this.dy = dy; this.blur = blur; this.color = color; }
    }

    /** Text whose shadows are computed together on one alpha mask, then the text drawn on top. */
    static final class Batch {
        private final Bitmap mask;
        private final Canvas mc;
        private final List<Object[]> ops = new ArrayList<>();

        Batch(int w, int h) {
            mask = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8);
            mc = new Canvas(mask);
        }

        private float haloStroke = -1;

        /**
         * Label halo: the shadow mask is the glyphs at (nearly) full strength, grown by a round stroke, so the soft
         * dark halo sits right at the glyph edge whatever the text's own opacity (dimmed text keeps a weaker halo).
         */
        Batch halo(float strokePx) { haloStroke = strokePx; return this; }

        private int forceColor;

        /** Debug: draw every glyph opaque in this colour and no shadows (a mask of where the text is). */
        Batch force(int color) { forceColor = color; return this; }

        float text(Paint p, String s, float x, float y, float ls) {
            Paint mp = new Paint(p);
            int ma = Color.alpha(p.getColor());
            if (haloStroke >= 0) {
                ma = Math.min(255, Math.round(ma * 4 / 3f));
                if (haloStroke > 0) {
                    mp.setStyle(Paint.Style.FILL_AND_STROKE);
                    mp.setStrokeWidth(haloStroke);
                    mp.setStrokeJoin(Paint.Join.ROUND);
                }
            }
            mp.setColor(Color.argb(ma, 255, 255, 255));
            float end = draw(mc, mp, s, x, y, ls);
            ops.add(new Object[]{p, s, x, y, ls});
            return end;
        }

        /** Paints the shadows (last one first, like CSS) and then the text. */
        void flush(Canvas target, Shadow... shadows) {
            if (forceColor != 0) {
                for (Object[] o : ops) {
                    Paint fp = new Paint((Paint) o[0]);
                    fp.setColor(forceColor);
                    draw(target, fp, (String) o[1], (Float) o[2], (Float) o[3], (Float) o[4]);
                }
                mask.recycle();
                return;
            }
            for (int i = shadows.length - 1; i >= 0; i--) {
                Shadow s = shadows[i];
                Paint bp = new Paint();
                bp.setMaskFilter(new BlurMaskFilter(blurRadius(s.blur / 2f), BlurMaskFilter.Blur.NORMAL));
                int[] off = new int[2];
                Bitmap b = mask.extractAlpha(bp, off);
                Paint cp = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
                cp.setColor(s.color);
                target.drawBitmap(b, off[0] + s.dx, off[1] + s.dy, cp);
                b.recycle();
            }
            for (Object[] o : ops) draw(target, (Paint) o[0], (String) o[1], (Float) o[2], (Float) o[3], (Float) o[4]);
            mask.recycle();
        }
    }
}
