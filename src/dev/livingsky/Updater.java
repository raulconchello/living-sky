package dev.livingsky;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dev.livingsky.render.Ensemble;
import dev.livingsky.render.MockData;
import dev.livingsky.render.Scene;
import dev.livingsky.render.Wx;

/**
 * One update: location -> (weather fetch if stale) -> skyline selection -> render -> push to every widget instance.
 * Always renders from the last good data when the network or location is unavailable.
 */
final class Updater {
    private Updater() {}

    static final int FETCH_NEVER = 0, FETCH_IF_STALE = 1, FETCH_FORCE = 2;
    static final long STALE_MS = 25 * 60_000L;
    static final ExecutorService EXEC = Executors.newSingleThreadExecutor();
    /** Column taps never wait behind network work (location, forecast, geocoding) running on EXEC. */
    static final ExecutorService COL_EXEC = Executors.newSingleThreadExecutor();
    private static final Object LOCK = new Object();
    /** Held only while rendering + pushing (not during network), shared with the column toggle. */
    private static final Object RENDER = new Object();

    /** Last render (debug preview) and its inputs (the tap toggle re-renders only the column from them). */
    static volatile Renderer.Out last;
    static volatile Renderer.Input lastIn;

    /** Tap toggle / revert: re-render only the column and push it; full update if nothing is in memory. */
    static void runColumn(Context c, boolean values, boolean scrollToNow, Runnable done) {
        Context app = c.getApplicationContext();
        COL_EXEC.execute(() -> {
            try {
                synchronized (RENDER) {
                    Renderer.Input in = lastIn;
                    Renderer.Out o = last;
                    if (in == null || o == null) { EXEC.execute(() -> run(app, "toggle", FETCH_NEVER)); return; }
                    long t0 = System.currentTimeMillis();
                    in.colValues = values;
                    in.alarmAt = nextAlarm(app, new Prefs(app), in.now, in.tz);
                    Renderer.column(app, in, o.geo, o);
                    WidgetUpdater.pushColumn(app, o, scrollToNow);
                    Log.i(Locator.TAG, "column " + (values ? "values" : "bars") + " pushed in " + (System.currentTimeMillis() - t0) + " ms"
                            + (scrollToNow ? " (back to now)" : " (scroll kept)"));
                    if (new Prefs(app).dump()) dump(app, o);
                }
            } catch (Throwable t) {
                Log.e(Locator.TAG, "column toggle failed", t);
            } finally {
                if (done != null) done.run();
            }
        });
    }

    /**
     * The next alarm (hours since epoch) from AlarmManager.getNextAlarmClock(), or NaN, also when a non-clock app set
     * it (see {@link #isClockApp}). Android exposes only the next one, so a real alarm behind a reminder shows once the
     * reminder has passed (NEXT_ALARM_CLOCK_CHANGED); snoozes report the snooze time. Debug override (prefs "alarm"):
     * "none", "Mon 08:45" or "08:45".
     */
    static double nextAlarm(Context c, Prefs prefs, double now, java.util.TimeZone tz) {
        String ov = prefs.alarmOverride();
        if (!ov.isEmpty()) {
            if (ov.equals("none")) return Double.NaN;
            try {
                String[] p = ov.trim().split("\\s+");
                String hm = p[p.length - 1];
                int dow = -1;
                if (p.length > 1) {
                    String d = p[0].toLowerCase(Locale.ROOT);
                    String[] days = {"sun", "mon", "tue", "wed", "thu", "fri", "sat"};
                    for (int i = 0; i < 7; i++) if (d.startsWith(days[i])) dow = i + 1;
                }
                Calendar cal = Calendar.getInstance(tz);
                cal.setTimeInMillis((long) (now * 3600000L));
                cal.set(Calendar.HOUR_OF_DAY, Integer.parseInt(hm.split(":")[0]));
                cal.set(Calendar.MINUTE, Integer.parseInt(hm.split(":")[1]));
                cal.set(Calendar.SECOND, 0);
                cal.set(Calendar.MILLISECOND, 0);
                for (int i = 0; i < 8 && (cal.getTimeInMillis() / 3600000.0 < now || (dow > 0 && cal.get(Calendar.DAY_OF_WEEK) != dow)); i++)
                    cal.add(Calendar.DAY_OF_MONTH, 1);
                return cal.getTimeInMillis() / 3600000.0;
            } catch (Exception e) {
                return Double.NaN;
            }
        }
        try {
            android.app.AlarmManager.AlarmClockInfo a = c.getSystemService(android.app.AlarmManager.class).getNextAlarmClock();
            if (a == null) return Double.NaN;
            String by = alarmOwner(a);
            if (!isClockApp(c, by)) { // e.g. a calendar or meeting reminder registered as an alarm clock
                Log.i(Locator.TAG, "next alarm set by " + by + ", not a clock app: hidden");
                return Double.NaN;
            }
            return a.getTriggerTime() / 3600000.0;
        } catch (Exception e) {
            return Double.NaN;
        }
    }

