package io.aawireless.wirelessaamanipulator.helpers.simulators;

import static android.content.Context.MODE_PRIVATE;
import static io.aawireless.wirelessaamanipulator.helpers.Constants.CONTROLCHANNEL;
import static io.aawireless.wirelessaamanipulator.helpers.Constants.FLAG_ALL;
import static io.aawireless.wirelessaamanipulator.helpers.Constants.FLAG_ENCRYPTED;
import static io.aawireless.wirelessaamanipulator.helpers.messageprocessors.EnergyModelTranslator.sensorChannel;
import static io.aawireless.wirelessaamanipulator.proto.SensorMessageId.SENSOR_MESSAGE_BATCH_VALUE;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONException;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

import javax.net.ssl.SSLEngine;

import io.aawireless.wirelessaamanipulator.helpers.AAMessage;
import io.aawireless.wirelessaamanipulator.helpers.Util;
import io.aawireless.wirelessaamanipulator.helpers.energymodel.EnergyModel;
import io.aawireless.wirelessaamanipulator.helpers.energymodel.EnergyModelFromJson;
import io.aawireless.wirelessaamanipulator.helpers.messageprocessors.EnergyModelTranslator;
import io.aawireless.wirelessaamanipulator.helpers.ssl.NioSSL;
import io.aawireless.wirelessaamanipulator.helpers.ssl.sslEngineBuilder;
import io.aawireless.wirelessaamanipulator.helpers.streams.InOutStream;
import io.aawireless.wirelessaamanipulator.helpers.interfaces.ServiceThreadPair;
import io.aawireless.wirelessaamanipulator.proto.ControlMessageType;
import io.aawireless.wirelessaamanipulator.proto.FuelData;
import io.aawireless.wirelessaamanipulator.proto.MessageStatus;
import io.aawireless.wirelessaamanipulator.proto.PhoneInfo;
import io.aawireless.wirelessaamanipulator.proto.SensorBatch;
import io.aawireless.wirelessaamanipulator.proto.SensorMessageId;
import io.aawireless.wirelessaamanipulator.proto.SensorRequest;
import io.aawireless.wirelessaamanipulator.proto.SensorResponse;
import io.aawireless.wirelessaamanipulator.proto.SensorType;
import io.aawireless.wirelessaamanipulator.proto.ServiceDiscoveryRequest;
import io.aawireless.wirelessaamanipulator.services.MainTaskService;

public class CarSimulator extends AASimulator{
    private static final String TAG = "CarSimulator";
    private final boolean isDummyBattery;
    private NioSSL ssl;
    /** Listening socket (6288), held so stopself() can unblock a thread parked in accept(). */
    private volatile ServerSocket serverSocket;

    // Bind-readiness gate. These are PRIMITIVE fields (default false before any ctor
    // runs), so they're safe to touch from run() even though AASimulator starts the
    // thread inside its constructor (start-in-ctor). awaitBound() lets the caller
    // hold off the "connect to 127.0.0.1:6288" broadcast until the ServerSocket is
    // actually bound - otherwise gearhead races us and its connect() is refused,
    // which is why projection "sometimes doesn't fire up".
    private volatile boolean bound = false;
    private volatile boolean bindFailed = false;
    /** True once gearhead (or any client) has connected to 6288 / we connected to 5277. */
    private volatile boolean accepted = false;

    /**
     * AA &gt; 17.3 killed the 6288 START broadcast, so instead of binding+accepting on 6288
     * we connect OUT to gearhead's DHU head-unit server on 127.0.0.1:5277 (the user starts
     * it from Android Auto's Developer settings). Protocol + TLS role are identical; only
     * the TCP direction is reversed.
     */
    private final boolean useHeadUnitServer;
    /** How long to keep trying to reach 5277 (the user may start the server after us). */
    private static final long HUS_CONNECT_TIMEOUT_MS = 90_000;
    private static final long HUS_RETRY_INTERVAL_MS  = 1_000;

    public boolean hasAccepted() { return accepted; }
    public boolean isHeadUnitServerMode() { return useHeadUnitServer; }

