import dev.livingsky.render.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Desktop unit test of the one-timeline rules (PROPOSAL section 4.5), on thousands of random timelines (model, radar,
 * ensemble, flags):
 *   - a strip day has a precipitation icon if and only if the column has solid bars that day;
 *   - a dry icon only with fewer than 2 hours of (faint) bars; a "chance" icon only with faint bars, no solid ones;
 *   - the strip's max / min are the column's own hourly temperatures;
 *   - "Rain %" is 20+ exactly where the hour has a bar, 50+ exactly where it has a solid one;
 *   - now: the word's state, the card's state and the column's first slot agree (wet, cloud).
 *
 * usage: ./build.sh test
 */
public class TimelineTest {
    public static void main(String[] a) {
        int n = a.length > 0 ? Integer.parseInt(a[0]) : 3000;
        List<String> fails = new ArrayList<>();
        int days = 0, chance = 0, precip = 0, dry = 0;
        for (int i = 0; i < n; i++) {
            Random r = new Random(1000 + i);
            double t0 = 495_000 + 24 * r.nextInt(400);           // a UTC midnight
            double now = t0 + r.nextInt(24 * 4) / 4.0 + r.nextDouble() * 0.25;
            Wx w = synth(r, t0, now);
            Ensemble e = r.nextInt(6) == 0 ? null : ens(r, w);
            NowObs o = r.nextInt(3) == 0 ? radar(r, now) : null;
            Timeline tl = Timeline.build(w, e, o, now, r.nextInt(8) != 0);
            String tag = "seed " + (1000 + i);
            for (String f : checkNow(tl)) fails.add(tag + ": " + f);
            int d0 = w.dayIndex(now);
            for (int d = d0 + 1; d <= d0 + 4; d++) {
                WxClass.Day day = tl.dayAt(d);
                if (day == null) { fails.add(tag + ": no strip day " + d); continue; }
                days++;
                if (day.chance) chance++; else if (day.precipIcon()) precip++; else dry++;
                for (String f : checkDay(tl, d)) fails.add(tag + " day " + d + ": " + f);
            }
            for (String f : checkPct(tl)) fails.add(tag + ": " + f);
        }
        for (int i = 0; i < Math.min(20, fails.size()); i++) System.out.println("FAIL " + fails.get(i));
        System.out.printf("%d timelines, %d strip days (%d precipitation, %d chance, %d dry): %s%n", n, days, precip, chance, dry,
                fails.isEmpty() ? "all invariants hold" : fails.size() + " FAILURES");
        if (!fails.isEmpty()) System.exit(1);
    }

    // ------------------------------------------------------------------ the checks (also used by LiveHarness)
    /** The samples the column draws for hours [t0, t1): the 15-min slots where they exist, else the hours. */
    static List<Timeline.Sample> drawn(Timeline tl, double t0, double t1) {
        List<Timeline.Sample> out = new ArrayList<>();
        for (Timeline.Sample h : tl.hour) {
            if (h.t < t0 - 1e-6 || h.t >= t1 - 1e-6) continue;
            boolean any = false;
            for (double q = Math.max(h.t, tl.nowT); q < h.t + 1 - 1e-6; q = Math.floor(q * 4 + 1e-6) / 4 + 0.25) {
                if (q - tl.slot0 < Timeline.SLOT_H - 0.5) { out.add(tl.at(q)); any = true; }
            }
            if (!any) out.add(h);
        }
        return out;
    }

    static List<String> checkDay(Timeline tl, int d) {
        List<String> f = new ArrayList<>();
        Wx w = tl.wx;
        WxClass.Day day = tl.dayAt(d);
        double t0 = w.dT[d], t1 = d + 1 < w.dT.length ? w.dT[d + 1] : t0 + 24;
        boolean solid = false;
        for (Timeline.Sample s : drawn(tl, t0, t1)) solid |= WxClass.solid(s);
        int barHours = 0;
        double mx = Double.NEGATIVE_INFINITY, mn = Double.POSITIVE_INFINITY;
        for (double h = t0; h < t1 - 1e-6; h += 1) {
            Timeline.Sample hs = tl.hourAt(h);
            if (hs == null) continue;
            boolean bar = false;
            for (Timeline.Sample s : drawn(tl, h, h + 1)) bar |= WxClass.bar(s);
            if (bar) barHours++;
            mx = Math.max(mx, hs.temp);
            mn = Math.min(mn, hs.temp);
        }
        if (day.precipIcon() != solid) f.add("icon " + day.icon + " but solid bars " + solid);
        if (day.dryIcon() && barHours >= 2) f.add("dry icon " + day.icon + " with " + barHours + " hours of bars");
        if (day.chance && (solid || barHours < 2)) f.add("chance icon with solid " + solid + ", " + barHours + " bar hours");
        if (!deg(day.max).equals(deg(mx)) || !deg(day.min).equals(deg(mn))) f.add("max/min " + deg(day.max) + "/" + deg(day.min) + " vs column " + deg(mx) + "/" + deg(mn));
        return f;
    }

