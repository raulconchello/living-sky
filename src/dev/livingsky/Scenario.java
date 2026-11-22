package dev.livingsky;

import dev.livingsky.render.Wx;

/** Debug-only synthetic weather on top of the real forecast, to check every sky type on the phone. */
public final class Scenario {
    private Scenario() {}

    static final String[] NAMES = {"", "clear", "partly", "overcast", "fog", "drizzle", "rain", "heavy", "showers", "thunder",
            "hail", "thunderhail", "sleet", "snow", "heavysnow", "wind", "rainwind", "mixed"};

    public static void apply(Wx w, double now, String s) {
        if (s.equals("mixed")) { mixed(w, now); return; }
        int code;
        double cloud, rain = 0, snow = 0, gust = 18, wind = 9;
        switch (s) {
            case "clear": code = 0; cloud = 5; break;
            case "partly": code = 2; cloud = 50; break;
            case "overcast": code = 3; cloud = 100; break;
            case "fog": code = 45; cloud = 100; wind = 3; gust = 6; break;
            case "drizzle": code = 53; cloud = 100; rain = 0.07; break;
            case "rain": code = 63; cloud = 100; rain = 0.5; break;
            case "heavy": code = 65; cloud = 100; rain = 1.6; gust = 35; break;
            case "showers": code = 81; cloud = 75; rain = 0.6; gust = 30; break;
            case "thunder": code = 95; cloud = 100; rain = 1.2; gust = 45; break;
            case "hail": code = 90; cloud = 100; rain = 0.8; gust = 40; break;
            case "thunderhail": code = 99; cloud = 100; rain = 1.0; gust = 55; wind = 30; break;
            case "sleet": code = 67; cloud = 100; rain = 0.2; snow = 0.12; break;
            case "snow": code = 73; cloud = 100; snow = 0.3; break;
            case "heavysnow": code = 75; cloud = 100; snow = 0.9; gust = 30; break;
            case "wind": code = 3; cloud = 70; wind = 48; gust = 78; break;
            case "rainwind": code = 63; cloud = 100; rain = 0.6; wind = 52; gust = 82; break;
            default: return;
        }
        // debug only: forecasts cached before RH was requested have no hourly RH; use the current value
        if (w.hRH.length != w.hT.length) {
            w.hRH = new double[w.hT.length];
            java.util.Arrays.fill(w.hRH, w.rh > 0 ? w.rh : 85);
        }
        w.code = code;
        w.cloud = cloud;
        w.wind = wind;
        w.gust = gust;
        for (int i = 0; i < w.hT.length; i++) {
            if (w.hT[i] < now - 1 || w.hT[i] > now + 8) continue;
            w.hCode[i] = code;
            w.hCloud[i] = cloud;
            w.hPP[i] = rain + snow > 0 ? 80 : 0;
            if (i < w.hGust.length) w.hGust[i] = gust;
        }
        for (int i = 0; i < w.mT.length; i++) {
            if (w.mT[i] < now - 0.5 || w.mT[i] > now + 3) continue;
            double f = 0.6 + 0.4 * Math.sin((w.mT[i] - now) * 2.1);
            w.mRain[i] = rain * f;
            w.mSnow[i] = snow * f;
            w.mPrecip[i] = (rain + snow) * f;
            w.mCode[i] = code;
        }
    }

    /** Debug: transitions in one column: clear (2 h) -> rain (2.5 h) -> snow (2.5 h) -> overcast, dry. */
    static void mixed(Wx w, double now) {
        if (w.hRH.length != w.hT.length) { w.hRH = new double[w.hT.length]; java.util.Arrays.fill(w.hRH, 85); }
        w.code = 0; w.cloud = 10;
        for (int i = 0; i < w.hT.length; i++) {
            double d = w.hT[i] - now;
            if (d < -1 || d > 10) continue;
            int code = d < 2 ? 0 : d < 4.5 ? 63 : d < 7 ? 73 : 3;
            w.hCode[i] = code;
            w.hCloud[i] = code == 0 ? 10 : 100;
            w.hPP[i] = code == 63 || code == 73 ? 85 : 5;
        }
        for (int i = 0; i < w.mT.length; i++) {
            double d = w.mT[i] - now;
            if (d < -0.5 || d > 10) continue;
            boolean rain = d >= 2 && d < 4.5, snow = d >= 4.5 && d < 7;
            double f = 0.7 + 0.3 * Math.sin(d * 2.3);
            w.mRain[i] = rain ? 0.35 * f : 0;
            w.mSnow[i] = snow ? 0.28 * f : 0;
            w.mPrecip[i] = rain ? 0.35 * f : snow ? 0.3 * f : 0;
            w.mCode[i] = rain ? 63 : snow ? 73 : d < 2 ? 0 : 3;
        }
    }
}