    public CarSimulator(ServiceThreadPair other, EnergyModelTranslator t) {
        super(other,  "CarToPhone", t);
        Log.d(TAG,"CarSimulator created");
        SharedPreferences prefs = other.getContext().getSharedPreferences("AA_SETTINGS", MODE_PRIVATE);
        isDummyBattery=prefs.getBoolean("dummy_battery",false);
        this.useHeadUnitServer =
                io.aawireless.wirelessaamanipulator.helpers.GearheadSettings.needsHeadUnitServer(other.getContext());
        Log.d(TAG, "gearhead versionName="
                + io.aawireless.wirelessaamanipulator.helpers.GearheadSettings.getVersionName(other.getContext())
                + " -> " + (useHeadUnitServer ? "DHU head-unit-server (connect 127.0.0.1:5277)"
                                              : "legacy broadcast (accept on 6288)"));
    }

    /**
     * Block until port 6288 is bound (backlog now queues incoming connects) or the
     * bind fails / times out.
     * @return true if bound and ready to accept, false otherwise.
     */
    public boolean awaitBound(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!bound && !bindFailed && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return bound;
            }
        }
        return bound;
    }



    /**
     * Connect to gearhead's DHU head-unit server on 127.0.0.1:5277, retrying while the
     * user starts it from Android Auto's Developer settings. Returns the connected socket,
     * or null if we time out / are asked to stop.
     */
    private java.net.Socket connectHeadUnitServer() {
        long deadline = System.currentTimeMillis() + HUS_CONNECT_TIMEOUT_MS;
        boolean advised = false;
        int attempt = 0;
        while (MainTaskService.running && !isInterrupted() && System.currentTimeMillis() < deadline) {
            attempt++;

            // EXPERIMENTAL: unify the 5277 network context with the projection socket. Try our OWN
            // address on the car Wi-Fi (e.g. 192.168.x.y):5277 OVER the car network FIRST. gearhead's
            // DHU may only listen on 127.0.0.1 though, so if that fails fall back to the plain
            // loopback 127.0.0.1:5277 that has always worked.
            android.net.Network carNet = other.getCarNetwork();
            String ourIp = other.getOwnCarNetworkIp();
            if (carNet != null && ourIp != null) {
                java.net.Socket s = connectOverCarNetwork(carNet, ourIp, 5277);
                if (s != null) {
                    Log.d(TAG, "Connected to head unit server " + ourIp + ":5277 over the car network "
                            + "(attempt " + attempt + ")");
                    io.aawireless.wirelessaamanipulator.status.AppStatus.clearAdvice();
                    return s;
                }
                Log.d(TAG, "5277 over car network (" + ourIp + ") failed -> trying loopback");
            }

            // Plain (unbound) loopback socket to gearhead. It MUST NOT be bound to the car
            // network: that network is local-only (no loopback route), so a marked socket's
            // data send finds no route to 127.0.0.1 and the gearhead handshake stalls even
            // though connect() slips through the kernel's local table.
            java.net.Socket s = new java.net.Socket();
            try {
                s.connect(new InetSocketAddress("127.0.0.1", 5277), 2000);
                Log.d(TAG, "Connected to head unit server 127.0.0.1:5277 over loopback (attempt " + attempt + ")");
                return s;
            } catch (Exception e) {
                try { s.close(); } catch (Exception ignored) {}
                if (!advised) {
                    advised = true;
                    Log.w(TAG, "head unit server (5277) not up yet - asking user to start it");
                    io.aawireless.wirelessaamanipulator.status.AppStatus.setAdvice(
                            io.aawireless.wirelessaamanipulator.helpers.GearheadSettings.START_HEAD_UNIT_SERVER_ADVICE);
                }
                try { Thread.sleep(HUS_RETRY_INTERVAL_MS); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); return null; }
            }
        }
        Log.e(TAG, "Gave up connecting to head unit server (5277) after " + attempt + " attempts");
        return null;
    }

    /**
     * Connect to {@code ip}:{@code port} routed over the car Wi-Fi network, using the same transient
     * bindProcessToNetwork() workaround as CarHandshakeClient.openTcpOverWifi (Network.getSocket
     * Factory().createSocket() has been returning EPERM on some devices). A socket connected while
     * the process is bound stays pinned to that network for its whole life, so the 5277 link then
     * rides the car adapter like the projection socket. Returns the connected socket, or null on
     * failure (caller falls back to plain loopback).
     */
    private java.net.Socket connectOverCarNetwork(android.net.Network net, String ip, int port) {
        android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                other.getContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        android.net.Network prevBound = (cm != null) ? cm.getBoundNetworkForProcess() : null;
        java.net.Socket s = null;
        try {
            if (cm != null) cm.bindProcessToNetwork(net);
            s = new java.net.Socket();
            s.connect(new InetSocketAddress(ip, port), 2000);
            s.setTcpNoDelay(true);
            return s;
        } catch (Exception e) {
            try { if (s != null) s.close(); } catch (Exception ignored) {}
            Log.w(TAG, "5277 connect over car network " + ip + " failed: " + e);
            return null;
        } finally {
            // Restore the process network binding so other traffic routes normally again.
            if (cm != null) { try { cm.bindProcessToNetwork(prevBound); } catch (Exception ignored) {} }
        }
    }

    @Override
    public void stopself() {
        // accept() does not respond to interrupt(), so a thread parked waiting for gearhead to
        // connect on 6288 (Stop pressed before a session starts) only unblocks when the listening
        // socket is closed. Close it here; run()'s finally double-closes harmlessly.
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}
        super.stopself();
    }

    @Override
    public void run() {
        super.run();

        serverSocket = null;
        try {
            java.net.Socket socket;
            if (useHeadUnitServer) {
                // AA > 17.3: connect OUT to gearhead's DHU head-unit server on 5277.
                socket = connectHeadUnitServer();
                if (socket == null) {
                    bindFailed = true; // release awaitBound()
                    throw new java.io.IOException("head unit server (5277) not reachable");
                }
                bound = true;
                accepted = true; // we're in - treat as accepted (no broadcast/nudge needed)
                io.aawireless.wirelessaamanipulator.status.AppStatus.clearAdvice();
            } else {
                serverSocket = new ServerSocket();
                serverSocket.setReuseAddress(true);
                try {
                    serverSocket.bind(new InetSocketAddress(6288));
                } catch (Exception bindEx) {
                    bindFailed = true; // release awaitBound() so we don't hang the broadcast
                    throw bindEx;
                }
                bound = true; // 6288 now accepts into the backlog; safe to tell gearhead to connect
                Log.d(TAG,"CarSimulator listening on port 6288 (bound)");
                socket = serverSocket.accept();
                accepted = true; // gearhead connected - stop any re-broadcast nudging
            }

            Log.d(TAG,"CarSimulator accepted connection, socket is: "+socket);
            inoutstream=new InOutStream(socket);
            inoutstream.setNamedstring(TAG);

            SSLEngine engine = sslEngineBuilder.Builder(other.getContext(),"keystore");
            Log.w(TAG, "Send Car hello: ");
            inoutstream.write(new byte[]{0, 3, 0, 6, 0, 1, 0, 4, 0, 1}, 500);
            //00 03 00 06 00 01 00 04 00 01

            byte[] data = inoutstream.readraw();
            Log.w(TAG, "Read phone hello: "+ Util.bytesToHex(data));

            Log.w(TAG, "Phone hello: ");
            ssl = new NioSSL(engine, inoutstream, true, TAG);

            other.setphoneSSL(ssl);
            linkSsl = ssl;   // enables a proper ByeBye to gearhead on shutdown
            //Auth complete should be sent by the car
            //inoutstream.write(new byte[]{0, 3, 0, 4, 0, 4, 8, 0}); // Auth Complete, must be sent in plaintext
            ready=true;

            while (!other.isPhoneready())
            {
                Thread.sleep(50);
            }
            Log.d(TAG,"Phone connected to AA, starting reading data from AA");
            while (MainTaskService.running  && !isInterrupted()) {
                AAMessage message = new AAMessage(inoutstream.readraw(), ssl);
                sessionLive = true;   // gearhead is talking encrypted -> a ByeBye will be understood
                if (consumeOwnByeByeResponse(message)) continue;   // answer to OUR ByeBye: don't relay
                // Visibility: what does gearhead send on the cluster channel? (open/setup)
                int vcl = io.aawireless.wirelessaamanipulator.hud.ClusterInfo.getVideoClusterServiceId();
                if (vcl >= 0 && (message.channel & 0xFF) == (vcl & 0xFF)) {
                    Log.d(TAG, "phone->car cluster id=" + message.getMessageID()
                            + " len=" + (message.getData() != null ? message.getData().length : 0));
                }
                if (isDummyBattery && message.getMessageID() == SensorMessageId.SENSOR_MESSAGE_REQUEST_VALUE && sensorChannel != null  && message.channel==sensorChannel)
                {

                    SensorRequest req = SensorRequest.parseFrom(message.getData());




                    if (req.getType() == SensorType.SENSOR_VEHICLE_ENERGY_MODEL_DATA) {
                        Log.d(TAG, "Received vehicle energy model data request: "+req);

                        AAMessage response=new AAMessage(message.channel,message.flag,SensorMessageId.SENSOR_MESSAGE_RESPONSE_VALUE);
                        response.setEncrypted(FLAG_ENCRYPTED);
                        response.setData(SensorResponse.newBuilder().setStatus(MessageStatus.STATUS_SUCCESS).build().toByteArray());
                        inoutstream.write(response.getFormated(ssl));

                        //fakeBattery(100);


                    }

                    else if (req.getType() == SensorType.SENSOR_FUEL){
                        Log.d(TAG, "Received vehicle fuel data request: "+req);
                        AAMessage response=new AAMessage(message.channel,message.flag,SensorMessageId.SENSOR_MESSAGE_RESPONSE_VALUE);
                        response.setEncrypted(FLAG_ENCRYPTED);
                        response.setData(SensorResponse.newBuilder().setStatus(MessageStatus.STATUS_SUCCESS).build().toByteArray());
                        inoutstream.write(response.getFormated(ssl));

                        AAMessage dummy = new AAMessage(EnergyModelTranslator.sensorChannel.byteValue(), FLAG_ALL, SENSOR_MESSAGE_BATCH_VALUE);
                        SensorBatch.Builder sb = SensorBatch.newBuilder();
                        sb.addFuelData(FuelData.newBuilder().setFuelLevel(100).setRange(500).setLowFuelWarning(false).build());
                        SensorBatch sbb=sb.build();
                        dummy.setData(sbb.toByteArray());
                        inoutstream.write(dummy.getFormated(ssl));


                    }

                    else
                        other.pushToPhone(message);
                }
                    else {
                        AAMessage outMsg = translator.process(message);
                        if (outMsg != null) other.pushToPhone(outMsg); // null = consumed
                    }

            }

        } catch (InterruptedException e) {
            Log.d(TAG, "CarSimulator interrupted, exiting cleanly");
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // A dropped socket (e.g. the car's WiFi going away) must NEVER kill the
            // process. Log and let the thread end; the service/receiver decides
            // whether to restart. Previously this rethrew as RuntimeException and
            // became an uncaught FATAL exception.
            Log.w(TAG, "CarSimulator terminating due to: " + e, e);
        } finally {
            // Mark not-ready so the peer simulator's isCarready() spin-wait can't
            // latch onto a dead link.
            ready = false;
            try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}
            // Close our end properly too (no-op if a ByeBye already closed it), and let the
            // service ByeBye the car side so neither peer is left attached to nothing.
            try { if (inoutstream != null) inoutstream.close(); } catch (Exception ignored) {}
            other.onSimulatorEnded(this);
            Log.d(TAG, "CarSimulator thread exiting");
        }

    }

    public void fakeBattery(int bat) throws JSONException {

        AAMessage dummy = new AAMessage(EnergyModelTranslator.sensorChannel.byteValue(), FLAG_ALL, SENSOR_MESSAGE_BATCH_VALUE);
        SensorBatch.Builder sb = SensorBatch.newBuilder();
        sb.addEnergyModel(EnergyModelFromJson.create(bat));
        SensorBatch sbb=sb.build();
        dummy.setData(sbb.toByteArray());
        send(dummy.getFormated(ssl));
        Log.d(TAG,"EnergyModel reported battery: "+sb.getEnergyModelList().get(0).getVehicleInfo().getBatteryLevelWh());
    }

   
}
