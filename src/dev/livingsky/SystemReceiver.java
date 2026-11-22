package dev.livingsky;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Boot, time / time-zone / locale change, app update: reschedule and re-render. */
public class SystemReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        if (WidgetUpdater.ids(c).length == 0) return;
        if (android.app.AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED.equals(i.getAction())) {
            android.util.Log.i(Locator.TAG, "NEXT_ALARM_CLOCK_CHANGED received");
            final PendingResult pr = goAsync();
            Updater.alarmChanged(c, pr::finish);
            return;
        }
        Scheduler.ensure(c);
        final PendingResult pr = goAsync();
        Updater.runAsync(c, String.valueOf(i.getAction()).replace("android.intent.action.", ""), Updater.FETCH_NEVER, pr::finish);
        Scheduler.fetchSoon(c, false);
    }
}
