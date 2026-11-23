package dev.livingsky;

import dev.livingsky.render.Timeline;

/**
 * Debug-only synthetic weather, written as timeline samples on top of the real one (now, the next 3 h of 15-min
 * slots, the sky and flags of the next 8 h), so the word, the card, the column and the strip all show it.
 */
public final class Scenario {
    private Scenario() {}

    static final String[] NAMES = {"", "clear", "partly", "overcast", "fog", "drizzle", "rain", "heavy", "showers", "thunder",
            "hail", "thunderhail", "sleet", "snow", "heavysnow", "wind", "rainwind", "mixed"};

    public static void apply(Timeline tl, double now, String s) {
        tl.key += "|" + s;
        if (s.equals("mixed")) { mixed(tl, now); return; }
        double cloud, rain = 0, snow = 0, gust = 18;   // rain / snow: mm per 15 min
        boolean fog = false, thunder = false, hail = false, showers = false;
        switch (s) {
            case "clear": cloud = 5; break;
            case "partly": cloud = 50; break;
            case "overcast": cloud = 100; break;
            case "fog": cloud = 100; fog = true; gust = 6; break;
            case "drizzle": cloud = 100; rain = 0.07; break;
            case "rain": cloud = 100; rain = 0.5; break;
            case "heavy": cloud = 100; rain = 1.6; gust = 35; break;
            case "showers": cloud = 75; rain = 0.6; gust = 30; showers = true; break;
            case "thunder": cloud = 100; rain = 1.2; gust = 45; thunder = true; break;
            case "hail": cloud = 100; rain = 0.8; gust = 40; hail = true; break;
            case "thunderhail": cloud = 100; rain = 1.0; gust = 55; thunder = true; hail = true; break;
            case "sleet": cloud = 100; rain = 0.2; snow = 0.12; break;
            case "snow": cloud = 100; snow = 0.3; break;
            case "heavysnow": cloud = 100; snow = 0.9; gust = 30; break;
            case "wind": cloud = 70; gust = 78; break;
            case "rainwind": cloud = 100; rain = 0.6; gust = 82; break;
            default: return;
        }
        double share = rain + snow > 0 ? snow / (rain + snow) : 0;
        set(tl.now, cloud, gust, (rain + snow) * 4 * 0.6, share, fog, thunder, hail, showers);
        for (Timeline.Sample sl : tl.slot) {
            double d = sl.t - now;
            if (d >= 3) break;
            double f = 0.6 + 0.4 * Math.sin(d * 2.1);
            set(sl, cloud, gust, (rain + snow) * 4 * f, share, fog, thunder, hail, showers);
        }
        for (Timeline.Sample h : tl.hour) {
            double d = h.t - now;
            if (d < -1 || d > 8) continue;
            boolean wet = d < 3 && rain + snow > 0;
            h.cloud = cloud;
            h.gust = gust;
            h.fog = fog;
            h.thunder = thunder && wet;
            h.hail = hail && wet;
            h.showers = showers && wet;
            h.freezing = false;
        }
        tl.aggregate();
    }

    private static void set(Timeline.Sample x, double cloud, double gust, double R, double share,
                            boolean fog, boolean thunder, boolean hail, boolean showers) {
        boolean wet = R > 0;
        x.cloud = cloud;
        x.gust = gust;
        x.P = wet ? 1 : 0;
        x.R = R;
        x.snow = share;
        x.fog = fog && !wet;
        x.thunder = thunder && wet;
        x.hail = hail && wet;
        x.showers = showers && wet;
        x.freezing = false;
        x.src = "scenario";
    }

    /** Debug: transitions in one column: clear (2 h) -> rain (2.5 h) -> snow (2.5 h) -> overcast, dry. */
    static void mixed(Timeline tl, double now) {
        set(tl.now, 10, 15, 0, 0, false, false, false, false);
        for (Timeline.Sample sl : tl.slot) {
            double d = sl.t - now;
            if (d >= 10) break;
            boolean rain = d >= 2 && d < 4.5, snow = d >= 4.5 && d < 7;
            double f = 0.7 + 0.3 * Math.sin(d * 2.3);
            set(sl, d < 2 ? 10 : 100, 15, rain ? 1.4 * f : snow ? 1.2 * f : 0, snow ? 1 : 0, false, false, false, false);
        }
        for (Timeline.Sample h : tl.hour) {
            double d = h.t - now;
            if (d < -1 || d > 10) continue;
            h.cloud = d < 2 ? 10 : 100;
            h.fog = false; h.thunder = false; h.hail = false; h.showers = false; h.freezing = false;
        }
        tl.aggregate();
    }
}
