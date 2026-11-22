package dev.livingsky.render;

/** Solar elevation, NOAA approximation (same formula as sky8.js solarEl, generalised to any lat/lon, UTC time). */
public final class Sun {
    private Sun() {}

    /** @param tH hours since the Unix epoch (UTC) */
    public static double elevation(double tH, double latDeg, double lonDeg) {
        long day = (long) Math.floor(tH / 24.0);
        double hrUtc = tH - day * 24.0;
        int doy = java.time.LocalDate.ofEpochDay(day).getDayOfYear();
        double g = 2 * Math.PI / 365 * (doy - 1 + (hrUtc - 12) / 24);
        double decl = 0.006918 - 0.399912 * Math.cos(g) + 0.070257 * Math.sin(g) - 0.006758 * Math.cos(2 * g)
                + 0.000907 * Math.sin(2 * g) - 0.002697 * Math.cos(3 * g) + 0.00148 * Math.sin(3 * g);
        double eqt = 229.18 * (0.000075 + 0.001868 * Math.cos(g) - 0.032077 * Math.sin(g)
                - 0.014615 * Math.cos(2 * g) - 0.040849 * Math.sin(2 * g));
        double lat = Math.toRadians(latDeg);
        double tst = hrUtc * 60 + eqt + 4 * lonDeg;
        double ha = Math.toRadians(tst / 4 - 180);
        double cz = Math.sin(lat) * Math.sin(decl) + Math.cos(lat) * Math.cos(decl) * Math.cos(ha);
        cz = Math.max(-1, Math.min(1, cz));
        return 90 - Math.toDegrees(Math.acos(cz));
    }
}
