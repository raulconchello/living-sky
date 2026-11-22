package dev.livingsky;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Small debug screen: force a place (or "auto at lat/lon"), day/night, a weather scenario or the sketch's reference
 * data; refresh now; see the last update, error and a preview of what was pushed to the widget.
 * Also the widget's (optional) configure activity, so Nova's "reconfigure" opens it.
 */
public class DebugActivity extends Activity {
    private Prefs prefs;
    private TextView status;
    private ImageView preview;
    private Spinner place, mode, scenario;
    private EditText lat, lon, name, at;
    private CheckBox mock;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private List<String> placeIds;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new Prefs(this);
        int wid = getIntent().getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
        if (wid != AppWidgetManager.INVALID_APPWIDGET_ID) {
            // configuration is optional: accept immediately so the launcher always keeps the widget
            setResult(RESULT_OK, new Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, wid));
        }
        float d = getResources().getDisplayMetrics().density;
        ScrollView sv = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * d);
        col.setPadding(pad, pad + (int) (24 * d), pad, pad);
        sv.addView(col);
        sv.setBackgroundColor(0xff15161b);

        TextView title = text("Living Sky – debug", 20);
        col.addView(title);
        preview = new ImageView(this);
        preview.setAdjustViewBounds(true);
        preview.setPadding(0, (int) (8 * d), 0, (int) (8 * d));
        col.addView(preview, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        status = text("", 12);
        status.setTextIsSelectable(true);
        col.addView(status);

        // place
        col.addView(label("Place"));
        placeIds = new ArrayList<>();
        List<String> names = new ArrayList<>();
        placeIds.add(""); names.add("Live location");
        placeIds.add("auto"); names.add("Automatic skyline at lat/lon");
        for (Places.Place p : Places.all(this)) { placeIds.add(p.id); names.add(p.id + " – " + p.label); }
        place = spinner(names);
        col.addView(place);
        LinearLayout ll = new LinearLayout(this);
        lat = edit("lat", String.format(Locale.US, "%.4f", prefs.autoLat()));
        lon = edit("lon", String.format(Locale.US, "%.4f", prefs.autoLon()));
        ll.addView(lat, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        ll.addView(lon, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        col.addView(ll);
        name = edit("place name for auto (optional)", prefs.autoName());
        name.setInputType(InputType.TYPE_CLASS_TEXT);
        col.addView(name);

        col.addView(label("Day / night"));
        mode = spinner(java.util.Arrays.asList("Automatic (sun below −4°)", "Day", "Night"));
        col.addView(mode);
        col.addView(label("Weather scenario (debug)"));
        List<String> sc = new ArrayList<>();
        for (String s : Scenario.NAMES) sc.add(s.isEmpty() ? "Live weather" : s);
        scenario = spinner(sc);
        col.addView(scenario);
        col.addView(label("Time override HH:mm (empty = now)"));
        at = edit("HH:mm", prefs.atMinutes() < 0 ? "" : String.format(Locale.US, "%02d:%02d", prefs.atMinutes() / 60, prefs.atMinutes() % 60));
        at.setInputType(InputType.TYPE_CLASS_DATETIME | InputType.TYPE_DATETIME_VARIATION_TIME);
        col.addView(at);
        mock = new CheckBox(this);
        mock.setText("Reference mock data (sketch: Ulm, Sun 22 Nov 11:39 / 22:10)");
        mock.setTextColor(Color.WHITE);
        col.addView(mock);

        col.addView(button("Apply and render", v -> { save(); run(Updater.FETCH_NEVER); }));
        col.addView(button("Refresh now (location + weather)", v -> { save(); run(Updater.FETCH_FORCE); }));
        col.addView(button("Reset overrides (live)", v -> {
            prefs.edit().remove("place").remove("mode").remove("mock").remove("scenario").remove("atMinutes").commit();
            load();
            run(Updater.FETCH_IF_STALE);
        }));
        col.addView(button("Clear cached automatic skylines", v -> { AutoSkylines.clearCache(this); run(Updater.FETCH_NEVER); }));
        col.addView(button("Add widget to home screen", v -> pin()));
        setContentView(sv);
        load();
        if (getIntent().getBooleanExtra("pin", false)) pin();
        refreshStatus();
    }

    private void pin() {
        AppWidgetManager m = getSystemService(AppWidgetManager.class);
        if (m.isRequestPinAppWidgetSupported()) m.requestPinAppWidget(new ComponentName(this, LivingSkyWidget.class), null, null);
        else status.setText("This launcher does not support pinning widgets.");
    }

    private void load() {
        place.setSelection(Math.max(0, placeIds.indexOf(prefs.place())));
        mode.setSelection(prefs.mode());
        scenario.setSelection(Math.max(0, java.util.Arrays.asList(Scenario.NAMES).indexOf(prefs.scenario())));
        mock.setChecked(prefs.mock());
        at.setText(prefs.atMinutes() < 0 ? "" : String.format(Locale.US, "%02d:%02d", prefs.atMinutes() / 60, prefs.atMinutes() % 60));
    }

    private void save() {
        SharedPreferences.Editor e = prefs.edit();
        e.putString("place", placeIds.get(place.getSelectedItemPosition()));
        try {
            Prefs.putDouble(e, "autoLat", Double.parseDouble(lat.getText().toString().trim()));
            Prefs.putDouble(e, "autoLon", Double.parseDouble(lon.getText().toString().trim()));
        } catch (NumberFormatException ignored) {
        }
        e.putString("autoName", name.getText().toString().trim());
        e.putInt("mode", mode.getSelectedItemPosition());
        e.putString("scenario", Scenario.NAMES[scenario.getSelectedItemPosition()]);
        e.putBoolean("mock", mock.isChecked());
        String a = at.getText().toString().trim();
        if (a.matches("\\d{1,2}:\\d{2}")) {
            String[] p = a.split(":");
            e.putInt("atMinutes", Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]));
        } else e.remove("atMinutes");
        e.commit();
    }

    private void run(int fetch) {
        status.setText("Rendering…");
        Updater.runAsync(this, "debug", fetch, () -> ui.post(this::refreshStatus));
    }

    private String time(long t) {
        return t == 0 ? "never" : DateFormat.format("yyyy-MM-dd HH:mm:ss", t).toString();
    }

    private void refreshStatus() {
        StringBuilder s = new StringBuilder();
        s.append("Last render: ").append(time(prefs.lastRender())).append(" (").append(prefs.renderMs()).append(" ms)\n");
        s.append("Last weather fetch: ").append(time(prefs.lastFetch())).append('\n');
        String err = prefs.lastError();
        s.append("Last error: ").append(err.isEmpty() ? "none" : err + " (" + time(prefs.lastErrorAt()) + ")").append('\n');
        s.append("Info: ").append(prefs.lastInfo()).append('\n');
        s.append(String.format(Locale.US, "Location: %s (%.4f, %.4f, fix %s)\n", prefs.placeName(), prefs.locLat(), prefs.locLon(), time(prefs.locTime())));
        s.append("Location permission: ").append(Locator.hasPermission(this) ? "yes" : "NO").append('\n');
        s.append("Widgets placed: ").append(WidgetUpdater.ids(this).length).append('\n');
        s.append("Hand-drawn places: ").append(Places.all(this)).append('\n');
        s.append("OSM generator compiled in: ").append(AutoSkylineBridge.available() ? "yes" : "no (procedural town fallback)");
        status.setText(s);
        Renderer.Out o = Updater.last;
        if (o != null) {
            Bitmap p = Renderer.preview(this, o, o.clockText != null ? o.clockText : DateFormat.format("HH:mm", System.currentTimeMillis()).toString());
            preview.setImageBitmap(p);
        }
    }

    private TextView text(String s, int sp) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(Color.WHITE);
        return t;
    }

    private TextView label(String s) {
        TextView t = text(s, 13);
        t.setTextColor(0xffaab4c8);
        t.setPadding(0, (int) (14 * getResources().getDisplayMetrics().density), 0, 0);
        return t;
    }

    private EditText edit(String hint, String v) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(v);
        e.setTextColor(Color.WHITE);
        e.setHintTextColor(0xff777777);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
        return e;
    }

    private Spinner spinner(List<String> items) {
        Spinner s = new Spinner(this);
        ArrayAdapter<String> a = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, items);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        s.setAdapter(a);
        return s;
    }

    private Button button(String s, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        b.setGravity(Gravity.CENTER);
        return b;
    }
}
