package io.aawireless.wirelessaamanipulator.hud;

/**
 * Central knobs for the police-HUD. Defaults chosen for the Zeekr cluster
 * (800x480 H264 BP, service id 21) but overridable at runtime for testing.
 */
public final class HudConfig {

    private HudConfig() {}

    /**
     * When true, the cluster video-sink + its input are stripped from the SDR we
     * forward to gearhead (so AA never projects to the cluster) and we drive it
     * ourselves. Keep false until the video session is actually wired, otherwise the
     * cluster just goes blank/native with nothing replacing it.
     */
    public static volatile boolean ownCluster = true;

    /**
     * First-bring-up aid: stream a bright moving test pattern instead of the compass,
     * to confirm the car actually displays our video on the cluster. Flip to false
     * once the pipeline is confirmed on-car.
     */
    public static volatile boolean testPattern = false;

    /**
     * Whether to ALSO open the cluster's paired input channel (id 22) after the video
     * channel (21). On-car, opening 22 in the middle of 21's Setup->Config->Focus
     * handshake makes the car withhold focus on 21 and the cluster stays black. The
     * known-good path opens 21 only, so this stays false. Flip to true only to retest
     * the paired-input theory. */
    public static volatile boolean openClusterInput = true;

    // Cluster video encoder settings. Low fps: the compass changes slowly, and every
    // extra frame is CPU + heat we don't need.
    public static volatile int clusterWidth = 800;
    public static volatile int clusterHeight = 480;
    public static volatile int clusterFps = 10;
    // Low bitrate keeps H264 frames small - ideally each access unit stays under the
    // ~15KB single-AA-frame limit so we avoid the (car-rejected) fragmentation path.
    public static volatile int clusterBitrate = 600_000;
    /** Keyframe interval, seconds. Longer = fewer big IDR frames. */
    public static volatile int clusterKeyframeIntervalS = 3;

    /**
     * Re-send the CODEC_CONFIG (SPS/PPS) AA message just before every keyframe. We send it
     * once at start by default (known-good). If the car's cluster decoder re-initialises
     * mid-stream, a bare IDR can't be decoded -> permanent silent black while the transport
     * keeps ACKing. Re-sending SPS/PPS via the SAME message the car accepts at startup lets
     * it re-sync on the next keyframe. OFF by default because it changes the proven start
     * path (encoder-level prepend broke startup); flip on to test the silent-black fix.
     */
    public static volatile boolean resendCodecConfig = false;
}
