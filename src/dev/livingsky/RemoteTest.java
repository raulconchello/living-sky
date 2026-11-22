package dev.livingsky;

import android.app.Presentation;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ListView;
import android.widget.RemoteViews;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;

/**
 * Debug: shows the widget's real RemoteViews (exactly what the launcher inflates) in a window on a private,
 * offscreen VirtualDisplay of the cell size, and captures remote.png (top of the column) and remote_scrolled.png
 * (column scrolled ~40 h down). Verifies TextClock + bundled font, margins and the ListView of slices without
 * touching the home screen.
 */
final class RemoteTest {
    private RemoteTest() {}

    static void run(Context c, int w, int h, Runnable done) {
        Renderer.Out o = Updater.last;
        if (o == null) { Log.e(Locator.TAG, "remote test: nothing rendered yet"); done.run(); return; }
        Handler ui = new Handler(Looper.getMainLooper());
        ImageReader ir = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3);
        DisplayManager dm = c.getSystemService(DisplayManager.class);
        int dpi = c.getResources().getDisplayMetrics().densityDpi;
        VirtualDisplay vd = dm.createVirtualDisplay("living-sky-test", w, h, dpi, ir.getSurface(), 0);
        Presentation pres = new Presentation(c, vd.getDisplay());
        FrameLayout host = new FrameLayout(pres.getContext());
        host.setBackgroundColor(0xff6a8a5a);
        RemoteViews rv = WidgetUpdater.build(c, o);
        View v = rv.apply(pres.getContext(), host);
        host.addView(v, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        pres.setContentView(host);
        pres.show();
        ListView list = v.findViewById(R.id.list);
        TextView clock = v.findViewById(R.id.clock);
        ui.postDelayed(() -> {
            save(c, ir, "remote.png");
            Log.i(Locator.TAG, "remote test: clock '" + clock.getText() + "' at " + pos(clock) + " " + clock.getWidth() + "x" + clock.getHeight()
                    + ", list " + pos(list) + " " + list.getWidth() + "x" + list.getHeight() + " items=" + (list.getAdapter() == null ? -1 : list.getAdapter().getCount())
                    + " children=" + list.getChildCount());
            View tab = v.findViewById(R.id.col_alarm);
            Log.i(Locator.TAG, "remote test: alarm tab " + (tab.getVisibility() == View.VISIBLE ? "visible" : "gone") + " at " + pos(tab)
                    + " " + tab.getWidth() + "x" + tab.getHeight() + ", clickable " + tab.isClickable() + ", click handler " + tab.hasOnClickListeners()
                    + ", '" + tab.getContentDescription() + "'; column " + pos(v.findViewById(R.id.col)) + " " + v.findViewById(R.id.col).getWidth()
                    + "x" + v.findViewById(R.id.col).getHeight());
            list.setSelectionFromTop(40, 0);
            ui.postDelayed(() -> {
                save(c, ir, "remote_scrolled.png");
                Log.i(Locator.TAG, "remote test: scrolled, first visible=" + list.getFirstVisiblePosition() + " children=" + list.getChildCount());
                pres.dismiss();
                vd.release();
                ir.close();
                done.run();
            }, 1500);
        }, 2500);
    }

