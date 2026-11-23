package dev.livingsky.render;

import static dev.livingsky.render.Noise.sm;

/**
 * The one classifier: the condition word, the card's weather state, the column's bars and band and the strip's day
 * icons all go through here, with one set of thresholds, so they cannot contradict each other.
 */
public final class WxClass {
    private WxClass() {}

    // ---------------------------------------------------------------- every threshold, in one place
    /** Probability of precipitation: faint bars / "chance" from here, solid bars / precipitation icon from here. */
    public static final double P_POSSIBLE = 0.20, P_LIKELY = 0.50;
    /** mm/h: a sample below this rate is dry (P = 0); an ensemble member is wet from this many mm in its hour. */
    public static final double WET_RATE = 0.1, WET_MM = 0.1;
    /** mm/h: drizzle below; "light" rain / the bars' light blue below; heavy from (water equivalent for snow). */
    public static final double DRIZZLE_MAX = 0.4, LIGHT_RAIN = 1, LIGHT_SNOW = 0.5, HEAVY_RAIN = 4, HEAVY_SNOW = 2.5;
    /** Cloud cover %: clear below CLEAR_MAX ("Sunny" / "Clear" below SUNNY_MAX), overcast above OVERCAST_MIN. */
    public static final double SUNNY_MAX = 15, CLEAR_MAX = 30, OVERCAST_MIN = 80;
    /** km/h: windy from this gust. */
    public static final double WINDY_GUST = 50;
    /** Snow share of the water: snow from SNOW_SHARE, rain up to SLEET_SHARE, sleet between. */
    public static final double SNOW_SHARE = 0.8, SLEET_SHARE = 0.2;
    /** Deg C: the type from the temperature when nothing else tells it (snow up to SNOW_T, sleet up to SLEET_T). */
    public static final double SNOW_T = 0.5, SLEET_T = 2;
    /** Observed visibility (m): fog below FOG_VIS, no fog from NO_FOG_VIS. */
    public static final double FOG_VIS = 1000, NO_FOG_VIS = 5000;
    /** Daylight hours of fog that make a dry day's icon fog. */
    public static final int DAY_FOG_HOURS = 4;
    /** Where the ensemble drives P, cloud is raised to 100 x smoothstep(RAISE_P0, RAISE_P1, P). */
    public static final double RAISE_P0 = 0.2, RAISE_P1 = 0.6;
    /** Minutes: radar is "now" up to RADAR_MAX_MIN old; a station decides wet / dry up to OBS_WET_MIN, the type up to OBS_TYPE_MIN. */
    public static final double RADAR_MAX_MIN = 15, OBS_WET_MIN = 40, OBS_TYPE_MIN = 60;
    /** mm/h: a fresh dry observation overrides a wet model below OBS_DRY_MAX, and a radar speck below RADAR_SPECK. */
    public static final double OBS_DRY_MAX = 0.5, RADAR_SPECK = 0.25;
    /** Hours ahead: radar weight 1 up to RADAR_FULL_H, 0 by RADAR_END_H; the 15-min model likewise up to DET_FULL_H / DET_END_H, then the ensemble. */
    public static final double RADAR_FULL_H = 1, RADAR_END_H = 2, DET_FULL_H = 2, DET_END_H = 3;

    public static final int NOW = 0, HOUR = 1;

    // ---------------------------------------------------------------- samples
    /** Precipitation type of a sample: DRY, RAIN, SLEET or SNOW (a freezing flag gives sleet). */
    public static int ptype(Timeline.Sample s) {
        if (s.P <= 0 || s.R < WET_RATE) return Wx.DRY;
        return type(s.snow, s.freezing);
    }

    public static int type(double snowShare, boolean freezing) {
        if (freezing) return Wx.SLEET;
        return snowShare >= SNOW_SHARE ? Wx.SNOW : snowShare <= SLEET_SHARE ? Wx.RAIN : Wx.SLEET;
    }

    /** Snow share from the temperature (deg C) when no source gives the type. */
    public static double snowFromTemp(double t) { return t <= SNOW_T ? 1 : t <= SLEET_T ? 0.5 : 0; }

