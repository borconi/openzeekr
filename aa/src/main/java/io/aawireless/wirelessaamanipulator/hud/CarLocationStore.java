package io.aawireless.wirelessaamanipulator.hud;

/**
 * Latest vehicle position + heading, fed from the Android Auto {@code LocationData}
 * sensor stream we already MITM (see EnergyModelTranslator). This intentionally
 * replaces FusedLocationProviderClient: the car streams GPS continuously over AA,
 * so tapping it costs the phone nothing (no GPS wake, no extra heat).
 *
 * All access is via volatiles - one writer (the sensor reader thread), many readers
 * (the Waze poller, the compass renderer).
 */
public final class CarLocationStore {

    private static volatile double latitude;
    private static volatile double longitude;
    /** Heading in degrees clockwise from true north, [0,360). NaN until known. */
    private static volatile double bearingDeg = Double.NaN;
    /** Ground speed in m/s, or NaN if unknown. */
    private static volatile double speedMps = Double.NaN;
    private static volatile long updatedAtMillis = 0L;

    private CarLocationStore() {}

    /** Fed from LocationData: latitude_e7 / longitude_e7 / bearing_e6 / speed_e3. */
    public static void update(int latitudeE7, int longitudeE7, int bearingE6, int speedE3,
                             boolean hasBearing, boolean hasSpeed) {
        latitude = latitudeE7 / 1e7;
        longitude = longitudeE7 / 1e7;
        if (hasBearing) bearingDeg = bearingE6 / 1e6;
        if (hasSpeed) speedMps = speedE3 / 1e3;
        updatedAtMillis = System.currentTimeMillis();
    }

    public static boolean hasFix() {
        return updatedAtMillis != 0L;
    }

    public static double getLatitude() { return latitude; }
    public static double getLongitude() { return longitude; }

    /** Heading in degrees, or NaN if the car hasn't reported one yet. */
    public static double getBearingDeg() { return bearingDeg; }
    public static double getSpeedMps() { return speedMps; }
    public static long getUpdatedAtMillis() { return updatedAtMillis; }

    /** Age of the current fix in millis (Long.MAX_VALUE if we never got one). */
    public static long ageMillis() {
        long t = updatedAtMillis;
        return t == 0L ? Long.MAX_VALUE : System.currentTimeMillis() - t;
    }
}
