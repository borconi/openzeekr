package io.aawireless.wirelessaamanipulator.hud;

/**
 * What the car advertised about its instrument cluster / secondary displays, read
 * out of the ServiceDiscoveryResponse. This gates the whole HUD feature:
 *
 *  - If {@link #imageCluster} is true we can push raw bitmaps to the cluster via
 *    NavigationNextTurnEvent.image (id 32772), sized {@link #width}x{@link #height}
 *    at {@link #colourDepthBits}.
 *  - If it's ENUM only, the cluster takes turn-enums, not pixels -> fall back to the
 *    video-sink route ({@link #videoDisplays}).
 *
 * Populated once per connection by EnergyModelTranslator.patchServiceDiscoveryResponse.
 */
public final class ClusterInfo {

    private static volatile boolean captured = false;
    private static volatile boolean hasNavStatusService = false;
    private static volatile boolean imageCluster = false;
    private static volatile int navServiceId = -1;   // AA channel id for the nav-status service
    private static volatile int minIntervalMs = 0;
    private static volatile int width = 0;
    private static volatile int height = 0;
    private static volatile int colourDepthBits = 0;
    /** Human-readable dump of any MediaSink displays (for the fallback route). */
    private static volatile String videoDisplays = "";

    // The cluster VIDEO sink (DISPLAY_TYPE_CLUSTER) - the route we actually use on
    // cars whose nav cluster is ENUM (e.g. Zeekr). serviceId is the AA channel.
    private static volatile boolean hasVideoCluster = false;
    private static volatile int videoClusterServiceId = -1;
    private static volatile int videoClusterInputServiceId = -1;
    private static volatile int videoClusterWidth = 0;
    private static volatile int videoClusterHeight = 0;

    public static void setVideoCluster(int serviceId, int width, int height) {
        hasVideoCluster = true;
        videoClusterServiceId = serviceId;
        videoClusterWidth = width;
        videoClusterHeight = height;
    }

    public static void setVideoClusterInput(int serviceId) {
        videoClusterInputServiceId = serviceId;
    }

    // Safe drawable area = codec margins + content insets, per side. The car covers /
    // reserves the rest (e.g. Zeekr: right margin 167, insets 77 all sides), so drawing
    // outside this is clipped or hidden.
    private static volatile int insetLeft = 0, insetTop = 0, insetRight = 0, insetBottom = 0;

    public static void setSafeInsets(int left, int top, int right, int bottom) {
        insetLeft = left; insetTop = top; insetRight = right; insetBottom = bottom;
    }
    /**
     * Every video config the car advertised for the cluster sink, indexed EXACTLY like the
     * car's list (Config.configuration_indices / Start.configuration_index refer to these
     * positions): {width, height, insetL, insetT, insetR, insetB, usable(1/0)}. "usable" =
     * a codec we can encode (H.264 Baseline).
     */
    private static volatile int[][] clusterConfigs = new int[0][];

    public static void setVideoClusterConfigs(int[][] cfgs) { clusterConfigs = cfgs; }

    /** Apply the config the car picked (its index in the car's list). */
    public static boolean selectVideoClusterConfig(int idx) {
        int[][] c = clusterConfigs;
        if (idx < 0 || idx >= c.length || c[idx][6] == 0) return false;
        videoClusterWidth = c[idx][0];
        videoClusterHeight = c[idx][1];
        setSafeInsets(c[idx][2], c[idx][3], c[idx][4], c[idx][5]);
        return true;
    }

    public static int getInsetLeft() { return insetLeft; }
    public static int getInsetTop() { return insetTop; }
    public static int getInsetRight() { return insetRight; }
    public static int getInsetBottom() { return insetBottom; }

    public static boolean hasVideoCluster() { return hasVideoCluster; }
    public static int getVideoClusterServiceId() { return videoClusterServiceId; }
    public static int getVideoClusterInputServiceId() { return videoClusterInputServiceId; }

    /**
     * The highest service id gearhead still opens (i.e. the id just below our cluster
     * sink). We wait for the car's ChannelOpenResponse on THIS channel before opening
     * ours, so we don't race gearhead's channel setup.
     */
    private static volatile int openTriggerChannelId = -1;
    public static void setOpenTriggerChannelId(int id) { openTriggerChannelId = id; }
    public static int getOpenTriggerChannelId() { return openTriggerChannelId; }
    public static int getVideoClusterWidth() { return videoClusterWidth; }
    public static int getVideoClusterHeight() { return videoClusterHeight; }

    private ClusterInfo() {}

    /**
     * Forget everything learned from the previous car's SDR. All fields here are static
     * and outlive a connection, so without this a second car in the same app process
     * inherits the Zeekr's cluster ids (sink 21 / input 22): we would then strip that
     * car's services 21/22 from the SDR, swallow its traffic on those channels and open
     * a "cluster" channel that isn't one - and projection never starts.
     */
    public static void reset() {
        captured = false;
        hasNavStatusService = false;
        imageCluster = false;
        navServiceId = -1;
        minIntervalMs = 0;
        width = 0;
        height = 0;
        colourDepthBits = 0;
        videoDisplays = "";
        hasVideoCluster = false;
        videoClusterServiceId = -1;
        videoClusterInputServiceId = -1;
        videoClusterWidth = 0;
        videoClusterHeight = 0;
        insetLeft = insetTop = insetRight = insetBottom = 0;
        clusterConfigs = new int[0][];
        openTriggerChannelId = -1;
    }

    public static void setNavStatus(int serviceId, boolean image, int minIntervalMs,
                                    int width, int height, int colourDepthBits) {
        ClusterInfo.navServiceId = serviceId;
        ClusterInfo.hasNavStatusService = true;
        ClusterInfo.imageCluster = image;
        ClusterInfo.minIntervalMs = minIntervalMs;
        ClusterInfo.width = width;
        ClusterInfo.height = height;
        ClusterInfo.colourDepthBits = colourDepthBits;
        ClusterInfo.captured = true;
    }

    public static void setNavStatusEnumOnly(int serviceId, int minIntervalMs) {
        ClusterInfo.navServiceId = serviceId;
        ClusterInfo.hasNavStatusService = true;
        ClusterInfo.imageCluster = false;
        ClusterInfo.minIntervalMs = minIntervalMs;
        ClusterInfo.captured = true;
    }

    public static void setVideoDisplays(String dump) { videoDisplays = dump; }

    public static boolean isCaptured() { return captured; }
    public static boolean hasNavStatusService() { return hasNavStatusService; }
    public static boolean isImageCluster() { return imageCluster; }
    public static int getNavServiceId() { return navServiceId; }
    public static int getMinIntervalMs() { return minIntervalMs; }
    public static int getWidth() { return width; }
    public static int getHeight() { return height; }
    public static int getColourDepthBits() { return colourDepthBits; }
    public static String getVideoDisplays() { return videoDisplays; }

    public static String summary() {
        if (!captured) return "ClusterInfo: not captured yet";
        if (!hasNavStatusService) return "ClusterInfo: car advertised NO NavigationStatusService";
        return "ClusterInfo: navServiceId=" + navServiceId
                + " type=" + (imageCluster ? "IMAGE" : "ENUM")
                + " size=" + width + "x" + height + "@" + colourDepthBits + "bpp"
                + " minInterval=" + minIntervalMs + "ms"
                + (videoDisplays.isEmpty() ? "" : " | video[" + videoDisplays + "]");
    }
}
