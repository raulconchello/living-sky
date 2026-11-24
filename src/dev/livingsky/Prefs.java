package dev.livingsky;

import android.content.Context;
import android.content.SharedPreferences;

/** Debug overrides, calibration and status, in one SharedPreferences file. */
final class Prefs {
    private final SharedPreferences sp;

    Prefs(Context c) { sp = c.getSharedPreferences("living_sky", Context.MODE_PRIVATE); }

    // ---- overrides (debug screen)
    /** "" = live location, "auto" = automatic skyline at autoLat/autoLon, or a hand-drawn place id. */
    String place() { return sp.getString("place", ""); }
    double autoLat() { return Double.longBitsToDouble(sp.getLong("autoLat", Double.doubleToLongBits(48.3984))); }
    double autoLon() { return Double.longBitsToDouble(sp.getLong("autoLon", Double.doubleToLongBits(9.9916))); }
    String autoName() { return sp.getString("autoName", ""); }
    /** 0 auto, 1 day, 2 night */
    int mode() { return sp.getInt("mode", 0); }
    /** Render the approved sketch's mock data (Ulm, Sun 22 Nov, 11:39 / 22:10) instead of live data. */
    boolean mock() { return sp.getBoolean("mock", false); }
    /** Synthetic weather for checking sky types: "", clear, partly, overcast, rain, snow, sleet, fog, thunder. */
    String scenario() { return sp.getString("scenario", ""); }
    /** Render as if the local time were this (minutes after midnight), -1 = real time. */
    int atMinutes() { return sp.getInt("atMinutes", -1); }

    /** Debug: also write preview.png / column.png to the external files dir after each render. */
    boolean dump() { return sp.getBoolean("dump", false); }

    /** The last render used the daylight look (the minute clock repaints with the matching text shadows). */
    boolean dayText() { return sp.getBoolean("dayText", false); }

    /** Clock text frozen by reference / time-travel mode ("" = live). */
    String fixedClock() { return sp.getString("fixedClock", ""); }

    /** Timeline column in values mode (after a tap) until colUntil (wall clock ms). */
    boolean colValues() { return sp.getBoolean("colValues", false) && System.currentTimeMillis() < sp.getLong("colUntil", 0); }
    /** Debug: alarm override ("" real, "none", "Mon 08:45", "08:45"). */
    String alarmOverride() { return sp.getString("alarm", ""); }
    /** Debug: column scroll offset (dp) for the preview dumps. */
    float colScrollDp() { return sp.getFloat("colScroll", 0f); }

    // ---- calibration (px)
    int cardDx() { return sp.getInt("cardDx", 0); }
    int cardDy() { return sp.getInt("cardDy", 0); }
    float clockDx() { return sp.getFloat("clockDx", 0f); }
    float clockDy() { return sp.getFloat("clockDy", 0f); }

    // ---- status
    long lastRender() { return sp.getLong("lastRender", 0); }
    long lastFetch() { return sp.getLong("lastFetch", 0); }
    long lastFetchAttempt() { return sp.getLong("lastFetchAttempt", 0); }
    long lastEnsAttempt() { return sp.getLong("lastEnsAttempt", 0); }
    /** Debug: log the timeline per hour (source, P, R, snow, cloud, class) on each render. */
    boolean rainDebug() { return sp.getBoolean("rainDebug", false); }
    /** Debug: false = the ensemble from now on (no radar / 15-min model window). */
    boolean nowcast() { return !sp.getBoolean("nowcastOff", false); }
    /** Debug: the moon's age in days, or NaN for the real moon. */
    /** Debug: the previous (cartoon) cloud style, for before/after renders. */
    boolean cloudsOld() { return sp.getBoolean("cloudsOld", false); }
    /** Debug: the card text's shadow style ("" default halo, "old" previous look, "mask" for measurements). */
    String textFx() { return sp.getString("textFx", ""); }
    /** Debug: the day strip ("" today + 4 days, "old" the previous next 4 days). */
    String strip() { return sp.getString("strip", ""); }
    /** Debug: the previous today mark in the strip (the bold name), for before/after renders. */
    boolean todayMarkOld() { return "old".equals(sp.getString("todayMark", "")); }
    double moonAge() { return sp.contains("moonAge") ? sp.getFloat("moonAge", 0) : Double.NaN; }
    String lastError() { return sp.getString("lastError", ""); }
    long lastErrorAt() { return sp.getLong("lastErrorAt", 0); }
    String lastInfo() { return sp.getString("lastInfo", ""); }
    long renderMs() { return sp.getLong("renderMs", 0); }

    // location cache
    boolean hasLoc() { return sp.contains("locLat"); }
    double locLat() { return Double.longBitsToDouble(sp.getLong("locLat", 0)); }
    double locLon() { return Double.longBitsToDouble(sp.getLong("locLon", 0)); }
    long locTime() { return sp.getLong("locTime", 0); }
    String placeName() { return sp.getString("placeName", ""); }
    double nameLat() { return Double.longBitsToDouble(sp.getLong("nameLat", 0)); }
    double nameLon() { return Double.longBitsToDouble(sp.getLong("nameLon", 0)); }

    SharedPreferences.Editor edit() { return sp.edit(); }

    static SharedPreferences.Editor putDouble(SharedPreferences.Editor e, String k, double v) {
        return e.putLong(k, Double.doubleToRawLongBits(v));
    }

    void setError(String err) {
        sp.edit().putString("lastError", err == null ? "" : err).putLong("lastErrorAt", System.currentTimeMillis()).apply();
    }
}
