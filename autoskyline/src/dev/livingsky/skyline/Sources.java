package dev.livingsky.skyline;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Network data sources: Overpass (OSM), Wikidata Query Service, Open-Meteo elevation. */
final class Sources {
    private Sources() {}

    /** Overpass endpoints, tried in order until one answers (main instance first). */
    static String[] OVERPASS = {
            "https://overpass-api.de/api/interpreter",
            "https://overpass.private.coffee/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter",
            "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
    };
    static String WIKIDATA = "https://query.wikidata.org/sparql";
    /** Upper bound for Overpass' own [timeout:] (seconds); the phone budget keeps it small. */
    static volatile int maxServerTimeout = 12;
    static String ELEVATION = "https://api.open-meteo.com/v1/elevation";

    static final double M_PER_DEG = 111320.0;

    // ------------------------------------------------------------------ Overpass

    static String overpassQuery(double lat, double lon, int serverTimeout) {
        String c = String.format(Locale.ROOT, "%.5f,%.5f", lat, lon);
        String a3 = "(around:3000," + c + ")", a4 = "(around:4000," + c + ")", a6 = "(around:6000," + c + ")",
                a25 = "(around:2500," + c + ")", a2 = "(around:2000," + c + ")";
        return "[out:json][timeout:" + serverTimeout + "][maxsize:33554432];\n"
                + "(\n"
                + " nwr[\"building\"~\"^(church|cathedral|chapel|basilica|mosque|temple|synagogue|shrine|monastery|tower|castle|palace|bell_tower|minaret)$\"]" + a3 + ";\n"
                + " nwr[\"amenity\"=\"place_of_worship\"][\"wikidata\"]" + a3 + ";\n"
                + " nwr[\"amenity\"=\"townhall\"][\"wikidata\"]" + a3 + ";\n"
                + " nwr[\"tower:type\"~\"^(bell_tower|communication|observation|defensive|minaret|watchtower|clock)$\"]" + a4 + ";\n"
                + " nwr[\"man_made\"~\"^(tower|communications_tower|chimney|water_tower|lighthouse)$\"]" + a4 + ";\n"
                + " nwr[\"historic\"~\"^(castle|fort|palace|city_gate|tower|monastery|church|cathedral)$\"]" + a6 + ";\n"
                + " nwr[\"building\"][\"wikidata\"][\"height\"]" + a3 + ";\n"
                + ")->.lm;\n"
                + "(\n"
                + " nwr[\"building\"][\"height\"~\"^([4-9][0-9]|[1-9][0-9]{2,})([.,][0-9]+)?( ?m)?$\"]" + a25 + ";\n"
                + " nwr[\"building\"][\"building:levels\"~\"^(1[3-9]|[2-9][0-9]|1[0-9][0-9])$\"]" + a25 + ";\n"
                + ")->.tall;\n"
                + "(\n"
                + " way[\"natural\"=\"coastline\"]" + a3 + ";\n"
                + " nwr[\"natural\"=\"water\"][\"water\"!~\"^(pond|basin|wastewater|reflecting_pool|fountain|fishpond|ditch|moat)$\"]" + a2 + ";\n"
                + " way[\"waterway\"~\"^(river|canal)$\"]" + a2 + ";\n"
                + " node[\"place\"~\"^(city|town|village|hamlet|suburb|quarter|borough)$\"](around:8000," + c + ");\n"
                + ")->.ctx;\n"
                + "(.lm; .tall; .ctx;)->.all;\n"
                + "node.all; out body qt;\n"
                + "way.all; out tags bb qt;\n"
                + "rel.all; out tags bb qt;\n";
    }

