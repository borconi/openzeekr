package io.aawireless.wirelessaamanipulator.helpers.bt;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

import androidauto.bluetooth.proto.AndroidAutoBluetooth;


// ^ If you used java_outer_classname, this may differ.
// Many setups generate top-level classes directly in the package, e.g. WifiStartRequest.
// See notes below; you may need to adjust imports.

public final class AaBtProtoClient {

    public interface Listener {
        default void onWifiStartRequest(AndroidAutoBluetooth.WifiStartRequest msg) {}
        default void onWifiInfoRequest(AndroidAutoBluetooth.WifiInfoRequest msg) {}
        default void onWifiInfoResponse(AndroidAutoBluetooth.WifiInfoResponse msg) {}
        default void onWifiVersionRequest(AndroidAutoBluetooth.WifiVersionRequest msg) {}
        default void onWifiVersionResponse(AndroidAutoBluetooth.WifiVersionResponse msg) {}
        default void onWifiConnectStatus(AndroidAutoBluetooth.WifiConnectStatus msg) {}
        default void onWifiStartResponse(AndroidAutoBluetooth.WifiStartResponse msg) {}
        default void onWifiPingRequest(AndroidAutoBluetooth.WifiPingRequest msg) {}
        default void onWifiPingResponse(AndroidAutoBluetooth.WifiPingResponse msg) {}

        /** Called if messageId is unknown, or parse fails. */
        default void onUnknown(int messageId, byte[] payload, Exception error) {}
    }

    private final AaBtFrameParser frameParser;

    public AaBtProtoClient(Listener listener) {
        this.frameParser = new AaBtFrameParser((messageId, payload) -> {
            try {
                dispatch(messageId, payload, listener);
            } catch (Exception e) {
                listener.onUnknown(messageId, payload, e);
            }
        });
    }

    /** Feed bytes from any stream source. */
    public void readLoop(InputStream in) throws IOException {
        frameParser.readLoop(in);
    }

    /** If you prefer pushing bytes yourself. */
    public void feed(byte[] data, int off, int len) {
        frameParser.feed(data, off, len);
    }

    private static void dispatch(int messageId, byte[] payload, Listener listener) throws Exception {
        switch (messageId) {
            case 1:
                listener.onWifiStartRequest(AndroidAutoBluetooth.WifiStartRequest.parseFrom(payload));
                return;
            case 2:
                listener.onWifiInfoRequest(AndroidAutoBluetooth.WifiInfoRequest.parseFrom(payload));
                return;
            case 3:
                listener.onWifiInfoResponse(AndroidAutoBluetooth.WifiInfoResponse.parseFrom(payload));
                return;
            case 4:
                listener.onWifiVersionRequest(AndroidAutoBluetooth.WifiVersionRequest.parseFrom(payload));
                return;
            case 5:
                listener.onWifiVersionResponse(AndroidAutoBluetooth.WifiVersionResponse.parseFrom(payload));
                return;
            case 6:
                listener.onWifiConnectStatus(AndroidAutoBluetooth.WifiConnectStatus.parseFrom(payload));
                return;
            case 7:
                listener.onWifiStartResponse(AndroidAutoBluetooth.WifiStartResponse.parseFrom(payload));
                return;
            case 8:
                listener.onWifiPingRequest(AndroidAutoBluetooth.WifiPingRequest.parseFrom(payload));
                return;
            case 9:
                listener.onWifiPingResponse(AndroidAutoBluetooth.WifiPingResponse.parseFrom(payload));
                return;
            default:
                listener.onUnknown(messageId, payload, null);
        }
    }

    /**
     * Frame parser for:
     *   [uint16 length][uint16 messageId][payload length bytes]
     * Qt QDataStream default byte order is Big Endian.
     */
    public static final class AaBtFrameParser {

        public interface FrameListener {
            void onFrame(int messageId, byte[] payload);
        }

        private final FrameListener listener;

        private byte[] buf = new byte[8192];
        private int writePos = 0;

        public AaBtFrameParser(FrameListener listener) {
            this.listener = listener;
        }

        public void feed(byte[] data, int off, int len) {
            ensureCapacity(writePos + len);
            System.arraycopy(data, off, buf, writePos, len);
            writePos += len;
            parseAvailable();
        }

        public void readLoop(InputStream in) throws IOException {
            byte[] tmp = new byte[1024];
            while (true) {
                int n = in.read(tmp);
                if (n == -1) return;
                feed(tmp, 0, n);
            }
        }

        private void parseAvailable() {
            int readPos = 0;

            while (true) {
                int available = writePos - readPos;
                if (available < 4) break;

                int length = readU16BE(buf, readPos);
                int messageId = readU16BE(buf, readPos + 2);

                int frameSize = 4 + length;
                if (available < frameSize) break;

                byte[] payload = Arrays.copyOfRange(buf, readPos + 4, readPos + 4 + length);
                listener.onFrame(messageId, payload);

                readPos += frameSize;
            }

            if (readPos > 0) {
                int remaining = writePos - readPos;
                System.arraycopy(buf, readPos, buf, 0, remaining);
                writePos = remaining;
            }
        }

        private static int readU16BE(byte[] b, int off) {
            return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
        }

        private void ensureCapacity(int needed) {
            if (needed <= buf.length) return;
            int newCap = buf.length;
            while (newCap < needed) newCap *= 2;
            buf = Arrays.copyOf(buf, newCap);
        }
    }
}