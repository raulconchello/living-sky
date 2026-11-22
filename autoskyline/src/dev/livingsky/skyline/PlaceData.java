package dev.livingsky.skyline;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Everything fetched for one place (one ~1 km grid cell). Cached as JSON so re-renders need no network. */
final class PlaceData {
    static final int VERSION = 6;

    /** Terrain survey around the viewer: azimuth step and ring distances (metres). */
    static final int SURVEY_AZ = 12;
    static final double[] SURVEY_D = {500, 1200, 2500, 5000, 9000, 15000, 25000};

    static final class Water {
        String type = "";          // coast, lake, river, water
        double lat, lon, sizeE, sizeN;
        String name = "";

        JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("type", type); o.put("lat", lat); o.put("lon", lon); o.put("sizeE", sizeE); o.put("sizeN", sizeN); o.put("name", name);
            return o;
        }

        static Water fromJson(JSONObject o) {
            Water w = new Water();
            w.type = o.optString("type", ""); w.lat = o.optDouble("lat", 0); w.lon = o.optDouble("lon", 0);
            w.sizeE = o.optDouble("sizeE", 0); w.sizeN = o.optDouble("sizeN", 0); w.name = o.optString("name", "");
            return w;
        }
    }

    double lat, lon;               // viewer = grid cell centre
    String placeName = "";
    final List<Landmark> landmarks = new ArrayList<>();
    final List<Water> water = new ArrayList<>();
    String placeType = "";         // city, town, village, hamlet, suburb, ...
    long population;
    double placeDist = Double.NaN;
    int tallCount;                 // buildings >= ~40 m / 13 levels
    int osmCount;                  // raw OSM elements seen
    boolean osmOk, wdOk, terrainOk;
    String errors = "";
    long fetchedAt;
    double viewerElev = Double.NaN;
    double[] survey;               // SURVEY_AZ * SURVEY_D.length elevations, NaN if missing
    String profileKey = "";
    double[] profile;              // camera-dependent elevation samples (see Composer)

    boolean complete() { return osmOk && wdOk && terrainOk; }

    JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("v", VERSION);
        o.put("lat", lat); o.put("lon", lon); o.put("placeName", placeName);
        o.put("landmarks", Landmark.toJsonArray(landmarks));
        JSONArray wa = new JSONArray();
        for (Water w : water) wa.put(w.toJson());
        o.put("water", wa);
        o.put("placeType", placeType); o.put("population", population);
        Landmark.putNum(o, "placeDist", placeDist);
        o.put("tallCount", tallCount); o.put("osmCount", osmCount);
        o.put("osmOk", osmOk); o.put("wdOk", wdOk); o.put("terrainOk", terrainOk);
        o.put("errors", errors); o.put("fetchedAt", fetchedAt);
        Landmark.putNum(o, "viewerElev", viewerElev);
        if (survey != null) o.put("survey", arr(survey));
        o.put("profileKey", profileKey);
        if (profile != null) o.put("profile", arr(profile));
        return o;
    }

    static PlaceData fromJson(JSONObject o) throws JSONException {
        if (o.optInt("v", 0) != VERSION) throw new JSONException("old cache version");
        PlaceData d = new PlaceData();
        d.lat = o.getDouble("lat"); d.lon = o.getDouble("lon"); d.placeName = o.optString("placeName", "");
        JSONArray la = o.optJSONArray("landmarks");
        if (la != null) for (int i = 0; i < la.length(); i++) d.landmarks.add(Landmark.fromJson(la.getJSONObject(i)));
        JSONArray wa = o.optJSONArray("water");
        if (wa != null) for (int i = 0; i < wa.length(); i++) d.water.add(Water.fromJson(wa.getJSONObject(i)));
        d.placeType = o.optString("placeType", ""); d.population = o.optLong("population", 0);
        d.placeDist = o.optDouble("placeDist", Double.NaN);
        d.tallCount = o.optInt("tallCount", 0); d.osmCount = o.optInt("osmCount", 0);
        d.osmOk = o.optBoolean("osmOk", false); d.wdOk = o.optBoolean("wdOk", false); d.terrainOk = o.optBoolean("terrainOk", false);
        d.errors = o.optString("errors", ""); d.fetchedAt = o.optLong("fetchedAt", 0);
        d.viewerElev = o.optDouble("viewerElev", Double.NaN);
        d.survey = darr(o.optJSONArray("survey"));
        d.profileKey = o.optString("profileKey", "");
        d.profile = darr(o.optJSONArray("profile"));
        return d;
    }

    private static JSONArray arr(double[] v) throws JSONException {
        JSONArray a = new JSONArray();
        for (double x : v) a.put(Double.isNaN(x) ? -99999 : Math.round(x * 10) / 10.0);
        return a;
    }

    private static double[] darr(JSONArray a) {
        if (a == null) return null;
        double[] v = new double[a.length()];
        for (int i = 0; i < v.length; i++) { double x = a.optDouble(i, -99999); v[i] = x <= -99998 ? Double.NaN : x; }
        return v;
    }
}