    /**
     * Fetch OSM features; throws if every endpoint failed. The main instance is asked first; a mirror is only
     * asked when it fails or has not answered after a few seconds (hedged request), so a healthy server sees one query.
     */
    static void fetchOverpass(final PlaceData d, final long deadline) throws IOException {
        java.util.concurrent.ExecutorService ex = java.util.concurrent.Executors.newFixedThreadPool(OVERPASS.length, new java.util.concurrent.ThreadFactory() {
            @Override public Thread newThread(Runnable r) { Thread t = new Thread(r, "autoskyline-osm"); t.setDaemon(true); return t; }
        });
        java.util.concurrent.ExecutorCompletionService<JSONObject> cs = new java.util.concurrent.ExecutorCompletionService<>(ex);
        IOException last = null;
        try {
            int started = 0, finished = 0;
            long nextStart = 0;
            while (true) {
                long now = System.currentTimeMillis();
                if (started < OVERPASS.length && (started == finished || now >= nextStart) && deadline - now > 2500) {
                    final String ep = OVERPASS[started];
                    final int serverTimeout = (int) Math.max(4, Math.min(maxServerTimeout, (deadline - now) / 1000 - 1));
                    cs.submit(new java.util.concurrent.Callable<JSONObject>() {
                        @Override public JSONObject call() throws Exception {
                            String body = Net.postForm(ep, "data=" + Net.enc(overpassQuery(d.lat, d.lon, serverTimeout)), deadline, "application/json");
                            return new JSONObject(body);
                        }
                    });
                    started++;
                    nextStart = now + 3500;
                }
                if (finished == started && (started == OVERPASS.length || deadline - System.currentTimeMillis() <= 2500)) break;
                long wait = Math.min(nextStart, deadline) - System.currentTimeMillis();
                if (started == OVERPASS.length) wait = deadline - System.currentTimeMillis();
                if (wait <= 0 && System.currentTimeMillis() >= deadline) break;
                java.util.concurrent.Future<JSONObject> f;
                try { f = cs.poll(Math.max(1, wait), java.util.concurrent.TimeUnit.MILLISECONDS); }
                catch (InterruptedException ie) { throw new IOException("interrupted"); }
                if (f == null) { if (System.currentTimeMillis() >= deadline) break; else continue; }
                finished++;
                try {
                    JSONObject root = f.get();
                    parseOverpass(d, root);
                    d.osmOk = true;
                    return;
                } catch (java.util.concurrent.ExecutionException e) {
                    Throwable c = e.getCause();
                    last = c instanceof IOException ? (IOException) c : new IOException("overpass: " + c);
                    nextStart = 0;   // failed fast: ask the next mirror now
                } catch (JSONException e) {
                    last = new IOException("overpass: " + e.getMessage());
                    nextStart = 0;
                } catch (InterruptedException ie) { throw new IOException("interrupted"); }
            }
        } finally {
            ex.shutdownNow();
        }
        throw last != null ? last : new IOException("overpass: timeout");
    }

    static void parseOverpass(PlaceData d, JSONObject root) throws JSONException {
        if (root.has("remark") && root.optString("remark").contains("runtime error"))
            throw new JSONException("overpass runtime error: " + root.optString("remark"));
        JSONArray els = root.getJSONArray("elements");
        d.osmCount = els.length();
        List<Landmark> raw = new ArrayList<>();
        List<Landmark> bellTowers = new ArrayList<>();
        double bestPlace = Double.MAX_VALUE;
        for (int i = 0; i < els.length(); i++) {
            JSONObject el = els.getJSONObject(i);
            JSONObject tags = el.optJSONObject("tags");
            if (tags == null) continue;
            double lat, lon, sizeE = 0, sizeN = 0;
            JSONObject b = el.optJSONObject("bounds");
            JSONObject ce = el.optJSONObject("center");
            if (el.has("lat")) { lat = el.getDouble("lat"); lon = el.getDouble("lon"); }
            else if (b != null) {
                double a0 = b.getDouble("minlat"), a1 = b.getDouble("maxlat"), o0 = b.getDouble("minlon"), o1 = b.getDouble("maxlon");
                lat = (a0 + a1) / 2; lon = (o0 + o1) / 2;
                sizeN = (a1 - a0) * M_PER_DEG; sizeE = (o1 - o0) * M_PER_DEG * Math.cos(Math.toRadians(lat));
            } else if (ce != null) { lat = ce.getDouble("lat"); lon = ce.getDouble("lon"); }
            else continue;

            String natural = tags.optString("natural"), waterway = tags.optString("waterway"), place = tags.optString("place");
            if ("coastline".equals(natural) || "water".equals(natural) || !waterway.isEmpty()) {
                PlaceData.Water w = new PlaceData.Water();
                w.type = "coastline".equals(natural) ? "coast" : (!waterway.isEmpty() ? "river"
                        : ("river".equals(tags.optString("water")) || "canal".equals(tags.optString("water")) ? "river" : "lake"));
                w.lat = lat; w.lon = lon; w.sizeE = sizeE; w.sizeN = sizeN; w.name = tags.optString("name");
                if ("lake".equals(w.type) && Math.max(sizeE, sizeN) < 150 && !tags.has("name")) continue;
                d.water.add(w);
                continue;
            }
            if (!place.isEmpty() && el.optString("type").equals("node")) {
                double dist = dist(d.lat, d.lon, lat, lon);
                double rank = dist / placeWeight(place);
                if (rank < bestPlace) {
                    bestPlace = rank;
                    d.placeType = place; d.placeDist = dist;
                    d.population = parsePop(tags.optString("population"));
                }
                continue;
            }
            Landmark l = classifyOsm(tags);
            if (l == null) continue;
            l.lat = lat; l.lon = lon; l.sizeE = sizeE; l.sizeN = sizeN; l.fromOsm = true;
            if (l.isTall()) d.tallCount++;
            if (l.kind == Landmark.Kind.BELL_TOWER && "bell_tower".equals(l.towerType)) bellTowers.add(l);
            raw.add(l);
        }
        // de-duplicate (node + way of the same thing), keeping the richer one
        List<Landmark> out = new ArrayList<>();
        for (Landmark l : raw) {
            Landmark same = null;
            for (Landmark o : out) {
                boolean sameQ = !l.qid.isEmpty() && l.qid.equals(o.qid);
                if (sameQ || (compatible(l, o) && dist(l.lat, l.lon, o.lat, o.lon) < 35 && (l.name.equals(o.name) || l.name.isEmpty() || o.name.isEmpty()))) { same = o; break; }
            }
            if (same == null) out.add(l); else mergeInto(same, l);
        }
        // attach bell towers to their church
        List<Landmark> remove = new ArrayList<>();
        for (Landmark t : out) {
            if (t.kind != Landmark.Kind.BELL_TOWER || !t.qid.isEmpty() && t.sitelinks > 0) continue;
            Landmark best = null; double bd = 1e9;
            for (Landmark c : out) {
                if (!c.isChurchLike()) continue;
                double dd = dist(t.lat, t.lon, c.lat, c.lon);
                double reach = Math.max(35, Math.max(c.sizeE, c.sizeN) * 0.65 + 12);
                if (dd < reach && dd < bd) { bd = dd; best = c; }
            }
            if (best != null) {
                best.towers++;
                if (!Double.isNaN(t.height) && (Double.isNaN(best.height) || t.height > best.height)) { best.height = t.height; best.towerHeight = true; }
                if (Double.isNaN(best.towerDE) || best.towers == 1) {
                    double[] en = en(best.lat, best.lon, t.lat, t.lon);
                    best.towerDE = en[0]; best.towerDN = en[1];
                }
                if (best.towerRoof.isEmpty()) best.towerRoof = t.roofShape;
                remove.add(t);
            }
        }
        out.removeAll(remove);
        d.landmarks.addAll(out);
    }

