package dev.livingsky.render;

/**
 * Low-precision Moon position (Paul Schlyter's method, ~1-2 deg) and phase, for drawing the moon on clear nights.
 * @see <a href="https://stjarnhimlen.se/comp/ppcomp.html">Computing planetary positions</a>
 */
public final class Moon {
    private Moon() {}

    /** Debug (--es moonage): the moon's age in days (0 new, 7.4 first quarter, 14.8 full, 22.1 last quarter); NaN = real. */
    public static volatile double ageOverride = Double.NaN;

    static final double SYNODIC = 29.530588853;

    /**
     * @return {altitudeDeg, azimuthDeg (0 = N, 90 = E), illuminatedFraction 0..1, waxing ? 1 : 0, limbDeg}: limbDeg is
     * the on-screen direction of the bright limb (degrees, 0 = right, 90 = down; the card faces south, east on the
     * left), from the real Sun-Moon geometry: the lit side always points toward the Sun, tilted as seen from here.
     */
    public static double[] at(double tH, double latDeg, double lonDeg) {
        double d = tH / 24.0 - 10956.0; // days since 2000 Jan 0.0 UT (1999-12-31 00:00), Schlyter epoch
        double N = rev(125.1228 - 0.0529538083 * d), i = 5.1454, w = rev(318.0634 + 0.1643573223 * d);
        double a = 60.2666, e = 0.054900, M = rev(115.3654 + 13.0649929509 * d);
        double E = M + Math.toDegrees(e * sin(M) * (1 + e * cos(M)));
        for (int k = 0; k < 3; k++) E = E - (E - Math.toDegrees(e * sin(E)) - M) / (1 - e * cos(E));
        double xv = a * (cos(E) - e), yv = a * Math.sqrt(1 - e * e) * sin(E);
        double v = Math.toDegrees(Math.atan2(yv, xv)), r = Math.sqrt(xv * xv + yv * yv);
        double xh = r * (cos(N) * cos(v + w) - sin(N) * sin(v + w) * cos(i));
        double yh = r * (sin(N) * cos(v + w) + cos(N) * sin(v + w) * cos(i));
        double zh = r * (sin(v + w) * sin(i));
        double lon = Math.toDegrees(Math.atan2(yh, xh)), lat = Math.toDegrees(Math.atan2(zh, Math.sqrt(xh * xh + yh * yh)));
        // main perturbations in longitude (largest terms)
        double Ms = rev(356.0470 + 0.9856002585 * d), ws = 282.9404 + 4.70935E-5 * d;
        double Ls = rev(Ms + ws), Lm = rev(N + w + M), Dm = rev(Lm - Ls), F = rev(Lm - N);
        lon += -1.274 * sin(M - 2 * Dm) + 0.658 * sin(2 * Dm) - 0.186 * sin(Ms) - 0.059 * sin(2 * M - 2 * Dm)
                - 0.057 * sin(M - 2 * Dm + Ms) + 0.053 * sin(M + 2 * Dm) + 0.046 * sin(2 * Dm - Ms) + 0.041 * sin(M - Ms);
        lat += -0.173 * sin(F - 2 * Dm);
        double ecl = 23.4393 - 3.563E-7 * d;
        double xe = cos(lat) * cos(lon), ye = cos(ecl) * cos(lat) * sin(lon) - sin(ecl) * sin(lat);
        double ze = sin(ecl) * cos(lat) * sin(lon) + cos(ecl) * sin(lat);
        double ra = Math.toDegrees(Math.atan2(ye, xe)), dec = Math.toDegrees(Math.atan2(ze, Math.sqrt(xe * xe + ye * ye)));
        double ut = ((tH % 24) + 24) % 24;
        double gmst0 = rev(Ls + 180) / 15.0;
        double lst = gmst0 + ut + lonDeg / 15.0;
        double ha = rev(lst * 15 - ra);
        double x = cos(ha) * cos(dec), y = sin(ha) * cos(dec), z = sin(dec);
        double xhor = x * sin(latDeg) - z * cos(latDeg), zhor = x * cos(latDeg) + z * sin(latDeg);
        double az = rev(Math.toDegrees(Math.atan2(y, xhor)) + 180), alt = Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, zhor))));
        // phase from the true elongation (Moon - Sun ecliptic longitude): 0 new, 90 first quarter, 180 full
        double sunLon = rev(Ls + 1.915 * sin(Ms) + 0.020 * sin(2 * Ms));
        double elong = rev(lon - sunLon);
        double age = ageOverride;
        if (!Double.isNaN(age)) { // debug: the same moon, with the Sun placed for that age
            elong = rev((((age % SYNODIC) + SYNODIC) % SYNODIC) / SYNODIC * 360);
            sunLon = rev(lon - elong);
        }
        double frac = (1 - cos(elong)) / 2;
        // bright limb: position angle chi (from celestial north toward east) of the Sun seen from the Moon, then
        // relative to the local vertical with the parallactic angle q (Meeus, ch. 14 and 48)
        double ras = Math.toDegrees(Math.atan2(cos(ecl) * sin(sunLon), cos(sunLon))), decs = Math.toDegrees(Math.asin(sin(ecl) * sin(sunLon)));
        double chi = Math.toDegrees(Math.atan2(cos(decs) * sin(ras - ra), sin(decs) * cos(dec) - cos(decs) * sin(dec) * cos(ras - ra)));
        double q = Math.toDegrees(Math.atan2(sin(ha), Math.tan(Math.toRadians(latDeg)) * cos(dec) - sin(dec) * cos(ha)));
        double zen = chi - q;                                      // from "up", toward the east (= left on the card)
        double limb = Math.toDegrees(Math.atan2(-cos(zen), -sin(zen)));   // screen: (-sin zen, -cos zen)
        return new double[]{alt, az, frac, elong < 180 ? 1 : 0, limb};
    }

    private static double rev(double x) { return x - Math.floor(x / 360.0) * 360.0; }
    private static double sin(double deg) { return Math.sin(Math.toRadians(deg)); }
    private static double cos(double deg) { return Math.cos(Math.toRadians(deg)); }
}
