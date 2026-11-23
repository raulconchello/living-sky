package dev.livingsky.render;

/**
 * Weather data in the shape the renderer needs (parsed from Open-Meteo, or built from the sketch's mock data).
 * All times are hours since the Unix epoch (UTC), as doubles.
 */
public final class Wx {
    public static final int DRY = 0, RAIN = 1, SLEET = 2, SNOW = 3;

    public double lat, lon;
    public String tz = "UTC";          // IANA zone of the forecast location
    public long fetchedAtMs;

    // current
    public double temp, feels, rh, uv, cloud; // cloud 0..100
    public double wind, gust;                  // km/h
    public int code;
    public boolean isDay = true;

    // hourly
    public double[] hT = new double[0], hTemp = new double[0], hCloud = new double[0], hPP = new double[0], hUV = new double[0];
    public double[] hGust = new double[0];
    public double[] hRH = new double[0], hPrecip = new double[0], hSnow = new double[0]; // RH %, mm/h, cm/h (fallbacks)
    public int[] hCode = new int[0];

    // minutely_15 (slot start)
    public double[] mT = new double[0], mPrecip = new double[0], mRain = new double[0], mSnow = new double[0];
    public int[] mCode = new int[0];

    // daily (start of local day)
    public double[] dT = new double[0], dMax = new double[0], dMin = new double[0], dRise = new double[0], dSet = new double[0];
    public double[] dGust = new double[0];
    public int[] dCode = new int[0];

    public boolean hasHourly() { return hT.length >= 2; }

    /** Ensemble precipitation for the timeline column (bars + "Rain %"), or null: deterministic data then. */
    public Ensemble ens;

    private boolean ensAt(double t) { return ens != null && ens.covers(t); }

    // ---- the column's precipitation: deterministic for the next hours, ensemble beyond
    /** Up to now + NOWCAST_H the main (deterministic) model rules the column's precipitation. */
    public static final double NOWCAST_H = 2;
    /** ... then it hands over to the ensemble with a smoothstep over this many hours (now + 2 h -> now + 3 h). */
    public static final double NOWCAST_BLEND_H = 1;
    /** Where the ensemble drives the column, a probability below this (%) draws no bar, texture or darkening. */
    public static final double ENS_MIN_PROB = 10;
    /** The column's "now" (set by the renderer); NaN = no deterministic window. */
    public double colNow = Double.NaN;
    /** Debug (--es nowcast off): the old column (ensemble from now on, no probability cutoff), for before/after. */
    public boolean nowcast = true;

    /** Weight of the deterministic run in the column at t: 1 up to now + 2 h, smoothstep to 0 by now + 3 h. */
    public double detWeight(double t) {
        if (!ensAt(t)) return 1;
        if (!nowcast || Double.isNaN(colNow)) return 0;
        double x = (t - colNow - NOWCAST_H) / NOWCAST_BLEND_H;
        if (x <= 0) return 1;
        if (x >= 1) return 0;
        return 1 - x * x * (3 - 2 * x);
    }

    /** "det", "blend" or "ens": which data drives the column at t (debug). */
    public String colSource(double t) {
        double w = detWeight(t);
        return w >= 1 ? "det" : w <= 0 ? "ens" : "blend";
    }

    /** Ensemble mean rate at t, or 0 where the members' probability is below ENS_MIN_PROB. */
    private double ensRate(double t) {
        if (nowcast) { double p = ens.probAt(t); if (p >= 0 && p < ENS_MIN_PROB) return 0; }
        return ens.rate(t);
    }

    /** Column bars and band texture: deterministic 15-min / hourly rate, blended into the ensemble mean. */
    public double colRate(double t) {
        double w = detWeight(t);
        if (w >= 1) return mmh(t);
        if (w <= 0) return ensRate(t);
        return w * mmh(t) + (1 - w) * ensRate(t);
    }

    /** Precipitation type at t from whichever source contributes more of the column's rate there. */
    public int colType(double t) {
        double w = detWeight(t);
        if (w >= 1) return ptype(t);
        double rd = w * mmh(t), re = (1 - w) * ensRate(t);
        if (rd <= 0 && re <= 0) return DRY;
        if (rd >= re) return ptype(t) != DRY ? ptype(t) : RAIN;
        int ty = ens.type(t);
        if (ty == DRY) ty = ptype(t) != DRY ? ptype(t) : RAIN;
        return ty;
    }

    /** Column "Rain %" for the hour that starts at hourStart: ensemble probability, else precipitation_probability. */
    public double colProb(double hourStart) {
        if (ensAt(hourStart + 0.5)) { double p = ens.probForHour(hourStart); if (p >= 0) return p; }
        int i = hourIndex(hourStart + 1.01); // Open-Meteo's probability at T is for the hour before T
        return i >= 0 ? hPP[i] : 0;
    }

    /**
     * Cloud cover (0..100) for the column's sky band, with the rain's handover: the deterministic hourly for the next
     * 2 h (so the column's "now" matches the card, which reads the same run), blended into the ensemble members' mean
     * by now + 3 h. The members' mean rarely reaches 0 or 100 (a split between clear and low cloud averages to broken
     * cloud), so it only drives the hours where the main run is less certain anyway.
     */
    public double colCloud(double t) {
        double e = ens != null ? ens.cloudAt(t) : -1;
        if (e < 0) return hourly(hCloud, t);
        if (!hasHourly()) return e;
        double w = detWeight(t);
        return w >= 1 ? hourly(hCloud, t) : w <= 0 ? e : w * hourly(hCloud, t) + (1 - w) * e;
    }