    static List<String> checkPct(Timeline tl) {
        List<String> f = new ArrayList<>();
        for (Timeline.Sample h : tl.hour) {
            if (h.t > tl.nowT + 72) break;
            boolean bar = false, solid = false;
            for (Timeline.Sample s : drawn(tl, h.t, h.t + 1)) { bar |= WxClass.bar(s); solid |= WxClass.solid(s); }
            int p = WxClass.pct(h.P);
            if ((p >= 20) != bar || (p >= 50) != solid) f.add(String.format("hour %.0f: Rain %d%% but bar %s solid %s", h.t, p, bar, solid));
        }
        return f;
    }

    static List<String> checkNow(Timeline tl) {
        List<String> f = new ArrayList<>();
        WxState word = WxClass.classify(tl.now, WxClass.NOW);
        WxState scene = Scene.state(tl);
        Timeline.Sample first = tl.at(tl.nowT);
        if (word.kind != scene.kind) f.add("word " + WxState.NAMES[word.kind] + " vs scene " + WxState.NAMES[scene.kind]);
        if (word.precip() != WxClass.bar(first)) f.add("word " + WxState.NAMES[word.kind] + " but first slot bar " + WxClass.bar(first));
        if (WxClass.bar(first) && !WxClass.solid(first)) f.add("now has a faint bar");
        if (Math.abs(tl.cloudAt(tl.nowT) - tl.now.cloud) > 1e-9) f.add("column cloud at now " + tl.cloudAt(tl.nowT) + " vs now " + tl.now.cloud);
        if (tl.hour.length > 0 && Math.abs(tl.hour[0].cloud - tl.now.cloud) > 1e-9) f.add("hour[0].cloud != now.cloud");
        if (tl.slot.length > 0 && (tl.slot[0].P != tl.now.P || tl.slot[0].R != tl.now.R)) f.add("slot[0] is not now");
        return f;
    }

    static String deg(double v) { long r = Math.round(v); return r + "°"; }

