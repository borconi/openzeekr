package io.aawireless.wirelessaamanipulator.hud;

/** Small great-circle helpers. All angles in degrees, distances in metres. */
public final class Geo {

    private static final double EARTH_RADIUS_M = 6_371_000.0;

    private Geo() {}

    /** Haversine distance in metres between two lat/lng points. */
    public static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return EARTH_RADIUS_M * c;
    }

    /**
     * Initial bearing from point 1 to point 2, in degrees clockwise from true north,
     * normalised to [0, 360).
     */
    public static double bearingDegrees(double lat1, double lon1, double lat2, double lon2) {
        double phi1 = Math.toRadians(lat1);
        double phi2 = Math.toRadians(lat2);
        double dLon = Math.toRadians(lon2 - lon1);
        double y = Math.sin(dLon) * Math.cos(phi2);
        double x = Math.cos(phi1) * Math.sin(phi2)
                - Math.sin(phi1) * Math.cos(phi2) * Math.cos(dLon);
        double deg = Math.toDegrees(Math.atan2(y, x));
        return (deg + 360.0) % 360.0;
    }

    /**
     * Relative bearing of a target given our heading, normalised to (-180, 180].
     * 0 = dead ahead, +90 = to the right, -90 = to the left, ±180 = behind.
     */
    public static double relativeBearing(double headingDeg, double targetBearingDeg) {
        double rel = targetBearingDeg - headingDeg;
        rel = ((rel + 180.0) % 360.0 + 360.0) % 360.0 - 180.0;
        return rel;
    }

    /**
     * Offset a lat/lng by north/east metres. Good enough for building a small
     * bounding box around the car (flat-earth approximation over a few km).
     */
    public static double[] offsetMeters(double lat, double lon, double northM, double eastM) {
        double dLat = northM / EARTH_RADIUS_M;
        double dLon = eastM / (EARTH_RADIUS_M * Math.cos(Math.toRadians(lat)));
        return new double[]{lat + Math.toDegrees(dLat), lon + Math.toDegrees(dLon)};
    }
}
