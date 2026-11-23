package dev.livingsky.render;

import static dev.livingsky.render.Noise.sm;

/** Derives the sky shader inputs from weather + time + place (shared by the phone and the desktop harness). */
public final class Scene {
    private Scene() {}

    public static final int MODE_AUTO = 0, MODE_DAY = 1, MODE_NIGHT = 2;

    /** Is it night for the skyline / shader choice? (sun below about -4 deg) */
    public static boolean isNight(double el, int mode) {
        if (mode == MODE_DAY) return false;
        if (mode == MODE_NIGHT) return true;
        return el < -4;
    }

    /** Local sunrise / sunset hour of the day containing now (falls back to 7.5 / 16.5). */
    public static double[] riseSet(Wx w, double now) {
        int i = w.dayIndex(now);
        if (i >= 0 && i < w.dRise.length && w.dSet[i] > w.dRise[i]) return new double[]{w.dRise[i] - w.dT[i], w.dSet[i] - w.dT[i], now - w.dT[i]};
        double localH = ((now % 24) + 24) % 24;
        return new double[]{7.5, 16.5, localH};
    }

    /**
     * @param mock   the sketch scenario: full overcast, rain approaching (sky8.js approach: true)
     * @param mode   MODE_AUTO / MODE_DAY / MODE_NIGHT (debug override)
     * @param heroGlow 1 for a hand-drawn landmark that is floodlit at night, lower for generic towns
     */
    /** The card's weather right now: the timeline's fused "now" through the one classifier (same as the word). */
    public static WxState state(Timeline tl) { return WxClass.classify(tl.now, WxClass.NOW); }

    public static SkyShader.Params sky(Timeline tl, double now, double lat, double lon, boolean mock, int mode, double heroGlow) {
        Wx w = tl.wx;
        SkyShader.Params p = new SkyShader.Params();
        double el = Sun.elevation(now, lat, lon);
        if (mode == MODE_NIGHT && el > -20) el = -30;
        if (mode == MODE_DAY && el < 18) el = 25;
        p.el = el;
        p.night = isNight(el, mode);
        double[] rs = riseSet(w, now);
        double sr = rs[0], ss = rs[1], hod = rs[2];
        if (mode == MODE_DAY && (hod < sr || hod > ss)) hod = (sr + ss) / 2;
        p.sunX = 140 + (hod - sr) / (ss - sr) * 110;
        p.sunY = p.HY - Math.max(-3, el) * 4.2;
        p.heroGlow = heroGlow;
        if (mock) { // the sketches: overcast with rain approaching (sky8.js), or the sunny variant (sky9.js, cloud .12)
            p.daylight = false; // the approved originals, for pixel comparison
            boolean sunny = tl.now.cloud < 50;
            p.cover = sunny ? tl.now.cloud / 100.0 : 1;
            p.approach = sunny ? 0 : 1;
            return p;
        }
        WxState st = state(tl);
        p.state = st.kind;
        double cov = st.cover;
        switch (st.kind) {
            case WxState.CLEAR: cov = Math.min(cov, 0.25); break;
            case WxState.PARTLY: cov = Math.max(0.35, Math.min(0.72, cov)); break;
            case WxState.SHOWERS: cov = 0.74; break;
            default: if (st.kind != WxState.OVERCAST || cov > 0.8) cov = 1;
        }
        p.cover = cov;
        // falling precipitation is drawn in front of the skyline by WeatherFx, so the sky shader only darkens
        p.precip = 0;
        p.ptype = Wx.DRY;
        p.fog = st.kind == WxState.FOG ? 0.8 : 0;
        switch (st.kind) {
            case WxState.DRIZZLE: p.storm = 0.12; break;
            case WxState.RAIN: p.storm = 0.28; break;
            case WxState.SLEET: p.storm = 0.25; p.tint = new double[]{170, 178, 192, 0.15}; break;
            case WxState.HEAVY: p.storm = 0.48; p.tint = new double[]{92, 100, 118, 0.18}; break;
            case WxState.SHOWERS: p.storm = 0.32; break;
            case WxState.THUNDER: p.storm = 0.7; p.tint = new double[]{58, 56, 84, 0.3}; break;
            case WxState.HAIL: p.storm = 0.5; p.tint = new double[]{100, 122, 106, 0.42}; break;
            case WxState.SNOW: p.storm = 0.05; p.tint = new double[]{196, 200, 210, 0.22}; break;
            case WxState.HEAVYSNOW: p.storm = 0.1; p.tint = new double[]{206, 210, 218, 0.38}; break;
            default: break;
        }
        if (!p.night) { // the brighter daylight base: keep rain and storms moody
            switch (st.kind) {
                case WxState.DRIZZLE: p.storm += 0.06; break;
                case WxState.RAIN: case WxState.SHOWERS: case WxState.SLEET: p.storm += 0.12; break;
                case WxState.HEAVY: p.storm += 0.2; break;
                case WxState.THUNDER: p.storm += 0.18; break;
                case WxState.HAIL: p.storm += 0.18; break;
                case WxState.HEAVYSNOW: p.storm += 0.22; break;
                case WxState.SNOW: p.storm += 0.08; break;
                case WxState.FOG: p.storm += 0.08; break;
                default: break;
            }
        }
        if (st.windy) p.stretch = 0.42;
        // rain is coming: the timeline's P over the next 3 h (the same samples as the column's bars)
        p.approach = st.precip() ? 0 : sm(0.25, 0.6, tl.maxP(0, 3)) * sm(0.5, 0.9, p.cover);
        return p;
    }
}