    private static double placeWeight(String p) {
        switch (p) {
            case "city": return 4;
            case "town": return 2.5;
            case "borough": case "suburb": return 1.6;
            case "village": return 1.3;
            case "quarter": return 1.1;
            default: return 1;
        }
    }

    static long parsePop(String s) {
        try { return Long.parseLong(s.replaceAll("[^0-9]", "")); } catch (NumberFormatException e) { return 0; }
    }

    static double parseLen(String s) {
        if (s == null || s.isEmpty()) return Double.NaN;
        s = s.trim().toLowerCase(Locale.ROOT).replace(',', '.');
        double f = 1;
        if (s.endsWith("ft") || s.endsWith("'")) { f = 0.3048; }
        s = s.replaceAll("[^0-9.]", " ").trim();
        int sp = s.indexOf(' ');
        if (sp > 0) s = s.substring(0, sp);
        try { return Double.parseDouble(s) * f; } catch (NumberFormatException e) { return Double.NaN; }
    }

    private static boolean compatible(Landmark a, Landmark b) {
        if (a.kind == b.kind) return true;
        if (a.isChurchLike() && b.isChurchLike()) return true;
        if (a.isTall() && b.isTall()) return true;
        return false;
    }

    static void mergeInto(Landmark a, Landmark b) {
        if (a.name.isEmpty()) a.name = b.name;
        if (a.qid.isEmpty()) a.qid = b.qid;
        if (b.sizeE * b.sizeN > a.sizeE * a.sizeN) { a.sizeE = b.sizeE; a.sizeN = b.sizeN; a.lat = b.lat; a.lon = b.lon; }
        if (!Double.isNaN(b.height) && (Double.isNaN(a.height) || b.height > a.height)) { a.height = b.height; a.towerHeight = b.towerHeight; }
        if (Double.isNaN(a.roofHeight)) a.roofHeight = b.roofHeight;
        if (Double.isNaN(a.levels)) a.levels = b.levels;
        if (a.style.isEmpty()) a.style = b.style;
        if (a.roofShape.isEmpty()) a.roofShape = b.roofShape;
        if (a.construction.isEmpty()) a.construction = b.construction;
        if (a.castleType.isEmpty()) a.castleType = b.castleType;
        a.ruin |= b.ruin;
        a.sitelinks = Math.max(a.sitelinks, b.sitelinks);
        if (rankKind(b.kind) > rankKind(a.kind)) a.kind = b.kind;
        a.fromOsm |= b.fromOsm; a.fromWd |= b.fromWd;
    }

    private static int rankKind(Landmark.Kind k) {
        switch (k) {
            case CATHEDRAL: return 5;
            case CASTLE: case TV_TOWER: case LIGHTHOUSE: case MOSQUE: return 4;
            case CHURCH: case PALACE: case SKYSCRAPER: return 3;
            case MONASTERY: case TOWN_HALL: case CITY_GATE: return 2;
            default: return 1;
        }
    }