    /** The app that set the alarm (the creator of its "show" intent), or null when it gave none. */
    static String alarmOwner(android.app.AlarmManager.AlarmClockInfo a) {
        android.app.PendingIntent show = a.getShowIntent();
        return show == null ? null : show.getCreatorPackage();
    }

    /**
     * Only alarms from a clock app get the pill: any app may register an "alarm clock" (calendar and meeting apps do,
     * for reminders), and Android reports only the next one. A clock app = Google Clock or any app that handles
     * AlarmClock.ACTION_SET_ALARM (the phone's own clock, alarm apps; declared in the manifest's queries).
     */
    static boolean isClockApp(Context c, String pkg) {
        if (pkg == null) return false;
        if (pkg.equals(WidgetUpdater.DESKCLOCK)) return true;
        android.content.Intent set = new android.content.Intent(android.provider.AlarmClock.ACTION_SET_ALARM);
        for (android.content.pm.ResolveInfo r : c.getPackageManager().queryIntentActivities(set, 0))
            if (r.activityInfo != null && pkg.equals(r.activityInfo.packageName)) return true;
        return false;
    }

    /** NEXT_ALARM_CLOCK_CHANGED: redraw only the column with the new alarm (full render if nothing is in memory). */
    static void alarmChanged(Context c, Runnable done) {
        Context app = c.getApplicationContext();
        COL_EXEC.execute(() -> {
            try {
                synchronized (RENDER) {
                    Renderer.Input in = lastIn;
                    Renderer.Out o = last;
                    if (in == null || o == null) { EXEC.execute(() -> run(app, "alarm changed", FETCH_NEVER)); return; }
                    in.alarmAt = nextAlarm(app, new Prefs(app), in.now, in.tz);
                    Renderer.column(app, in, o.geo, o);
                    WidgetUpdater.pushColumn(app, o, false);
                    Scheduler.scheduleTick(app);   // the tick 12 h before the (new) alarm
                    Log.i(Locator.TAG, "alarm changed: column redrawn, alarm " + (Double.isNaN(in.alarmAt) ? "none"
                            : new java.text.SimpleDateFormat("EEE HH:mm", Locale.US).format(new Date((long) (in.alarmAt * 3600000L)))
                            + (o.alarmMode == Renderer.ALARM_TAB ? " (tab)" : o.alarmMode == Renderer.ALARM_PILL ? " (pill)" : " (hidden)")));
                }
            } catch (Throwable t) {
                Log.e(Locator.TAG, "alarm redraw failed", t);
            } finally {
                if (done != null) done.run();
            }
        });
    }

    static boolean weatherStale(Context c) {
        Wx w = WeatherClient.cached(c);
        return w == null || System.currentTimeMillis() - w.fetchedAtMs > STALE_MS;
    }

    static void runAsync(Context c, String reason, int fetch, Runnable done) {
        Context app = c.getApplicationContext();
        EXEC.execute(() -> {
            try {
                run(app, reason, fetch);
            } finally {
                if (done != null) done.run();
            }
        });
    }

