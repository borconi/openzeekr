package io.aawireless.wirelessaamanipulator.hud;

/** A unified point of interest for the HUD: a Waze report or a fixed GATSO camera. */
public final class HudMarker {

    public enum Kind { POLICE, SPEED_TRAP, CAMERA }

    public final String id;
    public final Kind kind;
    public final double lat;
    public final double lon;
    public final int reports;       // Waze num_thumbs_up; -1 if not applicable (cameras)
    public final long publishMillis; // Waze publish time; 0 for cameras
    public final int speed;         // camera speed limit km/h; 0 if unknown

    public HudMarker(String id, Kind kind, double lat, double lon, int reports, long publishMillis, int speed) {
        this.id = id;
        this.kind = kind;
        this.lat = lat;
        this.lon = lon;
        this.reports = reports;
        this.publishMillis = publishMillis;
        this.speed = speed;
    }

    public boolean cameraLike() {
        return kind == Kind.CAMERA || kind == Kind.SPEED_TRAP;
    }

    public static HudMarker fromWaze(io.aawireless.wirelessaamanipulator.hud.waze.PoliceAlert a) {
        Kind k = a.isSpeedTrap() ? Kind.SPEED_TRAP : Kind.POLICE;
        return new HudMarker(a.id != null ? a.id : (a.latitude + "," + a.longitude),
                k, a.latitude, a.longitude, a.numThumbsUp, a.publishMillis, 0);
    }
}
