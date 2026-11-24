package dev.livingsky;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * adb-only debug commands (the receiver requires android.permission.DUMP, which the shell has), e.g.
 *   adb shell am broadcast -a dev.livingsky.DEBUG -n dev.livingsky/.DebugReceiver --es place munich --es mode night --es cmd render
 * extras: place ("" live | auto | id), lat, lon, name (auto), mode (auto|day|night), mock (true|false),
 *         scenario (""|clear|partly|overcast|rain|heavy|snow|sleet|fog|thunder), at ("HH:mm" | "" for real time),
 *         cardDx, cardDy (int px), clockDx, clockDy (float px), dump (true: write preview.png / column.png),
 *         reset (true: clear all overrides),
 *         raindebug (true: log the timeline per hour with each hour's source), nowcast (off: the ensemble from now on),
 *         moonage (days since new moon, e.g. 7.4 = first quarter; none = real), clouds (old: previous cloud style), alarm ("Mon 00:30" | "08:45" | none | real: mock the next alarm),
 *         colmode (values | bars: the column's tap mode, values held 10 min), colscroll (dp, preview dumps only),
 *         cmd (render | refresh | clearauto | remote | hosted | toggle).
 */
public class DebugReceiver extends BroadcastReceiver {
    /** adb drops empty strings, so "none" / "live" also mean "no override". */
    static String none(String v) {
        return v == null || v.equals("none") || v.equals("live") || v.equals("''") ? "" : v;
    }

    @Override
    public void onReceive(Context c, Intent i) {
        SharedPreferences.Editor e = new Prefs(c).edit();
        if (i.getBooleanExtra("reset", false)) {
            e.remove("place").remove("mode").remove("mock").remove("scenario").remove("atMinutes").remove("autoName")
             .remove("colValues").remove("colUntil").remove("colScroll").remove("alarm").remove("nowcastOff").remove("moonAge").remove("cloudsOld").remove("textFx").remove("strip").remove("todayMark");
        }
        if (i.hasExtra("place")) e.putString("place", none(i.getStringExtra("place")));
        if (i.hasExtra("lat")) Prefs.putDouble(e, "autoLat", Double.parseDouble(i.getStringExtra("lat")));
        if (i.hasExtra("lon")) Prefs.putDouble(e, "autoLon", Double.parseDouble(i.getStringExtra("lon")));
        if (i.hasExtra("name")) e.putString("autoName", none(i.getStringExtra("name")));
        if (i.hasExtra("alarm")) { // "real" / "live" -> the phone's next alarm; "none"; "Mon 00:30"; "08:45"
            String a = i.getStringExtra("alarm");
            if (a == null || a.equals("real") || a.equals("live")) e.remove("alarm"); else e.putString("alarm", a);
        }
        if (i.hasExtra("colscroll")) e.putFloat("colScroll", Float.parseFloat(i.getStringExtra("colscroll")));
        if (i.hasExtra("mode")) {
            String m = i.getStringExtra("mode");
            e.putInt("mode", "day".equals(m) ? 1 : "night".equals(m) ? 2 : 0);
        }
        if (i.hasExtra("mock")) e.putBoolean("mock", "true".equals(i.getStringExtra("mock")) || i.getBooleanExtra("mock", false));
        if (i.hasExtra("scenario")) e.putString("scenario", none(i.getStringExtra("scenario")));
        if (i.hasExtra("at")) {
            String at = i.getStringExtra("at");
            if (at == null || !at.matches("\\d{1,2}:\\d{2}")) e.remove("atMinutes"); // "now", "live", "" -> real time
            else {
                String[] p = at.split(":");
                e.putInt("atMinutes", Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]));
            }
        }
        if (i.hasExtra("raindebug")) e.putBoolean("rainDebug", "true".equals(i.getStringExtra("raindebug")));
        if (i.hasExtra("nowcast")) e.putBoolean("nowcastOff", "off".equals(i.getStringExtra("nowcast")));
        if (i.hasExtra("clouds")) e.putBoolean("cloudsOld", "old".equals(i.getStringExtra("clouds")));
        if (i.hasExtra("todaymark")) { // today's mark in the strip: new (default, the tinted column) | old (the bold name)
            String tm = none(i.getStringExtra("todaymark"));
            if (tm.equals("old")) e.putString("todayMark", tm); else e.remove("todayMark");
        }
        if (i.hasExtra("strip")) { // the day strip: new (default, today + 4 days) | old (the next 4 days)
            String sl = none(i.getStringExtra("strip"));
            if (sl.isEmpty() || sl.equals("new") || sl.equals("now")) e.remove("strip"); else e.putString("strip", sl);
        }
        if (i.hasExtra("textfx")) { // the card text's shadows: new (default) | old (previous look) | mask (measuring)
            String tf = none(i.getStringExtra("textfx"));
            if (tf.isEmpty() || tf.equals("new") || tf.equals("now")) e.remove("textFx"); else e.putString("textFx", tf);
        }
        if (i.hasExtra("moonage")) { // the moon's age in days (0 new .. 14.8 full .. 29.5), or none / live = real
            String ma = none(i.getStringExtra("moonage"));
            if (ma.isEmpty() || ma.equals("real")) e.remove("moonAge"); else e.putFloat("moonAge", Float.parseFloat(ma));
        }
        if (i.hasExtra("dump")) e.putBoolean("dump", "true".equals(i.getStringExtra("dump")));
        if (i.hasExtra("cardDx")) e.putInt("cardDx", Integer.parseInt(i.getStringExtra("cardDx")));
        if (i.hasExtra("cardDy")) e.putInt("cardDy", Integer.parseInt(i.getStringExtra("cardDy")));
        if (i.hasExtra("clockDx")) e.putFloat("clockDx", Float.parseFloat(i.getStringExtra("clockDx")));
        if (i.hasExtra("clockDy")) e.putFloat("clockDy", Float.parseFloat(i.getStringExtra("clockDy")));
        e.commit();
        e.commit();
        if (i.hasExtra("colmode")) ColumnMode.set(c, "values".equals(i.getStringExtra("colmode")), 10 * 60_000L); // debug: 10 min
        String cmd = i.getStringExtra("cmd");
        if ("clearauto".equals(cmd)) AutoSkylines.clearCache(c);
        if ("toggle".equals(cmd)) { // same path as a tap on the column
            final PendingResult pr = goAsync();
            ColumnMode.toggle(c, pr::finish);
            return;
        }
        if ("hosted".equals(cmd)) { // full system path through a temporary private AppWidgetHost
            final PendingResult pr = goAsync();
            Updater.runAsync(c, "adb-hosted", Updater.FETCH_NEVER, () -> new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                try {
                    // release the broadcast at once: a held debug broadcast would queue the test's own tap broadcast
                    RemoteTest.runHosted(c.getApplicationContext(), i.getIntExtra("w", 1038), i.getIntExtra("h", 548), () -> { });
                    pr.finish();
                } catch (Throwable t) {
                    Log.e(Locator.TAG, "hosted test failed", t);
                    pr.finish();
                }
            }));
            return;
        }
        if ("remote".equals(cmd)) { // inflate the RemoteViews offscreen (as a launcher would) and save remote.png
            final PendingResult pr = goAsync();
            Updater.runAsync(c, "adb-remote", Updater.FETCH_NEVER, () -> new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                try {
                    RemoteTest.run(c.getApplicationContext(), i.getIntExtra("w", 1038), i.getIntExtra("h", 548), pr::finish);
                } catch (Throwable t) {
                    Log.e(Locator.TAG, "remote test failed", t);
                    pr.finish();
                }
            }));
            return;
        }
        int fetch = "refresh".equals(cmd) ? Updater.FETCH_FORCE : Updater.FETCH_NEVER;
        final PendingResult pr = goAsync();
        Updater.runAsync(c, "adb", fetch, () -> {
            Log.i(Locator.TAG, "debug: " + new Prefs(c).lastInfo() + " | error: " + new Prefs(c).lastError());
            pr.finish();
        });
    }
}
