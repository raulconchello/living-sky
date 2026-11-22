package dev.livingsky;

import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;
import android.util.TypedValue;
import android.widget.RemoteViews;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * The widget clock in Inter ExtraLight. Launchers inflate RemoteViews in a restricted context, where TextView ignores
 * fonts bundled with the app (Nova showed the system font), so the clock is a small bitmap repainted every minute:
 * an exact, non-wakeup alarm at each minute boundary sends a partial update that replaces only this image (no full
 * re-render). While the screen is off nothing runs; the pending alarm fires as soon as the phone wakes.
 */
final class Clock {
    private Clock() {}

    /** Bitmap clock when exact alarms are allowed (they are: appops SCHEDULE_EXACT_ALARM allow), else TextClock. */
    static boolean enabled(Context c) {
        return c.getSystemService(android.app.AlarmManager.class).canScheduleExactAlarms();
    }

    /** Fixed text in reference / time-travel mode, else the current HH:mm (device time zone, like TextClock). */
    static String text(Context c, String fixed) {
        if (fixed != null) return fixed;
        // the alarm can fire a few ms before the boundary: round to the nearest second first
        long now = System.currentTimeMillis() + 1500;
        return new SimpleDateFormat("HH:mm", Locale.US).format(new Date(now));
    }

    static void apply(Context c, RemoteViews rv, float k, String fixed) {
        String t = text(c, fixed);
        Bitmap b = Renderer.clock(c, t, k, new Prefs(c).dayText());
        b.setDensity(c.getResources().getDisplayMetrics().densityDpi);
        int PX = TypedValue.COMPLEX_UNIT_PX;
        rv.setImageViewBitmap(R.id.clock_img, b);
        rv.setViewLayoutWidth(R.id.clock_img, b.getWidth(), PX);
        rv.setViewLayoutHeight(R.id.clock_img, b.getHeight(), PX);
        rv.setViewLayoutMargin(R.id.clock_img, RemoteViews.MARGIN_LEFT, Math.round(Renderer.CLOCK_X * k), PX);
        rv.setViewLayoutMargin(R.id.clock_img, RemoteViews.MARGIN_TOP, Math.round(Renderer.CLOCK_Y * k), PX);
        rv.setContentDescription(R.id.clock_tap, t);
    }

    /** Minute tick: repaint only the clock image on every placed widget. */
    static void tick(Context c) {
        int[] ids = WidgetUpdater.ids(c);
        if (ids.length == 0 || !enabled(c)) return;
        try {
            Prefs p = new Prefs(c);
            String fixed = p.fixedClock();
            RemoteViews rv = new RemoteViews(c.getPackageName(), R.layout.widget);
            apply(c, rv, c.getResources().getDisplayMetrics().density, fixed.isEmpty() ? null : fixed);
            AppWidgetManager.getInstance(c).partiallyUpdateAppWidget(ids, rv);
        } catch (Exception e) {
            Log.w(Locator.TAG, "clock tick failed", e);
        } finally {
            Scheduler.scheduleMinute(c);
        }
    }
}