    /** The column draws a bar (faint or solid). */
    public static boolean bar(Timeline.Sample s) { return s.P >= P_POSSIBLE && s.R >= WET_RATE; }

    /** A solid bar: precipitation is likely. */
    public static boolean solid(Timeline.Sample s) { return s.P >= P_LIKELY && s.R >= WET_RATE; }

    /** Bar opacity (and the band texture's weight): 0 without a bar, 0.3 faint at 20 %, solid from 70 %. */
    public static double presence(double P) { return P < P_POSSIBLE ? 0 : 0.3 + 0.7 * sm(0.2, 0.7, P); }

    /** Bar colour by type and rate (ARGB): white snow, light blue sleet (hatched) and light rain, deeper for heavier. */
    public static int barColor(int type, double R) {
        if (type == Wx.SNOW) return 0xffeef2fa;
        if (type == Wx.SLEET) return 0xff8cc0ff;
        return R < LIGHT_RAIN ? 0xff8cc0ff : R < HEAVY_RAIN ? 0xff4d97ff : 0xff2b63e8;
    }

    /** "Rain %": P in steps of 5, never across a category (a faint bar never reads 50, no bar never reads 20). */
    public static int pct(double P) {
        int v = (int) Math.round(P * 20) * 5;
        if (P < P_LIKELY) v = Math.min(v, (int) (P_LIKELY * 100) - 5);
        if (P < P_POSSIBLE) v = Math.min(v, (int) (P_POSSIBLE * 100) - 5);
        if (P >= P_POSSIBLE) v = Math.max(v, (int) (P_POSSIBLE * 100));
        if (P >= P_LIKELY) v = Math.max(v, (int) (P_LIKELY * 100));
        return Math.max(0, Math.min(100, v));
    }

    /** Cloud raised where precipitation is probable (weight 1 where the ensemble drives P). */
    public static double raise(double cloud, double P, double weight) {
        return Math.max(cloud, 100 * sm(RAISE_P0, RAISE_P1, P) * weight);
    }

    // ---------------------------------------------------------------- classify
    /**
     * The weather state of one sample. NOW: P is 0 or 1 (it precipitates or not). HOUR: precipitation from
     * P_POSSIBLE on, flagged {@link WxState#chance} below P_LIKELY (the column's faint bars).
     */
    public static WxState classify(Timeline.Sample s, int mode) {
        WxState st = new WxState();
        st.cloud = s.cloud;
        st.cover = Math.max(0, Math.min(1, s.cloud / 100.0));
        st.P = s.P;
        st.R = s.R;
        st.temp = s.temp;
        st.gust = s.gust;
        st.windy = s.gust >= WINDY_GUST;
        boolean wet = s.R >= WET_RATE && (mode == NOW ? s.P >= P_LIKELY : s.P >= P_POSSIBLE);
        if (wet) {
            st.type = ptype(s);
            st.freezing = s.freezing;
            st.showers = s.showers;
            st.chance = mode == HOUR && s.P < P_LIKELY;
            if (s.hail) st.kind = WxState.HAIL;
            else if (s.thunder) st.kind = WxState.THUNDER;
            else if (st.type == Wx.SNOW) st.kind = s.R >= HEAVY_SNOW ? WxState.HEAVYSNOW : WxState.SNOW;
            else if (st.type == Wx.SLEET) st.kind = WxState.SLEET;
            else if (s.showers) st.kind = s.R >= HEAVY_RAIN ? WxState.HEAVY : WxState.SHOWERS;
            else st.kind = s.R >= HEAVY_RAIN ? WxState.HEAVY : s.R < DRIZZLE_MAX ? WxState.DRIZZLE : WxState.RAIN;
            st.lightning = s.thunder;
        } else {
            st.type = Wx.DRY;
            st.kind = s.fog ? WxState.FOG : cloudKind(s.cloud);
        }
        double base;
        switch (st.kind) {
            case WxState.DRIZZLE: base = 0.5; break;
            case WxState.RAIN: base = 0.6; break;
            case WxState.HEAVY: base = 1; break;
            case WxState.SHOWERS: base = 0.65; break;
            case WxState.THUNDER: base = 0.8; break;
            case WxState.HAIL: base = 0.9; break;
            case WxState.SNOW: base = 0.6; break;
            case WxState.HEAVYSNOW: base = 1; break;
            case WxState.SLEET: base = 0.6; break;
            default: base = 0.5;
        }
        st.intensity = wet ? Math.max(0.35, Math.min(1, 0.5 * base + s.R / 6)) : base;
        return st;
    }

