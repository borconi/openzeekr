package io.aawireless.wirelessaamanipulator.hud;

import android.util.Log;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import io.aawireless.wirelessaamanipulator.helpers.Constants;
import io.aawireless.wirelessaamanipulator.helpers.ssl.NioSSL;

/**
 * Builds AA transport frames. The AA frame cap is 16384 for the *encrypted* body, so
 * whether we fragment is decided AFTER encryption, not on the plaintext size.
 *
 *  - If the whole message encrypts to <= a single frame body, send ONE frame (FLAG_ALL).
 *  - Otherwise fragment: FIRST/MIDDLE/LAST, each body an independently-encrypted chunk,
 *    and the FIRST frame carries a 4-byte TOTAL = the total DECRYPTED payload size
 *    (2-byte messageId + payload), which is what AAMessage reads back as payloadSize.
 *
 * CALLER MUST hold the ssl monitor across the whole call + the writes, so TLS wrap
 * order == on-wire order.
 */
public final class AAFraming {

    private static final String TAG = "AAFraming";

    /** Max encrypted body per frame so 4B header (+4B total on FIRST) stays under 16384. */
    private static final int MAX_FRAME_BODY = 16376;
    /** Try a single frame when the plaintext is small enough to comfortably fit one TLS record. */
    private static final int SINGLE_TRY_MAX_PLAINTEXT = 16300;
    /** Plaintext chunk when fragmenting (encrypts to < MAX_FRAME_BODY). */
    private static final int FRAG_PLAINTEXT_CHUNK = 15000;

    private AAFraming() {}

    public static List<byte[]> buildFrames(NioSSL ssl, int channel, int messageId,
                                           byte[] payload, boolean control) throws Exception {
        if (payload == null) payload = new byte[0];
        List<byte[]> frames = new ArrayList<>();

        // Fast path: encrypt the whole message once; if it fits a single frame, done.
        if (payload.length <= SINGLE_TRY_MAX_PLAINTEXT) {
            byte[] ct = ssl.encrypt(payload, (short) messageId, Constants.FLAG_ALL);
            if (ct.length <= MAX_FRAME_BODY) {
                frames.add(frame(channel, Constants.FLAG_ALL, control, ct, -1));
                return frames;
            }
            // else fall through to fragment (rare: ciphertext just over the cap)
        }

        // Fragment. Total = full DECRYPTED payload size (msgId(2) + payload) - matches
        // AAMessage's payloadSize field on the FIRST frame.
        int total = 2 + payload.length;
        int off = 0;
        boolean first = true;
        while (off < payload.length) {
            int len = Math.min(FRAG_PLAINTEXT_CHUNK, payload.length - off);
            boolean last = (off + len >= payload.length);
            byte flag = first ? Constants.FLAG_FIRST : (last ? Constants.FLAG_LAST : Constants.FLAG_MIDDLE);
            // FLAG_FIRST makes encrypt() prepend the 2-byte messageId; MIDDLE/LAST don't.
            byte[] ct = ssl.encrypt(Arrays.copyOfRange(payload, off, off + len), (short) messageId, flag);
            frames.add(frame(channel, flag, control, ct, first ? total : -1));
            off += len;
            first = false;
        }
        Log.d(TAG, "fragmented ch" + channel + " msg" + messageId + " payload=" + payload.length
                + " -> " + frames.size() + " frames, total(plain)=" + total);
        return frames;
    }

    private static byte[] frame(int channel, byte flag, boolean control, byte[] ct, int totalOrNeg1) {
        byte flagsByte = (byte) (flag | Constants.FLAG_ENCRYPTED | (control ? Constants.FLAG_CONTROL : 0));
        boolean first = flag == Constants.FLAG_FIRST;
        int extra = first ? 4 : 0;
        ByteBuffer bb = ByteBuffer.allocate(4 + extra + ct.length);
        bb.put((byte) channel);
        bb.put(flagsByte);
        bb.putShort((short) ct.length);
        if (first) bb.putInt(totalOrNeg1);
        bb.put(ct);
        return bb.array();
    }
}
