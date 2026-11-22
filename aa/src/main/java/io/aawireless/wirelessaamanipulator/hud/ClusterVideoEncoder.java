package io.aawireless.wirelessaamanipulator.hud;

import android.graphics.Bitmap;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Bundle;
import android.util.Log;

import java.nio.ByteBuffer;

/**
 * H.264 (Baseline) encoder for the cluster HUD, driven in MediaCodec byte-buffer
 * mode: each tick we ask a {@link FrameSource} for a Bitmap, convert it to YUV420,
 * feed the encoder, and hand the encoded access units to a {@link Sink}.
 *
 * Byte-buffer mode (not a VirtualDisplay + Presentation) keeps this self-contained
 * and light - we already produce a Bitmap for the compass, and there's no GL surface
 * or extra display to composite, which matters for phone heat.
 */
public final class ClusterVideoEncoder {

    private static final String TAG = "ClusterVideoEncoder";
    private static final String MIME = MediaFormat.MIMETYPE_VIDEO_AVC;

    /** Supplies the next frame to encode; called on the encoder thread. */
    public interface FrameSource {
        /** @return a bitmap of exactly (width x height), or null to emit a blank frame. */
        Bitmap renderFrame(int width, int height);
    }

    /** Receives encoded H.264. flags mirror MediaCodec.BufferInfo.flags. */
    public interface Sink {
        void onEncoded(byte[] data, int flags, long presentationTimeUs);
    }

    private final int width, height, fps, bitrate, keyframeIntervalS;
    private final FrameSource source;
    private final Sink sink;

    private MediaCodec codec;
    private volatile Thread thread;
    private volatile boolean running;

    public ClusterVideoEncoder(int width, int height, int fps, int bitrate,
                               int keyframeIntervalS, FrameSource source, Sink sink) {
        this.width = width;
        this.height = height;
        this.fps = Math.max(1, fps);
        this.bitrate = bitrate;
        this.keyframeIntervalS = keyframeIntervalS;
        this.source = source;
        this.sink = sink;
    }

    /** Ask the encoder to emit an IDR (sync) frame ASAP - lets the car re-sync after a gap. */
    public void requestKeyframe() {
        MediaCodec c = codec;
        if (c == null || !running) return;
        try {
            Bundle b = new Bundle();
            b.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
            c.setParameters(b);
        } catch (Exception e) {
            Log.w(TAG, "requestKeyframe failed", e);
        }
    }