    static String run(Context c, String reason, int fetch) {
        synchronized (LOCK) {
            Grade.init(c);
            long t0 = System.currentTimeMillis();
            Prefs prefs = new Prefs(c);
            StringBuilder info = new StringBuilder();
            try {
                Renderer.Input in = new Renderer.Input();
                in.mode = prefs.mode();
                in.mock = prefs.mock();
                String forced = prefs.place();
                double lat, lon;
                String place;
                Wx wx = null;
                if (in.mock) {
                    boolean night = in.mode == Scene.MODE_NIGHT;
                    boolean sunny = prefs.scenario().equals("clear");
                    wx = MockData.build(night, sunny);
                    in.now = MockData.now(night);
                    lat = MockData.LAT;
                    lon = MockData.LON;
                    place = "Weststadt, Ulm";
                    in.mode = Scene.MODE_AUTO;
                    Places.Place p = Places.byId(c, forced.isEmpty() || forced.equals("auto") ? "ulm" : forced);
                    in.sky = p != null ? Places.handDrawn(c, p, 0) : Places.select(c, lat, lon, place, false);
                    info.append(sunny ? "MOCK sunny reference (sky9). " : "MOCK reference scenario. ");
                } else {
                    // ---- where
                    Places.Place fp = forced.isEmpty() || forced.equals("auto") ? null : Places.byId(c, forced);
                    if (fp != null) {
                        lat = fp.lat;
                        lon = fp.lon;
                        place = prefs.autoName().isEmpty() ? fp.label : prefs.autoName(); // debug: a display name to test long headers
                        info.append("forced place ").append(fp.id).append(". ");
                    } else if (forced.equals("auto")) {
                        lat = prefs.autoLat();
                        lon = prefs.autoLon();
                        place = prefs.autoName();
                        if (place.isEmpty()) place = placeName(c, prefs, lat, lon, fetch != FETCH_NEVER, fetch == FETCH_FORCE);
                        info.append("forced auto at ").append(String.format(Locale.US, "%.4f,%.4f", lat, lon)).append(". ");
                    } else {
                        double[] ll = liveLocation(c, prefs, fetch);
                        if (ll == null) {
                            Wx cw = WeatherClient.cached(c);
                            if (cw == null) throw new IllegalStateException("no location yet (permission granted?)");
                            ll = new double[]{cw.lat, cw.lon};
                            prefs.setError("no location fix; using the forecast's location");
                        }
                        lat = ll[0];
                        lon = ll[1];
                        place = placeName(c, prefs, lat, lon, fetch != FETCH_NEVER, fetch == FETCH_FORCE);
                    }
                    // ---- weather
                    wx = WeatherClient.cached(c);
                    boolean far = wx != null && Locator.distanceKm(wx.lat, wx.lon, lat, lon) > 3;
                    boolean stale = wx == null || System.currentTimeMillis() - wx.fetchedAtMs > STALE_MS || far;
                    boolean throttled = System.currentTimeMillis() - prefs.lastFetchAttempt() < 90_000L;
                    if (fetch == FETCH_FORCE || (fetch == FETCH_IF_STALE && stale && !throttled)) {
                        prefs.edit().putLong("lastFetchAttempt", System.currentTimeMillis()).apply();
                        try {
                            wx = WeatherClient.fetch(c, lat, lon);
                            prefs.edit().putLong("lastFetch", System.currentTimeMillis()).apply();
                            prefs.setError("");
                        } catch (Exception e) {
                            Log.w(Locator.TAG, "weather fetch failed", e);
                            prefs.setError("weather: " + e.getMessage());
                        }
                    }
                    if (wx != null && Locator.distanceKm(wx.lat, wx.lon, lat, lon) > 50) {
                        info.append("forecast is for another place (fetch pending). ");
                    }
                    in.now = System.currentTimeMillis() / 3600000.0;
                    int at = prefs.atMinutes();
                    if (at >= 0) {
                        TimeZone tz = TimeZone.getTimeZone(wx != null ? wx.tz : TimeZone.getDefault().getID());
                        Calendar cal = Calendar.getInstance(tz);
                        cal.set(Calendar.HOUR_OF_DAY, at / 60);
                        cal.set(Calendar.MINUTE, at % 60);
                        cal.set(Calendar.SECOND, 0);
                        in.now = cal.getTimeInMillis() / 3600000.0;
                    }
                    if (wx == null) {
                        wx = new Wx();
                        wx.lat = lat;
                        wx.lon = lon;
                        wx.tz = TimeZone.getDefault().getID();
                        wx.cloud = 100;
                        in.noData = true;
                    }
                    // an old forecast (offline, rate-limited): its "current" block is stale, so read "now" from the hourly data
                    if (!in.noData && wx.hasHourly() && in.now - wx.fetchedAtMs / 3600000.0 > 0.75
                            && in.now >= wx.hT[0] && in.now <= wx.hT[wx.hT.length - 1]) {
                        double t = wx.hourly(wx.hTemp, in.now), dFeel = wx.feels - wx.temp;
                        wx.temp = t;
                        wx.feels = t + dFeel;
                        wx.code = wx.hourlyCode(in.now);
                        wx.cloud = wx.hourly(wx.hCloud, in.now);
                        if (wx.hUV.length == wx.hT.length) wx.uv = wx.hourly(wx.hUV, in.now);
                        if (wx.hGust.length == wx.hT.length) wx.gust = wx.hourly(wx.hGust, in.now);
                        info.append("forecast is ").append(Math.round((in.now - wx.fetchedAtMs / 3600000.0) * 60)).append(" min old (now from hourly). ");
                    }
                    // the column's rain: ensemble precipitation (bars and "Rain %" from the same members), refreshed hourly
                    if (!in.noData) {
                        Ensemble ens = EnsembleClient.cached(c);
                        boolean eFar = ens != null && Locator.distanceKm(ens.lat, ens.lon, lat, lon) > 3;
                        boolean eStale = ens == null || System.currentTimeMillis() - ens.fetchedAtMs > EnsembleClient.STALE_MS || eFar;
                        boolean eThrottled = System.currentTimeMillis() - prefs.lastEnsAttempt() < 5 * 60_000L;
                        if (fetch != FETCH_NEVER && eStale && (!eThrottled || fetch == FETCH_FORCE && eFar)) {
                            prefs.edit().putLong("lastEnsAttempt", System.currentTimeMillis()).apply();
                            try {
                                ens = EnsembleClient.fetch(c, lat, lon);
                                eFar = false;
                            } catch (Exception e) {
                                Log.w(Locator.TAG, "ensemble fetch failed: " + e.getMessage() + " (keeping the last one)");
                            }
                        }
                        if (ens != null && Locator.distanceKm(ens.lat, ens.lon, lat, lon) <= 25 && ens.covers(in.now)) {
                            wx.ens = ens;
                            info.append("rain: ensemble ").append(ens.model).append(" (").append(ens.members).append(", ")
                                    .append(Math.round((System.currentTimeMillis() - ens.fetchedAtMs) / 60000.0)).append(" min old). ");
                        } else {
                            info.append("rain: deterministic. ");
                        }
                        wx.nowcast = prefs.nowcast();
                        wx.colNow = in.now;
                    }
                    String sc = prefs.scenario();
                    if (!sc.isEmpty() && !in.noData) {
                        wx.ens = null; // the scenario's synthetic precipitation drives the bars
                        Scenario.apply(wx, in.now, sc);
                        info.append("scenario ").append(sc).append(". ");
                    }
                    if (prefs.rainDebug() && !in.noData) { wx.colNow = in.now; logRain(wx, in.now); }   // what the column draws
                    if (fp != null) in.sky = Places.handDrawn(c, fp, 0);
                    else if (forced.equals("auto")) in.sky = AutoSkylines.get(c, lat, lon, place, fetch != FETCH_NEVER); // debug: always the automatic one
                    else in.sky = Places.select(c, lat, lon, place, fetch != FETCH_NEVER);
                }
                in.wx = wx;
                in.lat = lat;
                in.lon = lon;
                in.place = place;
                in.tz = TimeZone.getTimeZone(wx.tz);
                float k = c.getResources().getDisplayMetrics().density;
                in.colValues = prefs.colValues();
                in.alarmAt = nextAlarm(c, prefs, in.now, in.tz);
                if (!in.colValues) prefs.edit().putBoolean("colValues", false).apply(); // expired: back to bars
                Renderer.Out o;
                synchronized (RENDER) {
                o = Renderer.render(c, in, k);
                if (in.mock || prefs.atMinutes() >= 0) {
                    SimpleDateFormat hm = new SimpleDateFormat("HH:mm", Locale.US);
                    hm.setTimeZone(in.tz);
                    o.clockText = hm.format(new Date((long) (in.now * 3600000L)));
                }
                prefs.edit().putString("fixedClock", o.clockText == null ? "" : o.clockText).putBoolean("dayText", o.day).commit();
                WidgetUpdater.push(c, o);
                last = o;
                lastIn = in;
                }
                if (prefs.dump()) dump(c, o);
                long ms = System.currentTimeMillis() - t0;
                if (!Double.isNaN(in.alarmAt)) info.append("alarm ").append(new SimpleDateFormat("EEE HH:mm", Locale.US).format(new Date((long) (in.alarmAt * 3600000L))))
                        .append(o.alarmMode == Renderer.ALARM_TAB ? " (tab)" : o.alarmMode == Renderer.ALARM_PILL ? " (pill)" : " (hidden)").append(". ");
                info.append("skyline ").append(in.sky.source);
                if (in.sky.distanceKm >= 0) info.append(String.format(Locale.US, " (%.1f km)", in.sky.distanceKm));
                info.append(String.format(Locale.US, "; sun %.1f°, %s; %s; %d ms%s; reason %s",
                        o.el, o.night ? "night" : "day", place, ms,
                        o.day ? (Grade.lastCached ? " (grade cached)" : " (grade " + Grade.lastMs + " ms)") : "", reason));
                o.info = info.toString();
                prefs.edit().putLong("lastRender", System.currentTimeMillis()).putString("lastInfo", o.info).putLong("renderMs", ms).apply();
                Log.i(Locator.TAG, "rendered: " + o.info);
                return o.info;
            } catch (Throwable t) {
                Log.e(Locator.TAG, "update failed", t);
                prefs.setError("render: " + t);
                return "error: " + t;
            } finally {
                Scheduler.scheduleTick(c);
                Scheduler.scheduleMinute(c);
            }
        }
    }

