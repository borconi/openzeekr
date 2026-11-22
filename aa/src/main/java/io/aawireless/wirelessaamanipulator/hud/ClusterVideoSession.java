package io.aawireless.wirelessaamanipulator.hud;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.media.MediaCodec;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import io.aawireless.wirelessaamanipulator.helpers.AAMessage;
import io.aawireless.wirelessaamanipulator.helpers.interfaces.ServiceThreadPair;
import io.aawireless.wirelessaamanipulator.proto.ChannelOpenRequest;
import io.aawireless.wirelessaamanipulator.proto.Config;
import io.aawireless.wirelessaamanipulator.proto.ControlMessageType;
import io.aawireless.wirelessaamanipulator.proto.MediaCodecType;
import io.aawireless.wirelessaamanipulator.proto.MediaMessageId;
import io.aawireless.wirelessaamanipulator.proto.Setup;
import io.aawireless.wirelessaamanipulator.proto.Start;
import io.aawireless.wirelessaamanipulator.proto.VideoFocusMode;
import io.aawireless.wirelessaamanipulator.proto.VideoFocusNotification;
import io.aawireless.wirelessaamanipulator.proto.VideoFocusRequestNotification;

/**
 * Owns the cluster video sink (we strip it from gearhead's SDR) and drives the full
 * AA A/V handshake as the phone, then streams the compass as H.264.
 *
 * Sequence (corrected on-car): the car does NOT initiate - the phone must, AND must
 * request video focus before Start (sending Start straight after Config gets
 * MESSAGE_UNEXPECTED_MESSAGE=255):
 *   -> ChannelOpenRequest
 *   <- ChannelOpenResponse
 *   -> Setup{H264_BP}
 *   <- Config{READY, cfgIdx, maxUnacked}
 *   -> VideoFocusRequest{PROJECTED}     (the step that was missing)
 *   <- VideoFocusNotification            (grant; we also fall back after a timeout)
 *   -> Start{session, cfgIdx}
 *   -> CODEC_CONFIG(SPS/PPS), DATA(ts + h264) ...
 *   <- Ack
 */
public final class ClusterVideoSession {

    private static final String TAG = "ClusterVideoSession";

    private static final int MSG_CHANNEL_OPEN_RESPONSE = ControlMessageType.MESSAGE_CHANNEL_OPEN_RESPONSE_VALUE; // 8
    private static final int MSG_CONFIG                 = MediaMessageId.MEDIA_MESSAGE_CONFIG_VALUE;               // 32771
    private static final int MSG_ACK                    = MediaMessageId.MEDIA_MESSAGE_ACK_VALUE;                  // 32772
    private static final int MSG_VIDEO_FOCUS_NOTIF      = MediaMessageId.MEDIA_MESSAGE_VIDEO_FOCUS_NOTIFICATION_VALUE; // 32776
    private static final int MSG_UNEXPECTED             = 255;

    /** If the car grants focus silently, Start anyway this long after the focus request. */
    private static final long FOCUS_GRANT_FALLBACK_MS = 2500;
    /** If we hit the unacked ceiling but the car hasn't acked in this long, assume it
     *  isn't acking and keep streaming rather than freezing forever. */
    private static final long ACK_STALL_MS = 1500;
    /** Re-run the handshake if Start hasn't happened this long after opening the channel. */
    private static final long HANDSHAKE_TIMEOUT_MS = 6000;
    /** Re-establish the whole session if no frame has been sent for this long while streaming. */
    private static final long STREAM_STALL_MS = 4000;
    /** Supervisor tick. */
    private static final long SUPERVISE_MS = 1000;
    /** Once gearhead's last channel-open response arrived, wait for this quiet gap
     *  (no further opens) before opening ours - i.e. gearhead has finished. */
    private static final long OPEN_SETTLE_MS = 1200;
    /** Absolute fallback: open ours anyway this long after the link became ready, even
     *  if we never observed gearhead's channel-open handshakes. */
    private static final long OPEN_MAX_WAIT_MS = 8000;

