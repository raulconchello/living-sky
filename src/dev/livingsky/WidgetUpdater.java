package dev.livingsky;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Paint;
import android.provider.AlarmClock;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.widget.RemoteViews;

/**
 * Builds the RemoteViews: one card bitmap, two stacked TextClocks (the clock ticks in the launcher without any
 * re-render) and a ListView of bitmap slices for the scrollable 48 h column (RemoteCollectionItems, API 31+).
 */
final class WidgetUpdater {
    private WidgetUpdater() {}

    static final String DESKCLOCK = "com.google.android.deskclock";

    static int[] ids(Context c) {
        return AppWidgetManager.getInstance(c).getAppWidgetIds(new ComponentName(c, LivingSkyWidget.class));
    }

    static PendingIntent refreshIntent(Context c) {
        Intent i = new Intent(c, TickReceiver.class).setAction(TickReceiver.ACTION_REFRESH);
        return PendingIntent.getBroadcast(c, 2, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static PendingIntent clockIntent(Context c) {
        Intent i = c.getPackageManager().getLaunchIntentForPackage(DESKCLOCK);
        if (i == null) i = new Intent(AlarmClock.ACTION_SHOW_ALARMS);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(c, 1, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** The alarm tab: the alarm's own "show" intent (opens it in Clock), else the same intent as the big clock. */
    static PendingIntent alarmIntent(Context c) {
        try {
            android.app.AlarmManager.AlarmClockInfo a = c.getSystemService(android.app.AlarmManager.class).getNextAlarmClock();
            if (a != null && a.getShowIntent() != null) return a.getShowIntent();
        } catch (Exception ignored) { }
        return clockIntent(c);
    }

    static PendingIntent toggleTemplate(Context c) {
        Intent i = new Intent(c, TickReceiver.class).setAction(TickReceiver.ACTION_TOGGLE_COL);
        // mutable: the rows' fill-in intents are merged into it
        return PendingIntent.getBroadcast(c, 5, i, PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** The timeline column: list of slices of the tall 72 h bitmap, tap template, values legend. */
    static void columnInto(Context c, RemoteViews rv, Renderer.Out o) {
        Renderer.Geo g = o.geo;
        float k = g.k;
        DisplayMetrics dm = c.getResources().getDisplayMetrics();
        int PX = TypedValue.COMPLEX_UNIT_PX;
        rv.setViewLayoutMargin(R.id.col, RemoteViews.MARGIN_LEFT, g.colL, PX);
        rv.setViewLayoutMargin(R.id.col, RemoteViews.MARGIN_TOP, g.colT, PX);
        rv.setViewLayoutWidth(R.id.col, g.colW, PX);
        rv.setViewLayoutHeight(R.id.col, g.colH, PX);
        rv.setViewLayoutHeight(R.id.fade, Math.round(22 * k), PX);
        rv.setViewLayoutHeight(R.id.col_legend, Math.round(22 * k), PX);
        if (o.colValues) {
            Bitmap lg = Renderer.legend(c, g);
            lg.setDensity(dm.densityDpi);
            rv.setImageViewBitmap(R.id.col_legend, lg);
        }
        rv.setViewVisibility(R.id.col_legend, o.colValues ? android.view.View.VISIBLE : android.view.View.GONE);
        if (o.alarmTab != null) {
            o.alarmTab.setDensity(dm.densityDpi);
            rv.setImageViewBitmap(R.id.col_alarm, o.alarmTab);
            rv.setViewLayoutWidth(R.id.col_alarm, o.alarmTab.getWidth(), PX);
            rv.setViewLayoutHeight(R.id.col_alarm, o.alarmTab.getHeight(), PX);
            rv.setContentDescription(R.id.col_alarm, "Next alarm " + o.alarmText + ". Opens Clock.");
            rv.setOnClickPendingIntent(R.id.col_alarm, alarmIntent(c));
        }
        rv.setViewVisibility(R.id.col_alarm, o.alarmTab != null ? android.view.View.VISIBLE : android.view.View.GONE);
        RemoteViews.RemoteCollectionItems.Builder items = new RemoteViews.RemoteCollectionItems.Builder()
                .setHasStableIds(true).setViewTypeCount(1);
        int[] e = o.rowEdges;
        for (int i = 0; i + 1 < e.length; i++) {
            int h = e[i + 1] - e[i];
            Bitmap slice = Bitmap.createBitmap(o.column, 0, e[i], o.column.getWidth(), h);
            slice.setDensity(dm.densityDpi);
            RemoteViews row = new RemoteViews(c.getPackageName(), R.layout.col_row);
            row.setImageViewBitmap(R.id.row, slice);
            row.setViewLayoutHeight(R.id.row, h, PX);
            row.setOnClickFillInIntent(R.id.row, new Intent().putExtra("row", i));
            items.addItem(o.rowIds[i], row);
        }
        rv.setRemoteAdapter(R.id.list, items.build());
        rv.setPendingIntentTemplate(R.id.list, toggleTemplate(c));
    }

    /** Column-only partial update (tap toggle): no card re-render; keeps the list's scroll unless asked. */
    static boolean PARTIAL_TOGGLE = false;

    static void pushColumn(Context c, Renderer.Out o, boolean scrollToNow) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        if (PARTIAL_TOGGLE) {
            RemoteViews rv = new RemoteViews(c.getPackageName(), R.layout.widget);
            columnInto(c, rv, o);
            if (scrollToNow) rv.setScrollPosition(R.id.list, 0);
            m.partiallyUpdateAppWidget(ids(c), rv);
        } else {
            // same layout and stable row ids: the host re-applies onto the existing list and keeps its adapter
            for (int id : ids(c)) m.updateAppWidget(id, build(c, o, scrollToNow));
        }
    }

    static void push(Context c, Renderer.Out o) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        for (int id : ids(c)) m.updateAppWidget(id, build(c, o));
    }

    static RemoteViews build(Context c, Renderer.Out o) { return build(c, o, true); }

    static RemoteViews build(Context c, Renderer.Out o, boolean scrollToNow) {
        Prefs prefs = new Prefs(c);
        Renderer.Geo g = o.geo;
        float k = g.k;
        DisplayMetrics dm = c.getResources().getDisplayMetrics();
        int PX = TypedValue.COMPLEX_UNIT_PX;
        RemoteViews rv = new RemoteViews(c.getPackageName(), R.layout.widget);

        // card: exact pixel size, centred in the cell (calibration shifts it)
        rv.setViewLayoutWidth(R.id.card, g.cardW, PX);
        rv.setViewLayoutHeight(R.id.card, g.cardH, PX);
        rv.setViewLayoutMargin(R.id.card, RemoteViews.MARGIN_LEFT, Math.max(0, prefs.cardDx()) * 2, PX);
        rv.setViewLayoutMargin(R.id.card, RemoteViews.MARGIN_RIGHT, Math.max(0, -prefs.cardDx()) * 2, PX);
        rv.setViewLayoutMargin(R.id.card, RemoteViews.MARGIN_TOP, Math.max(0, prefs.cardDy()) * 2, PX);
        rv.setViewLayoutMargin(R.id.card, RemoteViews.MARGIN_BOTTOM, Math.max(0, -prefs.cardDy()) * 2, PX);
        o.card.setDensity(dm.densityDpi);
        rv.setImageViewBitmap(R.id.bg, o.card);
        rv.setOnClickPendingIntent(R.id.bg, refreshIntent(c));

        // clock: a bitmap in Inter ExtraLight, repainted every minute (exact alarm) by a partial update; the TextClocks
        // (system font, launchers ignore app fonts) are the fallback when exact alarms are not allowed
        boolean bitmapClock = Clock.enabled(c);
        rv.setViewVisibility(R.id.clock_img, bitmapClock ? android.view.View.VISIBLE : android.view.View.GONE);
        rv.setViewVisibility(R.id.clock_tap, bitmapClock ? android.view.View.VISIBLE : android.view.View.GONE);
        rv.setViewVisibility(R.id.clock, bitmapClock ? android.view.View.GONE : android.view.View.VISIBLE);
        rv.setViewVisibility(R.id.clock_glow, bitmapClock ? android.view.View.GONE : android.view.View.VISIBLE);
        if (bitmapClock) {
            Clock.apply(c, rv, k, o.clockText);
            rv.setViewLayoutMargin(R.id.clock_tap, RemoteViews.MARGIN_LEFT, Math.round(13 * k), PX);
            rv.setViewLayoutMargin(R.id.clock_tap, RemoteViews.MARGIN_TOP, Math.round(22 * k), PX);
            rv.setOnClickPendingIntent(R.id.clock_tap, clockIntent(c));
        }
        // TextClock fallback: pen origin at x 13 dp, baseline where Chromium puts it (top 22, 54 dp, line-height 1)
        Paint p = TextKit.paint(c, 200, 54 * k, 0xffffffff, Renderer.FEAT);
        Paint.FontMetricsInt fm = p.getFontMetricsInt();
        int pad = Math.round(16 * k);
        float left = 13 * k - pad + prefs.clockDx();
        float top = o.clockBaselineDp * k + fm.ascent + prefs.clockDy();
        for (int id : new int[]{R.id.clock_glow, R.id.clock}) {
            rv.setViewPadding(id, pad, 0, pad, 0);
            rv.setViewLayoutMargin(id, RemoteViews.MARGIN_LEFT, Math.round(left), PX);
            rv.setViewLayoutMargin(id, RemoteViews.MARGIN_TOP, Math.round(top), PX);
            CharSequence fmt = o.clockText != null ? "'" + o.clockText + "'" : "HH:mm";
            rv.setCharSequence(id, "setFormat24Hour", fmt);
            rv.setCharSequence(id, "setFormat12Hour", fmt);
        }
        rv.setOnClickPendingIntent(R.id.clock, clockIntent(c));

        columnInto(c, rv, o);
        if (scrollToNow) rv.setScrollPosition(R.id.list, 0); // periodic updates bring the column back to "now"
        return rv;
    }
}