    /** Map OSM tags to a landmark, or null when not a skyline feature. */
    static Landmark classifyOsm(JSONObject t) {
        String building = t.optString("building"), manMade = t.optString("man_made"), historic = t.optString("historic"),
                amenity = t.optString("amenity"), towerType = t.optString("tower:type"), religion = t.optString("religion");
        Landmark l = new Landmark();
        l.name = t.optString("name", t.optString("name:en", ""));
        l.qid = t.optString("wikidata", "");
        if (!l.qid.matches("Q[0-9]+")) l.qid = "";
        l.height = parseLen(t.optString("height"));
        if (Double.isNaN(l.height)) l.height = parseLen(t.optString("est_height"));
        double minH = parseLen(t.optString("min_height"));
        if (!Double.isNaN(minH) && !Double.isNaN(l.height) && l.height < minH) l.height = Double.NaN;
        l.levels = parseLen(t.optString("building:levels"));
        double roofH = parseLen(t.optString("roof:height"));
        if (!Double.isNaN(roofH) && !Double.isNaN(l.height)) l.roofHeight = l.height - roofH;
        l.roofShape = t.optString("roof:shape", "");
        l.construction = t.optString("tower:construction", t.optString("construction", ""));
        l.towerType = towerType;
        String arch = (t.optString("building:architecture") + " " + t.optString("architecture")).toLowerCase(Locale.ROOT);
        l.style = styleOf(arch);
        l.ruin = "yes".equals(t.optString("ruins")) || "ruins".equals(building) || "yes".equals(t.optString("historic:ruins"));
        l.castleType = t.optString("castle_type", "");

        if ("lighthouse".equals(manMade)) l.kind = Landmark.Kind.LIGHTHOUSE;
        else if ("chimney".equals(manMade)) l.kind = Landmark.Kind.CHIMNEY;
        else if ("water_tower".equals(manMade)) l.kind = Landmark.Kind.WATER_TOWER;
        else if ("communications_tower".equals(manMade) || ("tower".equals(manMade) && "communication".equals(towerType))) {
            boolean lattice = l.construction.contains("lattice") || "mast".equals(manMade);
            if (lattice) l.kind = Landmark.Kind.LATTICE_TOWER;
            else if ("communications_tower".equals(manMade) || (!Double.isNaN(l.height) && l.height >= 90) || l.construction.contains("concrete"))
                l.kind = Landmark.Kind.TV_TOWER;
            else return null; // small antenna masts
        }
        else if (("tower".equals(manMade) || "tower".equals(building) || "bell_tower".equals(building) || !towerType.isEmpty())
                && !building.matches("church|cathedral|chapel|basilica|mosque")) {
            if ("bell_tower".equals(towerType) || "bell_tower".equals(building)) { l.kind = Landmark.Kind.BELL_TOWER; l.towerType = "bell_tower"; }
            else if ("minaret".equals(towerType) || "minaret".equals(building)) return null;
            else if ("lighting".equals(towerType) || "cooling".equals(towerType) || "radar".equals(towerType) || "monitoring".equals(towerType)) return null;
            else {
                l.kind = Landmark.Kind.TOWER;
                if ("city_gate".equals(historic)) l.kind = Landmark.Kind.CITY_GATE;
            }
            if (Double.isNaN(l.height) && l.qid.isEmpty() && !"bell_tower".equals(l.towerType) && !"defensive".equals(towerType)
                    && !"observation".equals(towerType)) return null;
            if (!Double.isNaN(l.height) && l.height < 12) return null;
        }
        else if ("castle".equals(historic) || "castle".equals(building)) {
            l.kind = l.castleType.matches("palace|stately|schloss|manor|chateau|château") ? Landmark.Kind.PALACE : Landmark.Kind.CASTLE;
            if ("fortress".equals(l.castleType) && Double.isNaN(l.height)) l.kind = Landmark.Kind.FORT;
        }
        else if ("palace".equals(historic) || "palace".equals(building)) l.kind = Landmark.Kind.PALACE;
        else if ("fort".equals(historic)) l.kind = Landmark.Kind.FORT;
        else if ("city_gate".equals(historic)) l.kind = Landmark.Kind.CITY_GATE;
        else if ("cathedral".equals(building) || "cathedral".equals(historic)) l.kind = Landmark.Kind.CATHEDRAL;
        else if ("mosque".equals(building) || ("place_of_worship".equals(amenity) && "muslim".equals(religion))) l.kind = Landmark.Kind.MOSQUE;
        else if ("synagogue".equals(building) || "jewish".equals(religion)) l.kind = Landmark.Kind.SYNAGOGUE;
        else if ("temple".equals(building) || "shrine".equals(building)) l.kind = Landmark.Kind.TEMPLE;
        else if ("chapel".equals(building)) l.kind = Landmark.Kind.CHAPEL;
        else if ("monastery".equals(building) || "monastery".equals(historic)) l.kind = Landmark.Kind.MONASTERY;
        else if ("church".equals(building) || "basilica".equals(building) || "church".equals(historic) || "place_of_worship".equals(amenity))
            l.kind = Landmark.Kind.CHURCH;
        else if ("townhall".equals(amenity)) l.kind = Landmark.Kind.TOWN_HALL;
        else if (!building.isEmpty()) {
            double h = l.estimateHeightRaw();
            if (Double.isNaN(h) || h < 38) return null;
            l.kind = h >= 95 ? Landmark.Kind.SKYSCRAPER : Landmark.Kind.HIGHRISE;
            if (Double.isNaN(l.height)) l.height = h;
        }
        else return null;

        if (l.isChurchLike() && "basilica".equals(building)) l.style = l.style.isEmpty() ? "basilica" : l.style;
        if (l.kind == Landmark.Kind.CHURCH && t.optString("denomination").contains("orthodox") && l.style.isEmpty()) l.style = "orthodox";
        return l;
    }

