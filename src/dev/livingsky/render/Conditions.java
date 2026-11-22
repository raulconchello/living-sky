package dev.livingsky.render;

/**
 * The condition word on the top line ("Weststadt, Ulm · Rain"), from design/layout/notes.md (WMO -> English, with a
 * short fallback). Codes 0 and 1 use the night words after sunset; the gust flag appends ", windy" ("Windy" alone).
 */
public final class Conditions {
    private Conditions() {}

    /** @return {full, short} */
    public static String[] of(int code, boolean afterSunset) {
        switch (code) {
            case 0: return two(afterSunset ? "Clear" : "Sunny");
            case 1: return two(afterSunset ? "Mostly clear" : "Mostly sunny");
            case 2: return two("Partly cloudy");
            case 3: return two("Overcast");
            case 45: return two("Fog");
            case 48: return two("Freezing fog");
            case 51: return two("Light drizzle");
            case 53: return two("Drizzle");
            case 55: return two("Heavy drizzle");
            case 56: case 57: return new String[]{"Freezing drizzle", "Icy drizzle"};
            case 61: return two("Light rain");
            case 63: return two("Rain");
            case 65: return two("Heavy rain");
            case 66: case 67: return two("Freezing rain");
            case 71: return two("Light snow");
            case 73: return two("Snow");
            case 75: return two("Heavy snow");
            case 77: return two("Snow grains");
            case 80: return two("Light showers");
            case 81: return two("Showers");
            case 82: return two("Heavy showers");
            case 85: return two("Snow showers");
            case 86: return new String[]{"Heavy snow showers", "Snow showers"};
            case 87: case 88: case 89: case 90: return new String[]{"Hail showers", "Hail"};
            case 95: return two("Thunderstorm");
            case 96: case 99: return new String[]{"Thunderstorm with hail", "Storm, hail"};
            default: return two("Overcast");
        }
    }

    private static String[] two(String s) { return new String[]{s, s}; }

    /**
     * The WMO code that matches what the card shows (WxState may refine a dry code by the live precipitation or the
     * cloud cover), so the word and the sky always agree.
     */
    public static int effectiveCode(int code, WxState st) {
        boolean dryCode = code <= 3;
        switch (st.kind) {
            case WxState.CLEAR: return code == 0 || code == 1 ? code : 1;
            case WxState.PARTLY: return code == 2 ? 2 : (dryCode ? 2 : code);
            case WxState.OVERCAST: return dryCode ? 3 : code;
            case WxState.DRIZZLE: return dryCode ? 53 : code;
            case WxState.RAIN: return dryCode ? 63 : code;
            case WxState.HEAVY: return dryCode || code == 61 || code == 63 ? 65 : code;
            case WxState.SNOW: return dryCode ? 73 : code;
            case WxState.HEAVYSNOW: return dryCode || code == 71 || code == 73 ? 75 : code;
            case WxState.SLEET: return dryCode ? 66 : code;
            default: return code;
        }
    }
}