    public static int cloudKind(double cloud) {
        return cloud < CLEAR_MAX ? WxState.CLEAR : cloud <= OVERCAST_MIN ? WxState.PARTLY : WxState.OVERCAST;
    }

    // ---------------------------------------------------------------- the word
    /** The condition word {full, short} for the header ("Light rain", ", windy" appended, "Windy" alone when dry). */
    public static String[] word(WxState st, boolean night) {
        String[] w = base(st, night);
        if (!st.windy) return w;
        boolean calm = st.kind == WxState.CLEAR || st.kind == WxState.PARTLY || st.kind == WxState.OVERCAST;
        if (calm) return new String[]{"Windy", "Windy"};
        return new String[]{w[0] + ", windy", w[1] + ", windy"};
    }

    private static String[] base(WxState st, boolean night) {
        double R = st.R;
        switch (st.kind) {
            case WxState.CLEAR: return two(st.cloud < SUNNY_MAX ? (night ? "Clear" : "Sunny") : (night ? "Mostly clear" : "Mostly sunny"));
            case WxState.PARTLY: return two("Partly cloudy");
            case WxState.OVERCAST: return two("Overcast");
            case WxState.FOG: return two(st.temp <= 0 ? "Freezing fog" : "Fog");
            case WxState.DRIZZLE: return st.freezing ? new String[]{"Freezing drizzle", "Icy drizzle"} : two(R < 0.2 ? "Light drizzle" : "Drizzle");
            case WxState.RAIN: return two(R < LIGHT_RAIN ? "Light rain" : "Rain");
            case WxState.HEAVY: return two(st.showers ? "Heavy showers" : "Heavy rain");
            case WxState.SHOWERS: return two(R < LIGHT_RAIN ? "Light showers" : "Showers");
            case WxState.THUNDER: return two("Thunderstorm");
            case WxState.HAIL: return st.lightning ? new String[]{"Thunderstorm with hail", "Storm, hail"} : new String[]{"Hail showers", "Hail"};
            case WxState.SNOW: return two(st.showers ? "Snow showers" : R < LIGHT_SNOW ? "Light snow" : "Snow");
            case WxState.HEAVYSNOW: return st.showers ? new String[]{"Heavy snow showers", "Snow showers"} : two("Heavy snow");
            case WxState.SLEET:
                if (st.freezing) return R < DRIZZLE_MAX ? new String[]{"Freezing drizzle", "Icy drizzle"} : two("Freezing rain");
                return two("Sleet");
            case WxState.WINDY: return two("Windy");
            default: return two("Overcast");
        }
    }

    private static String[] two(String s) { return new String[]{s, s}; }

    // ---------------------------------------------------------------- the day (strip)
    /** A strip day's verdict, aggregated from the same hourly samples the column draws. */
    public static final class Day {
        public int kind = WxState.OVERCAST, type = Wx.DRY;
        public boolean chance;            // the "chance of" icon: possible, not likely
        public int likely, possible;      // hours with a solid / any bar
        public double maxP, maxR, max = Double.NaN, min = Double.NaN, maxGust, meanCloud;
        public String icon = "overcast";

        public boolean precipIcon() { return !chance && kind >= WxState.DRIZZLE && kind != WxState.FOG && kind != WxState.WINDY; }
        public boolean dryIcon() { return !chance && !precipIcon(); }
    }