    static String styleOf(String s) {
        s = s.toLowerCase(Locale.ROOT);
        StringBuilder b = new StringBuilder();
        if (s.contains("gothic")) b.append("gothic ");
        if (s.contains("baroque") || s.contains("rococo")) b.append("baroque ");
        if (s.contains("romanesque")) b.append("romanesque ");
        if (s.contains("renaissance")) b.append("renaissance ");
        if (s.contains("neoclassic") || s.contains("classicism") || s.contains("neo-classic")) b.append("neoclassical ");
        if (s.contains("art deco")) b.append("artdeco ");
        if (s.contains("byzantin")) b.append("byzantine ");
        if (s.contains("moorish") || s.contains("mudéjar") || s.contains("mudejar") || s.contains("islamic") || s.contains("ottoman")) b.append("moorish ");
        if (s.contains("modernisme") || s.contains("art nouveau") || s.contains("jugendstil")) b.append("nouveau ");
        if (s.contains("modern") && !s.contains("modernisme") || s.contains("international style") || s.contains("brutalis")
                || s.contains("high-tech") || s.contains("contemporary") || s.contains("deconstruct")) b.append("modern ");
        return b.toString().trim();
    }

    // ------------------------------------------------------------------ Wikidata

    private static final String[][] WD_CLASSES = {
            {"CATHEDRAL", "Q2977 Q56242215"},
            {"CHURCH", "Q16970 Q1088552 Q56242063 Q2031836 Q317557 Q55876909 Q10631691 Q20064854 Q334383 Q1509716 Q1129743 Q120560 Q163687"},
            {"CHAPEL", "Q108325 Q1457501"},
            {"MONASTERY", "Q44613 Q160742"},
            {"MOSQUE", "Q32815"}, {"SYNAGOGUE", "Q34627"}, {"TEMPLE", "Q44539"},
            {"CASTLE", "Q23413 Q17715832"},
            {"PALACE", "Q16560 Q15848826 Q751876"},
            {"FORT", "Q57821 Q1785071 Q57831"},
            {"TOWER", "Q12518 Q1440300"},
            {"BELL_TOWER", "Q200334"},
            {"TV_TOWER", "Q11166728 Q1798641"},
            {"WATER_TOWER", "Q274153"}, {"LIGHTHOUSE", "Q39715"}, {"CHIMNEY", "Q170477"},
            {"CITY_GATE", "Q82117"},
            {"SKYSCRAPER", "Q11303"}, {"HIGHRISE", "Q18142 Q1021645 Q27686 Q41176"},
            {"BRIDGE", "Q12280 Q12570 Q158438"},
            {"TOWN_HALL", "Q543654"},
    };

    private static final String[][] WD_WATER = {
            {"river", "Q4022 Q47521"}, {"lake", "Q23397 Q131681 Q187223"}, {"sea", "Q165 Q39594 Q1322134 Q41762 Q9430"},
    };

    static String wikidataQuery(double lat, double lon, double radiusKm) {
        StringBuilder values = new StringBuilder();
        for (String[] c : WD_CLASSES) for (String q : c[1].split(" ")) values.append(" wd:").append(q);
        for (String[] c : WD_WATER) for (String q : c[1].split(" ")) values.append(" wd:").append(q);
        return "SELECT ?item ?itemLabel ?sl (MAX(?h0) AS ?h) (MAX(?fl0) AS ?fl) (MAX(?pop0) AS ?pop) (SAMPLE(?coord) AS ?c)"
                + " (GROUP_CONCAT(DISTINCT STRAFTER(STR(?c0), \"entity/\"); separator=\" \") AS ?cls)"
                + " (GROUP_CONCAT(DISTINCT ?stl; separator=\"|\") AS ?sty) WHERE {\n"
                + " SERVICE wikibase:around { ?item wdt:P625 ?coord. bd:serviceParam wikibase:center \"Point("
                + String.format(Locale.ROOT, "%.5f %.5f", lon, lat) + ")\"^^geo:wktLiteral. bd:serviceParam wikibase:radius \""
                + String.format(Locale.ROOT, "%.1f", radiusKm) + "\". }\n"
                + " ?item wikibase:sitelinks ?sl.\n"
                + " { VALUES ?c0 {" + values + " }\n"
                + "   ?item wdt:P31 ?c0.\n"
                + "   OPTIONAL { ?item p:P2048/psn:P2048/wikibase:quantityAmount ?h0. }\n"
                + "   OPTIONAL { ?item wdt:P1101 ?fl0. }\n"
                + "   OPTIONAL { ?item wdt:P149 ?st. ?st rdfs:label ?stl. FILTER(LANG(?stl) = \"en\") } }\n"
                + " UNION { ?item wdt:P1082 ?pop0. }\n"
                + " SERVICE wikibase:label { bd:serviceParam wikibase:language \"en,de,es,fr,it,nl,pl,mul\". }\n"
                + "} GROUP BY ?item ?itemLabel ?sl ORDER BY DESC(?sl) LIMIT 220";
    }

