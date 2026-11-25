package io.aawireless.wirelessaamanipulator.connectors;

import static io.aawireless.wirelessaamanipulator.services.MainTaskService.running;

import android.Manifest;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.util.Log;

import androidx.annotation.RequiresPermission;

import com.google.protobuf.MessageLite;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

// Protos
import androidauto.bluetooth.proto.AndroidAutoBluetooth;
import androidauto.bluetooth.proto.AndroidAutoBluetooth.WifiInfoRequest;
import androidauto.bluetooth.proto.AndroidAutoBluetooth.WifiInfoResponse;
import androidauto.bluetooth.proto.AndroidAutoBluetooth.WifiVersionRequest;
import androidauto.bluetooth.proto.AndroidAutoBluetooth.WifiVersionResponse;
import androidauto.bluetooth.proto.AndroidAutoBluetooth.WifiStartRequest;
import androidauto.bluetooth.proto.AndroidAutoBluetooth.WifiStartResponse;
import androidauto.bluetooth.proto.AndroidAutoBluetooth.WifiConnectStatus;
import androidauto.bluetooth.proto.AndroidAutoBluetooth.WifiStatus;

import io.aawireless.wirelessaamanipulator.helpers.GearheadSettings;
import io.aawireless.wirelessaamanipulator.helpers.interfaces.HandshakeCallbacks;
import io.aawireless.wirelessaamanipulator.helpers.interfaces.ServiceThreadPair;
import io.aawireless.wirelessaamanipulator.helpers.wifi.WifiConnector;
import io.aawireless.wirelessaamanipulator.status.AppStatus;

public class CarHandshakeClient extends Thread {
    private static final String TAG = "CarHandshakeClient";
    private static final UUID AA_WIRELESS_UUID =
            UUID.fromString("4de17a00-52cb-11e6-bdf4-0800200c9a66");
    private volatile boolean linkDown = true; // true until reader+socket are healthy

    /**
     * Delay between replying WifiVersionResponse (id=5) and proactively sending
     * WifiInfoRequest (id=2). Some head units (e.g. Zeekr, per aawireless-style
     * reference impl) parse only ONE frame per readyRead event AND pass the whole
     * remaining buffer as the proto payload - two frames coalesced into a single
     * RFCOMM read make the car's protobuf parse FAIL (trailing 0x00 tag byte) and
     * the second frame is never processed. The delay forces separate reads.
     */
    private static final long POST_VERSION_DELAY_MS = 300;

    // Identity presented to the car inside WifiVersionResponse (id=5).
    // A real phone (decoded from btsnoop of a genuine phone<->this Zeekr) sends a
    // device_serial (field 3) AND a WifiDeviceInfo (field 6). Zeekr rejects a
    // bare version/status response, so we must supply these. Fixed values are
    // fine for testing; make them per-install-unique later if desired.
    private static final String DEVICE_SERIAL           = "WAAM0000001";
    private static final String DEVICE_ID               = "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d";
    private static final String CONNECTIVITY_LIFETIME_ID = "f0e1d2c3-b4a5-4968-8776-5a4b3c2d1e0f";

    // Proactive BT keepalive ping (id=8) loop; a real phone pings ~every second.
    private volatile Thread pingThread;

    // Backoff
    private static final long BACKOFF_START_MS = 700;
    private static final long BACKOFF_MAX_MS   = 15_000;

    private final BluetoothDevice device;
    private volatile BluetoothSocket socket;

    private final Context mContext;
    private final HandshakeCallbacks callbacks;
    private final ServiceThreadPair service;

    // Streams
    private volatile InputStream in;
    private volatile OutputStream out;

    // Wi-Fi network handle
    private volatile Network wifiNetwork;

    // Keep the connector alive for the life of the handshake: it owns the
    // NetworkCallback registration, and losing the reference would let the
    // request be GC'd/leaked. Also lets us hear about network loss.
    private volatile WifiConnector wifiConnector;

    private volatile boolean stop = false;
    private volatile boolean aclConnected = false; // best-effort BT link indicator

    /**
     * True once the projection TCP socket is up. From that point the RFCOMM link is
     * expendable: the car normally closes it and projection continues over TCP, so
     * we must NOT try to reconnect BT (that caused a reconnect/handshake loop).
     */
    private volatile boolean projectionActive = false;

    /**
     * Consecutive RFCOMM connect() failures. When native Wireless Android Auto is
     * enabled, gearhead reacts to the same ACL_CONNECTED and grabs the car's AA
     * RFCOMM channel first, so our connect keeps failing. We can't read that toggle
     * (it's inside gearhead's sandbox - see GearheadSettings), so repeated connect
     * failures are our best available signal.
     */
    private int consecutiveConnectFailures = 0;
    private static final int CONNECT_FAILS_BEFORE_ADVICE = 3;

    private Thread readerThread;

    private volatile int port;
    private volatile String ip;

    /**
     * WPP-over-TCP endpoint advertised inside the car's WifiVersionRequest (wire field 6,
     * gearhead: "Received WifiProjectionProtocolInfo in the WifiVersionRequest" ->
     * HU_SUPPORTS_WPP_OVER_TCP), e.g. Peugeot "0.0.0.0":30615. It is NOT the projection port:
     * it carries the same [len][id][payload] WPP messages as RFCOMM, and the car sends its
     * WifiStartRequest (id=1, with the real projection port, Peugeot 30515) over it.
     * "0.0.0.0" = the car's own address on its access point (the DHCP server / gateway).
     */
    private volatile String versionEndpointIp;
    private volatile int versionEndpointPort = -1;
    /** WPP control channel over the car's Wi-Fi (see versionEndpointPort). */
    private volatile Socket wppSocket;
    private volatile Thread wppPingThread;
    /** Serialises the projection TCP open (reader thread vs. fallback thread). */
    private final Object tcpLock = new Object();
    /** True once we've sent WifiInfoRequest (id=2), whichever message triggered it. */
    private volatile boolean infoRequested = false;

