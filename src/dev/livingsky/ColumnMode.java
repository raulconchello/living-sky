package dev.livingsky;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

/**
 * The column's tap toggle: bars (default) <-> values (rain probability, RH, UV). Only the column is re-rendered
 * (from the cached sky band) and pushed with a partial update that keeps the list's scroll position; values mode
 * reverts to bars by itself after {@link #VALUES_MS} (non-wakeup: if the screen is off it happens on wake).
 */
final class ColumnMode {
    private ColumnMode() {}

    static final long VALUES_MS = 60_000L;

    static PendingIntent revertIntent(Context c) {
        Intent i = new Intent(c, TickReceiver.class).setAction(TickReceiver.ACTION_COL_REVERT);
        return PendingIntent.getBroadcast(c, 6, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static void toggle(Context c, Runnable done) {
        Prefs p = new Prefs(c);
        boolean values = !p.colValues();
        set(c, values, values ? VALUES_MS : 0);
        Updater.runColumn(c, values, false, done);
    }

    static void revert(Context c, Runnable done) {
        set(c, false, 0);
        Updater.runColumn(c, false, true, done);
    }

    /** Sets the mode (and its expiry) and arms / cancels the revert alarm. */
    static void set(Context c, boolean values, long forMs) {
        new Prefs(c).edit().putBoolean("colValues", values).putLong("colUntil", values ? System.currentTimeMillis() + forMs : 0).commit();
        AlarmManager am = c.getSystemService(AlarmManager.class);
        PendingIntent pi = revertIntent(c);
        am.cancel(pi);
        if (values) {
            long at = SystemClock.elapsedRealtime() + forMs;
            if (am.canScheduleExactAlarms()) am.setExact(AlarmManager.ELAPSED_REALTIME, at, pi);
            else am.set(AlarmManager.ELAPSED_REALTIME, at, pi);
        }
    }
}
