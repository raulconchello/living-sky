package dev.livingsky.render;

/**
 * A weather state, as {@link WxClass#classify} returns it: the card's state right now (sky shader parameters, the
 * foreground weather pass, the condition word), a column hour, or (WINDY) a strip day.
 */
public final class WxState {
    public static final int CLEAR = 0, PARTLY = 1, OVERCAST = 2, FOG = 3, DRIZZLE = 4, RAIN = 5, HEAVY = 6, SHOWERS = 7,
            THUNDER = 8, HAIL = 9, SNOW = 10, HEAVYSNOW = 11, SLEET = 12, WINDY = 13;
    public static final String[] NAMES = {"clear", "partly", "overcast", "fog", "drizzle", "rain", "heavy", "showers",
            "thunder", "hail", "snow", "heavysnow", "sleet", "windy"};

    public int kind = OVERCAST;
    public int type = Wx.DRY;      // precipitation type (Wx.DRY / RAIN / SLEET / SNOW)
    public double intensity = 0.5; // 0..1 within the state
    public double gust;            // km/h
    public boolean windy;          // gusts >= WxClass.WINDY_GUST
    public boolean lightning;      // thunder (also thunder with hail)
    public boolean chance;         // possible, not likely (an hour's faint bars)
    public boolean freezing, showers;
    public double cover = 1;       // 0..1
    public double cloud = 100, P, R, temp;

    public boolean precip() { return kind >= DRIZZLE && kind != FOG && kind != WINDY; }
    public boolean rainy() { return kind == DRIZZLE || kind == RAIN || kind == HEAVY || kind == SHOWERS || kind == THUNDER || kind == SLEET; }
    public boolean snowy() { return kind == SNOW || kind == HEAVYSNOW; }
}
