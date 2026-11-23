import dev.livingsky.render.*;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Live check on the desktop, with the phone's own code (sources, parsers, Timeline, classifier): per city, the fused
 * "now", the column's first hours, the 4 strip days, and assertions that the word, the card's state and the column's
 * "now" agree and that the strip invariants hold. Ends with the daily call budget.
 *
 * usage: ./build.sh live [name lat lon ...]   (default: Ulm, Barcelona, Malgrat de Mar, Chicago)
 */
public class LiveHarness {
    static final String[] TYPES = {"dry", "rain", "sleet", "snow"};

    public static void main(String[] a) throws Exception {
        List<Object[]> cities = new ArrayList<>();
        if (a.length >= 3) for (int i = 0; i + 2 < a.length; i += 3) cities.add(new Object[]{a[i], Double.parseDouble(a[i + 1]), Double.parseDouble(a[i + 2])});
        else {
            cities.add(new Object[]{"Ulm", 48.3984, 9.9916});
            cities.add(new Object[]{"Barcelona", 41.3874, 2.1686});
            cities.add(new Object[]{"Malgrat de Mar", 41.6455, 2.7413});
            cities.add(new Object[]{"Chicago", 41.8781, -87.6298});
        }
        int failures = 0, members = 40;
        for (Object[] c : cities) {
            String name = (String) c[0];
            double lat = (Double) c[1], lon = (Double) c[2];
            long nowMs = System.currentTimeMillis();
            double now = nowMs / 3600000.0;
            Wx w = Wx.parse(Sources.get(Sources.forecastUrl(lat, lon), 20000), nowMs);
            Ensemble e = null;
            for (String m : Sources.ENS_MODELS) {
                try { e = Ensemble.parse(Sources.get(Sources.ensembleUrl(lat, lon, m), 25000), m, lat, lon, nowMs); } catch (Exception ex) { System.out.println("  ensemble " + m + ": " + ex.getMessage()); }
                if (e != null) break;
            }
            if (e != null) members = Math.max(members, e.members);
            NowObs o = null;
            if (Sources.inGermany(lat, lon)) {
                String r = null, cw = null;
                try { r = Sources.get(Sources.radarUrl(lat, lon, nowMs), 8000); } catch (Exception ex) { System.out.println("  radar: " + ex.getMessage()); }
                try { cw = Sources.get(Sources.currentUrl(lat, lon), 8000); } catch (Exception ex) { System.out.println("  station: " + ex.getMessage()); }
                o = NowObs.parse(r, cw, lat, lon, nowMs);
            }
            Timeline tl = Timeline.build(w, e, o, now, true);
            TimeZone tz = TimeZone.getTimeZone(w.tz);
            SimpleDateFormat hm = new SimpleDateFormat("EEE HH:mm", Locale.ENGLISH);
            hm.setTimeZone(tz);
            System.out.printf("%n=== %s (%.4f, %.4f), %s local; ensemble %s (%d members)%s%n", name, lat, lon, hm.format(new Date(nowMs)),
                    e != null ? e.model : "none", e != null ? e.members : 0,
                    o != null ? String.format(Locale.US, "; radar %s; station %s %s, %.1f km, %s", o.radarIssueMs > 0 ? Math.round((nowMs - o.radarIssueMs) / 60000.0) + " min old" : "none",
                            o.station, o.condition, o.distKm, o.obsMs > 0 ? Math.round((nowMs - o.obsMs) / 60000.0) + " min old" : "-") : "");
            // ---- now
            WxState st = WxClass.classify(tl.now, WxClass.NOW);
            boolean night = Sun.elevation(now, lat, lon) < -0.83;
            String word = WxClass.word(st, night)[0];
            SkyShader.Params sp = Scene.sky(tl, now, lat, lon, false, Scene.MODE_AUTO, 1);
            System.out.printf("NOW     src %-9s wet %-5s type %-5s R %.2f  cloud %3.0f%%  %s  -> word \"%s\", scene %s  [%s]%n",
                    tl.now.src, tl.now.P > 0, TYPES[WxClass.ptype(tl.now)], tl.now.R, tl.now.cloud, Renderer2.deg(tl.now.temp), word,
                    WxState.NAMES[sp.state], tl.nowNote);
            // ---- column, first hours
            System.out.println("COLUMN  hour  src               P%   R mm/h type   cloud temp  bars(15 min)");
            SimpleDateFormat hh = new SimpleDateFormat("HH:mm", Locale.ENGLISH);
            hh.setTimeZone(tz);
            for (int i = 0; i < Math.min(8, tl.hour.length); i++) {
                Timeline.Sample h = tl.hour[i];
                StringBuilder bars = new StringBuilder();
                for (Timeline.Sample s : TimelineTest.drawn(tl, h.t, h.t + 1)) bars.append(WxClass.solid(s) ? '#' : WxClass.bar(s) ? '+' : '.');
                System.out.printf("        %s %-17s %3d  %5.2f  %-5s  %3.0f%%  %4s  %s%n", hh.format(new Date((long) (Math.max(h.t, i == 0 ? now : h.t) * 3600000L))),
                        h.src, WxClass.pct(h.P), h.R, TYPES[WxClass.ptype(h)], h.cloud, Renderer2.deg(h.temp), bars);
            }
            // ---- strip
            SimpleDateFormat dn = new SimpleDateFormat("EEE", Locale.ENGLISH);
            dn.setTimeZone(tz);
            int d0 = w.dayIndex(now);
            System.out.println("STRIP   day  icon          max/min    P%   likely/possible h, bar hours (# solid, + faint)");
            for (int d = d0 + 1; d <= d0 + 4; d++) {
                WxClass.Day day = tl.dayAt(d);
                if (day == null) { System.out.println("        (no day " + d + ")"); failures++; continue; }
                StringBuilder hrs = new StringBuilder();   // the hours with bars (# solid, + faint), local
                for (Timeline.Sample h : tl.hour) {
                    if (h.t < w.dT[d] - 1e-6 || h.t >= w.dT[d] + 24 - 1e-6 || !WxClass.bar(h)) continue;
                    hrs.append(' ').append(hh.format(new Date((long) (h.t * 3600000L))), 0, 2).append(WxClass.solid(h) ? '#' : '+');
                }
                System.out.printf("        %s  %-13s %4s/%-4s %3d%%  %d/%d %s%n", dn.format(new Date((long) ((w.dT[d] + 12) * 3600000L))), day.icon,
                        Renderer2.deg(day.max), Renderer2.deg(day.min), WxClass.pct(day.maxP), day.likely, day.possible, hrs);
            }
            // ---- assertions
            List<String> f = new ArrayList<>(TimelineTest.checkNow(tl));
            if (sp.state != st.kind) f.add("scene state " + WxState.NAMES[sp.state] + " vs word state " + WxState.NAMES[st.kind]);
            for (int d = d0 + 1; d <= d0 + 4; d++) if (tl.dayAt(d) != null) for (String x : TimelineTest.checkDay(tl, d)) f.add("day " + d + ": " + x);
            f.addAll(TimelineTest.checkPct(tl));
            System.out.println(f.isEmpty() ? "CHECK   ok: word, scene and column-now agree (" + WxState.NAMES[st.kind] + "); strip invariants hold"
                    : "CHECK   FAILED: " + String.join("; ", f));
            failures += f.size();
        }
        // ---- budget (PROPOSAL 4.7): Open-Meteo weight per call x calls per day
        double fw = Sources.forecastWeight(), ew = Sources.ensembleWeight(members);
        int fPerDay = 24 * 60 / 30, ePerDay = 24 * 60 / 120;
        System.out.printf("%nBUDGET  forecast %d vars -> weight %.1f x %d/day = %.0f; ensemble %d vars x %d members -> %.1f x %d/day = %.0f;"
                        + " Open-Meteo total %.0f/day (limit 10000; before: 422); Bright Sky (DE only) radar + station <= 96 + 96/day%n",
                Sources.vars(Sources.F_CURRENT) + Sources.vars(Sources.F_HOURLY) + Sources.vars(Sources.F_MIN15) + Sources.vars(Sources.F_DAILY),
                fw, fPerDay, fw * fPerDay, Sources.vars(Sources.E_HOURLY), members, ew, ePerDay, ew * ePerDay, fw * fPerDay + ew * ePerDay);
        System.out.println(failures == 0 ? "ALL OK" : failures + " FAILURES");
        if (failures > 0) System.exit(1);
    }

    /** The renderer's degree formatting (Renderer is Android-only). */
    static final class Renderer2 {
        static String deg(double v) { long r = Math.round(v); return r == 0 ? "0°" : (r < 0 ? "−" + (-r) : String.valueOf(r)) + "°"; }
    }
}
