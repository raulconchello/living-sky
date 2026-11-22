package dev.livingsky.render;

import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * The approved sketch's mock scenario (Ulm, Sun 22 Nov 2026, 11:39 by day / 22:10 at night, rainy evening), with the
 * Open-Meteo hourly + minutely_15 numbers copied from sky8.js. Used by the debug screen's "reference" mode to compare
 * the phone against design/skylines/preview/ulm_{day,night}.png pixel for pixel.
 */
public final class MockData {
    private MockData() {}

    public static final double LAT = 48.395, LON = 9.9645;

    /** Hours since epoch of Sat 21 Nov 2026 00:00 Europe/Berlin (the sketch's time origin). */
    public static double t0() {
        return ZonedDateTime.of(2026, 11, 21, 0, 0, 0, 0, ZoneId.of("Europe/Berlin")).toEpochSecond() / 3600.0;
    }

    public static double now(boolean night) { return t0() + 24 + (night ? 22 + 10 / 60.0 : 11 + 39 / 60.0); }

    // hour, temp, code, precip probability %, precip mm, cloud %
    private static final double[][] HOURLY = {
            {34, 3.1, 3, 0, 0.0, 100}, {35, 3.6, 3, 0, 0.0, 100}, {36, 4.1, 3, 3, 0.0, 100}, {37, 4.4, 3, 15, 0.0, 100},
            {38, 4.6, 61, 30, 0.2, 100}, {39, 4.5, 61, 48, 0.4, 100}, {40, 4.3, 61, 60, 0.8, 100}, {41, 4.0, 61, 78, 0.9, 100},
            {42, 3.4, 61, 85, 1.13, 100}, {43, 3.1, 61, 85, 0.85, 100}, {44, 2.6, 61, 80, 0.75, 100}, {45, 2.0, 61, 73, 0.8, 100},
            {46, 1.8, 61, 73, 0.6, 100}, {47, 1.4, 61, 73, 0.6, 100}, {48, 1.1, 61, 73, 0.8, 100}, {49, 1.0, 71, 60, 0.7, 100},
            {50, 1.3, 61, 55, 0.4, 100}, {51, 1.6, 61, 53, 0.3, 100}, {52, 2.0, 3, 50, 0.0, 100}, {53, 2.0, 3, 35, 0.0, 100},
            {54, 1.9, 3, 28, 0.0, 100}, {55, 1.4, 3, 20, 0.0, 100}, {56, 0.8, 2, 15, 0.0, 74}, {57, 0.8, 1, 13, 0.0, 16},
            {58, 1.3, 2, 10, 0.0, 69}, {59, 1.8, 3, 6, 0.0, 72}, {60, 2.2, 3, 3, 0.0, 100}, {61, 1.8, 3, 0, 0.0, 73},
            {62, 1.8, 2, 0, 0.0, 55}};
    // slot start (h), precip mm / 15 min, snowfall cm, code
    private static final double[][] MIN15 = {
            {34, 0, 0, 3}, {34.25, 0, 0, 3}, {34.5, 0, 0, 3}, {34.75, 0, 0, 3}, {35, 0, 0, 3}, {35.25, 0, 0, 3}, {35.5, 0, 0, 3}, {35.75, 0, 0, 3},
            {36, 0, 0, 3}, {36.25, 0, 0, 3}, {36.5, 0, 0, 3}, {36.75, 0, 0, 3}, {37, 0, 0, 3}, {37.25, 0, 0, 61}, {37.5, 0, 0, 61}, {37.75, 0.1, 0, 61},
            {38, 0.1, 0, 61}, {38.25, 0.1, 0, 61}, {38.5, 0.1, 0, 61}, {38.75, 0.1, 0, 61}, {39, 0.1, 0, 61}, {39.25, 0.2, 0, 61}, {39.5, 0.2, 0, 61}, {39.75, 0.2, 0, 61},
            {40, 0.2, 0, 61}, {40.25, 0.2, 0, 61}, {40.5, 0.2, 0, 61}, {40.75, 0.2, 0, 61}, {41, 0.3, 0, 61}, {41.25, 0.3, 0, 61}, {41.5, 0.3, 0, 61}, {41.75, 0.3, 0, 61},
            {42, 0.2, 0, 61}, {42.25, 0.2, 0, 61}, {42.5, 0.2, 0, 61}, {42.75, 0.2, 0, 61}, {43, 0.2, 0, 61}, {43.25, 0.2, 0, 61}, {43.5, 0.2, 0, 61}, {43.75, 0.15, 0, 61},
            {44, 0.18, 0, 61}, {44.25, 0.2, 0, 61}, {44.5, 0.2, 0, 61}, {44.75, 0.2, 0, 61}, {45, 0.2, 0, 61}, {45.25, 0.2, 0, 61}, {45.5, 0.2, 0, 61}, {45.75, 0.1, 0, 61},
            {46, 0.1, 0, 61}, {46.25, 0.1, 0, 61}, {46.5, 0.1, 0, 61}, {46.75, 0.2, 0, 61}, {47, 0.2, 0, 61}, {47.25, 0.2, 0.07, 61}, {47.5, 0.2, 0.07, 61}, {47.75, 0.2, 0.07, 61},
            {48, 0.2, 0.07, 61}, {48.25, 0.2, 0.07, 71}, {48.5, 0.2, 0.07, 71}, {48.75, 0.2, 0.07, 71}, {49, 0.1, 0.07, 71}, {49.25, 0.1, 0, 61}, {49.5, 0.1, 0, 61}, {49.75, 0.1, 0, 61},
            {50, 0.1, 0, 61}, {50.25, 0.1, 0, 61}, {50.5, 0.1, 0, 61}, {50.75, 0.1, 0, 61}, {51, 0, 0, 61}, {51.25, 0, 0, 3}};

