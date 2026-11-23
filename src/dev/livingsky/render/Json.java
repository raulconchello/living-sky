package dev.livingsky.render;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small JSON reader in plain Java (no org.json), so the forecast, ensemble and radar parsers run the same on the
 * phone and in the desktop harness. Objects are maps, arrays lists, numbers doubles; JSON null is Java null.
 */
public final class Json {
    private final String s;
    private int i;

    private Json(String s) { this.s = s; }

    public static Object parse(String s) {
        Json j = new Json(s);
        j.ws();
        Object v = j.value();
        j.ws();
        if (j.i != s.length()) throw new IllegalArgumentException("json: trailing data at " + j.i);
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object o) { return o instanceof Map ? (Map<String, Object>) o : null; }

    @SuppressWarnings("unchecked")
    public static List<Object> arr(Object o) { return o instanceof List ? (List<Object>) o : null; }

    public static Map<String, Object> obj(Map<String, Object> m, String k) { return m == null ? null : obj(m.get(k)); }

    public static List<Object> arr(Map<String, Object> m, String k) { return m == null ? null : arr(m.get(k)); }

    public static double num(Map<String, Object> m, String k, double def) {
        Object v = m == null ? null : m.get(k);
        return v instanceof Double ? (Double) v : def;
    }

    public static String str(Map<String, Object> m, String k, String def) {
        Object v = m == null ? null : m.get(k);
        return v instanceof String ? (String) v : def;
    }

    /** A numeric array (nulls -> def, times scale), or an empty array when missing. */
    public static double[] nums(Map<String, Object> m, String k, double scale, double def) {
        List<Object> a = arr(m, k);
        if (a == null) return new double[0];
        double[] r = new double[a.size()];
        for (int n = 0; n < r.length; n++) r[n] = a.get(n) instanceof Double ? (Double) a.get(n) * scale : def;
        return r;
    }

    /** JSON string literal (for the small caches this app writes). */
    public static String quote(String v) {
        StringBuilder b = new StringBuilder("\"");
        for (int n = 0; n < v.length(); n++) {
            char c = v.charAt(n);
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
            else b.append(c);
        }
        return b.append('"').toString();
    }

    private void ws() { while (i < s.length() && s.charAt(i) <= ' ') i++; }

    private Object value() {
        if (i >= s.length()) throw new IllegalArgumentException("json: unexpected end");
        char c = s.charAt(i);
        switch (c) {
            case '{': return object();
            case '[': return array();
            case '"': return string();
            case 't': lit("true"); return Boolean.TRUE;
            case 'f': lit("false"); return Boolean.FALSE;
            case 'n': lit("null"); return null;
            default: return number();
        }
    }

    private void lit(String w) {
        if (!s.startsWith(w, i)) throw new IllegalArgumentException("json: bad literal at " + i);
        i += w.length();
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<>();
        i++;
        ws();
        if (s.charAt(i) == '}') { i++; return m; }
        while (true) {
            ws();
            String k = string();
            ws();
            if (s.charAt(i++) != ':') throw new IllegalArgumentException("json: ':' expected at " + (i - 1));
            ws();
            m.put(k, value());
            ws();
            char c = s.charAt(i++);
            if (c == '}') return m;
            if (c != ',') throw new IllegalArgumentException("json: ',' expected at " + (i - 1));
        }
    }

    private List<Object> array() {
        List<Object> a = new ArrayList<>();
        i++;
        ws();
        if (s.charAt(i) == ']') { i++; return a; }
        while (true) {
            ws();
            a.add(value());
            ws();
            char c = s.charAt(i++);
            if (c == ']') return a;
            if (c != ',') throw new IllegalArgumentException("json: ',' expected at " + (i - 1));
        }
    }

    private String string() {
        if (s.charAt(i) != '"') throw new IllegalArgumentException("json: string expected at " + i);
        i++;
        StringBuilder b = null;
        int start = i;
        while (true) {
            char c = s.charAt(i++);
            if (c == '"') return b == null ? s.substring(start, i - 1) : b.toString();
            if (c == '\\') {
                if (b == null) b = new StringBuilder(s.substring(start, i - 1));
                char e = s.charAt(i++);
                switch (e) {
                    case 'n': b.append('\n'); break;
                    case 't': b.append('\t'); break;
                    case 'r': b.append('\r'); break;
                    case 'b': b.append('\b'); break;
                    case 'f': b.append('\f'); break;
                    case 'u': b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
                    default: b.append(e);
                }
            } else if (b != null) b.append(c);
        }
    }

    private Double number() {
        int start = i;
        while (i < s.length()) {
            char c = s.charAt(i);
            if ((c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') i++;
            else break;
        }
        if (start == i) throw new IllegalArgumentException("json: value expected at " + i);
        return Double.parseDouble(s.substring(start, i));
    }
}