    /** Debug: write the composited preview and the raw column to the app's external files dir (adb pull). */
    static void dump(Context c, Renderer.Out o) {
        try {
            java.io.File dir = c.getExternalFilesDir(null);
            if (dir == null) return;
            android.graphics.Bitmap p = Renderer.preview(c, o, o.clockText != null ? o.clockText
                    : new SimpleDateFormat("HH:mm", Locale.US).format(new Date()), new Prefs(c).colScrollDp());
            try (java.io.FileOutputStream f = new java.io.FileOutputStream(new java.io.File(dir, "preview.png"))) {
                p.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, f);
            }
            try (java.io.FileOutputStream f = new java.io.FileOutputStream(new java.io.File(dir, "column.png"))) {
                o.column.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, f);
            }
            p.recycle();
            java.io.File wj = new java.io.File(c.getFilesDir(), "weather.json");
            if (wj.exists()) try (java.io.FileOutputStream f = new java.io.FileOutputStream(new java.io.File(dir, "weather.json"))) {
                f.write(java.nio.file.Files.readAllBytes(wj.toPath()));
            }
        } catch (Exception e) {
            Log.w(Locator.TAG, "dump failed", e);
        }
    }

    /**
     * Debug (--es raindebug true): per hour for the next 24 h, at the hour's middle: which data drives the column
     * (det / blend / ens), the ensemble probability ("Rain %"), the rate the column draws and both sources' rates.
     */
    static void logRain(Wx w, double now) {
        StringBuilder b = new StringBuilder("rain debug (" + (w.ens != null ? "ensemble " + w.ens.model + ", " + w.ens.members + " members" : "deterministic")
                + (w.nowcast ? ", det first " + (int) Wx.NOWCAST_H + " h, cutoff < " + (int) Wx.ENS_MIN_PROB + " %" : ", nowcast off") + "):");
        java.util.Calendar cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone(w.tz));
        for (int h = 0; h < 24; h++) {
            double hs = Math.floor(now) + h, t = Math.max(now, hs) + (h == 0 ? 0 : 0.5);
            cal.setTimeInMillis((long) (hs * 3600000L));
            b.append(String.format(Locale.US, " %02d:%s %.0f%% col %.2f (det %.2f, ens %.2f)", cal.get(java.util.Calendar.HOUR_OF_DAY),
                    w.colSource(t), w.colProb(hs), w.colRate(t), w.mmh(t), w.ens != null && w.ens.covers(t) ? w.ens.rate(t) : 0.0));
        }
        Log.i(Locator.TAG, b.toString());
    }

    /** Live coarse location; a fresh fix when the last one is old or a forced refresh. Remembered in prefs. */
    static double[] liveLocation(Context c, Prefs prefs, int fetch) {
        Location l = Locator.lastKnown(c);
        long age = l == null ? Long.MAX_VALUE : System.currentTimeMillis() - l.getTime();
        if (fetch != FETCH_NEVER && (fetch == FETCH_FORCE || age > 30 * 60_000L)) {
            Location f = Locator.current(c, 15_000);
            if (f != null) l = f;
        }
        if (l != null) {
            SharedPreferences.Editor e = prefs.edit();
            Prefs.putDouble(e, "locLat", l.getLatitude());
            Prefs.putDouble(e, "locLon", l.getLongitude());
            e.putLong("locTime", l.getTime()).apply();
            return new double[]{l.getLatitude(), l.getLongitude()};
        }
        if (prefs.hasLoc()) return new double[]{prefs.locLat(), prefs.locLon()};
        return null;
    }

    /** Cached reverse geocode; looked up again when we have moved more than 400 m. */
    static String placeName(Context c, Prefs prefs, double lat, double lon, boolean allowNetwork, boolean force) {
        String n = prefs.placeName();
        boolean near = !n.isEmpty() && Locator.distanceKm(lat, lon, prefs.nameLat(), prefs.nameLon()) < 0.4;
        if ((near && !force) || !allowNetwork) return n;
        String fresh = Locator.placeName(c, lat, lon);
        if (fresh != null && !fresh.isEmpty()) {
            SharedPreferences.Editor e = prefs.edit().putString("placeName", fresh);
            Prefs.putDouble(e, "nameLat", lat);
            Prefs.putDouble(e, "nameLon", lon);
            e.apply();
            return fresh;
        }
        return n;
    }
}
