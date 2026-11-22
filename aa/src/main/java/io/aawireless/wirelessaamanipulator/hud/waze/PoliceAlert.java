package io.aawireless.wirelessaamanipulator.hud.waze;

/** One Waze alert (police or mobile speed trap), trimmed to what the HUD needs. */
public final class PoliceAlert {
    public final String id;
    public final String type;           // e.g. POLICE
    public final String subtype;        // POLICE_HIDING, POLICE_WITH_MOBILE_CAMERA, POLICE_VISIBLE, or null
    public final double latitude;
    public final double longitude;
    public final long publishMillis;    // epoch millis of publish_datetime_utc, 0 if unknown
    public final int numThumbsUp;       // "number of reports" (confirmations)

    public PoliceAlert(String id, String type, String subtype, double latitude, double longitude,
                       long publishMillis, int numThumbsUp) {
        this.id = id;
        this.type = type;
        this.subtype = subtype;
        this.latitude = latitude;
        this.longitude = longitude;
        this.publishMillis = publishMillis;
        this.numThumbsUp = numThumbsUp;
    }

    /** True for a mobile speed trap (handheld/car radar) -> draw the camera icon. */
    public boolean isSpeedTrap() {
        return subtype != null && subtype.contains("CAMERA");
    }
}
