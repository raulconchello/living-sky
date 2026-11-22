package dev.livingsky;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.PersistableBundle;

/**
 * Refresh plan:
 *  - a non-wakeup alarm on every quarter hour (00/15/30/45, which includes midnight) re-renders from cached data,
 *    so the column starts at "now" and the date flips at midnight; non-wakeup = no battery cost while the screen is off;
 *    one extra tick exactly 12 h before the next alarm, when the column's alarm tab appears;
 *  - a periodic job (30 min, network) refreshes the forecast; one-shot jobs fetch after taps, boot, moves;
 *  - the clock itself is a TextClock and needs nothing.
 */
final class Scheduler {
    private Scheduler() {}

    static final int JOB_PERIODIC = 1, JOB_NOW = 2;

    static void ensure(Context c) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js.getPendingJob(JOB_PERIODIC) == null) {
            js.schedule(new JobInfo.Builder(JOB_PERIODIC, new ComponentName(c, UpdateJob.class))
                    .setPeriodic(30 * 60_000L, 10 * 60_000L)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true)
                    .build());
        }
        scheduleTick(c);
        Locator.listenPassive(c);
    }

    static void cancelAll(Context c) {
        c.getSystemService(JobScheduler.class).cancelAll();
        c.getSystemService(AlarmManager.class).cancel(tickIntent(c));
        c.getSystemService(AlarmManager.class).cancel(minuteIntent(c));
    }

    /** One-off update job (runs as soon as the network is available). */
    static void fetchSoon(Context c, boolean force) {
        PersistableBundle extras = new PersistableBundle();
        extras.putInt("fetch", force ? Updater.FETCH_FORCE : Updater.FETCH_IF_STALE);
        c.getSystemService(JobScheduler.class).schedule(new JobInfo.Builder(JOB_NOW, new ComponentName(c, UpdateJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setOverrideDeadline(60_000L)
                .setExtras(extras)
                .build());
    }

    static PendingIntent tickIntent(Context c) {
        Intent i = new Intent(c, TickReceiver.class).setAction(TickReceiver.ACTION_TICK);
        return PendingIntent.getBroadcast(c, 1, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static PendingIntent minuteIntent(Context c) {
        Intent i = new Intent(c, TickReceiver.class).setAction(TickReceiver.ACTION_MINUTE);
        return PendingIntent.getBroadcast(c, 4, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** Next minute boundary, exact and non-wakeup (the bitmap clock). */
    static void scheduleMinute(Context c) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (WidgetUpdater.ids(c).length == 0 || !am.canScheduleExactAlarms()) { am.cancel(minuteIntent(c)); return; }
        long now = System.currentTimeMillis();
        am.setExact(AlarmManager.RTC, (now / 60_000L + 1) * 60_000L, minuteIntent(c));
    }

    /** Next quarter hour (+3 s). Exact when allowed, otherwise a short window. */
    static void scheduleTick(Context c) {
        if (WidgetUpdater.ids(c).length == 0) return;
        AlarmManager am = c.getSystemService(AlarmManager.class);
        long now = System.currentTimeMillis();
        long q = 15 * 60_000L;
        long at = (now / q + 1) * q + 3_000L;
        // the alarm tab appears exactly 12 h before the alarm (an alarm off the quarter hours would otherwise show it
        // up to 15 min late); the tab -> pill handover happens on a full hour, which is always a quarter tick
        double al = Updater.nextAlarm(c, new Prefs(c), now / 3600000.0, java.util.TimeZone.getDefault());
        if (!Double.isNaN(al)) {
            long t12 = Math.round(al * 3600000.0) - Math.round(Renderer.AL_TAB_H * 3600000.0) + 1_000L;
            if (t12 > now && t12 < at) at = t12;
        }
        PendingIntent pi = tickIntent(c);
        if (am.canScheduleExactAlarms()) am.setExact(AlarmManager.RTC, at, pi);
        else am.setWindow(AlarmManager.RTC, at, 60_000L, pi);
    }
}