    static void fetchWikidata(PlaceData d, long deadline) throws IOException {
        String body = Net.postForm(WIKIDATA, "query=" + Net.enc(wikidataQuery(d.lat, d.lon, 4.5)), deadline, "application/sparql-results+json");
        try {
            parseWikidata(d, new JSONObject(body));
            d.wdOk = true;
        } catch (JSONException e) {
            throw new IOException("wikidata: bad json: " + e.getMessage());
        }
    }

    static void parseWikidata(PlaceData d, JSONObject root) throws JSONException {
        JSONArray bs = root.getJSONObject("results").getJSONArray("bindings");
        Map<String, Landmark.Kind> classKind = new HashMap<>();
        for (String[] c : WD_CLASSES) for (String q : c[1].split(" ")) classKind.put(q, Landmark.Kind.valueOf(c[0]));
        Map<String, String> waterKind = new HashMap<>();
        for (String[] c : WD_WATER) for (String q : c[1].split(" ")) waterKind.put(q, c[0]);
        for (int i = 0; i < bs.length(); i++) {
            JSONObject b = bs.getJSONObject(i);
            String uri = val(b, "item");
            String qid = uri.substring(uri.lastIndexOf('/') + 1);
            String coord = val(b, "c"); // Point(lon lat)
            int p = coord.indexOf('('), q = coord.indexOf(')');
            if (p < 0 || q < 0) continue;
            String[] ll = coord.substring(p + 1, q).trim().split("\\s+");
            if (ll.length < 2) continue;
            Landmark l = new Landmark();
            try { l.lon = Double.parseDouble(ll[0]); l.lat = Double.parseDouble(ll[1]); } catch (NumberFormatException e) { continue; }
            l.qid = qid;
            l.name = val(b, "itemLabel");
            if (l.name.equals(qid)) l.name = "";
            try { l.sitelinks = Integer.parseInt(val(b, "sl")); } catch (NumberFormatException e) { l.sitelinks = 0; }
            l.height = parseLen(val(b, "h"));
            l.levels = parseLen(val(b, "fl"));
            l.style = styleOf(val(b, "sty"));
            String pop = val(b, "pop");
            if (!pop.isEmpty() && l.sitelinks >= 3) {
                long pv = parsePop(pop.contains(".") ? pop.substring(0, pop.indexOf('.')) : pop);
                if (pv > d.population) d.population = pv;
            }
            String wk = null;
            for (String c : val(b, "cls").split(" ")) if (waterKind.containsKey(c)) wk = waterKind.get(c);
            if (wk != null) {
                if (l.sitelinks < ("river".equals(wk) ? 5 : 10)) continue;   // only notable rivers and lakes
                PlaceData.Water w = new PlaceData.Water();
                w.type = "sea".equals(wk) ? "coast" : wk;
                w.lat = l.lat; w.lon = l.lon; w.name = l.name; w.sizeE = w.sizeN = "river".equals(wk) ? 0 : 2000;
                d.water.add(w);
                continue;
            }
            Landmark.Kind best = null; int br = -1;
            for (String c : val(b, "cls").split(" ")) {
                Landmark.Kind k = classKind.get(c);
                if (k == null) continue;
                int r = wdRank(k);
                if (r > br) { br = r; best = k; }
            }
            if (best == null) continue;
            l.kind = best;
            if (best == Landmark.Kind.HIGHRISE) {
                double h = l.estimateHeightRaw();
                if (Double.isNaN(h) || h < 38) continue;
                if (h >= 95) l.kind = Landmark.Kind.SKYSCRAPER;
                if (Double.isNaN(l.height)) l.height = h;
            }
            if (best == Landmark.Kind.SKYSCRAPER && Double.isNaN(l.estimateHeightRaw())) l.height = Double.NaN;
            if (best == Landmark.Kind.BRIDGE) continue;
            if (l.name.toLowerCase(Locale.ROOT).contains("ruin")) l.ruin = true;
            l.fromWd = true;
            mergeWd(d, l);
        }
    }

    private static int wdRank(Landmark.Kind k) {
        switch (k) {
            case CATHEDRAL: return 9;
            case TV_TOWER: case LIGHTHOUSE: case CHIMNEY: case WATER_TOWER: return 8;
            case MOSQUE: case SYNAGOGUE: case TEMPLE: return 7;
            case CASTLE: return 7;
            case CHURCH: return 6;
            case PALACE: return 5;
            case SKYSCRAPER: return 5;
            case CITY_GATE: case BELL_TOWER: return 5;
            case MONASTERY: return 4;
            case FORT: case TOWN_HALL: return 3;
            case TOWER: return 2;
            case CHAPEL: return 2;
            default: return 0;
        }
    }