    public static Wx build(boolean night) { return build(night, false); }

    /**
     * @param sunny the sky9.js "sunny" variant (make_sunny.py): a clear, dry November day, cloud 12 % now / 8 % hourly,
     *              6 deg, feels 4, UV 1, RH 68 %, days Mon clear 7/-1, Tue partly 6/0, Wed clear 5/-2, Thu overcast 4/-1.
     */
    public static Wx build(boolean night, boolean sunny) {
        double t0 = t0();
        Wx w = new Wx();
        w.lat = LAT; w.lon = LON; w.tz = "Europe/Berlin";
        int n = HOURLY.length;
        w.hT = new double[n]; w.hTemp = new double[n]; w.hCloud = new double[n]; w.hPP = new double[n]; w.hUV = new double[n]; w.hCode = new int[n];
        for (int i = 0; i < n; i++) {
            double[] r = HOURLY[i];
            w.hT[i] = t0 + r[0]; w.hTemp[i] = r[1]; w.hCode[i] = (int) r[2]; w.hPP[i] = r[3]; w.hCloud[i] = r[5];
        }
        int m = MIN15.length;
        w.mT = new double[m]; w.mPrecip = new double[m]; w.mRain = new double[m]; w.mSnow = new double[m]; w.mCode = new int[m];
        for (int i = 0; i < m; i++) {
            double[] r = MIN15[i];
            w.mT[i] = t0 + r[0]; w.mPrecip[i] = r[1]; w.mSnow[i] = r[2]; w.mCode[i] = (int) r[3];
            // sky8.js: code 71-77 -> snow, snowfall > 0 -> sleet. Expressed through the live rule (rain + snow = sleet).
            w.mRain[i] = (w.mCode[i] >= 71 && w.mCode[i] <= 77) ? 0 : r[1];
        }
        // daily: Sun 22 (today) .. Thu 26
        int[] codes = {61, 66, 45, 3, 71};
        double[] mx = {4.8, 2.2, 0.2, -1.0, -1.3}, mn = {1.2, -2.2, -4.2, -4.2, -4.0};
        double[][] sun = {{7 + 37 / 60.0, 16 + 34 / 60.0}, {7 + 39 / 60.0, 16 + 33 / 60.0}, {7 + 40 / 60.0, 16 + 32 / 60.0},
                {7 + 42 / 60.0, 16 + 31 / 60.0}, {7 + 43 / 60.0, 16 + 30 / 60.0}};
        w.dT = new double[5]; w.dMax = mx; w.dMin = mn; w.dCode = codes; w.dRise = new double[5]; w.dSet = new double[5];
        for (int i = 0; i < 5; i++) {
            w.dT[i] = t0 + 24 * (i + 1);
            w.dRise[i] = w.dT[i] + sun[i][0];
            w.dSet[i] = w.dT[i] + sun[i][1];
        }
        if (!night) { w.temp = 3.8; w.feels = 1; w.uv = 1; w.rh = 93; }
        else { w.temp = 2; w.feels = -1; w.uv = 0; w.rh = 95; }
        w.code = 3; w.cloud = 100; w.isDay = !night;
        w.fetchedAtMs = (long) (now(night) * 3600000L);
        if (sunny) {
            for (int i = 0; i < n; i++) {
                int hh = (int) Math.round(HOURLY[i][0]) % 24;
                w.hTemp[i] += 2.2;
                w.hCode[i] = hh >= 8 && hh <= 16 ? (hh % 5 == 0 ? 1 : 0) : 0;
                w.hPP[i] = 0;
                w.hCloud[i] = 8;
            }
            for (int i = 0; i < m; i++) { w.mPrecip[i] = 0; w.mRain[i] = 0; w.mSnow[i] = 0; w.mCode[i] = 0; }
            w.dCode = new int[]{0, 0, 2, 0, 3};
            w.dMax = new double[]{8, 7, 6, 5, 4};
            w.dMin = new double[]{1, -1, 0, -2, -1};
            w.temp = 6; w.feels = 4; w.uv = 1; w.rh = 68; w.code = 0; w.cloud = 12;
        }
        return w;
    }
}