    /**
     * @param h      the day's hourly samples (local 00-24, those available)
     * @param rise   sunrise / sunset (hours since epoch; NaN: 8-16 local by the hours' positions)
     */
    public static Day day(Timeline.Sample[] h, double rise, double set) {
        Day d = new Day();
        double sw = 0, ss = 0, maxR = 0;
        int wetN = 0, showersN = 0;
        boolean freezing = false;
        for (Timeline.Sample s : h) {
            if (Double.isNaN(d.max) || s.temp > d.max) d.max = s.temp;
            if (Double.isNaN(d.min) || s.temp < d.min) d.min = s.temp;
            d.maxGust = Math.max(d.maxGust, s.gust);
            d.maxP = Math.max(d.maxP, s.R >= WET_RATE ? s.P : 0);
            if (!bar(s)) continue;
            d.possible++;
            if (solid(s)) d.likely++;
            wetN++;
            if (s.showers) showersN++;
            freezing |= s.freezing;
            sw += s.P * s.R;
            ss += s.P * s.R * s.snow;
            maxR = Math.max(maxR, s.R);
        }
        d.maxR = maxR;
        boolean precip = d.likely >= 1;
        d.chance = !precip && d.possible >= 2;
        if (precip || d.chance) {
            d.type = type(sw > 0 ? ss / sw : 0, freezing);
            if (d.chance) {
                d.kind = d.type == Wx.SNOW ? WxState.SNOW : d.type == Wx.SLEET ? WxState.SLEET : WxState.RAIN;
            } else {
                boolean heavy = false, thunder = false, hail = false;
                for (Timeline.Sample s : h) {
                    if (!solid(s)) continue;
                    heavy |= s.R >= (d.type == Wx.SNOW ? HEAVY_SNOW : HEAVY_RAIN);
                    thunder |= s.thunder;
                    hail |= s.hail;
                }
                if (hail) d.kind = WxState.HAIL;
                else if (thunder) d.kind = WxState.THUNDER;
                else if (d.type == Wx.SNOW) d.kind = heavy ? WxState.HEAVYSNOW : WxState.SNOW;
                else if (d.type == Wx.SLEET) d.kind = WxState.SLEET;
                else if (heavy) d.kind = WxState.HEAVY;
                else if (showersN * 2 >= wetN) d.kind = WxState.SHOWERS;
                else if (maxR < DRIZZLE_MAX) d.kind = WxState.DRIZZLE;
                else d.kind = WxState.RAIN;
            }
        } else {
            int fogH = 0, n = 0;
            double cs = 0;
            for (Timeline.Sample s : h) {
                double c = s.t + 0.5;
                boolean light = Double.isNaN(rise) ? true : c >= rise && c <= set;
                if (!light) continue;
                n++;
                cs += s.cloud;
                if (s.fog) fogH++;
            }
            if (n == 0) for (Timeline.Sample s : h) { n++; cs += s.cloud; }
            d.meanCloud = n > 0 ? cs / n : 100;
            if (fogH >= DAY_FOG_HOURS) d.kind = WxState.FOG;
            else if (d.maxGust >= WINDY_GUST) d.kind = WxState.WINDY;
            else d.kind = cloudKind(d.meanCloud);
        }
        d.icon = icon(d.kind, d.chance, d.type);
        return d;
    }

    /** Icon file (assets/icons/<key>.svg) for a state. */
    public static String icon(int kind, boolean chance, int type) {
        if (chance) return type == Wx.SNOW ? "chance-snow" : type == Wx.SLEET ? "chance-sleet" : "chance-rain";
        switch (kind) {
            case WxState.CLEAR: return "clear";
            case WxState.PARTLY: return "partly";
            case WxState.FOG: return "fog";
            case WxState.WINDY: return "wind";
            case WxState.DRIZZLE: return "drizzle";
            case WxState.RAIN: case WxState.HEAVY: return "rain";
            case WxState.SHOWERS: return "showers";
            case WxState.THUNDER: return "thunder";
            case WxState.HAIL: return "hail";
            case WxState.SNOW: return "snow";
            case WxState.HEAVYSNOW: return "heavysnow";
            case WxState.SLEET: return "sleet";
            default: return "overcast";
        }
    }
}
