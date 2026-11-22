package io.aawireless.wirelessaamanipulator.helpers.simulators;

import android.os.Looper;
import android.util.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.aawireless.wirelessaamanipulator.helpers.AAMessage;
import io.aawireless.wirelessaamanipulator.helpers.Util;
import io.aawireless.wirelessaamanipulator.helpers.messageprocessors.EnergyModelTranslator;
import io.aawireless.wirelessaamanipulator.helpers.ssl.NioSSL;
import io.aawireless.wirelessaamanipulator.helpers.streams.InOutStream;
import io.aawireless.wirelessaamanipulator.helpers.interfaces.ServiceThreadPair;

public class AASimulator extends Thread{

    InOutStream inoutstream;
    ServiceThreadPair other;

    protected EnergyModelTranslator translator;
    protected boolean ready=false;


    private final ExecutorService outThread = Executors.newSingleThreadExecutor();

    public AASimulator(ServiceThreadPair other, String thread, EnergyModelTranslator t) {
        super(thread);
        this.other = other;
        this.translator=t;
        // NOTE: do NOT start() here. Subclass constructors set fields AFTER super()
        // returns (e.g. PhoneSimulator assigns inoutstream from its socket). Starting
        // the thread in this base ctor let run() race ahead of that assignment and
        // read inoutstream==null - PhoneSimulator then wrongly fell into its
        // "listen on 6299" server branch instead of using the real-car socket.
        // Callers must invoke start() once construction is fully complete.
    }

    public void run(){
        Looper.prepare();
    }

    public void send(byte[] data){
        if (closing || outThread.isShutdown()) return;   // link closed (ByeBye done): drop late sends
        try {
        outThread.execute(new Runnable() {
            @Override
            public void run() {
                try {


                       // Log.d("AASimulator", "Using stream: "+inoutstream.getNamedstring()+". Sending message: " + Util.bytesToHex(data));
                        inoutstream.write(data);


                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // raced with byeByeAndClose() shutting the writer down; the link is gone
        }
    }

    public boolean isReady(){
        return ready;
    }
    public void stopself(){

        interrupt();
    }

    // ------------------------------------------------------------------
    // Graceful shutdown (ByeByeRequest -> ByeByeResponse -> close)
    // ------------------------------------------------------------------

    /** TLS session of this link once its handshake completed (set by the subclass). */
    protected volatile NioSSL linkSsl;
    /**
     * True once the peer has sent us encrypted traffic in the main loop - proof it has
     * switched encryption on. Before that an encrypted ByeBye is meaningless to it (gearhead
     * logs "encrypted frame with no ssl context" and drops it; it only enables SSL on the
     * car's AuthComplete), so a plain close is the right goodbye.
     */
    protected volatile boolean sessionLive = false;
    private volatile boolean closing = false;
    private volatile boolean byeByeAcked = false;
    private final Object byeLock = new Object();

    public boolean isClosing() { return closing; }

    /**
     * End THIS link the way the protocol expects instead of just dropping the socket:
     * send ByeByeRequest on the control channel, wait (bounded) for the peer's
     * ByeByeResponse, then half-close and close. Without this gearhead's head unit server
     * can stay half-attached and need a force-stop of Android Auto.
     *
     * Safe from any thread, idempotent, and still closes the socket if the link never got
     * as far as TLS (nothing to say ByeBye over). Blocks for at most ~waitMs + 1 s.
     */
    public void byeByeAndClose(io.aawireless.wirelessaamanipulator.proto.ByeByeReason reason, long waitMs) {
        synchronized (byeLock) {
            if (closing) return;
            closing = true;
        }
        final String name = getName();
        final NioSSL ssl = linkSsl;
        final InOutStream stream = inoutstream;
        if (ssl != null && stream != null && !sessionLive) {
            Log.d("AASimulator", name + ": session not live yet (peer hasn't spoken encrypted) -> plain close");
        }
        if (ssl != null && stream != null && sessionLive) {
            try {
                AAMessage bye = new AAMessage(io.aawireless.wirelessaamanipulator.helpers.Constants.CONTROLCHANNEL,
                        io.aawireless.wirelessaamanipulator.helpers.Constants.FLAG_ALL,
                        io.aawireless.wirelessaamanipulator.proto.ControlMessageType.MESSAGE_BYEBYE_REQUEST_VALUE);
                bye.setEncrypted(io.aawireless.wirelessaamanipulator.helpers.Constants.FLAG_ENCRYPTED);
                bye.setData(io.aawireless.wirelessaamanipulator.proto.ByeByeRequest.newBuilder()
                        .setReason(reason).build().toByteArray());
                final byte[] frame;
                synchronized (ssl) { frame = bye.getFormated(ssl); }   // TLS wrap order == send order
                // Through the same single writer as every other send, so it can't interleave
                // with a queued frame; wait until it's actually on the wire.
                outThread.submit(() -> stream.write(frame))
                        .get(1000, java.util.concurrent.TimeUnit.MILLISECONDS);
                Log.d("AASimulator", name + ": -> ByeByeRequest(" + reason + ")");

                long end = System.currentTimeMillis() + waitMs;
                synchronized (byeLock) {
                    long left;
                    while (!byeByeAcked && (left = end - System.currentTimeMillis()) > 0) byeLock.wait(left);
                }
                Log.d("AASimulator", name + (byeByeAcked ? ": <- ByeByeResponse, closing"
                        : ": no ByeByeResponse within " + waitMs + "ms, closing anyway"));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                Log.w("AASimulator", name + ": ByeBye send failed (" + e + "), closing anyway");
            }
        }
        try { if (stream != null) stream.close(); } catch (Exception ignored) {}
        outThread.shutdownNow();
        interrupt();
    }

    /**
     * Reader loops call this first for every message: swallow the ByeByeResponse that
     * answers OUR ByeByeRequest (the other side never asked, so it must not be relayed).
     */
    protected boolean consumeOwnByeByeResponse(AAMessage m) {
        if (!closing) return false;
        if ((m.getChannel() & 0xFF) != 0
                || m.getMessageID() != io.aawireless.wirelessaamanipulator.proto.ControlMessageType.MESSAGE_BYEBYE_RESPONSE_VALUE) {
            return false;
        }
        synchronized (byeLock) { byeByeAcked = true; byeLock.notifyAll(); }
        return true;
    }
}