    private static final int MSG_CHANNEL_OPEN_REQUEST = ControlMessageType.MESSAGE_CHANNEL_OPEN_REQUEST_VALUE; // 7

    private final ServiceThreadPair service;
    private final HudState hudState;
    private final CompassRenderer renderer = new CompassRenderer();

    private volatile Thread supervisor;
    private volatile boolean running;
    private volatile boolean channelOpenSent;
    private volatile boolean inputOpenSent;
    private volatile boolean focusRequested;
    private volatile boolean started;   // Start sent + encoder streaming

    // Gearhead channel-open observation, so we open ours only after it has finished.
    private volatile long readySinceMs = 0;
    private volatile long lastOpenRespMs = 0;
    private volatile boolean triggerChannelOpened = false;

    private int configurationIndex = 0;
    private volatile int sessionId = 1;   // bumped on each focus regain (see rearmStream)
    private int maxUnacked = 4;
    private volatile int unacked = 0;
    private volatile long lastAckMs = 0;
    private volatile long handshakeStartedMs = 0;
    private volatile long lastFrameSentMs = 0;
    private volatile Runnable onStreaming;

    // Diagnostics: make the "black screen" state observable from a single log line.
    private volatile long framesSent = 0;      // DATA access units handed to the car
    private volatile long acksReceived = 0;    // ACKs the car sent back
    private volatile long lastHeartbeatMs = 0;
    private volatile boolean loggedFirstCodecConfig = false;
    private volatile boolean loggedFirstKeyframe = false;
    private volatile boolean loggedFirstAck = false;
    private volatile byte[] codecConfigBytes;   // SPS/PPS, for optional re-send before keyframes

    private ClusterVideoEncoder encoder;

    public ClusterVideoSession(ServiceThreadPair service, HudState hudState) {
        this.service = service;
        this.hudState = hudState;
    }

    /** Invoked once, after the encoder starts - used to kick off GATSO off the video path. */
    public void setOnStreaming(Runnable r) { this.onStreaming = r; }

    public synchronized void start() {
        if (running) return;
        running = true;
        Thread t = new Thread(this::supervise, "cluster-video-supervisor");
        t.setDaemon(true);
        supervisor = t;
        t.start();
    }

    public synchronized void stop() {
        running = false;
        Thread t = supervisor; supervisor = null;
        if (t != null) t.interrupt();
        if (encoder != null) { encoder.stop(); encoder = null; }
        channelOpenSent = inputOpenSent = focusRequested = started = false;
        triggerChannelOpened = false;
        readySinceMs = lastOpenRespMs = 0;
        unacked = 0;
    }

    private int channel() { return ClusterInfo.getVideoClusterServiceId(); }
    private int width()  { int w = ClusterInfo.getVideoClusterWidth();  return w > 0 ? w : HudConfig.clusterWidth; }
    private int height() { int h = ClusterInfo.getVideoClusterHeight(); return h > 0 ? h : HudConfig.clusterHeight; }

