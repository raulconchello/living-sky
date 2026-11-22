package dev.livingsky.skyline;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;

/** Minimal blocking HTTP client (HttpURLConnection) with a hard deadline. Android-safe. */
final class Net {
    static final String USER_AGENT = "LivingSkyWidget/1.0";
    /** Max response size we accept (bytes). */
    static final int MAX_BYTES = 12 * 1024 * 1024;

    private Net() {}

    static final class HttpException extends IOException {
        final int code;
        HttpException(int code, String msg) { super("HTTP " + code + ": " + msg); this.code = code; }
    }

    static String enc(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); } catch (java.io.UnsupportedEncodingException e) { throw new RuntimeException(e); }
    }

    static String get(String url, long deadline, String accept) throws IOException {
        return request(url, null, deadline, accept);
    }

    static String postForm(String url, String form, long deadline, String accept) throws IOException {
        return request(url, form, deadline, accept);
    }

    private static String request(String url, String form, long deadline, String accept) throws IOException {
        long left = deadline - System.currentTimeMillis();
        if (left < 300) throw new IOException("no time left");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout((int) Math.min(left, 5000));
            c.setReadTimeout((int) Math.max(300, left));
            c.setUseCaches(false);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", USER_AGENT);
            c.setRequestProperty("Accept-Encoding", "gzip");
            if (accept != null) c.setRequestProperty("Accept", accept);
            if (form != null) {
                byte[] body = form.getBytes(StandardCharsets.UTF_8);
                c.setDoOutput(true);
                c.setRequestMethod("POST");
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
                c.setFixedLengthStreamingMode(body.length);
                try (OutputStream os = c.getOutputStream()) { os.write(body); }
            }
            int code = c.getResponseCode();
            if (code != 200) {
                String msg = "";
                try {
                    InputStream es = c.getErrorStream();
                    if (es != null) {
                        if ("gzip".equalsIgnoreCase(c.getContentEncoding())) es = new GZIPInputStream(es);
                        try (InputStream e2 = es) { msg = new String(readAll(e2, 4000, deadline + 1000), StandardCharsets.UTF_8); }
                    }
                } catch (IOException ignored) { }
                msg = msg.replaceAll("\\s+", " ");
                throw new HttpException(code, msg.length() > 160 ? msg.substring(0, 160) : msg);
            }
            InputStream in = c.getInputStream();
            if ("gzip".equalsIgnoreCase(c.getContentEncoding())) in = new GZIPInputStream(in);
            try (InputStream is = in) {
                return new String(readAll(is, MAX_BYTES, deadline), StandardCharsets.UTF_8);
            }
        } finally {
            c.disconnect();
        }
    }

    private static byte[] readAll(InputStream in, int max, long deadline) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream(16384);
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) {
            bo.write(buf, 0, n);
            if (bo.size() > max) throw new IOException("response too large");
            if (System.currentTimeMillis() > deadline) throw new IOException("deadline exceeded while reading");
        }
        return bo.toByteArray();
    }
}