    public synchronized void start() throws Exception {
        if (running) return;
        MediaFormat fmt = MediaFormat.createVideoFormat(MIME, width, height);
        fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        fmt.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
        fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, keyframeIntervalS);
        // The car asked for Baseline (H264_BP). Best-effort; encoders may clamp.
        fmt.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline);
        // NB: do NOT set KEY_PREPEND_HEADER_TO_SYNC_FRAMES - with it on, MediaCodec stops
        // emitting the standalone codec-config buffer, so the car never receives the AA
        // CODEC_CONFIG message it needs before DATA and the cluster is black from frame one.
        // We keep SPS/PPS as the separate CODEC_CONFIG message and instead re-send that
        // message periodically (see ClusterVideoSession) to survive a mid-stream decoder reset.

        codec = MediaCodec.createEncoderByType(MIME);
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        codec.start();

        running = true;
        Thread t = new Thread(this::loop, "cluster-h264");
        t.setDaemon(true);
        thread = t;
        t.start();
        Log.d(TAG, "encoder started " + width + "x" + height + "@" + fps + "fps " + bitrate + "bps");
    }

    public void stop() {
        Thread t;
        synchronized (this) {
            if (!running && codec == null && thread == null) return;
            running = false;
            t = thread;
            thread = null;
        }
        // Wait for the cluster-h264 loop to finish its current feedOneFrame/drain and EXIT before we
        // release the MediaCodec. Releasing the codec while that thread is still inside feedOneFrame
        // (dequeueInputBuffer / getInputImage / queueInputBuffer) is a native use-after-free - a
        // SIGSEGV in feedOneFrame that kills the whole process (and with it the co-hosted key service),
        // seen on AA teardown when the car link drops. The loop's dequeue uses a 10 ms timeout and we
        // interrupt its frame-pacing sleep, so it exits promptly. Join OUTSIDE the lock, and guard
        // against a self-join in case stop() is ever reached from the loop thread.
        if (t != null && t != Thread.currentThread()) {
            t.interrupt();
            try { t.join(2000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
        synchronized (this) {
            try { if (codec != null) { codec.stop(); codec.release(); } } catch (Exception ignored) {}
            codec = null;
        }
        Log.d(TAG, "encoder stopped");
    }

    private void loop() {
        long frameIntervalMs = 1000L / fps;
        long frameIndex = 0;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

        while (running) {
            long tickStart = System.currentTimeMillis();
            try {
                feedOneFrame(frameIndex++);
                drain(info);
            } catch (IllegalStateException e) {
                Log.w(TAG, "codec state error", e);
                return;
            } catch (Exception e) {
                Log.w(TAG, "encode tick failed", e);
            }
            long elapsed = System.currentTimeMillis() - tickStart;
            long sleep = frameIntervalMs - elapsed;
            if (sleep > 0) {
                try { Thread.sleep(sleep); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            }
        }
    }

    private void feedOneFrame(long frameIndex) {
        int inIdx = codec.dequeueInputBuffer(10_000);
        if (inIdx < 0) return; // no input buffer free this tick; skip
        long ptsUs = frameIndex * 1_000_000L / fps;

        Image img = codec.getInputImage(inIdx);
        int size = 0;
        if (img != null) {
            Bitmap bmp = null;
            try {
                bmp = source.renderFrame(width, height);
            } catch (Throwable t) {
                Log.w(TAG, "frame source failed", t);
            }
            fillImageFromBitmap(img, bmp);
            if (bmp != null) bmp.recycle();
            // getInputImage means we queue the whole configured buffer.
            size = bufferSizeForImage();
        }
        codec.queueInputBuffer(inIdx, 0, size, ptsUs, 0);
    }

    private int bufferSizeForImage() {
        // YUV420: Y = w*h, U+V = w*h/2.
        return width * height * 3 / 2;
    }

    private void drain(MediaCodec.BufferInfo info) {
        while (true) {
            int outIdx = codec.dequeueOutputBuffer(info, 0);
            if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) return;
            if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue;
            if (outIdx < 0) return;

            ByteBuffer out = codec.getOutputBuffer(outIdx);
            if (out != null && info.size > 0) {
                out.position(info.offset);
                out.limit(info.offset + info.size);
                byte[] data = new byte[info.size];
                out.get(data);
                try {
                    sink.onEncoded(data, info.flags, info.presentationTimeUs);
                } catch (Throwable t) {
                    Log.w(TAG, "sink failed", t);
                }
            }
            codec.releaseOutputBuffer(outIdx, false);
            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return;
        }
    }

    /** Convert an ARGB_8888 bitmap into the encoder's YUV420 input Image (BT.601). */
    private void fillImageFromBitmap(Image img, Bitmap bmp) {
        int w = width, h = height;
        int[] argb = new int[w * h];
        if (bmp != null && bmp.getWidth() == w && bmp.getHeight() == h) {
            bmp.getPixels(argb, 0, w, 0, 0, w, h);
        } // else leave argb all-zero -> black frame

        Image.Plane[] planes = img.getPlanes();
        ByteBuffer yBuf = planes[0].getBuffer();
        ByteBuffer uBuf = planes[1].getBuffer();
        ByteBuffer vBuf = planes[2].getBuffer();
        int yRow = planes[0].getRowStride();
        int uRow = planes[1].getRowStride();
        int vRow = planes[2].getRowStride();
        int uPix = planes[1].getPixelStride();
        int vPix = planes[2].getPixelStride();

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int c = argb[y * w + x];
                int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;

                int yy = ((66 * r + 129 * g + 25 * b + 128) >> 8) + 16;
                yBuf.put(y * yRow + x, (byte) clamp(yy));

                if ((x & 1) == 0 && (y & 1) == 0) {
                    int uu = ((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128;
                    int vv = ((112 * r - 94 * g - 18 * b + 128) >> 8) + 128;
                    int cx = x / 2, cy = y / 2;
                    uBuf.put(cy * uRow + cx * uPix, (byte) clamp(uu));
                    vBuf.put(cy * vRow + cx * vPix, (byte) clamp(vv));
                }
            }
        }
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }
}