    // ------------------------------------------------------------------ random inputs
    static Wx synth(Random r, double t0, double now) {
        Wx w = new Wx();
        w.lat = 48.4; w.lon = 10; w.tz = "UTC";
        w.fetchedAtMs = Math.round((now - (r.nextInt(5) == 0 ? 1.5 : 0.2)) * 3600000.0);
        int n = 24 * 6;
        w.hT = new double[n]; w.hTemp = new double[n]; w.hCloud = new double[n]; w.hUV = new double[n]; w.hGust = new double[n];
        w.hRH = new double[n]; w.hPrecip = new double[n]; w.hSnow = new double[n]; w.hCode = new int[n];
        double temp = -4 + r.nextDouble() * 20, cloud = r.nextDouble() * 100;
        int[] wetCodes = {51, 61, 63, 65, 71, 73, 75, 80, 81, 85, 95, 96, 66};
        for (int i = 0; i < n; i++) {
            w.hT[i] = t0 + i;
            temp += r.nextGaussian() * 0.7;
            cloud = Math.max(0, Math.min(100, cloud + r.nextGaussian() * 18));
            w.hTemp[i] = temp; w.hCloud[i] = cloud; w.hGust[i] = 5 + r.nextDouble() * (r.nextInt(5) == 0 ? 70 : 35);
            w.hRH[i] = 60 + r.nextDouble() * 40; w.hUV[i] = r.nextDouble() * 3;
            boolean wet = r.nextInt(25) == 0;
            w.hPrecip[i] = wet ? 0.1 + r.nextDouble() * 3 : r.nextInt(8) == 0 ? 0.05 : 0;
            w.hSnow[i] = wet && temp < 1.5 ? w.hPrecip[i] * 0.7 * r.nextDouble() : 0;
            w.hCode[i] = wet ? wetCodes[r.nextInt(wetCodes.length)] : r.nextInt(10) == 0 ? 45 : r.nextInt(4);
        }
        w.temp = w.hourly(w.hTemp, now); w.feels = w.temp - 2; w.rh = 80; w.uv = 1; w.cloud = w.hourly(w.hCloud, now);
        w.gust = 20; w.wind = 8; w.code = w.hCode[(int) Math.floor(now - t0)];
        double m0 = Math.floor(now * 4) / 4;
        int m = 96;
        w.mT = new double[m]; w.mPrecip = new double[m]; w.mRain = new double[m]; w.mSnow = new double[m]; w.mCode = new int[m];
        boolean wet = r.nextBoolean();
        for (int i = 0; i < m; i++) {
            if (r.nextInt(6) == 0) wet = !wet;
            w.mT[i] = m0 + i * 0.25;
            w.mPrecip[i] = wet ? Math.round(r.nextDouble() * 8) / 10.0 : 0;
            boolean snow = temp < 1 && r.nextBoolean();
            w.mSnow[i] = snow ? w.mPrecip[i] * 0.7 : 0;
            w.mRain[i] = snow ? 0 : w.mPrecip[i];
            w.mCode[i] = wet ? wetCodes[r.nextInt(wetCodes.length)] : r.nextInt(4);
        }
        int nd = 6;
        w.dT = new double[nd]; w.dRise = new double[nd]; w.dSet = new double[nd];
        for (int d = 0; d < nd; d++) { w.dT[d] = t0 + 24 * d; w.dRise[d] = w.dT[d] + 7.5; w.dSet[d] = w.dT[d] + 16.5; }
        return w;
    }

    static Ensemble ens(Random r, Wx w) {
        int n = w.hT.length;
        Ensemble e = new Ensemble();
        e.model = "test"; e.members = 40; e.t = new double[n]; e.p = new double[n]; e.r = new double[n]; e.snow = new double[n];
        double[] grid = {0.025, 0.1, 0.175, 0.2, 0.225, 0.3, 0.45, 0.475, 0.5, 0.525, 0.7, 0.9, 1};
        double wetness = new double[]{0.01, 0.04, 0.12, 0.4}[r.nextInt(4)];   // dry, showery, unsettled, wet regimes
        double p = 0;
        for (int i = 0; i < n; i++) {
            e.t[i] = w.hT[i] + 1;
            if (r.nextInt(3) == 0) p = r.nextDouble() < wetness ? grid[r.nextInt(grid.length)] : r.nextBoolean() ? 0 : grid[r.nextInt(3)];
            e.p[i] = p;
            e.r[i] = p > 0 ? 0.1 + r.nextDouble() * (r.nextInt(6) == 0 ? 6 : 1.5) : 0;
            e.snow[i] = r.nextInt(3) == 0 ? r.nextDouble() : r.nextBoolean() ? 1 : 0;
        }
        return e;
    }

    static NowObs radar(Random r, double now) {
        NowObs o = new NowObs();
        long nowMs = Math.round(now * 3600000.0);
        long issue = (nowMs / 300_000L) * 300_000L - 300_000L * r.nextInt(3);
        int k = 30;
        o.frameMs = new long[k]; o.frameR = new double[k];
        boolean wet = r.nextBoolean();
        for (int i = 0; i < k; i++) {
            if (r.nextInt(5) == 0) wet = !wet;
            o.frameMs[i] = issue - 300_000L * 4 + 300_000L * i;
            o.frameR[i] = wet ? r.nextDouble() * 3 : r.nextInt(6) == 0 ? 0.12 : 0;
        }
        o.radarIssueMs = issue;
        if (r.nextBoolean()) {
            o.obsMs = nowMs - 60_000L * r.nextInt(50);
            o.condition = new String[]{"dry", "rain", "snow", "sleet", "fog", "dry"}[r.nextInt(6)];
            o.precip10 = o.condition.equals("rain") ? 0.1 : 0;
            o.visibilityM = r.nextInt(4) == 0 ? 600 : 20000;
            o.distKm = 7;
        }
        return o;
    }
}
