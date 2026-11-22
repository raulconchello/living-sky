package dev.livingsky;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.os.Bundle;

/** The home-screen widget provider. */
public class LivingSkyWidget extends AppWidgetProvider {
    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        Scheduler.ensure(c);
        final PendingResult pr = goAsync();
        Updater.runAsync(c, "onUpdate", Updater.FETCH_NEVER, pr::finish);
        if (Updater.weatherStale(c)) Scheduler.fetchSoon(c, false);
    }

    @Override
    public void onAppWidgetOptionsChanged(Context c, AppWidgetManager m, int id, Bundle o) {
        final PendingResult pr = goAsync();
        Updater.runAsync(c, "options", Updater.FETCH_NEVER, pr::finish);
    }

    @Override
    public void onEnabled(Context c) {
        Scheduler.ensure(c);
        Scheduler.fetchSoon(c, false);
    }

    @Override
    public void onDisabled(Context c) {
        Scheduler.cancelAll(c);
    }
}