    /** Rain probability (0..100) around time t, for the column's sky band ("rain is coming" dimming): same blend. */
    public double colProbAt(double t) {
        if (ensAt(t)) {
            double p = ens.probAt(t);
            if (p >= 0) {
                if (nowcast && p < ENS_MIN_PROB) p = 0;
                double w = detWeight(t);
                return w * hourly(hPP, t) + (1 - w) * p;
            }
        }
        return hourly(hPP, t);
    }

    /** Linear interpolation of an hourly series at t (clamped), like sky8.js hourly(). */
    public double hourly(double[] v, double t) {
        int n = hT.length;
        if (n == 0) return 0;
        if (n == 1) return v[0];
        int i = (int) Math.floor(t - hT[0]);
        i = Math.max(0, Math.min(n - 2, i));
        double f = Math.max(0, Math.min(1, t - hT[i]));
        return v[i] + (v[i + 1] - v[i]) * f;
    }

    public int hourlyCode(double t) {
        int n = hT.length;
        if (n == 0) return code;
        int i = (int) Math.round(t - hT[0]);
        return hCode[Math.max(0, Math.min(n - 1, i))];
    }

    /** 0..1: fog hours (WMO 45/48), linearly blended between hours so the band has no hard edge. */
    public double fogAt(double t) {
        int n = hT.length;
        if (n == 0) return isFog(code) ? 1 : 0;
        if (n == 1) return isFog(hCode[0]) ? 1 : 0;
        int i = Math.max(0, Math.min(n - 2, (int) Math.floor(t - hT[0])));
        double f = Math.max(0, Math.min(1, t - hT[i]));
        double a = isFog(hCode[i]) ? 1 : 0, b = isFog(hCode[i + 1]) ? 1 : 0;
        return a + (b - a) * f;
    }

    /** 0..1: hours whose code is in [lo, hi] (or equals also), blended linearly between hours. */
    public double codeAt(double t, int lo, int hi, int also) {
        int n = hT.length;
        if (n < 2) return 0;
        int i = Math.max(0, Math.min(n - 2, (int) Math.floor(t - hT[0])));
        double f = Math.max(0, Math.min(1, t - hT[i]));
        int ca = hCode[i], cb = hCode[i + 1];
        double a = (ca >= lo && ca <= hi) || ca == also ? 1 : 0, b = (cb >= lo && cb <= hi) || cb == also ? 1 : 0;
        return a + (b - a) * f;
    }

    private int slot(double t) {
        int n = mT.length;
        int i = (int) Math.floor((t - mT[0]) * 4 + 1e-6);
        return Math.max(0, Math.min(n - 1, i));
    }

    /** Precipitation rate in mm/h for the 15-min slot containing t (falls back to hourly when no minutely data). */
    public double mmh(double t) {
        if (mT.length > 0 && t >= mT[0] - 0.25 && t < mT[mT.length - 1] + 0.25) return mPrecip[slot(t)] * 4;
        int h = hourIndex(t); // beyond the 15-min data (old forecast): hourly precipitation
        return h >= 0 && h < hPrecip.length ? hPrecip[h] : 0;
    }

    /** Hourly index for the hour containing t, or -1 outside the data. */
    public int hourIndex(double t) {
        if (hT.length == 0 || t < hT[0] || t >= hT[hT.length - 1] + 1) return -1;
        return Math.max(0, Math.min(hT.length - 1, (int) Math.floor(t - hT[0])));
    }

    /** Relative humidity at the hour containing t, or -1 when unknown. */
    public double rhAt(double t) {
        int h = hourIndex(t);
        return h >= 0 && h < hRH.length ? hRH[h] : -1;
    }

    public int ptype(double t) {
        if (mT.length == 0 || t < mT[0] - 0.25 || t >= mT[mT.length - 1] + 0.25) {
            int h = hourIndex(t);
            if (h < 0 || h >= hPrecip.length || hPrecip[h] <= 0) return DRY;
            double sn = h < hSnow.length ? hSnow[h] : 0;
            int c = hCode[h];
            if (sn > 0 && sn < hPrecip[h] * 0.55) return SLEET; // snowfall cm ~ 0.7 x water mm when it is all snow
            if (sn > 0 || (c >= 71 && c <= 77) || c == 85 || c == 86) return SNOW;
            if (c == 56 || c == 57 || c == 66 || c == 67) return SLEET;
            return RAIN;
        }
        int i = slot(t);
        if (mPrecip[i] <= 0) return DRY;
        double rain = mRain[i], snow = mSnow[i];
        int c = mCode[i];
        if (snow > 0 && rain > 0) return SLEET;
        if (snow > 0) return SNOW;
        if ((c >= 71 && c <= 77) || c == 85 || c == 86) return SNOW;
        if (c == 56 || c == 57 || c == 66 || c == 67) return SLEET;
        return RAIN;
    }

    /** Index of the daily row whose local day contains t, or -1. */
    public int dayIndex(double t) {
        for (int i = dT.length - 1; i >= 0; i--) if (t >= dT[i] - 1e-6) return i;
        return -1;
    }

    public static boolean isFog(int code) { return code == 45 || code == 48; }
    public static boolean isHail(int code) { return code == 96 || code == 99; }
    public static boolean isThunder(int code) { return code >= 95; }
}
