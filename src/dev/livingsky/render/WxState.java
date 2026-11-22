package dev.livingsky.render;

/**
 * The weather state the card shows right now, from the current WMO code, cloud cover, the live 15-min precipitation
 * and wind gusts. Drives the sky shader parameters and the foreground weather pass.
 */
public final class WxState {
    public static final int CLEAR = 0, PARTLY = 1, OVERCAST = 2, FOG = 3, DRIZZLE = 4, RAIN = 5, HEAVY = 6, SHOWERS = 7,
            THUNDER = 8, HAIL = 9, SNOW = 10, HEAVYSNOW = 11, SLEET = 12;
    public static final String[] NAMES = {"clear", "partly", "overcast", "fog", "drizzle", "rain", "heavy", "showers",
            "thunder", "hail", "snow", "heavysnow", "sleet"};

    public int kind = OVERCAST;
    public double intensity = 0.5; // 0..1 within the state
    public double gust;            // km/h
    public boolean windy;          // gusts >= 50 km/h
    public boolean lightning;      // thunder (also thunder with hail)
    public double cover = 1;       // 0..1

    public boolean precip() { return kind >= DRIZZLE && kind != FOG; }
    public boolean rainy() { return kind == DRIZZLE || kind == RAIN || kind == HEAVY || kind == SHOWERS || kind == THUNDER || kind == SLEET; }
    public boolean snowy() { return kind == SNOW || kind == HEAVYSNOW; }

    public static int fromCode(int code, double cover) {
        if (code == 0 || code == 1) return cover > 0.8 ? OVERCAST : cover > 0.3 ? PARTLY : CLEAR;
        if (code == 2) return cover > 0.85 ? OVERCAST : PARTLY;
        if (code == 3) return cover < 0.3 ? CLEAR : cover < 0.8 ? PARTLY : OVERCAST;
        if (code == 45 || code == 48) return FOG;
        if (code >= 51 && code <= 55) return DRIZZLE;
        if (code == 56 || code == 57 || code == 66 || code == 67) return SLEET;
        if (code == 61 || code == 63) return RAIN;
        if (code == 65) return HEAVY;
        if (code == 71 || code == 73 || code == 77 || code == 85) return SNOW;
        if (code == 75 || code == 86) return HEAVYSNOW;
        if (code == 80 || code == 81) return SHOWERS;
        if (code == 82) return HEAVY;
        if (code >= 87 && code <= 90) return HAIL; // hail showers without thunder (WMO 4677; used by the debug scenario)
        if (code == 95) return THUNDER;
        if (code == 96 || code == 99) return HAIL;
        return OVERCAST;
    }

    public static WxState of(Wx w, double now) {
        WxState s = new WxState();
        s.cover = Math.max(0, Math.min(1, w.cloud / 100.0));
        s.kind = fromCode(w.code, s.cover);
        double mmh = w.mmh(now);
        int pt = w.ptype(now);
        // precipitation measured now but a dry code: show what is falling
        if (!s.precip() && s.kind != FOG && mmh > 0.05 && pt != Wx.DRY) {
            s.kind = pt == Wx.SNOW ? (mmh > 2.5 ? HEAVYSNOW : SNOW) : pt == Wx.SLEET ? SLEET : mmh > 4 ? HEAVY : mmh < 0.4 ? DRIZZLE : RAIN;
        }
        if (s.kind == RAIN && mmh > 4) s.kind = HEAVY;
        if (s.kind == SNOW && mmh > 2.5) s.kind = HEAVYSNOW;
        s.lightning = s.kind == THUNDER || s.kind == HAIL && (w.code == 96 || w.code == 99);
        double base;
        switch (s.kind) {
            case DRIZZLE: base = 0.5; break;
            case RAIN: base = 0.6; break;
            case HEAVY: base = 1; break;
            case SHOWERS: base = 0.65; break;
            case THUNDER: base = 0.8; break;
            case HAIL: base = 0.9; break;
            case SNOW: base = 0.6; break;
            case HEAVYSNOW: base = 1; break;
            case SLEET: base = 0.6; break;
            default: base = 0.5;
        }
        s.intensity = mmh > 0 ? Math.max(0.35, Math.min(1, 0.5 * base + mmh / 6)) : base;
        s.gust = Math.max(w.gust, w.wind);
        s.windy = s.gust >= 50;
        return s;
    }
}