    /** Merge a Wikidata item into the OSM landmark list (by QID, else by proximity), or add it. */
    static void mergeWd(PlaceData d, Landmark w) {
        Landmark match = null;
        for (Landmark o : d.landmarks) if (!o.qid.isEmpty() && o.qid.equals(w.qid)) { match = o; break; }
        if (match == null) {
            double bd = 1e9;
            for (Landmark o : d.landmarks) {
                if (!o.qid.isEmpty() && o.fromWd) continue;
                boolean comp = compatible(o, w) || (o.isChurchLike() && w.kind == Landmark.Kind.BELL_TOWER)
                        || (w.kind == Landmark.Kind.CASTLE && o.kind == Landmark.Kind.PALACE) || (w.kind == Landmark.Kind.PALACE && o.kind == Landmark.Kind.CASTLE);
                if (!comp) continue;
                double dd = dist(o.lat, o.lon, w.lat, w.lon);
                double reach = Math.max(60, Math.max(o.sizeE, o.sizeN) * 0.6 + 25);
                if (dd < reach && dd < bd) { bd = dd; match = o; }
            }
        }
        if (match == null && w.fromWd) {
            // Wikidata often has separate items for a church, its tower and its abbey: fold them together
            double bd = 1e9;
            for (Landmark o : d.landmarks) {
                if (o.fromOsm) continue;
                boolean comp = (o.isChurchLike() || o.kind == Landmark.Kind.TOWN_HALL) && (w.isChurchLike() || w.kind == Landmark.Kind.TOWER || w.kind == Landmark.Kind.BELL_TOWER)
                        || (w.isChurchLike() || w.kind == Landmark.Kind.TOWN_HALL) && (o.kind == Landmark.Kind.TOWER || o.kind == Landmark.Kind.BELL_TOWER)
                        || (o.isChurchLike() && w.isChurchLike());
                if (!comp) continue;
                double dd = dist(o.lat, o.lon, w.lat, w.lon);
                if (dd < 70 && dd < bd) { bd = dd; match = o; }
            }
            if (match != null) {
                boolean wTower = w.kind == Landmark.Kind.TOWER || w.kind == Landmark.Kind.BELL_TOWER;
                boolean mTower = match.kind == Landmark.Kind.TOWER || match.kind == Landmark.Kind.BELL_TOWER;
                if (mTower && !wTower) { // the church/town hall becomes the main item, keeping the tower's height
                    Landmark t = match;
                    d.landmarks.remove(t);
                    w.sitelinks = Math.max(w.sitelinks, t.sitelinks);
                    if (!Double.isNaN(t.height) && (Double.isNaN(w.height) || t.height > w.height)) { w.height = t.height; w.towerHeight = true; }
                    if (w.style.isEmpty()) w.style = t.style;
                    w.towers = Math.max(1, w.towers);
                    d.landmarks.add(w);
                    return;
                }
                if (w.sitelinks > match.sitelinks && !wTower) { if (!w.name.isEmpty()) match.name = w.name; match.qid = w.qid; }
                match.sitelinks = Math.max(match.sitelinks, w.sitelinks);
                if (!Double.isNaN(w.height) && (Double.isNaN(match.height) || w.height > match.height)) match.height = w.height;
                if (match.style.isEmpty()) match.style = w.style;
                if (w.kind == Landmark.Kind.CATHEDRAL) match.kind = Landmark.Kind.CATHEDRAL;
                return;
            }
        }
        if (match == null) { d.landmarks.add(w); return; }
        // OSM geometry wins; Wikidata adds notability, height, style. Names: OSM's local name for the same entity,
        // otherwise the most notable item's name
        boolean sameEntity = !match.qid.isEmpty() && match.qid.equals(w.qid);
        if (!w.name.isEmpty() && (match.name.isEmpty() || (!sameEntity && match.fromWd && w.sitelinks > match.sitelinks))) match.name = w.name;
        match.sitelinks = Math.max(match.sitelinks, w.sitelinks);
        if (match.qid.isEmpty()) match.qid = w.qid;
        if (!Double.isNaN(w.height) && w.height > 5) {
            // Wikidata gives the architectural height (to the spire tip); OSM often the roof or a rounded value
            if (Double.isNaN(match.height)) match.height = w.height;
            else if (w.height > match.height) {
                if (match.isTall() && Double.isNaN(match.roofHeight)) match.roofHeight = match.height;
                match.height = w.height;
            }
        }
        if (Double.isNaN(match.levels)) match.levels = w.levels;
        if (!w.style.isEmpty()) match.style = (match.style + " " + w.style).trim();
        if (w.kind == Landmark.Kind.CATHEDRAL && match.kind == Landmark.Kind.CHURCH) match.kind = Landmark.Kind.CATHEDRAL;
        if (w.kind == Landmark.Kind.CASTLE && match.kind == Landmark.Kind.PALACE) match.kind = Landmark.Kind.CASTLE;   // a "Schloss" that Wikidata calls a castle
        match.ruin |= w.ruin;
        match.fromWd = true;
    }