    /**
     * Like {@link #run} but through the real system path: a private AppWidgetHost (needs
     * `adb shell cmd appwidget grantbind --package dev.livingsky`) binds a temporary instance of the widget, so the
     * RemoteViews travel through AppWidgetService exactly as they do to Nova. The instance is deleted afterwards.
     */
    static void runHosted(Context c, int w, int h, Runnable done) {
        Handler ui = new Handler(Looper.getMainLooper());
        android.appwidget.AppWidgetManager m = android.appwidget.AppWidgetManager.getInstance(c);
        android.appwidget.AppWidgetHost awh = new android.appwidget.AppWidgetHost(c, 4242);
        awh.startListening();
        int id = awh.allocateAppWidgetId();
        if (!m.bindAppWidgetIdIfAllowed(id, new android.content.ComponentName(c, LivingSkyWidget.class))) {
            Log.e(Locator.TAG, "hosted test: bind not allowed (adb shell cmd appwidget grantbind --package dev.livingsky)");
            awh.deleteAppWidgetId(id);
            awh.stopListening();
            done.run();
            return;
        }
        ImageReader ir = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3);
        DisplayManager dm = c.getSystemService(DisplayManager.class);
        VirtualDisplay vd = dm.createVirtualDisplay("living-sky-host", w, h, c.getResources().getDisplayMetrics().densityDpi, ir.getSurface(), 0);
        Presentation pres = new Presentation(c, vd.getDisplay());
        FrameLayout frame = new FrameLayout(pres.getContext());
        frame.setBackgroundColor(0xff6a8a5a);
        android.appwidget.AppWidgetHostView hv = awh.createView(pres.getContext(), id, m.getAppWidgetInfo(id));
        hv.setPadding(0, 0, 0, 0);
        frame.addView(hv, new FrameLayout.LayoutParams(w, h));
        pres.setContentView(frame);
        pres.show();
        Renderer.Out o = Updater.last;
        if (o != null) m.updateAppWidget(id, WidgetUpdater.build(c, o));
        ui.postDelayed(() -> {
            save(c, ir, "hosted.png");
            ListView list = hv.findViewById(R.id.list);
            Log.i(Locator.TAG, "hosted test: list " + (list == null ? "missing" : pos(list) + " adapter=" + list.getAdapter()
                    + " items=" + (list.getAdapter() == null ? -1 : list.getAdapter().getCount()) + " children=" + list.getChildCount()));
            if (list != null) list.smoothScrollToPositionFromTop(30, 0, 0);
            ui.postDelayed(() -> {
                save(c, ir, "hosted_scrolled.png");
                int before = list == null ? -1 : list.getFirstVisiblePosition();
                int topBefore = list == null || list.getChildCount() == 0 ? 0 : list.getChildAt(0).getTop();
                if (list != null) Log.i(Locator.TAG, "hosted test: scrolled, first visible=" + before + " top=" + topBefore);
                // tap a row, as a finger would: the fill-in intent toggles values mode; the list must keep its position
                if (list != null && list.getChildCount() > 2) {
                    View row = list.getChildAt(2).findViewById(R.id.row); // the fill-in click listener sits on the row's view
                    boolean ok = row != null && row.performClick();
                    Log.i(Locator.TAG, "hosted test: tapped row view, handled=" + ok);
                }
                ui.postDelayed(() -> {
                    save(c, ir, "hosted_toggled.png");
                    int after = list == null ? -1 : list.getFirstVisiblePosition();
                    int topAfter = list == null || list.getChildCount() == 0 ? 0 : list.getChildAt(0).getTop();
                    Log.i(Locator.TAG, "hosted test: after tap, first visible=" + after + " top=" + topAfter
                            + (after == before && topAfter == topBefore ? " (scroll kept)" : " (scroll MOVED)"));
                    pres.dismiss();
                    vd.release();
                    ir.close();
                    awh.deleteAppWidgetId(id);
                    awh.stopListening();
                    done.run();
                }, 9000);
            }, 2500);
        }, 4000);
    }

    static String pos(View v) {
        int[] p = new int[2];
        v.getLocationInWindow(p);
        return "(" + p[0] + "," + p[1] + ")";
    }

    static void save(Context c, ImageReader ir, String name) {
        Image img = ir.acquireLatestImage();
        if (img == null) { Log.w(Locator.TAG, "remote test: no frame for " + name); return; }
        try {
            Image.Plane pl = img.getPlanes()[0];
            int w = img.getWidth(), h = img.getHeight(), rs = pl.getRowStride(), ps = pl.getPixelStride();
            Bitmap b = Bitmap.createBitmap(rs / ps, h, Bitmap.Config.ARGB_8888);
            ByteBuffer buf = pl.getBuffer();
            b.copyPixelsFromBuffer(buf);
            Bitmap cut = Bitmap.createBitmap(b, 0, 0, w, h);
            try (FileOutputStream f = new FileOutputStream(new File(c.getExternalFilesDir(null), name))) {
                cut.compress(Bitmap.CompressFormat.PNG, 100, f);
            }
        } catch (Exception e) {
            Log.w(Locator.TAG, "remote test save failed", e);
        } finally {
            img.close();
        }
    }
}
