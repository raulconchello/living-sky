package dev.livingsky.render;

/**
 * Hourly precipitation from an ensemble (Open-Meteo ensemble API, e.g. icon_seamless with 40 members): per hour the
 * probability (% of members with >= 0.1 mm) and the mean amount, so the column's rain bars and its "Rain %" values
 * always come from the same members. Open-Meteo's hourly precipitation at time T is the sum over the preceding hour
 * (T-1 .. T): {@link #t} holds those end times; the hour that starts at h is entry h + 1.
 */
public final class Ensemble {
    public double[] t = new double[0];     // end of each hour (hours since epoch)
    public double[] prob = new double[0];  // % of members >= 0.1 mm
    public double[] mean = new double[0];  // mean mm over the members (= mm/h)
    public double[] snow = new double[0];  // mean snowfall cm
    public double[] cloud = new double[0]; // mean cloud cover % (an instant value at t, not an hourly sum); -1 unknown
    public String model = "";
    public int members;
    public long fetchedAtMs;
    public double lat, lon;

    public boolean covers(double time) { return t.length > 1 && time >= t[0] - 1 && time < t[t.length - 1]; }

    /** Index of the hour interval (end time) containing time, or -1. */
    private int idx(double time) {
        if (t.length == 0) return -1;
        int i = (int) Math.ceil(time - t[0] + 1e-9);   // interval (t[i]-1, t[i]] contains time
        return i >= 0 && i < t.length ? i : -1;
    }

    /** Mean rate (mm/h) at time, linear between hour-interval centres (t - 0.5): smooth over the 4 quarter slots. */
    public double rate(double time) {
        int n = t.length;
        if (n == 0) return 0;
        double x = time - (t[0] - 0.5);           // 0 at the first interval's centre
        int i = (int) Math.floor(x);
        if (i < 0) return mean[0];
        if (i >= n - 1) return mean[n - 1];
        double f = x - i;
        return mean[i] + (mean[i + 1] - mean[i]) * f;
    }

    /** Precipitation type from the mean snowfall (cm ~ 0.7 x water mm when it is all snow). */
    public int type(double time) {
        int i = idx(time);
        if (i < 0 || mean[i] <= 0) return Wx.DRY;
        double frac = snow[i] / 0.7 / Math.max(1e-6, mean[i]);
        return frac > 0.8 ? Wx.SNOW : frac > 0.2 ? Wx.SLEET : Wx.RAIN;
    }

    /** Probability (%) for the hour that starts at hourStart (its interval ends at hourStart + 1), or -1. */
    public double probForHour(double hourStart) {
        int i = idx(hourStart + 0.5);
        return i < 0 ? -1 : prob[i];
    }

    /** Members' mean cloud cover (%) at time, linear between the hourly instants, or -1 when unknown. */
    public double cloudAt(double time) {
        int n = cloud.length;
        if (n < 2 || n != t.length || time < t[0] || time > t[n - 1]) return -1;
        int i = Math.min(n - 2, (int) Math.floor(time - t[0]));
        double a = cloud[i], b = cloud[i + 1];
        if (a < 0 || b < 0) return -1;
        return a + (b - a) * Math.max(0, Math.min(1, time - t[i]));
    }

    /** Probability (%) of the interval containing time (for the sky band's rain darkening), or -1. */
    public double probAt(double time) {
        int i = idx(time);
        return i < 0 ? -1 : prob[i];
    }
}
