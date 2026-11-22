package dev.livingsky;

import android.Manifest;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationManager;
import android.os.CancellationSignal;
import android.util.Log;

import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Coarse location (platform LocationManager, fused provider) and the place name for the location line. */
final class Locator {
    private Locator() {}

    static final String TAG = "LivingSky";

    static boolean hasPermission(Context c) {
        return c.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    /** Newest last-known fix from the fused / network / passive providers (no power cost). */
    static Location lastKnown(Context c) {
        if (!hasPermission(c)) return null;
        LocationManager lm = c.getSystemService(LocationManager.class);
        Location best = null;
        for (String p : new String[]{LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER}) {
            try {
                if (!lm.hasProvider(p)) continue;
                Location l = lm.getLastKnownLocation(p);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            } catch (SecurityException | IllegalArgumentException ignored) {
            }
        }
        return best;
    }

    /** A fresh coarse fix (fused provider), waiting at most timeoutMs. Falls back to the last known fix. */
    static Location current(Context c, long timeoutMs) {
        Location last = lastKnown(c);
        if (!hasPermission(c)) return last;
        LocationManager lm = c.getSystemService(LocationManager.class);
        String prov = lm.hasProvider(LocationManager.FUSED_PROVIDER) ? LocationManager.FUSED_PROVIDER : LocationManager.NETWORK_PROVIDER;
        AtomicReference<Location> got = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        CancellationSignal cancel = new CancellationSignal();
        try {
            lm.getCurrentLocation(prov, cancel, Executors.newSingleThreadExecutor(), l -> { got.set(l); done.countDown(); });
            if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) cancel.cancel();
        } catch (Exception e) {
            Log.w(TAG, "getCurrentLocation failed", e);
        }
        Location l = got.get();
        return l != null ? l : last;
    }

    /** Passive location updates delivered to TickReceiver (re-render when the user moves). Costs no extra power. */
    static void listenPassive(Context c) {
        if (!hasPermission(c)) return;
        try {
            LocationManager lm = c.getSystemService(LocationManager.class);
            Intent i = new Intent(c, TickReceiver.class).setAction(TickReceiver.ACTION_LOCATION);
            PendingIntent pi = PendingIntent.getBroadcast(c, 3, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            lm.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, 5 * 60_000L, 1000f, pi);
        } catch (Exception e) {
            Log.w(TAG, "passive location listen failed", e);
        }
    }

    /**
     * "Sublocality, City" (e.g. "Weststadt, Ulm"), falling back to the city only. Platform Geocoder first; when it has
     * no sublocality, OpenStreetMap Nominatim's suburb fills it in (generic User-Agent, coordinates only). Null when unknown.
     */
    static String placeName(Context c, double lat, double lon) {
        String sub = null, city = null, subAdmin = null, admin = null;
        if (Geocoder.isPresent()) {
            try {
                Geocoder g = new Geocoder(c, Locale.getDefault());
                AtomicReference<List<Address>> res = new AtomicReference<>();
                CountDownLatch done = new CountDownLatch(1);
                g.getFromLocation(lat, lon, 1, new Geocoder.GeocodeListener() {
                    @Override public void onGeocode(List<Address> a) { res.set(a); done.countDown(); }
                    @Override public void onError(String msg) { Log.w(TAG, "geocoder: " + msg); done.countDown(); }
                });
                done.await(12, TimeUnit.SECONDS);
                List<Address> a = res.get();
                if (a != null && !a.isEmpty()) {
                    Address ad = a.get(0);
                    sub = ad.getSubLocality(); city = ad.getLocality(); subAdmin = ad.getSubAdminArea(); admin = ad.getAdminArea();
                }
            } catch (Exception e) {
                Log.w(TAG, "geocoder failed", e);
            }
        }
        if (sub == null || sub.isEmpty() || city == null || city.isEmpty()) {
            String[] n = nominatim(lat, lon);
            if (n != null) {
                if (sub == null || sub.isEmpty()) sub = n[0];
                if (city == null || city.isEmpty()) city = n[1];
                if (subAdmin == null || subAdmin.isEmpty()) subAdmin = n[2];
                if (admin == null || admin.isEmpty()) admin = n[3];
            }
        }
        return compose(sub, city, subAdmin, admin);
    }

    static String compose(String sub, String city, String subAdmin, String admin) {
        if (city == null || city.isEmpty()) city = subAdmin != null && !subAdmin.isEmpty() ? subAdmin : admin;
        if (city == null || city.isEmpty()) return sub == null || sub.isEmpty() ? null : sub;
        if (sub != null && !sub.isEmpty() && !sub.equalsIgnoreCase(city)) return sub + ", " + city;
        return city;
    }

    /** OpenStreetMap reverse geocoding with a generic User-Agent: {suburb, city, county, state} or null. */
    static String[] nominatim(double lat, double lon) {
        try {
            String url = String.format(Locale.US, "https://nominatim.openstreetmap.org/reverse?format=jsonv2&lat=%.4f&lon=%.4f&zoom=14&accept-language=", lat, lon)
                    + URLEncoder.encode(Locale.getDefault().getLanguage(), "UTF-8");
            JSONObject a = new JSONObject(WeatherClient.httpGet(url, 12000)).getJSONObject("address");
            String sub = a.optString("suburb", a.optString("city_district", a.optString("quarter", "")));
            String city = a.optString("city", a.optString("town", a.optString("village", a.optString("municipality", ""))));
            return new String[]{sub, city, a.optString("county", ""), a.optString("state", "")};
        } catch (Exception e) {
            Log.w(TAG, "nominatim failed: " + e);
            return null;
        }
    }

    static double distanceKm(double lat1, double lon1, double lat2, double lon2) {
        double r = 6371.0, p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2);
        double dp = p2 - p1, dl = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * r * Math.asin(Math.min(1, Math.sqrt(a)));
    }
}
