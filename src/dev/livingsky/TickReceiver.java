package dev.livingsky;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.location.LocationManager;

/** Quarter-hour alarm, tap-to-refresh and passive location updates. */
public class TickReceiver extends BroadcastReceiver {
    static final String ACTION_TICK = "dev.livingsky.TICK";
    static final String ACTION_REFRESH = "dev.livingsky.REFRESH";
    static final String ACTION_LOCATION = "dev.livingsky.LOCATION";
    static final String ACTION_MINUTE = "dev.livingsky.MINUTE";
    static final String ACTION_TOGGLE_COL = "dev.livingsky.TOGGLE_COL";
    static final String ACTION_COL_REVERT = "dev.livingsky.COL_REVERT";

    @Override
    public void onReceive(Context c, Intent i) {
        String a = i.getAction();
        if (a == null) return;
        if (ACTION_MINUTE.equals(a)) { Clock.tick(c); return; } // ~20 ms, on the main thread
        final PendingResult pr = goAsync();
        switch (a) {
            case ACTION_TOGGLE_COL: // tap on the column: bars <-> rain probability / RH / UV
                ColumnMode.toggle(c, pr::finish);
                break;
            case ACTION_COL_REVERT:
                ColumnMode.revert(c, pr::finish);
                break;
            case ACTION_REFRESH:
                // user tap: fetch right away (location + weather), fall back to a job if this takes too long
                Updater.runAsync(c, "tap", Updater.FETCH_FORCE, pr::finish);
                break;
            case ACTION_LOCATION: {
                Location l = i.getParcelableExtra(LocationManager.KEY_LOCATION_CHANGED, Location.class);
                Prefs p = new Prefs(c);
                boolean moved = l != null && (!p.hasLoc()
                        || Locator.distanceKm(l.getLatitude(), l.getLongitude(), p.locLat(), p.locLon()) > 1.0);
                if (moved) {
                    Updater.runAsync(c, "moved", Updater.FETCH_NEVER, pr::finish);
                    Scheduler.fetchSoon(c, false);
                } else pr.finish();
                break;
            }
            default: // tick
                Updater.runAsync(c, "tick", Updater.FETCH_NEVER, pr::finish);
                if (Updater.weatherStale(c)) Scheduler.fetchSoon(c, false);
        }
    }
}
