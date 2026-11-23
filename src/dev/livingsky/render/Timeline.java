package dev.livingsky.render;

import static dev.livingsky.render.Noise.sm;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * One timeline per render, read by everything on screen: the header word, the card's weather, the column (bars,
 * band, labels, "Rain %") and the day strip. Every sample has the same fields, so the parts cannot disagree.
 *
 * Sources by horizon:
 *   now       fused (see {@link #fuseNow}): radar, cross-checked with the nearest station (Germany), else the
 *             15-min model; temperature, cloud, wind, UV, RH from the model's current values;
 *   0-2 h     radar nowcast (Germany: weight 1 to +1 h, 0 by +2 h), then the 15-min model (P is 0 or 1);
 *   2-3 h     smoothstep from the model to the ensemble;
 *   3-120 h   ensemble: P = share of wet members, R = their mean rate, snow share; cloud raised where P is high.
 * Temperature, cloud, RH, UV and gusts are the deterministic model (best_match) throughout.
 */
public final class Timeline {
    public static final class Sample {
        public double t;                               // start (hours since epoch); now: the current time
        public double temp, feels, rh = -1, uv, gust, cloud = 100;   // deterministic base (cloud 0..100)
        public double P, R, snow;                      // P 0..1 of >= 0.1 mm; R mm/h if it precipitates; snow share
        public boolean fog, thunder, hail, freezing, showers;
        public String src = "det";                     // radar | obs | det | ens | blend (debug)
        public int code;                               // deterministic WMO code (flags are gated from it)
        double ensW;                                   // weight of the ensemble in P (the cloud raise follows it)

        public Sample copy() {
            Sample s = new Sample();
            s.t = t; s.temp = temp; s.feels = feels; s.rh = rh; s.uv = uv; s.gust = gust; s.cloud = cloud;
            s.P = P; s.R = R; s.snow = snow; s.fog = fog; s.thunder = thunder; s.hail = hail; s.freezing = freezing;
            s.showers = showers; s.src = src; s.code = code; s.ensW = ensW;
            return s;
        }

        public boolean wet() { return P > 0 && R >= WxClass.WET_RATE; }
    }

    /** The 15-min slots cover the column (72 h) and a little more. */
    public static final double SLOT_H = 74;

    public Wx wx;
    public double nowT;
    public Sample now = new Sample();
    public double slot0;                               // start of slot[0] (the quarter containing now)
    public Sample[] slot = new Sample[0];
    public Sample[] hour = new Sample[0];              // hour[0] contains now; the forecast's hourly grid
    public WxClass.Day[] day = new WxClass.Day[0];     // indexed like wx.dT (null: no hours that day)
    public boolean radar;                              // German radar drives now and 0-2 h
    public String nowNote = "model";                   // where "now" came from (info line)
    public String notes = "";
    public String key = "";                            // identity of the inputs (the column's band cache)

    private Ensemble ens;
    private NowObs obs;
    private boolean nowcast;

    /**
     * @param ens      the ensemble (null: the deterministic run everywhere)
     * @param obs      radar + station (Germany), or null
     * @param nowcast  false (debug): the ensemble from now on, no model / radar window
     */
    public static Timeline build(Wx w, Ensemble ens, NowObs obs, double now, boolean nowcast) {
        Timeline tl = new Timeline();
        tl.wx = w;
        tl.nowT = now;
        tl.ens = ens != null && ens.covers(now) ? ens : null;
        tl.obs = obs;
        tl.nowcast = nowcast;
        tl.radar = obs != null && obs.radarFresh(ms(now));
        tl.key = w.fetchedAtMs + "|" + (tl.ens != null ? tl.ens.fetchedAtMs : 0) + "|" + (obs != null ? obs.fetchedAtMs : 0) + "|" + nowcast;
        tl.fuseNow();
        tl.hours();
        tl.slots();
        tl.precipHours();
        tl.flagsAndCloud();
        tl.inherit();
        tl.days();
        return tl;
    }

    static long ms(double h) { return Math.round(h * 3600000.0); }

    // ------------------------------------------------------------------ the deterministic 15-min model
    /** {P, R, snow share, code} of the model at tc: the 15-min slot when covered, else the hour that contains tc. */
    double[] detAt(double tc) {
        Wx w = wx;
        int n = w.mT.length;
        if (n > 0 && tc >= w.mT[0] && tc < w.mT[n - 1] + 0.25) {
            int i = Math.max(0, Math.min(n - 1, (int) Math.floor((tc - w.mT[0]) * 4 + 1e-6)));
            return det(w.mPrecip[i] * 4, w.mSnow[i] / 0.7 * 4, w.mCode[i]);
        }
        int j = w.hasHourly() ? w.at(w.hT[0] + Math.floor(tc - w.hT[0]) + 1) : -1;   // the entry that ends the hour
        if (j < 0 || j >= w.hPrecip.length) return new double[]{0, 0, 0, w.code};
        return det(w.hPrecip[j], j < w.hSnow.length ? w.hSnow[j] / 0.7 : 0, w.hCode[j]);
    }

    private static double[] det(double rate, double snowRate, int code) {
        if (rate < WxClass.WET_RATE) return new double[]{0, 0, 0, code};
        double share = Math.min(1, snowRate / rate);
        if (share <= 0 && Wx.isSnowCode(code)) share = 1;
        return new double[]{1, rate, share, code};
    }

    // ------------------------------------------------------------------ now (fusion)
    private void fuseNow() {
        Wx w = wx;
        Sample s = now;
        s.t = nowT;
        double age = nowT - w.fetchedAtMs / 3600000.0;
        boolean old = w.hasHourly() && age > 0.75 && nowT >= w.hT[0] && nowT <= w.hT[w.hT.length - 1];
        if (old) { // an old forecast (offline, rate-limited): its "current" block is stale, read "now" from the hourly data
            s.temp = w.hourly(w.hTemp, nowT);
            s.feels = s.temp + (w.feels - w.temp);
            s.rh = w.hRH.length == w.hT.length ? w.hourly(w.hRH, nowT) : w.rh;
            s.uv = w.hourly(w.hUV, nowT);
            s.gust = w.hourly(w.hGust, nowT);
            s.cloud = w.hourly(w.hCloud, nowT);
            s.code = w.hCode[Math.max(0, Math.min(w.hT.length - 1, (int) Math.round(nowT - w.hT[0])))];
            notes += "forecast is " + Math.round(age * 60) + " min old (now from hourly). ";
        } else {
            s.temp = w.temp; s.feels = w.feels; s.rh = w.rh; s.uv = w.uv; s.gust = Math.max(w.gust, w.wind);
            s.cloud = w.cloud; s.code = w.code;
        }
        double[] m = detAt(Math.floor(nowT * 4) / 4 + 0.125);   // the model's 15-min slot that contains now
        int mCode = (int) m[3];
        boolean modelWet = m[0] > 0;
        long nowMs = ms(nowT);
        boolean obsOk = obs != null && obs.obsFresh(nowMs, WxClass.OBS_WET_MIN);   // fresh enough to say wet / dry
        boolean obsDry = obsOk && !obs.obsWet() && ("dry".equals(obs.condition) || "fog".equals(obs.condition)) && !(obs.precip10 > 0);
        boolean wet;
        double R;
        String src;
        if (radar) {
            double rr = obs.radarNow();
            wet = rr >= WxClass.WET_RATE;
            R = rr;
            src = "radar";
            nowNote = String.format(Locale.US, "radar %.1f mm/h (%d min old)", rr, Math.round((nowMs - obs.radarIssueMs) / 60000.0));
            if (!wet && obsOk && obs.obsWet()) {              // light snow / drizzle the radar misses
                wet = true; R = obsRate(m); src = "obs"; nowNote += ", station wet";
            } else if (wet && rr < WxClass.RADAR_SPECK && obsDry) { // a speck (clutter, virga) over a dry station
                wet = false; R = 0; src = "radar+obs"; nowNote += ", station dry";
            }
        } else if (obsOk && obs.obsWet()) {
            wet = true; R = obsRate(m); src = "obs"; nowNote = "station " + obs.condition;
        } else if (obsDry && modelWet && m[1] < WxClass.OBS_DRY_MAX) {
            wet = false; R = 0; src = "obs"; nowNote = "station dry (model " + String.format(Locale.US, "%.1f", m[1]) + " mm/h)";
        } else {
            wet = modelWet; R = m[1]; src = "det"; nowNote = "model";
        }
        if (obs != null && !radar) nowNote += obs.frameMs.length > 0 ? " (radar too old)" : " (no radar)";
        s.src = src;
        s.P = wet ? 1 : 0;
        s.R = wet ? Math.max(R, WxClass.WET_RATE) : 0;
        // type: a fresh station, else the model's split, else the temperature
        boolean obsType = obs != null && obs.obsFresh(nowMs, WxClass.OBS_TYPE_MIN) && obs.condition != null;
        String cond = obsType ? obs.condition : "";
        if (cond.equals("rain")) s.snow = 0;
        else if (cond.equals("sleet")) s.snow = 0.5;
        else if (cond.equals("snow")) s.snow = 1;
        else if (modelWet) s.snow = m[2];
        else s.snow = WxClass.snowFromTemp(s.temp);
        // fog: the station's visibility decides when clear-cut, else the model
        boolean fogModel = Wx.isFog(s.code) || Wx.isFog(mCode);
        boolean visOk = obs != null && obs.obsFresh(nowMs, WxClass.OBS_TYPE_MIN) && !Double.isNaN(obs.visibilityM);
        if (visOk && obs.visibilityM < WxClass.FOG_VIS) s.fog = true;
        else if (visOk && obs.visibilityM >= WxClass.NO_FOG_VIS) s.fog = false;
        else s.fog = fogModel || cond.equals("fog");
        s.thunder = wet && (cond.equals("thunderstorm") || Wx.isThunder(s.code) || Wx.isThunder(mCode));
        s.hail = wet && (cond.equals("hail") || Wx.isHail(s.code) || Wx.isHail(mCode));
        s.showers = wet && (Wx.isShowers(s.code) || Wx.isShowers(mCode));
        s.freezing = wet && (Wx.isFreezing(s.code) || Wx.isFreezing(mCode));
        if (wet) s.fog = false;
    }

    /** Rate for a wet observation: the measured 10-min amount, else the model's rate if it is wet, else 0.5 mm/h. */
    private double obsRate(double[] m) {
        if (obs.precip10 > 0) return Math.max(WxClass.WET_RATE, obs.precip10 * 6);
        return m[0] > 0 ? m[1] : 0.5;
    }

    // ------------------------------------------------------------------ hours (skeleton), slots, aggregation
    private int j0;   // hourly index of hour[0]

    private void hours() {
        Wx w = wx;
        if (!w.hasHourly()) return;
        j0 = Math.max(0, (int) Math.floor(nowT - w.hT[0] + 1e-9));
        int n = w.hT.length - j0;
        if (n <= 0) return;
        hour = new Sample[n];
        for (int i = 0; i < n; i++) {
            int j = j0 + i, je = Math.min(w.hT.length - 1, j + 1);   // je: the entry that ends this hour
            Sample h = new Sample();
            h.t = w.hT[j];
            h.temp = w.hTemp[j];
            h.feels = h.temp;
            h.rh = j < w.hRH.length ? w.hRH[j] : -1;
            h.uv = j < w.hUV.length ? w.hUV[j] : 0;
            h.gust = je < w.hGust.length ? w.hGust[je] : 0;
            h.code = w.hCode[je];
            // precipitation beyond the slots: the ensemble, else the model's hour
            int ei = ens != null ? ens.idx(h.t + 0.5) : -1;
            if (ei >= 0) { h.P = ens.p[ei]; h.R = ens.r[ei]; h.snow = ens.snow[ei]; h.src = "ens"; h.ensW = 1; }
            else { double[] d = detAt(h.t + 0.5); h.P = d[0]; h.R = d[1]; h.snow = d[2]; h.src = "det"; }
            norm(h);
            hour[i] = h;
        }
    }

    private void slots() {
        slot0 = Math.floor(nowT * 4) / 4;
        int n = (int) Math.ceil((nowT + SLOT_H - slot0) * 4);
        slot = new Sample[n];
        for (int k = 0; k < n; k++) {
            double ts = slot0 + k * 0.25, tc = ts + 0.125, lead = tc - nowT;
            Sample s = new Sample();
            s.t = ts;
            double[] d = detAt(tc);
            double Pn = d[0], Rn = d[1], Sn = d[2];
            String srcN = "det";
            if (radar && nowcast) {
                double rr = obs.radarRate(ts, ts + 0.25);
                double wr = lead <= WxClass.RADAR_FULL_H ? 1 : 1 - sm(WxClass.RADAR_FULL_H, WxClass.RADAR_END_H, lead);
                if (!Double.isNaN(rr) && wr > 0.001) {
                    double Pr = rr >= WxClass.WET_RATE ? 1 : 0;
                    double Sr = Pn > 0 ? Sn : WxClass.snowFromTemp(wx.hourly(wx.hTemp, tc));
                    double[] m = mix(Pr, Pr > 0 ? rr : 0, Sr, wr, Pn, Rn, Sn);
                    Pn = m[0]; Rn = m[1]; Sn = m[2];
                    srcN = wr >= 0.999 ? "radar" : "radar+det";
                }
            }
            int ei = ens != null ? ens.idx(tc) : -1;
            double wd = ei < 0 ? 1 : !nowcast ? 0 : lead <= WxClass.DET_FULL_H ? 1 : 1 - sm(WxClass.DET_FULL_H, WxClass.DET_END_H, lead);
            if (wd >= 0.999) { s.P = Pn; s.R = Rn; s.snow = Sn; s.src = srcN; }
            else if (wd <= 0.001) { s.P = ens.p[ei]; s.R = ens.r[ei]; s.snow = ens.snow[ei]; s.src = "ens"; }
            else {
                double[] m = mix(Pn, Rn, Sn, wd, ens.p[ei], ens.r[ei], ens.snow[ei]);
                s.P = m[0]; s.R = m[1]; s.snow = m[2]; s.src = "blend";
            }
            s.ensW = 1 - Math.min(1, Math.max(0, wd));
            norm(s);
            slot[k] = s;
        }
        if (n > 0) { Sample s0 = now.copy(); s0.t = slot0; s0.ensW = 0; slot[0] = s0; }   // the column's first slot is "now"
    }

    /** Mix of two precipitation sources (weights w, 1 - w): P blends, R is the conditional mean, snow by water. */
    static double[] mix(double P1, double R1, double S1, double w, double P2, double R2, double S2) {
        double P = w * P1 + (1 - w) * P2;
        double E = w * P1 * R1 + (1 - w) * P2 * R2;
        double R = P > 0 ? E / P : 0;
        double S = E > 0 ? (w * P1 * R1 * S1 + (1 - w) * P2 * R2 * S2) / E : 0;
        return new double[]{P, R, S};
    }

    /** Below the wet rate nothing falls: P = 0 (so P > 0 always means R >= WET_RATE). */
    private static void norm(Sample s) {
        if (s.R < WxClass.WET_RATE || s.P <= 1e-6) { s.P = 0; s.R = 0; }
        s.P = Math.min(1, s.P);
        s.snow = Math.max(0, Math.min(1, s.snow));
    }

    /** Hours that overlap the slots take P (max), R (P-weighted) and snow (by water) from them; hour[0] is "now". */
    private void precipHours() {
        for (int i = 0; i < hour.length; i++) {
            Sample h = hour[i];
            double P = 0, Pw = 0, E = 0, ES = 0, ew = 0;
            int n = 0;
            Set<String> src = new LinkedHashSet<>();
            for (int k = Math.max(0, slotIndex(h.t)); k < slot.length && slot[k].t < h.t + 1 - 1e-6; k++) {
                Sample s = slot[k];
                if (s.t < h.t - 1e-6) continue;
                n++;
                P = Math.max(P, s.P);
                Pw += s.P;
                E += s.P * s.R;
                ES += s.P * s.R * s.snow;
                ew += s.ensW;
                src.add(s.src);
            }
            if (n == 0) continue;
            h.P = P;
            h.R = Pw > 0 ? E / Pw : 0;
            h.snow = E > 0 ? ES / E : 0;
            h.ensW = ew / n;
            h.src = src.size() == 1 ? src.iterator().next() : String.join("/", src);
            norm(h);
        }
        if (hour.length > 0) mirrorNow();
    }

    /** The current hour's sky and flags are "now" (its precipitation includes the now slot). */
    private void mirrorNow() {
        Sample h = hour[0];
        h.cloud = now.cloud;
        h.fog = now.fog; h.thunder = now.thunder; h.hail = now.hail; h.showers = now.showers; h.freezing = now.freezing;
    }

    /** Flags from the model's codes, gated by P; cloud from the model, raised where the ensemble says it is wet. */
    private void flagsAndCloud() {
        Wx w = wx;
        for (int i = 1; i < hour.length; i++) {
            Sample h = hour[i];
            int je = j0 + i + 1;
            boolean th = false, ha = false, sh = false;
            for (int j = je - 1; j <= je + 1; j++) {
                if (j < 0 || j >= w.hCode.length) continue;
                int c = w.hCode[j];
                th |= Wx.isThunder(c);
                ha |= Wx.isHail(c);
                sh |= Wx.isShowers(c);
            }
            boolean wet = h.P >= WxClass.P_POSSIBLE;
            h.fog = Wx.isFog(h.code) && !wet;
            h.thunder = th && wet;
            h.hail = ha && wet;
            h.showers = sh && wet;
            h.freezing = Wx.isFreezing(h.code) && wet;
            h.cloud = WxClass.raise(w.hourly(w.hCloud, h.t + 0.5), h.P, h.ensW);
        }
    }

    /** Slots after the first take their hour's flags and the cloud curve; the first slot is "now". */
    private void inherit() {
        for (int k = 1; k < slot.length; k++) {
            Sample s = slot[k];
            double tc = s.t + 0.125;
            Sample h = hourOf(tc);
            if (h != null) {
                s.fog = h.fog && s.P < WxClass.P_POSSIBLE; s.thunder = h.thunder; s.hail = h.hail; s.showers = h.showers; s.freezing = h.freezing;
                s.gust = h.gust; s.rh = h.rh; s.uv = h.uv; s.code = h.code;
            }
            s.temp = wx.hasHourly() ? wx.hourly(wx.hTemp, tc) : now.temp;
            s.feels = s.temp;
            s.cloud = cloudAt(tc);
        }
        if (slot.length > 0) { Sample s0 = now.copy(); s0.t = slot0; slot[0] = s0; }
    }

    private void days() {
        Wx w = wx;
        day = new WxClass.Day[w.dT.length];
        for (int d = 0; d < w.dT.length; d++) {
            double t0 = w.dT[d], t1 = d + 1 < w.dT.length ? w.dT[d + 1] : t0 + 24;
            List<Sample> hs = new ArrayList<>();
            for (Sample h : hour) if (h.t >= t0 - 1e-6 && h.t < t1 - 1e-6) hs.add(h);
            if (hs.isEmpty()) continue;
            double rise = d < w.dRise.length && w.dRise[d] > 0 ? w.dRise[d] : Double.NaN;
            double set = d < w.dSet.length && w.dSet[d] > rise ? w.dSet[d] : Double.NaN;
            if (Double.isNaN(set)) rise = Double.NaN;
            day[d] = WxClass.day(hs.toArray(new Sample[0]), rise, set);
        }
    }

    /** After a scenario edited now, slots or hours: hours from the slots again, flags / cloud down to the slots, days. */
    public void aggregate() {
        if (slot.length > 0) { Sample s0 = now.copy(); s0.t = slot0; slot[0] = s0; }
        precipHours();
        inherit();
        days();
    }

    // ------------------------------------------------------------------ lookups
    int slotIndex(double t) { return (int) Math.floor((t - slot0) * 4 + 1e-6); }

    /** The hourly sample whose hour contains t, or null. */
    public Sample hourOf(double t) {
        if (hour.length == 0) return null;
        int i = (int) Math.floor(t - hour[0].t + 1e-9);
        return i >= 0 && i < hour.length ? hour[i] : null;
    }

    /** The hourly sample that starts at hs (a whole hour of the grid), or null. */
    public Sample hourAt(double hs) {
        if (hour.length == 0) return null;
        int i = (int) Math.round(hs - hour[0].t);
        return i >= 0 && i < hour.length && Math.abs(hour[i].t - hs) < 0.01 ? hour[i] : null;
    }

    /** The sample the column draws at t: "now" before the first slot, the 15-min slot, then hourly, clamped. */
    public Sample at(double t) {
        if (slot.length == 0 || t < slot0 + 0.25) return now;
        int k = slotIndex(t);
        if (k < slot.length) return slot[k];
        Sample h = hourOf(t);
        if (h != null) return h;
        return hour.length > 0 ? hour[hour.length - 1] : now;
    }

    /**
     * Cloud cover (0..100) at t: now.cloud at now, each hour's cloud at its middle, linear between. The card's broken
     * cloud and the column's band both read this curve.
     */
    public double cloudAt(double t) {
        if (t <= nowT || hour.length < 2) return now.cloud;
        double c1 = hour[1].t + 0.5;
        if (t <= c1) return now.cloud + (hour[1].cloud - now.cloud) * (t - nowT) / Math.max(1e-6, c1 - nowT);
        int i = (int) Math.floor(t - c1) + 1;
        if (i >= hour.length - 1) return hour[hour.length - 1].cloud;
        double f = (t - (hour[i].t + 0.5));
        return hour[i].cloud + (hour[i + 1].cloud - hour[i].cloud) * Math.max(0, Math.min(1, f));
    }

    /** Highest P in (from, to] hours from now (the card's "rain is coming" darkening). */
    public double maxP(double from, double to) {
        double m = 0;
        for (Sample s : slot) {
            double c = s.t + 0.125 - nowT;
            if (c > from && c <= to) m = Math.max(m, s.P);
        }
        return m;
    }

    /** The strip day (wx.dT index), or null. */
    public WxClass.Day dayAt(int d) { return d >= 0 && d < day.length ? day[d] : null; }
}