    private static String val(JSONObject b, String k) {
        JSONObject o = b.optJSONObject(k);
        return o == null ? "" : o.optString("value", "");
    }

    // ------------------------------------------------------------------ elevation

    static String OPENTOPO = "https://api.opentopodata.org/v1/mapzen";
    private static volatile long openMeteoBlockedUntil;
    private static final Object TOPO_LOCK = new Object();
    private static long topoLast;

    /**
     * Elevations for the given points (NaN where unavailable), batches of 100 per request. Uses Open-Meteo; when it
     * refuses (rate limit) or fails, falls back to OpenTopoData (public API: 100 points, 1 request per second).
     */
    static double[] elevations(double[] lats, double[] lons, long deadline) throws IOException {
        int n = lats.length;
        double[] out = new double[n];
        java.util.Arrays.fill(out, Double.NaN);
        IOException last = null;
        int ok = 0;
        for (int i0 = 0; i0 < n; i0 += 100) {
            int i1 = Math.min(n, i0 + 100);
            try {
                double[] v = elevationBatch(lats, lons, i0, i1, deadline);
                System.arraycopy(v, 0, out, i0, v.length);
                ok++;
            } catch (IOException e) { last = e; }
        }
        if (ok == 0) throw last != null ? last : new IOException("elevation: no data");
        return out;
    }

    private static double[] elevationBatch(double[] lats, double[] lons, int i0, int i1, long deadline) throws IOException {
        IOException first = null;
        if (System.currentTimeMillis() > openMeteoBlockedUntil) {
            StringBuilder la = new StringBuilder(), lo = new StringBuilder();
            for (int i = i0; i < i1; i++) {
                if (i > i0) { la.append(','); lo.append(','); }
                la.append(String.format(Locale.ROOT, "%.5f", lats[i]));
                lo.append(String.format(Locale.ROOT, "%.5f", lons[i]));
            }
            try {
                String body = Net.get(ELEVATION + "?latitude=" + la + "&longitude=" + lo, deadline, "application/json");
                JSONArray a = new JSONObject(body).getJSONArray("elevation");
                double[] v = new double[i1 - i0];
                for (int i = 0; i < v.length; i++) v[i] = a.optDouble(i, Double.NaN);
                return v;
            } catch (Net.HttpException e) {
                first = e;
                if (e.code == 429) openMeteoBlockedUntil = System.currentTimeMillis() + 15 * 60_000L;
            } catch (IOException e) {
                first = e;
            } catch (JSONException e) {
                first = new IOException("elevation: bad json");
            }
        }
        // fallback: OpenTopoData, politely spaced
        synchronized (TOPO_LOCK) {
            long wait = topoLast + 1100 - System.currentTimeMillis();
            if (wait > 0) {
                if (System.currentTimeMillis() + wait > deadline - 500) throw first != null ? first : new IOException("elevation: no time");
                try { Thread.sleep(wait); } catch (InterruptedException ie) { throw new IOException("interrupted"); }
            }
            StringBuilder q = new StringBuilder();
            for (int i = i0; i < i1; i++) {
                if (i > i0) q.append('|');
                q.append(String.format(Locale.ROOT, "%.5f,%.5f", lats[i], lons[i]));
            }
            try {
                String body = Net.postForm(OPENTOPO, "locations=" + Net.enc(q.toString()), deadline, "application/json");
                JSONArray a = new JSONObject(body).getJSONArray("results");
                double[] v = new double[i1 - i0];
                for (int i = 0; i < v.length; i++) {
                    JSONObject o = a.optJSONObject(i);
                    v[i] = o == null || o.isNull("elevation") ? Double.NaN : o.optDouble("elevation", Double.NaN);
                }
                return v;
            } catch (JSONException e) {
                throw new IOException("opentopodata: bad json");
            } finally {
                topoLast = System.currentTimeMillis();
            }
        }
    }

    // ------------------------------------------------------------------ geometry helpers

    static double dist(double lat1, double lon1, double lat2, double lon2) {
        double[] v = en(lat1, lon1, lat2, lon2);
        return Math.hypot(v[0], v[1]);
    }

    /** East/north metres from point 1 to point 2 (local equirectangular). */
    static double[] en(double lat1, double lon1, double lat2, double lon2) {
        double e = (lon2 - lon1) * M_PER_DEG * Math.cos(Math.toRadians((lat1 + lat2) / 2));
        double n = (lat2 - lat1) * M_PER_DEG;
        return new double[]{e, n};
    }

    /** Point at east/north offset (metres) from lat/lon. */
    static double[] offset(double lat, double lon, double e, double n) {
        double la = lat + n / M_PER_DEG;
        double lo = lon + e / (M_PER_DEG * Math.cos(Math.toRadians(lat)));
        return new double[]{la, lo};
    }

}