    // Learned credentials (if ever provided / remembered)
    private volatile String lastSsid;
    private volatile String lastKey;

    // Guards writing so "reader blocks on write" safely
    private final Object writeLock = new Object();
    private boolean havewificredentials;

    // Receiver to track ACL connected/disconnected


    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    public CarHandshakeClient(BluetoothDevice device, Context context, HandshakeCallbacks cb) {
        this.device = device;
        this.service = (ServiceThreadPair) context;
        this.mContext = context.getApplicationContext();
        this.callbacks = cb;


        // Create initial socket (we will recreate on every reconnect attempt anyway)
        this.socket = createSocketOrNull();
    }

    public boolean isSameDevice(BluetoothDevice other) {
        if (other == null || device == null) return false;
        return device.getAddress().equals(other.getAddress());
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    @Override
    public void run() {

        // The BT handshake exists to hand off to the car's Wi-Fi hotspot, so Wi-Fi must
        // be on. Turn it on now if the user has it off (silent pre-Q; prompts on 10+).
        try {
            io.aawireless.wirelessaamanipulator.helpers.wifi.WifiEnabler.ensureEnabled(mContext);
        } catch (Exception e) {
            Log.w(TAG, "WifiEnabler.ensureEnabled failed", e);
        }

        long backoff = BACKOFF_START_MS;

        while (!stop) {

            boolean connectedThisAttempt = false;

            try {
                // Fresh connection => stop any ping loop from a previous attempt.
                stopPingLoop();

                // Always recreate socket for a clean connect
                socket = createSocketOrNull();
                if (socket == null) throw new IOException("Failed to create RFCOMM socket");

                Log.d(TAG, "Connecting to RFCOMM socket...");
                socket.connect();
                Log.d(TAG, "RFCOMM Connected");
                connectedThisAttempt = true;
                backoff = BACKOFF_START_MS; // reset after a successful connect

                // We got the channel, so nothing is stealing it from us.
                consecutiveConnectFailures = 0;
                AppStatus.clearAdvice();

                in = socket.getInputStream();
                out = socket.getOutputStream();
                final OutputStream rfOut = out;
                FrameParser parser = new FrameParser((id, p) -> onIncomingFrame(id, p, rfOut, "BT"));

                byte[] tmp = new byte[2048];
                try {
                    while (!stop) {
                        int n = in.read(tmp);
                        if (n == -1) {
                            Log.w(TAG, "Reader EOF (car closed RFCOMM)");
                            break;
                        }
                        // Raw dump: shows whether the car bursts multiple frames in one read
                        Log.d(TAG, "<< raw " + n + "B: " + toHex(tmp, 0, n));
                        parser.feed(tmp, 0, n);
                    }
                } catch (IOException e) {
                    if (!stop) Log.w(TAG, "Reader ended: " + e);
                }

                closeCurrentSocketQuietly();

                // WPP-over-TCP cars may drop RFCOMM once the TCP control channel is up; the
                // start request then arrives on TCP. Don't re-run the BT handshake meanwhile.
                if (!projectionActive && wppSocket != null) {
                    Log.d(TAG, "RFCOMM closed but WPP control channel is live over TCP -> waiting on it");
                    while (!stop && !projectionActive && wppSocket != null) Thread.sleep(500);
                }

                // The car tears the RFCOMM link down once projection has moved to TCP -
                // and projection keeps running fine without it. Reconnecting here would
                // re-run the whole handshake for nothing and hammer the car in a loop
                // (previously the reader-error path fell straight through with NO delay).
                if (projectionActive) {
                    Log.d(TAG, "RFCOMM closed but projection is live over TCP -> not reconnecting");
                    return;
                }
                if (stop) return;
                Log.d(TAG, "RFCOMM lost before projection was up; retrying in " + backoff + "ms");

            } catch (Exception e) {
                if (stop) return;
                closeCurrentSocketQuietly();

                if (!connectedThisAttempt) {
                    consecutiveConnectFailures++;
                    // Only meaningful before projection: afterwards the link is expendable.
                    if (!projectionActive && consecutiveConnectFailures >= CONNECT_FAILS_BEFORE_ADVICE) {
                        Log.w(TAG, "RFCOMM connect failed " + consecutiveConnectFailures
                                + "x in a row - native Wireless Android Auto is probably enabled "
                                + "and taking the car's AA channel before us.");
                        AppStatus.setAdvice(GearheadSettings.NATIVE_WIRELESS_ADVICE);
                    }
                }
                Log.e(TAG, "RFCOMM/connect failed (retry in " + backoff + "ms)", e);
            }

            // Exponential backoff between attempts (BACKOFF_START_MS -> BACKOFF_MAX_MS).
            try {
                Thread.sleep(backoff);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
            backoff = Math.min(backoff * 2, BACKOFF_MAX_MS);
        }
    }

    /** Close streams + socket for the current attempt without disturbing stop/ping state. */
    private void closeCurrentSocketQuietly() {
        try { if (in != null) in.close(); } catch (Exception ignored) {}
        try { if (out != null) out.close(); } catch (Exception ignored) {}
        in = null;
        out = null;
        BluetoothSocket s = socket;
        socket = null;
        try { if (s != null) s.close(); } catch (Exception ignored) {}
    }

    // ---- Public helpers you already had ----

    public void sendWiFiStartCompleted() {
        WifiStartResponse startResp = WifiStartResponse.newBuilder()
                .setIpAddress(ip != null ? ip : "")
                .setPort(port)
                .setStatus(WifiStatus.STATUS_SUCCESS)
                .build();
        try {
            // Reply only if we have a live out
            if (out != null && !stop) {
                sendFrameSync(out, 7, startResp);
                Log.d(TAG, "Sent WifiStartResponse (id=7) SUCCESS");
            }
        } catch (IOException e) {
            // keep same behavior as your old class
            throw new RuntimeException(e);
        }
    }

    /** Call this from outside to stop the thread permanently. */
    public void requestStop() {
        stop = true;
        interrupt();
        stopAndCloseCurrentSocket();
        releaseWifi();
    }

    /**
     * Unregister the NetworkCallback and drop the Network handle. Safe to call
     * repeatedly. Leaving the callback registered leaks the network request and
     * keeps the car's AP request alive after we're done with it.
     */
    private void releaseWifi() {
        WifiConnector c = wifiConnector;
        wifiConnector = null;
        wifiNetwork = null;
        if (c != null) {
            try {
                c.disconnect(mContext);
            } catch (Exception e) {
                Log.w(TAG, "Failed to release WifiConnector", e);
            }
        }
    }

    /** Preferred stop method name. */
    public void shutdown() {
        requestStop();
    }

    /** The joined car Wi-Fi {@link Network}, or null if we have not joined (or it was lost). */
    public Network getWifiNetwork() {
        return wifiNetwork;
    }

    /**
     * The PHONE's OWN IPv4 address on the car Wi-Fi (e.g. 192.168.x.y), from the joined Network's
     * link addresses - NOT {@link #carAddressOn} (which returns the car / gateway address). Used to
     * route the gearhead head-unit-server (5277) connection over the car network so its network
     * context matches the projection socket. Returns null if not joined / no IPv4 address.
     */
    public String getOwnAddressOnCarNetwork() {
        Network net = wifiNetwork;
        if (net == null) return null;
        try {
            ConnectivityManager cm = mContext.getSystemService(ConnectivityManager.class);
            android.net.LinkProperties lp = cm != null ? cm.getLinkProperties(net) : null;
            if (lp == null) return null;
            for (android.net.LinkAddress la : lp.getLinkAddresses()) {
                java.net.InetAddress a = la.getAddress();
                if (a instanceof java.net.Inet4Address
                        && !a.isLoopbackAddress() && !a.isAnyLocalAddress()) {
                    return a.getHostAddress();
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "getOwnAddressOnCarNetwork failed", e);
        }
        return null;
    }

    // ---- Projection drop (car-driven re-start; handshake client stays alive) ----

    /**
     * Called when the projection session drops but we stay alive waiting for the car. We do NOT
     * re-open projection ourselves (the car drives the session). This only clears projectionActive
     * so that, if the car re-drives a session over the still-open RFCOMM, a re-sent WifiStartRequest
     * (id=1) opens a fresh projection socket instead of being ignored as "already open". The RFCOMM
     * link, the reader thread, and the cached WiFi handle are left untouched.
     */
    public void onProjectionDropped() {
        synchronized (tcpLock) {
            projectionActive = false;
        }
        Log.d(TAG, "onProjectionDropped: projectionActive cleared; RFCOMM/handshake kept alive, "
                + "waiting for the car to re-drive a session");
    }


    // ---- Reader / framing ----

    /**
     * Reader-driven: parse message -> perform action -> reply (synchronously).
     * No latches, no ordering requirements, no proactive sends.
     */
    private void onIncomingFrame(int messageId, byte[] payload, OutputStream reply, String via) {
        if (stop) return;
        final boolean bt = "BT".equals(via);

        // Unconditional inbound frame log - NEVER swallow a message silently.
        Log.d(TAG, "<< [" + via + "] frame id=" + messageId
                + " len=" + (payload != null ? payload.length : 0)
                + " hex=" + toHex(payload, 0, payload != null ? payload.length : 0));

        try {
            switch (messageId) {

                case 4: { // WifiVersionRequest -> respond with WifiVersionResponse (id=5)
                    WifiVersionRequest verReq = WifiVersionRequest.parseFrom(payload);

                    int major = verReq.hasMajorVersion() ? verReq.getMajorVersion() : 4;
                    int minor = verReq.hasMinorVersion() ? verReq.getMinorVersion() : 3;
                    Log.d(TAG, "Car requested WifiVersion " + major + "." + minor
                            + " endpoint-in-version=" + describeVersionEndpoint(payload));
                    rememberVersionEndpoint(payload);

                    // Match a REAL phone's response exactly (decoded from a btsnoop of a
                    // genuine phone<->this Zeekr handshake):
                    //   - ECHO the car's version (4.3), do NOT downgrade to 1.6
                    //   - set device_serial (field 3)
                    //   - set WifiDeviceInfo (field 6): device_id + connectivity_lifetime_id
                    // Zeekr silently drops (10s watchdog) a bare 1.6/status-only response.
                    WifiVersionResponse.WifiDeviceInfo devInfo =
                            WifiVersionResponse.WifiDeviceInfo.newBuilder()
                                    .setDeviceId(DEVICE_ID)
                                    .setConnectivityLifetimeId(CONNECTIVITY_LIFETIME_ID)
                                    .build();

                    // Over WPP-TCP answer like gearhead does natively to the Peugeot ("HU requested
                    // version: 4.3, phone using version: 6.1"); keep the echo on BT (Zeekr needs it).
                    int respMajor = bt ? major : 6;
                    int respMinor = bt ? minor : 1;
                    WifiVersionResponse verResp = WifiVersionResponse.newBuilder()
                            .setMajorVersion(respMajor)
                            .setMinorVersion(respMinor)
                            .setDeviceSerial(DEVICE_SERIAL)
                            .setStatus(WifiStatus.STATUS_SUCCESS)
                            .setDeviceInfo(devInfo)
                            .build();

                    if (reply != null) {
                        sendFrameSync(reply, 5, verResp);
                        Log.d(TAG, "Replied [" + via + "] WifiVersionResponse (id=5) " + respMajor + "." + respMinor
                                + " (car " + major + "." + minor + ") serial=" + DEVICE_SERIAL);
                    }
                    // Over TCP we're already on the car's Wi-Fi: no credentials request, and
                    // the TCP channel has its own ping loop.
                    if (!bt) return;

                    // Proactively request WiFi credentials (WifiInfoRequest, id=2). THIS is
                    // what drives the car to send WifiInfoResponse (id=3) with the AP
                    // ssid/key so we can join - without it the app never asks for WiFi.
                    // Small delay first so id=5 and id=2 don't coalesce into one read for
                    // the car's fragile parser.
                    try {
                        Thread.sleep(POST_VERSION_DELAY_MS);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                    sendFrameSync(reply, 2, WifiInfoRequest.newBuilder().build());
                    infoRequested = true;
                    Log.d(TAG, "Sent WifiInfoRequest (id=2) after version, awaiting WifiInfoResponse (id=3)");

                    // Keepalive ping loop, as a real phone does continuously.
                    startPingLoop();
                    return;
                }


                case 3: { // WifiInfoResponse -> learn credentials (some firmwares may send this)
                    havewificredentials=true;
                    WifiInfoResponse msg = WifiInfoResponse.parseFrom(payload);

                    String ssid = msg.hasSsid() ? msg.getSsid() : "";
                    String key  = msg.hasKey() ? msg.getKey() : "";

                    lastSsid = ssid;
                    lastKey  = key;

                    callbacks.onWifiCredentials(ssid, key);
                    Log.d(TAG, "Learned WifiInfoResponse: ssid=" + ssid + " keyLen=" + (key != null ? key.length() : 0));
                    // NOT on this reader thread: the join can take 10 s+ (the car's AP comes up
                    // slowly) and meanwhile nobody would read RFCOMM - unanswered pings / an
                    // unread WifiStartRequest made the Peugeot drop the BT link after ~10 s.
                    startWifiJoinAsync();
                    return;
                }

                case 1: { // WifiStartRequest = the car's projection endpoint (ip/port). Ack + maybe TCP.
                    WifiStartRequest startReq = WifiStartRequest.parseFrom(payload);

                    String reqIp = startReq.hasIpAddress() ? startReq.getIpAddress() : null;
                    int reqPort = startReq.hasPort() ? startReq.getPort() : -1;
                    Log.d(TAG, "Got [" + via + "] WifiStartRequest (car endpoint): ip=" + reqIp + " port=" + reqPort
                            + " havewificredentials=" + havewificredentials + " wifiNetwork=" + wifiNetwork);
                    // "0.0.0.0"/empty = the car itself on its AP (resolvable once we're on it).
                    if ((reqIp == null || reqIp.isEmpty() || "0.0.0.0".equals(reqIp)) && wifiNetwork != null) {
                        String car = carAddressOn(wifiNetwork);
                        if (car != null) reqIp = car;
                    }
                    ip = reqIp;
                    port = reqPort;
                    callbacks.onDeviceEndpoint(ip, port);

                    // Cars that skip the version exchange (no id=4) open with id=1 and then
                    // wait for the phone to ask for the Wi-Fi details. Ask now if nobody has.
                    if (!infoRequested && !havewificredentials && reply != null) {
                        sendFrameSync(reply, 2, WifiInfoRequest.newBuilder().build());
                        infoRequested = true;
                        Log.d(TAG, "Sent WifiInfoRequest (id=2) after WifiStartRequest (no version exchange)");
                    }

                    // Acknowledge (real phone replies WifiStartResponse id=7, status SUCCESS)
                    // on the channel the request came in on.
                    if (reply != null) {
                        WifiStartResponse startOk = WifiStartResponse.newBuilder()
                                .setStatus(WifiStatus.STATUS_SUCCESS)
                                .build();
                        sendFrameSync(reply, 7, startOk);
                        Log.d(TAG, "Replied [" + via + "] WifiStartResponse (id=7) SUCCESS");
                    }

                    // If we've already joined the car's WiFi, open the projection TCP socket
                    // now and report id=6 SUCCESS. Otherwise case 3 (WifiInfoResponse ->
                    // ensureWifiConnectedIfPossible) opens it right after the WiFi join.
                    // Guard: some cars re-send id=1 until they see TCP. Opening a second socket
                    // would start a second PhoneSimulator against the same car.
                    if (projectionActive) {
                        Log.d(TAG, "WifiStartRequest repeated but TCP projection already open -> ignoring");
                    } else if (wifiNetwork != null && ip != null && !ip.isEmpty() && port > 0) {
                        try {
                            openTcpOverWifi(ip, port);
                            callbacks.onBootstrapComplete();
                            if (reply != null) {
                                sendFrameSync(reply, 6, WifiConnectStatus.newBuilder()
                                        .setStatus(WifiStatus.STATUS_SUCCESS).build());
                                Log.d(TAG, "Replied [" + via + "] WifiConnectStatus (id=6) SUCCESS");
                            }
                        } catch (IOException e) {
                            Log.e(TAG, "TCP connect failed on WifiStartRequest", e);
                        }
                    }
                    return;
                }

                case 8: { // WifiPingRequest -> echo timestamp back as WifiPingResponse (id=9)
                    // The aawireless-style HU pings over RFCOMM as a keepalive; a phone
                    // that never answers may get its link torn down by the car.
                    AndroidAutoBluetooth.WifiPingRequest ping =
                            AndroidAutoBluetooth.WifiPingRequest.parseFrom(payload);
                    AndroidAutoBluetooth.WifiPingResponse pong =
                            AndroidAutoBluetooth.WifiPingResponse.newBuilder()
                                    .setTimestamp(ping.hasTimestamp() ? ping.getTimestamp() : 0)
                                    .build();
                    if (reply != null) {
                        sendFrameSync(reply, 9, pong);
                        Log.d(TAG, "Replied [" + via + "] WifiPingResponse (id=9) ts=" + pong.getTimestamp());
                    }
                    return;
                }

                case 9: { // WifiPingResponse -> car answered our keepalive ping; nothing to do
                    return;
                }

                default:
                    Log.d(TAG, "Received unhandled messageId=" + messageId + " len=" + (payload != null ? payload.length : 0));
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to handle messageId=" + messageId + " len=" + (payload != null ? payload.length : 0), e);
        }
    }

    /**
     * Proactively ping the car over BT (id=8) ~once per second during BOOTSTRAP
     * only; the car answers id=9. This keeps the RFCOMM link alive between the
     * version exchange and the moment projection comes up on TCP.
     *
     * It is deliberately NOT a lifetime keepalive: {@link #openTcpOverWifi} stops
     * it as soon as the projection socket connects. Past that point the car has
     * moved to TCP and stops pongging, so continued id=8 traffic is pure noise on
     * the car's fragile RFCOMM parser.
     *
     * One loop per connection; {@link #stopPingLoop()} also tears it down on
     * reconnect and shutdown.
     */
    private void startPingLoop() {
        if (pingThread != null) return;
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(400); // don't coalesce the first ping with id=5
                while (!stop && out != null && Thread.currentThread() == pingThread) {
                    try {
                        AndroidAutoBluetooth.WifiPingRequest ping =
                                AndroidAutoBluetooth.WifiPingRequest.newBuilder()
                                        .setTimestamp(System.currentTimeMillis())
                                        .build();
                        sendFrameSync(out, 8, ping);
                    } catch (IOException e) {
                        Log.w(TAG, "Ping send failed, stopping ping loop", e);
                        return;
                    }
                    Thread.sleep(1000);
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }, "aa-bt-ping");
        t.setDaemon(true);
        pingThread = t;
        t.start();
        Log.d(TAG, "Started BT keepalive ping loop (id=8)");
    }

    private void stopPingLoop() {
        Thread t = pingThread;
        pingThread = null;
        if (t != null) t.interrupt();
    }

    private final java.util.concurrent.atomic.AtomicBoolean wifiJoining =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private void startWifiJoinAsync() {
        if (!wifiJoining.compareAndSet(false, true)) {
            Log.d(TAG, "Wi-Fi join already in progress");
            return;
        }
        Thread t = new Thread(() -> {
            try {
                ensureWifiConnectedIfPossible();
            } finally {
                wifiJoining.set(false);
            }
        }, "aa-wifi-join");
        t.setDaemon(true);
        t.start();
    }

    private void ensureWifiConnectedIfPossible() {
        // Already on the car's Wi-Fi (e.g. RFCOMM re-handshake after the WPP TCP channel dropped):
        // no new join, but the TCP control channel still has to come back.
        if (wifiNetwork != null) {
            if (!projectionActive && (ip == null || port <= 0)) startWppOverTcp();
            return;
        }

        // Only attempt if we have creds.
        final String ssid = lastSsid;
        final String key  = lastKey;
        if (ssid == null || ssid.isEmpty()) return;

        try {
            WifiConnector connector = new WifiConnector();
            // Drop the cached Network the moment the car's AP goes away. Without
            // this, a re-handshake reuses a dead Network handle and every
            // openTcpOverWifi fails with ENONET ("Machine is not on the network").
            connector.setNetworkListener(lost -> {
                if (lost.equals(wifiNetwork)) {
                    Log.w(TAG, "Car WiFi lost -> clearing wifiNetwork (was " + lost + ")");
                    wifiNetwork = null;
                    // Tell the service to PROACTIVELY full-close the gearhead (5277) link now, so its
                    // stuck pump hits EOF and its accept-loop frees - instead of waiting out the
                    // read-side socket timeout. See HandshakeCallbacks.onCarWifiLost.
                    try { callbacks.onCarWifiLost(); } catch (Exception ignored) {}
                }
            });
            wifiConnector = connector;

            Network joined = connector.connectWpa2(mContext, ssid, key != null ? key : "")
                    .orTimeout(25, TimeUnit.SECONDS)
                    .join();

            // onLost can fire between onAvailable and this assignment. The connector
            // clears its own handle on loss, so if it's already gone, don't cache a
            // dead Network - bail out and let a later id=3 retry.
            if (connector.getNetwork() == null) {
                Log.w(TAG, "WiFi was lost during join; not caching dead network " + joined);
                releaseWifi();
                return;
            }
            wifiNetwork = joined;

            Log.d(TAG, "Connected to WiFi. network=" + wifiNetwork);


            WifiConnectStatus.Builder ok = WifiConnectStatus.newBuilder();
            ok.setStatus(WifiStatus.STATUS_SUCCESS);
            sendFrameSync(out, 6, ok.build());
            callbacks.onWifiConnected(wifiNetwork);
            if (port > 0 && (ip == null || ip.isEmpty() || "0.0.0.0".equals(ip))) {
                String car = carAddressOn(wifiNetwork);   // id=1 came before the join
                if (car != null) ip = car;
            }
            if (ip != null && port > 0 && !projectionActive) {
                openTcpOverWifi(ip, port);
            } else if (!projectionActive) {
                // No WifiStartRequest (id=1) yet. Cars advertising WPP-over-TCP in their version
                // request (Peugeot) send it over that TCP channel instead of RFCOMM.
                startWppOverTcp();
            }
        } catch (Throwable t) {
            Log.e(TAG, "WiFi connect attempt failed", t);
            // Release the failed request's callback so the next attempt starts clean.
            releaseWifi();
        }
    }

    /**
     * Open the WPP control channel over TCP that the car advertised in its version request
     * ("0.0.0.0" -> the car's address on its AP) and run it exactly like RFCOMM: same framing,
     * same handlers, replies on this socket, ~1 s pings as gearhead does. The car's
     * WifiStartRequest (id=1) with the real projection ip/port arrives here; its handler then
     * opens the projection socket. Stays open for the session (gearhead keeps it too).
     */
    private void startWppOverTcp() {
        final int vPort = versionEndpointPort;
        if (vPort <= 0) {
            Log.d(TAG, "No WifiStartRequest (id=1) yet and no WPP-over-TCP endpoint advertised -> waiting for id=1 on BT");
            return;
        }
        if (wppSocket != null) return;
        Thread t = new Thread(() -> {
            Socket s = null;
            try {
                Network net = wifiNetwork;
                if (net == null || stop) return;
                String host = versionEndpointIp;
                if (host == null || host.isEmpty() || "0.0.0.0".equals(host)) host = carAddressOn(net);
                if (host == null) {
                    Log.w(TAG, "WPP over TCP: can't determine the car's address on its Wi-Fi");
                    return;
                }
                Socket raw = net.getSocketFactory().createSocket();
                s = raw;
                raw.connect(new InetSocketAddress(host, vPort), 5000);
                raw.setTcpNoDelay(true);
                wppSocket = raw;
                Log.d(TAG, "WPP over TCP: connected to " + host + ":" + vPort + ", starting TLS (phone = server)");

                // The car opens with a TLS ClientHello straight on the socket (seen on the
                // Peugeot: 16 03 01 .. 01) and drops the link on anything plaintext. Same roles
                // and identity as the projection link: car = TLS client, phone = TLS server
                // presenting the AA phone certificate.
                javax.net.ssl.SSLSocket tls;
                try {
                    javax.net.ssl.SSLContext ctx = io.aawireless.wirelessaamanipulator.helpers.ssl.sslEngineBuilder
                            .context(io.aawireless.wirelessaamanipulator.helpers.ssl.CertProvider.getKeyStore(mContext));
                    tls = (javax.net.ssl.SSLSocket) ctx.getSocketFactory().createSocket(raw, host, vPort, true);
                } catch (Exception e) {
                    throw new IOException("WPP TLS setup failed: " + e, e);
                }
                tls.setUseClientMode(false);
                tls.setWantClientAuth(true);   // log the car's cert if it sends one; don't require it
                tls.setEnabledProtocols(new String[]{"TLSv1.2"});
                s = tls;
                wppSocket = tls;
                tls.startHandshake();
                javax.net.ssl.SSLSession ses = tls.getSession();
                String peer;
                try {
                    peer = ((java.security.cert.X509Certificate) ses.getPeerCertificates()[0])
                            .getSubjectX500Principal().getName();
                } catch (Exception e) {
                    peer = "none";
                }
                Log.d(TAG, "WPP TLS handshake done: " + ses.getProtocol() + " " + ses.getCipherSuite()
                        + " carCert=" + peer + " -> waiting for WifiStartRequest (id=1)");

                // One WPP session per phone: gearhead runs ONLY the TCP one with this car. With
                // RFCOMM still up the Peugeot dropped TCP (~3 s after its id=10) and kept BT.
                // The run loop waits on the TCP channel instead of reconnecting RFCOMM.
                stopPingLoop();
                closeCurrentSocketQuietly();
                Log.d(TAG, "WPP moved to TCP -> closed RFCOMM");

                final InputStream wIn = tls.getInputStream();
                final OutputStream wOut = tls.getOutputStream();
                startWppPingLoop(wOut);

                FrameParser parser = new FrameParser((id, p) -> onIncomingFrame(id, p, wOut, "TCP"));
                byte[] tmp = new byte[2048];
                while (!stop) {
                    int n = wIn.read(tmp);
                    if (n == -1) {
                        Log.w(TAG, "WPP TCP: car closed the control channel");
                        break;
                    }
                    Log.d(TAG, "<< [TCP] raw " + n + "B: " + toHex(tmp, 0, n));
                    parser.feed(tmp, 0, n);
                }
            } catch (IOException e) {
                if (!stop) Log.w(TAG, "WPP TCP control channel ended: " + e);
            } finally {
                closeWpp(s);
            }
        }, "aa-wpp-tcp");
        t.setDaemon(true);
        t.start();
    }

    private void startWppPingLoop(OutputStream wOut) {
        Thread t = new Thread(() -> {
            try {
                while (!stop && Thread.currentThread() == wppPingThread) {
                    sendFrameSync(wOut, 8, AndroidAutoBluetooth.WifiPingRequest.newBuilder()
                            .setTimestamp(System.currentTimeMillis())
                            .build());
                    Thread.sleep(1000);
                }
            } catch (IOException e) {
                Log.w(TAG, "WPP TCP ping send failed, stopping ping loop: " + e);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }, "aa-wpp-tcp-ping");
        t.setDaemon(true);
        wppPingThread = t;
        t.start();
    }

    /** Close the WPP TCP channel (only if {@code s} is still the current one, or s == null). */
    private void closeWpp(Socket s) {
        Socket cur = wppSocket;
        if (s == null) s = cur;
        if (s == null) return;
        if (cur == s) {
            wppSocket = null;
            Thread p = wppPingThread;
            wppPingThread = null;
            if (p != null) p.interrupt();
        }
        try { s.close(); } catch (Exception ignored) {}
    }

    /** Remember the endpoint block from the version request for the fallback above. */
    private void rememberVersionEndpoint(byte[] payload) {
        String ep = describeVersionEndpoint(payload);
        int colon = ep.lastIndexOf(':');
        if (colon <= 0) return;
        try {
            int p = Integer.parseInt(ep.substring(colon + 1));
            if (p > 0 && p < 65536) {
                versionEndpointIp = ep.substring(0, colon);
                versionEndpointPort = p;
            }
        } catch (NumberFormatException ignored) {}
    }

    /** The car's own address on its AP: the DHCP server, else an IPv4 route gateway. */
    private String carAddressOn(Network net) {
        try {
            ConnectivityManager cm = mContext.getSystemService(ConnectivityManager.class);
            android.net.LinkProperties lp = cm != null ? cm.getLinkProperties(net) : null;
            if (lp == null) return null;
            Log.d(TAG, "car Wi-Fi link: dhcpServer=" + lp.getDhcpServerAddress()
                    + " addrs=" + lp.getLinkAddresses() + " routes=" + lp.getRoutes());
            if (lp.getDhcpServerAddress() != null) return lp.getDhcpServerAddress().getHostAddress();
            for (android.net.RouteInfo r : lp.getRoutes()) {
                if (r.hasGateway() && r.getGateway() instanceof java.net.Inet4Address) {
                    return r.getGateway().getHostAddress();
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "carAddressOn failed", e);
        }
        return null;
    }

    private Socket openTcpOverWifi(String ip, int port) throws IOException {
        synchronized (tcpLock) {
        if (projectionActive) {   // id=1 and the fallback can race; only one TCP session
            Log.d(TAG, "Projection TCP already open -> not opening " + ip + ":" + port);
            return null;
        }
        if (wifiNetwork == null) throw new IOException("wifiNetwork is null");

        // Binding a socket to a freshly-joined WifiNetworkSpecifier network via
        // Network.getSocketFactory().createSocket() has been returning EPERM on some devices/runs
        // ("Binding socket to network N failed: EPERM"). Work around it by transiently binding the
        // PROCESS to the car network, connecting a PLAIN socket, then restoring the previous binding:
        // a socket connected while the process is bound stays pinned to that network for its whole
        // life, so projection rides the car adapter (and the socket dies cleanly when that adapter
        // goes away). Loopback (the 5277 DHU link) is unaffected; only outbound internet is briefly
        // unavailable during the connect, and we restore the binding immediately in finally. Retry a
        // few times since a just-joined network can still be settling. Re-read wifiNetwork each pass
        // in case the onLost listener cleared it meanwhile.
        final int maxAttempts = 4;
        final ConnectivityManager cm =
                (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        Socket s = null;
        IOException lastErr = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            Network net = wifiNetwork;
            if (net == null) throw new IOException("wifiNetwork cleared while opening projection socket");
            Network prevBound = (cm != null) ? cm.getBoundNetworkForProcess() : null;
            try {
                if (cm != null) cm.bindProcessToNetwork(net);
                s = new Socket();
                s.connect(new InetSocketAddress(ip, port), 5000);
            } catch (IOException e) {
                lastErr = e;
                try { if (s != null) s.close(); } catch (Exception ignored) {}
                s = null;
            } finally {
                // Restore the process network binding so cloud/internet traffic is routed normally again.
                if (cm != null) { try { cm.bindProcessToNetwork(prevBound); } catch (Exception ignored) {} }
            }
            if (s != null) break;
            Log.w(TAG, "openTcpOverWifi process-bind connect attempt " + attempt + "/" + maxAttempts
                    + " failed: " + (lastErr != null ? lastErr.getMessage() : "?"));
            if (attempt < maxAttempts) {
                try { Thread.sleep(300L * attempt); }
                catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted during WiFi socket retry", ie);
                }
            }
        }
        if (s == null) throw (lastErr != null ? lastErr : new IOException("could not open projection socket"));
        s.setTcpNoDelay(true);

        // Projection is now up on TCP. The BT keepalive (id=8) exists only to hold
        // the RFCOMM link together through bootstrap; once the car is talking over
        // TCP it stops answering id=8 (no id=9 pongs in logcat) and the pings are
        // just noise on a link the car's fragile frame parser is still reading.
        stopPingLoop();
        // From here on, the car closing RFCOMM is expected and must not trigger a
        // BT reconnect - projection lives on this TCP socket now.
        projectionActive = true;
        Log.d(TAG, "TCP projection established -> stopped BT keepalive, RFCOMM now expendable");

        service.onTcpConnected(s);
        return s;
        }
    }

    // ---- Stop helpers ----

    private void stopAndCloseCurrentSocket() {
        stopPingLoop();
        closeWpp(null);
        BluetoothSocket s = socket;
        socket = null;

        // Stop reader
        Thread rt = readerThread;
        readerThread = null;
        if (rt != null) rt.interrupt();

        // Close streams/socket
        try { if (in != null) in.close(); } catch (Exception ignored) {}
        try { if (out != null) out.close(); } catch (Exception ignored) {}
        in = null;
        out = null;

        try { if (s != null) s.close(); } catch (Exception ignored) {}

    }



    // ---- Helpers for reconnection rules ----

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private BluetoothSocket createSocketOrNull() {
        try {
            BluetoothSocket s = device.createInsecureRfcommSocketToServiceRecord(AA_WIRELESS_UUID);
            Log.d(TAG, "Created RFCOMM socket");
            return s;
        } catch (Exception e) {
            Log.e(TAG, "Failed to create socket", e);
            return null;
        }
    }



    // ---- Framing ----

    private void sendFrameSync(OutputStream out, int messageId, MessageLite msg) throws IOException {
        synchronized (writeLock) {
            if (stop) return;
            if (out == null) return;
            sendFrame(out, messageId, msg);
        }
    }

    private static void sendFrame(OutputStream out, int messageId, MessageLite msg) throws IOException {
        byte[] payload = msg.toByteArray();
        int length = payload.length;
        if (length > 0xFFFF) throw new IOException("Payload too large: " + length);

        ByteArrayOutputStream bos = new ByteArrayOutputStream(4 + length);
        writeU16BE(bos, length);
        writeU16BE(bos, messageId);
        bos.write(payload);

        byte[] frame = bos.toByteArray();
        Log.d(TAG, ">> frame id=" + messageId + " len=" + length
                + " hex=" + toHex(frame, 0, frame.length));

        out.write(frame);
        out.flush();
    }

    private static void writeU16BE(ByteArrayOutputStream bos, int v) {
        bos.write((v >>> 8) & 0xFF);
        bos.write(v & 0xFF);
    }

    /**
     * The car's WifiVersionRequest carries, as wire field 6, an endpoint block
     * {1: ip_address, 2: port} (Zeekr: "0.0.0.0":9999). Our .proto numbers these fields
     * differently from the wire, so decode it by hand. Diagnostic only.
     */
    private static String describeVersionEndpoint(byte[] p) {
        try {
            int i = 0;
            while (p != null && i < p.length) {
                long[] tag = readVarint(p, i); i = (int) tag[1];
                int field = (int) (tag[0] >>> 3), wt = (int) (tag[0] & 7);
                if (wt == 0) { i = (int) readVarint(p, i)[1]; continue; }
                if (wt != 2) return "unparsed(wt=" + wt + ")";
                long[] len = readVarint(p, i); i = (int) len[1];
                int end = i + (int) len[0];
                if (field == 6) {
                    String ip = null; long port = -1;
                    int j = i;
                    while (j < end) {
                        long[] t = readVarint(p, j); j = (int) t[1];
                        int f = (int) (t[0] >>> 3), w = (int) (t[0] & 7);
                        if (w == 0) { long[] v = readVarint(p, j); j = (int) v[1]; if (f == 2) port = v[0]; }
                        else if (w == 2) {
                            long[] l = readVarint(p, j); j = (int) l[1];
                            if (f == 1) ip = new String(p, j, (int) l[0], java.nio.charset.StandardCharsets.UTF_8);
                            j += (int) l[0];
                        } else break;
                    }
                    return ip + ":" + port;
                }
                i = end;
            }
        } catch (Exception e) {
            return "err(" + e.getMessage() + ")";
        }
        return "none";
    }

    /** @return {value, nextIndex} */
    private static long[] readVarint(byte[] b, int i) {
        long v = 0; int shift = 0;
        while (true) {
            int x = b[i++] & 0xFF;
            v |= (long) (x & 0x7F) << shift;
            if ((x & 0x80) == 0) return new long[]{v, i};
            shift += 7;
        }
    }

    /** Hex dump helper for frame-level debugging (capped to keep logcat sane). */
    private static String toHex(byte[] b, int off, int len) {
        if (b == null) return "null";
        int max = Math.min(len, 512); // BT handshake frames are small; 128 cut the Peugeot's 136B id=4
        StringBuilder sb = new StringBuilder(max * 3 + 8);
        for (int i = 0; i < max; i++) {
            sb.append(String.format("%02X", b[off + i]));
            if (i < max - 1) sb.append(' ');
        }
        if (len > max) sb.append(" …(").append(len).append("B total)");
        return sb.toString();
    }

    // ---- Frame parser ----

    private static final class FrameParser {
        interface Listener { void onFrame(int messageId, byte[] payload); }

        private final Listener listener;
        private byte[] buf = new byte[8192];
        private int writePos = 0;

        FrameParser(Listener listener) {
            this.listener = listener;
        }

        void feed(byte[] data, int off, int len) {
            ensureCapacity(writePos + len);
            System.arraycopy(data, off, buf, writePos, len);
            writePos += len;
            parseAvailable();
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