    /**
     * Single driver: opens the channel once the car link is ready, retries the handshake
     * if it stalls (fixes "sometimes doesn't start"), and re-establishes the session if
     * the stream stops sending frames (fixes the mid-drive blackout).
     */
    private void supervise() {
        while (running) {
            try { superviseStep(); }
            catch (Throwable t) { Log.w(TAG, "supervise step failed", t); }
            try { Thread.sleep(SUPERVISE_MS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
    }

    private void superviseStep() {
        if (!HudConfig.ownCluster || !ClusterInfo.hasVideoCluster() || !service.isPhoneready()) {
            readySinceMs = 0;
            return;
        }
        long now = System.currentTimeMillis();
        if (readySinceMs == 0) readySinceMs = now;
        if (!started) {
            if (!channelOpenSent) {
                if (readyToOpen(now)) {
                    handshakeStartedMs = now;
                    openChannel();
                }
            } else if (now - handshakeStartedMs > HANDSHAKE_TIMEOUT_MS) {
                Log.w(TAG, "handshake stalled " + (now - handshakeStartedMs) + "ms -> retrying");
                resetHandshake();
                handshakeStartedMs = now;
                openChannel();
            }
        } else if (now - lastFrameSentMs > STREAM_STALL_MS) {
            Log.w(TAG, "stream stalled (" + (now - lastFrameSentMs) + "ms since last frame) -> re-establishing");
            reestablish();
        } else if (now - lastHeartbeatMs > 5000) {
            // One line every 5s that tells us the whole story remotely: are frames going
            // out, and is the car consuming them (acking)?
            lastHeartbeatMs = now;
            Log.d(TAG, "streaming hb: framesSent=" + framesSent + " acks=" + acksReceived
                    + " unacked=" + unacked + " sinceAck=" + (now - lastAckMs) + "ms"
                    + " loc=" + CarLocationStore.getLatitude() + "," + CarLocationStore.getLongitude()
                    + " hdg=" + CarLocationStore.getBearingDeg()
                    + " ch=" + channel() + " " + width() + "x" + height());
        }
    }

    /** Don't open our channels until gearhead has finished opening its own. */
    private boolean readyToOpen(long now) {
        if (triggerChannelOpened) return true;                                    // saw the car open the channel just below ours
        if (lastOpenRespMs > 0 && now - lastOpenRespMs > OPEN_SETTLE_MS) return true; // gearhead's opens went quiet
        if (now - readySinceMs > OPEN_MAX_WAIT_MS) {                              // never observed any opens - proceed anyway
            Log.w(TAG, "no gearhead channel-open observed in " + OPEN_MAX_WAIT_MS + "ms -> opening anyway");
            return true;
        }
        return false;
    }

    /** Observe gearhead's channel-open handshakes (car->phone). Does NOT consume. */
    public void observeCarMessage(AAMessage msg) {
        if (!running || msg.getMessageID() != MSG_CHANNEL_OPEN_RESPONSE) return;
        int ch = msg.getChannel() & 0xFF;
        int sink = ClusterInfo.getVideoClusterServiceId();
        int input = ClusterInfo.getVideoClusterInputServiceId();
        if ((sink >= 0 && ch == (sink & 0xFF)) || (input >= 0 && ch == (input & 0xFF))) return; // ours
        lastOpenRespMs = System.currentTimeMillis();
        int trig = ClusterInfo.getOpenTriggerChannelId();
        if (trig >= 0 && ch == (trig & 0xFF)) {
            triggerChannelOpened = true;
            Log.d(TAG, "car opened trigger ch=" + ch + " -> clear to open cluster channels");
        }
    }

    /** Drop handshake progress so the next supervise tick re-opens from scratch.
     *  Keeps triggerChannelOpened - gearhead only opens its channels once. */
    private synchronized void resetHandshake() {
        channelOpenSent = false;
        inputOpenSent = false;
        focusRequested = false;
        unacked = 0;
    }

    /** Tear down encoder + handshake so the supervisor rebuilds the whole session. */
    private synchronized void reestablish() {
        started = false;
        if (encoder != null) { try { encoder.stop(); } catch (Exception ignored) {} encoder = null; }
        loggedFirstCodecConfig = loggedFirstKeyframe = loggedFirstAck = false;
        codecConfigBytes = null;   // a rebuilt encoder emits fresh SPS/PPS
        resetHandshake();
    }

    /**
     * Re-establish the media session after a video-focus bounce: a fresh Start (NEW session
     * id) + SPS/PPS + forced keyframe, matching the sequence the car accepted at first start.
     * Runs ONLY on a focus regain, so it cannot affect the normal startup path. This fixes the
     * "black after a focus loss/gain" case where the car rebuilt its session and was ignoring
     * DATA sent under the stale session while still ACKing at the transport layer.
     */
    private synchronized void rearmStream() {
        if (!started || encoder == null) return;
        sessionId++;
        Log.d(TAG, "focus regained -> re-arm session=" + sessionId + " cfgIdx=" + configurationIndex);
        Start start = Start.newBuilder().setSessionId(sessionId).setConfigurationIndex(configurationIndex).build();
        service.sendToCarFragmented(channel(), MediaMessageId.MEDIA_MESSAGE_START_VALUE, start.toByteArray(), false);
        if (codecConfigBytes != null) {
            service.sendToCarFragmented(channel(), MediaMessageId.MEDIA_MESSAGE_CODEC_CONFIG_VALUE,
                    codecConfigBytes, false);
        }
        unacked = 0;
        lastAckMs = System.currentTimeMillis();
        lastFrameSentMs = System.currentTimeMillis();
        requestKeyframe();   // next DATA is a self-decodable IDR for the new session
    }

    private int inputChannel() { return ClusterInfo.getVideoClusterInputServiceId(); }

    private void openChannel() {
        if (channelOpenSent) return;
        channelOpenSent = true;
        ChannelOpenRequest req = ChannelOpenRequest.newBuilder()
                .setPriority(0).setServiceId(channel()).build();
        Log.d(TAG, "-> ChannelOpenRequest (video) ch=" + channel());
        service.sendToCarFragmented(channel(), MSG_CHANNEL_OPEN_REQUEST, req.toByteArray(), true);
    }

    /** Open the paired cluster INPUT channel too, mirroring gearhead - we don't use the
     *  input events, but the car expects the display's input channel to be opened. */
    private void openInputChannel() {
        if (!HudConfig.openClusterInput) return;   // 21-only is the known-good video path
        int in = inputChannel();
        if (inputOpenSent || in < 0) return;
        inputOpenSent = true;
        ChannelOpenRequest req = ChannelOpenRequest.newBuilder()
                .setPriority(0).setServiceId(in).build();
        Log.d(TAG, "-> ChannelOpenRequest (input) ch=" + in);
        service.sendToCarFragmented(in, MSG_CHANNEL_OPEN_REQUEST, req.toByteArray(), true);
    }

    /** Car-origin message on one of our cluster channels (video sink or input). Consume. */
    public boolean onCarMessage(AAMessage msg) {
        if (!running || !HudConfig.ownCluster) return false;
        int ch = msg.getChannel() & 0xFF;
        int id = msg.getMessageID();
        byte[] data = msg.getData();

        // Cluster input channel: we opened it but don't use input - just absorb it so it
        // never leaks to gearhead (which no longer knows this channel).
        if (inputChannel() >= 0 && ch == (inputChannel() & 0xFF)) {
            if (id == MSG_CHANNEL_OPEN_RESPONSE) Log.d(TAG, "<- ChannelOpenResponse (input) ch=" + ch);
            else Log.d(TAG, "<- input(ch" + ch + ") msg id=" + id + " len=" + (data != null ? data.length : 0));
            return true;
        }

        try {
            if (id == MSG_CHANNEL_OPEN_RESPONSE) {
                Log.d(TAG, "<- ChannelOpenResponse (video)");
                sendSetup();
                openInputChannel();   // now open the paired input channel, like gearhead would
            } else if (id == MSG_CONFIG) {
                Config cfg = Config.parseFrom(data);
                if (cfg.getConfigurationIndicesCount() > 0) configurationIndex = cfg.getConfigurationIndices(0);
                // Use the video config the CAR picked (resolution + safe area), not blindly
                // the first one it advertised. Prefer the first offered index we can encode.
                // Only before Start: the encoder is sized once, when streaming begins.
                if (!started) {
                    for (int i = 0; i < cfg.getConfigurationIndicesCount(); i++) {
                        int idx = cfg.getConfigurationIndices(i);
                        if (ClusterInfo.selectVideoClusterConfig(idx)) { configurationIndex = idx; break; }
                    }
                }
                if (cfg.hasMaxUnacked() && cfg.getMaxUnacked() > 0) maxUnacked = cfg.getMaxUnacked();
                Log.d(TAG, "   Config status=" + cfg.getStatus() + " cfgIdx=" + configurationIndex
                        + " offered=" + cfg.getConfigurationIndicesList()
                        + " -> " + width() + "x" + height() + " maxUnacked=" + maxUnacked);
                sendVideoFocusRequest();
            } else if (id == MSG_VIDEO_FOCUS_NOTIF) {
                if (!started) {
                    Log.d(TAG, "   VideoFocusNotification -> Start");
                    sendStartAndStream();
                } else {
                    VideoFocusNotification f = VideoFocusNotification.parseFrom(data);
                    Log.d(TAG, "   VideoFocusNotification (post-start) focus=" + f.getFocus()
                            + " unsolicited=" + f.getUnsolicited());
                    if (f.getFocus() == VideoFocusMode.VIDEO_FOCUS_PROJECTED) {
                        // Focus (re)granted after a bounce. The car has rebuilt its video
                        // session and is waiting for a fresh Start before it will render DATA
                        // again - otherwise the cluster stays black while transport keeps
                        // ACKing. Re-arm with a new session id.
                        rearmStream();
                    } else {
                        // Car took the cluster (native view). Ask for focus back; the grant
                        // notification will re-arm the stream above.
                        focusRequested = false;
                        sendVideoFocusRequest();
                    }
                }
            } else if (id == MSG_ACK) {
                lastAckMs = System.currentTimeMillis();
                unacked = 0;
                acksReceived++;
                if (!loggedFirstAck) {
                    loggedFirstAck = true;
                    Log.d(TAG, "<- first ACK (car IS consuming our video)");
                }
            } else if (id == MSG_UNEXPECTED) {
                Log.e(TAG, "   car said MESSAGE_UNEXPECTED - sequence is wrong at this point");
            } else {
                Log.d(TAG, "   unhandled cluster msg id=" + id
                        + " len=" + (data != null ? data.length : 0));
            }
        } catch (Exception e) {
            Log.w(TAG, "onCarMessage id=" + id + " failed", e);
        }
        return true;
    }

    private void sendSetup() {
        Setup setup = Setup.newBuilder().setType(MediaCodecType.MEDIA_CODEC_VIDEO_H264_BP).build();
        Log.d(TAG, "-> Setup H264_BP");
        service.sendToCarFragmented(channel(), MediaMessageId.MEDIA_MESSAGE_SETUP_VALUE, setup.toByteArray(), false);
    }

    private void sendVideoFocusRequest() {
        if (focusRequested) return;
        focusRequested = true;
        VideoFocusRequestNotification req = VideoFocusRequestNotification.newBuilder()
                .setMode(VideoFocusMode.VIDEO_FOCUS_PROJECTED).build();
        Log.d(TAG, "-> VideoFocusRequest PROJECTED");
        service.sendToCarFragmented(channel(), MediaMessageId.MEDIA_MESSAGE_VIDEO_FOCUS_REQUEST_VALUE,
                req.toByteArray(), false);
        // Fallback: some HUs grant focus without an explicit notification.
        Thread t = new Thread(() -> {
            try { Thread.sleep(FOCUS_GRANT_FALLBACK_MS); } catch (InterruptedException e) { return; }
            if (running && !started) {
                Log.d(TAG, "   no VideoFocusNotification within " + FOCUS_GRANT_FALLBACK_MS + "ms -> Start anyway");
                sendStartAndStream();
            }
        }, "cluster-focus-fallback");
        t.setDaemon(true);
        t.start();
    }

    private synchronized void sendStartAndStream() {
        if (started) return;
        started = true;
        lastAckMs = System.currentTimeMillis();
        lastFrameSentMs = System.currentTimeMillis();   // grace before the stall watchdog arms
        Start start = Start.newBuilder().setSessionId(sessionId).setConfigurationIndex(configurationIndex).build();
        Log.d(TAG, "-> Start session=" + sessionId + " cfgIdx=" + configurationIndex);
        service.sendToCarFragmented(channel(), MediaMessageId.MEDIA_MESSAGE_START_VALUE, start.toByteArray(), false);
        try {
            encoder = new ClusterVideoEncoder(width(), height(), HudConfig.clusterFps,
                    HudConfig.clusterBitrate, HudConfig.clusterKeyframeIntervalS,
                    this::renderFrame, this::onEncoded);
            encoder.start();
            Log.d(TAG, "streaming started");
        } catch (Exception e) {
            Log.e(TAG, "encoder start failed", e);
        }
        Runnable r = onStreaming;
        onStreaming = null;
        if (r != null) { // e.g. GATSO update, off the video path
            Thread pt = new Thread(r, "post-stream");
            pt.setPriority(Thread.MIN_PRIORITY);
            pt.setDaemon(true);
            pt.start();
        }
    }

    private Bitmap renderFrame(int w, int h) {
        if (HudConfig.testPattern) return testPattern(w, h);
        return renderer.render(hudState.getMarkers(),
                CarLocationStore.getLatitude(), CarLocationStore.getLongitude(),
                CarLocationStore.getBearingDeg(), w, h,
                ClusterInfo.getInsetLeft(), ClusterInfo.getInsetTop(),
                ClusterInfo.getInsetRight(), ClusterInfo.getInsetBottom(),
                hudState.getWazeLastSuccessMs(), hudState.isWazeFailing());
    }

    private void onEncoded(byte[] data, int flags, long ptsUs) {
        if (!running || !started) return;
        boolean codecConfig = (flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
        boolean keyframe = (flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
        if (codecConfig) {
            codecConfigBytes = data.clone();   // keep for optional re-send before keyframes
            service.sendToCarFragmented(channel(), MediaMessageId.MEDIA_MESSAGE_CODEC_CONFIG_VALUE, data, false);
            lastFrameSentMs = System.currentTimeMillis();
            if (!loggedFirstCodecConfig) {
                loggedFirstCodecConfig = true;
                Log.d(TAG, "-> first CODEC_CONFIG (SPS/PPS) " + data.length + "B");
            }
            return;
        }
        // Optional silent-black mitigation: re-send SPS/PPS (same message the car accepts at
        // start) right before each keyframe, so a mid-stream decoder reset re-syncs on the
        // next IDR instead of staying black forever. OFF by default.
        if (keyframe && HudConfig.resendCodecConfig && codecConfigBytes != null) {
            service.sendToCarFragmented(channel(), MediaMessageId.MEDIA_MESSAGE_CODEC_CONFIG_VALUE,
                    codecConfigBytes, false);
        }
        // Flow control: only ever drop P-frames. Keyframes ALWAYS go through - dropping an
        // IDR is exactly what leaves the cluster black until the next one.
        if (!keyframe && unacked >= maxUnacked) {
            if (System.currentTimeMillis() - lastAckMs < ACK_STALL_MS) return; // wait within window
            Log.d(TAG, "acks stalled -> resume + keyframe");
            unacked = 0;
            lastAckMs = System.currentTimeMillis();
            requestKeyframe();  // re-sync the decoder cleanly after the gap
        }
        ByteBuffer buf = ByteBuffer.allocate(8 + data.length).order(ByteOrder.BIG_ENDIAN);
        buf.putLong(ptsUs);
        buf.put(data);
        service.sendToCarFragmented(channel(), MediaMessageId.MEDIA_MESSAGE_DATA_VALUE, buf.array(), false);
        unacked++;
        framesSent++;
        lastFrameSentMs = System.currentTimeMillis();
        if (keyframe && !loggedFirstKeyframe) {
            loggedFirstKeyframe = true;
            Log.d(TAG, "-> first KEYFRAME " + data.length + "B (stream is producing IDR)");
        }
    }

    private void requestKeyframe() {
        ClusterVideoEncoder e = encoder;
        if (e != null) e.requestKeyframe();
    }

    private final Paint tpText = new Paint(Paint.ANTI_ALIAS_FLAG);

    private Bitmap testPattern(int w, int h) {
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        long t = System.currentTimeMillis() / 500;
        c.drawColor((t % 2 == 0) ? Color.rgb(230, 30, 120) : Color.rgb(30, 120, 230));
        tpText.setColor(Color.WHITE);
        tpText.setTextSize(h * 0.18f);
        tpText.setTextAlign(Paint.Align.CENTER);
        c.drawText("HUD TEST", w / 2f, h / 2f, tpText);
        float x = (System.currentTimeMillis() % 2000) / 2000f * w;
        c.drawRect(x, 0, x + 20, h, tpText);
        return bmp;
    }
}
