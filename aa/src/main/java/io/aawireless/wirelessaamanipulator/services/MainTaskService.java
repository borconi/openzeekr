package io.aawireless.wirelessaamanipulator.services;

import static io.aawireless.wirelessaamanipulator.helpers.Constants.FLAG_ALL;
import static io.aawireless.wirelessaamanipulator.helpers.Constants.FLAG_ENCRYPTED;
import static io.aawireless.wirelessaamanipulator.proto.SensorMessageId.SENSOR_MESSAGE_BATCH_VALUE;
import static io.aawireless.wirelessaamanipulator.proto.SensorMessageId.SENSOR_MESSAGE_REQUEST_VALUE;
import static io.aawireless.wirelessaamanipulator.proto.SensorMessageId.SENSOR_MESSAGE_RESPONSE_VALUE;

import android.Manifest;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Network;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;

import java.net.Socket;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import androidauto.ev.proto.Ev;
import io.aawireless.wirelessaamanipulator.connectors.CarHandshakeClient;
import io.aawireless.wirelessaamanipulator.connectors.CarHandshakeClientManager;
import io.aawireless.wirelessaamanipulator.helpers.AAMessage;
import io.aawireless.wirelessaamanipulator.helpers.AAStarter;
import io.aawireless.wirelessaamanipulator.helpers.Constants;
import io.aawireless.wirelessaamanipulator.helpers.energymodel.EnergyModel;
import io.aawireless.wirelessaamanipulator.helpers.interfaces.HandshakeCallbacks;
import io.aawireless.wirelessaamanipulator.helpers.messageprocessors.EnergyModelTranslator;
import io.aawireless.wirelessaamanipulator.helpers.simulators.AASimulator;
import io.aawireless.wirelessaamanipulator.helpers.simulators.CarSimulator;
import io.aawireless.wirelessaamanipulator.helpers.simulators.PhoneSimulator;
import io.aawireless.wirelessaamanipulator.helpers.interfaces.ServiceThreadPair;
import io.aawireless.wirelessaamanipulator.helpers.ssl.NioSSL;
import io.aawireless.wirelessaamanipulator.helpers.weather.OutsideTempService;
import io.aawireless.wirelessaamanipulator.proto.FuelData;
import io.aawireless.wirelessaamanipulator.proto.SensorBatch;
import io.aawireless.wirelessaamanipulator.proto.SensorMessageId;
import io.aawireless.wirelessaamanipulator.proto.SensorResponse;
import io.aawireless.wirelessaamanipulator.proto.SensorType;
import io.aawireless.wirelessaamanipulator.receivers.BluetoothReceiver;
import io.aawireless.wirelessaamanipulator.status.AppStatus;


public class MainTaskService extends Service implements ServiceThreadPair {
    private volatile Network wifiNetwork;
    private volatile Socket cartcpSocket;
    private volatile BluetoothDevice targetDevice;
    private volatile String targetAddress;
    private volatile CarHandshakeClient handshakeThread;

    public static boolean running = false;
    /** Guards against launching the car side + AA broadcast twice if the car re-sends
     *  WifiInfoResponse (id=3). Reset in onDestroy. */
    private volatile boolean carLaunched = false;
    private AASimulator car,phone;
    private EnergyModelTranslator t = new EnergyModelTranslator(this);

    // Police-HUD compass: poll Waze around the car and inject a drawing into the
    // instrument cluster. Both self-gate (Waze on a location fix, injector on the
    // car advertising an IMAGE cluster), so they're safe to start early.
    private volatile io.aawireless.wirelessaamanipulator.hud.waze.WazePoller wazePoller;
    private volatile io.aawireless.wirelessaamanipulator.hud.HudState hudState;
    private volatile io.aawireless.wirelessaamanipulator.hud.ClusterVideoSession clusterVideoSession;


    private static final String TAG="MainTaskService";
    private NioSSL phoneSSL,carSSL;
    /** Dummy-battery test scheduler (onTcpConnected). Held so onDestroy can shut it down - it is a
     *  non-daemon thread the old process-kill teardown used to reap. */
    private volatile ScheduledExecutorService batteryScheduler;

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private static volatile boolean crashFileLoggerInstalled = false;

    /**
     * Write any uncaught exception's stack trace to files/crash-&lt;time&gt;.txt before the
     * process dies. Logcat's small buffer loses our crashes on a drive; this survives.
     * Read with: adb shell run-as io.aawireless.wirelessaamanipulator cat files/crash-*.txt
     */
    private void installCrashFileLogger() {
        if (crashFileLoggerInstalled) return;
        crashFileLoggerInstalled = true;
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        final java.io.File dir = getFilesDir();
        Thread.setDefaultUncaughtExceptionHandler((thread, ex) -> {
            try {
                java.io.StringWriter sw = new java.io.StringWriter();
                ex.printStackTrace(new java.io.PrintWriter(sw));
                String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                        .format(new java.util.Date());
                try (java.io.FileWriter w = new java.io.FileWriter(new java.io.File(dir, "crash-" + stamp + ".txt"))) {
                    w.write("thread=" + thread.getName() + "\n" + sw);
                }
                Log.e(TAG, "UNCAUGHT on thread " + thread.getName(), ex);
            } catch (Throwable ignored) {}
            if (previous != null) previous.uncaughtException(thread, ex);
        });
    }

    @Override
    public void onCreate() {
        super.onCreate();
        installCrashFileLogger();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Plain started service (no startForeground): OpenZeekr's single foreground service
        // (ProximityService in :core) keeps the process alive. We are started via startService()
        // from the BluetoothReceiver (broadcast temp-allowlist) or the Android Auto tab (app in
        // the foreground), both of which permit a background service start.
        Log.d(TAG,"onStartCommand: "+intent);
        // START_NOT_STICKY: do NOT let the system re-deliver a null intent and auto-restart us in
        // the background. BluetoothReceiver restarts us on the next ACL_CONNECTED, and the Android
        // Auto tab can start us manually.
        if (intent == null) return START_NOT_STICKY;

        targetAddress = intent.getStringExtra(BluetoothReceiver.EXTRA_DEVICE_ADDRESS);


        String action = intent != null ? intent.getAction() : null;
        Log.d(TAG, "onStartCommand action=" + action);

        if ("io.aawireless.ACTION_STOP".equals(action)) {
            Log.d(TAG, "Stopping service...");
            running = false;              // your loop breaker
            stopSelf();
            return START_NOT_STICKY;
        }


        // The only legitimate start carries the car's address (from BluetoothReceiver, ACL path).
        if (targetAddress == null) {
            updateNotification("No target address");
            stopSelf();
            return START_NOT_STICKY;
        }


        // Android 12+ BT permission check
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                updateNotification("Missing BLUETOOTH_CONNECT permission");
                stopSelf();
                return START_NOT_STICKY;
            }
        }

        if (targetAddress != null) {
            io.aawireless.wirelessaamanipulator.storage.KnowDevicesStore.add(getApplicationContext(), targetAddress);
            // Android 12+ requires BLUETOOTH_CONNECT permission to call getRemoteDevice + connect APIs safely
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    // We can’t do much without it; keep service alive or stop.
                    Log.d(TAG,"We do not have permission to communicate with BT devices");
                    stopSelf();
                    return START_NOT_STICKY;
                }
            }

            BluetoothManager bluetoothManager =
                    getSystemService(BluetoothManager.class);

            BluetoothAdapter adapter =
                    bluetoothManager != null ? bluetoothManager.getAdapter() : null;

            if (adapter != null) {
                try {
                    targetDevice = adapter.getRemoteDevice(targetAddress);
                    Log.d(TAG,"Found device: "+targetDevice.getName());
                    handshakeThread=CarHandshakeClientManager.startOrSwitch(targetDevice, this, new HandshakeCallbacks() {

                        @Override
                        public void onWifiCredentials(String ssid, String key) {
                            Log.d(TAG, "WiFi creds: " + ssid);
                            updateNotification("WiFi creds received: " + ssid+", pass: "+key);
                            running=true;
                            updateLocation();

                            // Launch the car side + tell gearhead to connect EXACTLY once.
                            // The car may re-send WifiInfoResponse (id=3); a second
                            // CarSimulator would fight the first for port 6288.
                            if (carLaunched) {
                                Log.d(TAG, "Car side already launched - ignoring duplicate creds");
                                return;
                            }
                            carLaunched = true;
                            if (!startCarSide()) carLaunched = false; // bind failed -> allow a retry on the next creds
                        }

                        @Override
                        public void onWifiConnected(Network network) {
                            wifiNetwork = network;
                            Log.d(TAG, "WiFi connected. network=" + network);
                            updateNotification("WiFi connected");


                        }

                        @Override
                        public void onCarWifiLost() {
                            // The car AP dropped: proactively full-close the gearhead (5277) link so
                            // its stuck pump hits EOF and its accept-loop frees (the FIN/RST cascades
                            // through gearhead's shared socketpair fd). Capture the field locally to
                            // avoid a race with teardown; closeGearheadLink() is idempotent + safe.
                            AASimulator c = car;
                            if (c instanceof CarSimulator) {
                                Log.w(TAG, "Car WiFi lost -> proactively closing the gearhead 5277 link");
                                ((CarSimulator) c).closeGearheadLink();
                            }
                        }

                        @Override
                        public void onDeviceEndpoint(String ip, int port) {
                            Log.d(TAG, "Device endpoint: " + ip + ":" + port);
                            updateNotification("Device endpoint: " + ip + ":" + port);


                        }



                        @Override
                        public void onBootstrapComplete() {
                            Log.d(TAG, "Bootstrap complete!");
                            updateNotification("Connected (bootstrap complete)");
                            // Start your real comms here, using tcpSocket + wifiNetwork
                            // startProjection(tcpSocket, wifiNetwork);
                        }

                        @Override
                        public void onError(Throwable t) {
                            Log.e(TAG, "Handshake error", t);
                            updateNotification("Error: " + t.getMessage());
                            stopSelf();
                        }
                    });


                } catch (IllegalArgumentException e) {
                    stopSelf();
                    return START_NOT_STICKY;
                }
            }
        }

        if (targetDevice != null) {
            // Update notification
            String name = targetDevice.getName();
            updateNotification("Connected: " + (name != null ? name : targetAddress));

            // Start your communication logic here:
            // startCommunication(targetDevice);
        }

        return START_NOT_STICKY;
    }


    /** Set once we're tearing everything down ourselves (no peer-triggered ByeBye then). */
    private volatile boolean shuttingDown = false;

    /** How long to wait for the peer's ByeByeResponse before closing anyway. */
    private static final long BYEBYE_WAIT_MS = 1000;

    // ---- Passive, car-driven projection re-start (EXPERIMENTAL) ----
    // A phone-initiated projection rebuild does NOT work: the car drives the Android Auto session,
    // so it ignores a session we re-open that it did not ask for. Instead, on a projection drop we
    // tear down ONLY the projection layer (HUD + both simulators + projection TCP) and stay ALIVE
    // with the CarHandshakeClient + RFCOMM open, idle, waiting for the car. The car re-starts a
    // session either by re-sending the handshake frames over the still-open RFCOMM (id=3/id=1 ->
    // the existing handlers -> onTcpConnected rebuild) or by re-connecting A2DP / its ACL (-> the
    // BluetoothReceiver -> a fresh startOrSwitch, exactly like a cold start). If the BT ACL is
    // genuinely gone, we fall back to the full teardown + stopSelf.
    /** True while the projection layer is torn down and we are idle waiting for the car. Guards the
     *  projection-only teardown so the second simulator's link-end does not tear down twice. */
    private final java.util.concurrent.atomic.AtomicBoolean projectionDown =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    @Override
    public void onSimulatorEnded(AASimulator which) {
        if (shuttingDown) return;
        Log.w(TAG, (which == car ? "Android Auto (head unit server)" : "car") + " link ended");

        // If the car's classic Bluetooth is genuinely gone, there is nothing to wait for: fall back
        // to the full teardown + stopSelf (the BluetoothReceiver restarts us on a real reconnect).
        if (!isTargetAclConnected()) {
            Log.w(TAG, "car Bluetooth (ACL) gone -> full teardown of the projection session");
            stopHud();
            stopSelf();
            return;
        }

        // Still on the car's Bluetooth: tear down ONLY the projection layer and stay ALIVE, idle,
        // with the CarHandshakeClient + RFCOMM open. We do NOT rebuild projection ourselves (the car
        // drives the session and ignores a phone-initiated rebuild). We wait for the car to re-drive
        // a session: either over the still-open RFCOMM (id=3 / id=1 -> handlers -> onTcpConnected) or
        // via an A2DP / ACL reconnect (-> BluetoothReceiver -> startOrSwitch, like a cold start).
        //
        // Both simulators drop together, so guard against a double teardown: the first link-end does
        // the teardown, the second (triggered when we stop the surviving simulator) returns here.
        if (!projectionDown.compareAndSet(false, true)) {
            Log.d(TAG, "projection already torn down -> ignoring duplicate link end");
            return;
        }
        Log.w(TAG, "-> tearing down projection layer only; staying alive with RFCOMM open, "
                + "waiting for the car to re-start Android Auto");
        AppStatus.setConnectionStage("projection dropped - waiting for the car to re-start Android Auto");
        teardownProjectionLayer();
    }

    /** @return true if the car (targetDevice) is still ACL-connected over classic Bluetooth. */
    private boolean isTargetAclConnected() {
        return targetAddress != null && BluetoothReceiver.isAclConnected(targetAddress);
    }

    /**
     * Tear down ONLY the projection layer: HUD / cluster video (keeping ClusterVideoEncoder.stop()'s
     * join-before-release ordering, via stopHud), both simulators (bounded ByeBye then stopself,
     * which closes the projection TCP socket they own), and the two TLS engines. Resets the per-
     * session guards (carLaunched) and the handshake client's projectionActive so a car-driven
     * re-handshake over the still-open RFCOMM rebuilds cleanly. Does NOT shutdown the handshake
     * client, close RFCOMM, release WiFi, or stopSelf - the service stays alive and idle.
     */
    private void teardownProjectionLayer() {
        stopHud();

        AASimulator oldCar = car, oldPhone = phone;
        car = null; phone = null;

        // ByeBye both links in parallel (bounded). Safe when called from a simulator thread: we join
        // the ByeBye HELPER threads, never a simulator's own thread, so there is no self-join.
        java.util.List<Thread> byes = new java.util.ArrayList<>();
        for (final AASimulator sim : new AASimulator[]{oldCar, oldPhone}) {
            if (sim == null) continue;
            Thread t = new Thread(() -> sim.byeByeAndClose(
                    io.aawireless.wirelessaamanipulator.proto.ByeByeReason.USER_SELECTION, BYEBYE_WAIT_MS),
                    "aa-byebye-" + sim.getName());
            t.start();
            byes.add(t);
        }
        long deadline = System.currentTimeMillis() + BYEBYE_WAIT_MS + 1000;
        for (Thread t : byes) {
            try { t.join(Math.max(1, deadline - System.currentTimeMillis())); }
            catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
        // Break the read loops for good (closes the sockets, incl. the projection TCP socket the
        // PhoneSimulator owns). Stopping the surviving simulator triggers its own onSimulatorEnded,
        // which the projectionDown guard swallows.
        for (AASimulator sim : new AASimulator[]{oldCar, oldPhone}) {
            if (sim != null) { try { sim.stopself(); } catch (Exception ignored) {} }
        }
        // Drop the old TLS engines so a fresh session installs new ones.
        for (NioSSL ssl : new NioSSL[]{carSSL, phoneSSL}) {
            if (ssl != null) { try { ssl.close(); } catch (Exception ignored) {} }
        }
        carSSL = null; phoneSSL = null;

        // Allow the car side to be rebuilt by a car-driven re-handshake (id=3 -> onWifiCredentials
        // -> startCarSide), and let a re-sent WifiStartRequest (id=1) open a fresh projection socket.
        carLaunched = false;
        CarHandshakeClient hs = handshakeThread;
        if (hs != null) hs.onProjectionDropped();

        Log.d(TAG, "Projection layer torn down; RFCOMM / handshake client kept alive and idle");
    }

    @Override
    public void onDestroy() {
        Log.d(TAG,"Service OnDestroy got called");
        shuttingDown = true;
        carLaunched=false;
        // Reset the idle-waiting guard so the next session starts clean.
        projectionDown.set(false);

        // Stop the test schedulers first: both are non-daemon / linger-against-a-dead-service
        // executors that the old process-kill teardown used to reap. OutsideTempService.start()
        // re-arms its own executor on the next session (it resets its STARTED/STOPPED guards).
        ScheduledExecutorService sched = batteryScheduler;
        batteryScheduler = null;
        if (sched != null) sched.shutdownNow();
        try { OutsideTempService.stop(); } catch (Exception ignored) {}

        stopHud();   // stop cluster video + Waze poller + HUD state so nothing is sent during the ByeBye

        // Say ByeBye on BOTH links (in parallel, bounded) BEFORE dropping `running`: the read loops
        // must still be running to receive the ByeByeResponse. byeByeAndClose() also closes each
        // link's stream/socket, shuts down its per-link writer executor, and interrupts the
        // simulator thread, so the 6288/6299 ServerSockets are closed by the read loops' own
        // finally blocks once they exit.
        java.util.List<Thread> byes = new java.util.ArrayList<>();
        for (final AASimulator sim : new AASimulator[]{car, phone}) {
            if (sim == null) continue;
            Thread t = new Thread(() -> sim.byeByeAndClose(
                    io.aawireless.wirelessaamanipulator.proto.ByeByeReason.USER_SELECTION, BYEBYE_WAIT_MS),
                    "aa-byebye-" + sim.getName());
            t.start();
            byes.add(t);
        }
        long deadline = System.currentTimeMillis() + BYEBYE_WAIT_MS + 1500;
        for (Thread t : byes) {
            try { t.join(Math.max(1, deadline - System.currentTimeMillis())); }
            catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
        running=false;

        // Break the simulator read loops for good (running=false + an explicit interrupt) and drop
        // the references so the simulators and their buffers can be collected.
        for (AASimulator sim : new AASimulator[]{car, phone}) {
            if (sim != null) { try { sim.stopself(); } catch (Exception ignored) {} }
        }

        // Close the two TLS engines (releases each SSLEngine's native context promptly; the
        // underlying sockets were already closed by byeByeAndClose above).
        for (NioSSL ssl : new NioSSL[]{carSSL, phoneSSL}) {
            if (ssl != null) { try { ssl.close(); } catch (Exception ignored) {} }
        }
        carSSL = null; phoneSSL = null;
        car = null; phone = null;

        // Full teardown (this runs for a real BT disconnect via the ACL-gone branch in
        // onSimulatorEnded -> stopSelf, and for any other stop). shutdown() -> requestStop() ->
        // releaseWifi(), which disconnects the WifiConnector and NULLS the cached wifiNetwork. That
        // is deliberate: the car's Wi-Fi Network must be dropped on a genuine BT disconnect so the
        // NEXT session (a fresh CarHandshakeClient from startOrSwitch) requests a brand-new Network
        // object rather than reusing a stale one (the stale-network theory for wedged DHU / EPERM
        // bind). The keep-RFCOMM projection-drop path does NOT reach here, so it keeps the Wi-Fi.
        try {
            if (handshakeThread != null) handshakeThread.shutdown();
        }
        catch (Exception e){}
        handshakeThread = null;

        super.onDestroy();
        // NO Process.killProcess / System.exit: MainTaskService shares the app process with
        // ProximityService (the digital-key / proximity keep-alive foreground service). Killing the
        // process would drop the live DK BLE session and force a reconnect. Everything above releases
        // the AA stack (schedulers, HUD, both links' sockets + writer executors + TLS engines, the
        // handshake client) so the co-hosted service keeps running untouched. The crash-file logger
        // is an uncaught-exception handler that writes synchronously when it fires, so there is
        // nothing buffered to flush on a normal shutdown.
    }



    /**
     * Report handshake / connection progress. With no foreground notification of our own, this
     * just posts to the in-app status bus; the Android Auto tab (AppStatus listener) surfaces it
     * as the "Connection" line. Safe to call from callbacks on any thread.
     */
    private void updateNotification(String text) {
        io.aawireless.wirelessaamanipulator.status.AppStatus.setConnectionStage(text);
    }

    private void updateLocation() throws SecurityException{
        FusedLocationProviderClient fused =
                LocationServices.getFusedLocationProviderClient(this);

        fused.getLastLocation()
                .addOnSuccessListener(location -> {
                    Log.d(TAG,"Last know loccation: "+location);
                    if (location != null) {
                        double lat = location.getLatitude();
                        double lon = location.getLongitude();


                                try {
                                    Log.d(TAG,"Trying to get weather data");
                                    OutsideTempService.start(MainTaskService.this,lat,lon);
                                } catch (Exception e) {
                                    // Weather is best-effort; never crash the app for it.
                                    Log.w(TAG, "Failed to get weather data", e);
                                }

                    } else {
                        // No cached location available
                    }
                })
                .addOnFailureListener(e -> {
                    // Handle error
                    e.printStackTrace();
                });
    }

    @Override
    public void pushToPhone(AAMessage data) {
        // Lock the car TLS engine so wrap-order == send-order (TLS records are
        // sequence-numbered); the cluster video sender takes the same lock.
        AASimulator dst = phone;
        if (dst == null) return;   // mid reconnect-rebuild: old link gone, new one not up yet
        NioSSL ssl = carSSL;
        if (ssl == null) { dst.send(data.getFormated(null)); return; }
        synchronized (ssl) {
            byte[] temp = data.getFormated(ssl);
            dst.send(temp);
        }
    }

    @Override
    public void sendToCarFragmented(int channel, int messageId, byte[] payload, boolean control) {
        NioSSL ssl = carSSL;
        AASimulator dst = phone;
        if (ssl == null || dst == null) {
            Log.w(TAG, "sendToCarFragmented: car link not ready (ssl=" + ssl + ")");
            return;
        }
        try {
            synchronized (ssl) {
                java.util.List<byte[]> frames =
                        io.aawireless.wirelessaamanipulator.hud.AAFraming.buildFrames(
                                ssl, channel, messageId, payload, control);
                for (byte[] f : frames) dst.send(f);
            }
        } catch (Exception e) {
            Log.w(TAG, "sendToCarFragmented failed on channel " + channel + " msg " + messageId, e);
        }
    }

    @Override
    public void pushToCar(AAMessage data) {
            AASimulator dst = car;
            if (dst == null) return;   // mid reconnect-rebuild: old link gone, new one not up yet
            byte[] temp=data.getFormated(phoneSSL);
            dst.send(temp);
    }

    private int counter=0;
    private float temp=20f;
    @Override
    public void onTcpConnected(Socket socket) {
        // New car session: forget the previous car's cluster/sensor ids before anything
        // (cluster supervisor, translator) can act on them. Re-learned from this car's SDR.
        io.aawireless.wirelessaamanipulator.hud.ClusterInfo.reset();
        EnergyModelTranslator.sensorChannel = null;

        // A fresh projection socket is up -> we are no longer idle-waiting.
        projectionDown.set(false);

        // If the car side is currently torn down (a car-driven re-start where the re-handshake did
        // NOT come through onWifiCredentials, e.g. the car re-sent only WifiStartRequest / id=1),
        // rebuild it here so we have a whole fresh linked pair. On a normal cold start the car side
        // was already built in the creds callback (id=3), so car != null and we only add the phone.
        if (car == null) {
            Log.d(TAG, "onTcpConnected with no car side -> rebuilding a fresh car+phone simulator pair");
            this.t = new EnergyModelTranslator(this); // fresh shared link object for the new pair
            startCarSide();                           // fresh CarSimulator (reconnects gearhead 5277 / binds 6288)
        }

        PhoneSimulator phoneSim = new PhoneSimulator(MainTaskService.this, socket, t);
        phone = phoneSim;
        phoneSim.start(); // AASimulator no longer self-starts; start AFTER inoutstream is set
        startHud();
        //Temporary run a task every 20 seocnd.
        Log.d(TAG,"Dummy battery state is: "+getContext().getSharedPreferences("AA_SETTINGS", MODE_PRIVATE).getBoolean("dummy_battery",false));
        if (getContext().getSharedPreferences("AA_SETTINGS", MODE_PRIVATE).getBoolean("dummy_battery",false)) {
            // Replace any scheduler from a previous car session before starting a new one, and keep
            // the handle so onDestroy can shut it down (these are non-daemon threads).
            ScheduledExecutorService previous = batteryScheduler;
            if (previous != null) previous.shutdownNow();
            ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
            batteryScheduler = scheduler;
            OutsideTempService.setTemp(getSharedPreferences("AA_SETTINGS", MODE_PRIVATE).getFloat("VALUE_1", 20f));
            scheduler.scheduleWithFixedDelay(() -> {
                Log.d("MainTaskService", "Running every 20 seconds");
                try {

                    OutsideTempService.setTemp(getSharedPreferences("AA_SETTINGS", MODE_PRIVATE).getFloat("VALUE_1", 20f));
                    CarSimulator thecar = (CarSimulator) car;
                    thecar.fakeBattery((int) getSharedPreferences("AA_SETTINGS", MODE_PRIVATE).getFloat("VALUE_2", 100));

                } catch (Exception e) {
                    Log.w(TAG, "Error sending dummy message" + e.getMessage());
                }

            }, 3, 5, TimeUnit.SECONDS);
        }

    }

    /**
     * Build + start the CarSimulator (the gearhead-facing half) and, in legacy 6288 mode,
     * broadcast the AA start + nudge gearhead to connect. Shared by the first-session creds
     * path and the projection reconnect rebuild. Sets the {@link #car} field.
     *
     * @return true if the car side came up / is coming up (head-unit-server mode, or 6288
     *         bound and broadcast); false only if the 6288 bind failed.
     */
    private boolean startCarSide() {
        CarSimulator carSim = new CarSimulator(MainTaskService.this, t);
        car = carSim;
        carSim.start(); // AASimulator no longer self-starts

        if (carSim.isHeadUnitServerMode()) {
            // AA > 17.3: the START broadcast is dead. CarSimulator connects OUT to gearhead's
            // DHU head-unit server (127.0.0.1:5277), which the user starts from Android Auto ->
            // Developer settings. Nothing to broadcast or nudge here.
            Log.d(TAG, "AA >17.3 -> head unit server (5277) mode; not broadcasting");
            updateNotification("Start the head unit server in Android Auto (Developer settings)");
            return true;
        } else if (carSim.awaitBound(3000)) {
            // CRITICAL: only broadcast to gearhead once port 6288 is bound. Otherwise gearhead's
            // connect() races our bind()/accept() and is refused, so projection never fires up.
            Log.d(TAG, "CarSimulator bound on 6288 -> broadcasting AA start");
            AAStarter.startself(MainTaskService.this);
            nudgeGearheadUntilConnected(carSim);
            return true;
        } else {
            Log.e(TAG, "CarSimulator failed to bind 6288 in time - not broadcasting");
            updateNotification("Failed to open projection port 6288");
            return false;
        }
    }

    /**
     * gearhead sometimes ignores/misses a single WirelessStartupReceiver broadcast,
     * so projection "doesn't fire up". Re-send it a few times until the CarSimulator
     * actually accepts gearhead's connection on 6288 (or we give up). Runs off the
     * handshake reader thread.
     */
    private void nudgeGearheadUntilConnected(final CarSimulator carSim) {
        Thread t = new Thread(() -> {
            for (int attempt = 1; attempt <= 4 && running; attempt++) {
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (carSim.hasAccepted()) {
                    Log.d(TAG, "gearhead connected to 6288 - stop nudging");
                    return;
                }
                Log.w(TAG, "gearhead not connected to 6288 yet - re-broadcasting AA start (retry " + attempt + ")");
                AAStarter.startself(MainTaskService.this);
            }
            if (!carSim.hasAccepted()) {
                Log.e(TAG, "gearhead never connected to 6288 after retries - "
                        + "is native Wireless Android Auto ON, or is AA busy in another session?");
                updateNotification("Android Auto didn't start - check it isn't already projecting / native wireless is off");
            }
        }, "aa-gearhead-nudge");
        t.setDaemon(true);
        t.start();
    }

    /** Start the Waze poller + cluster injector once (idempotent). */
    private synchronized void startHud() {
        android.content.SharedPreferences prefs = getSharedPreferences("AA_SETTINGS", MODE_PRIVATE);

        // "Replace maps with traffic info" - only hijack the cluster video when enabled.
        boolean replaceMaps = prefs.getBoolean("replace_maps", true);
        io.aawireless.wirelessaamanipulator.hud.HudConfig.ownCluster = replaceMaps;
        if (!replaceMaps) {
            Log.d(TAG, "Replace-maps disabled - cluster HUD off (native maps left intact)");
            return;
        }

        // Waze API key comes from settings; fall back to the build-time key if present.
        String key = prefs.getString("waze_api_key", "");
        if (key == null || key.trim().isEmpty()) {
            key = io.aawireless.wirelessaamanipulator.BuildConfig.WAZE_API_KEY;
        }
        // Fixed speed cameras (GatsoUpdater / OSM feeds) do NOT need a Waze key, so keep the cluster
        // HUD running in cameras-only mode when no Waze key is configured - only the live police layer
        // needs Waze. (Previously we returned early here, which also left GATSO cameras unrendered.)
        String trimmedKey = (key != null) ? key.trim() : "";
        if (!trimmedKey.isEmpty()) {
            long pollMs = prefs.getInt("waze_interval_sec", 300) * 1000L;
            if (wazePoller == null) {
                wazePoller = new io.aawireless.wirelessaamanipulator.hud.waze.WazePoller(this, trimmedKey, pollMs);
                wazePoller.start();
            }
        } else {
            Log.i(TAG, "No Waze API key set - starting cluster HUD in speed-cameras-only mode");
        }
        if (hudState == null) {
            hudState = new io.aawireless.wirelessaamanipulator.hud.HudState(this, wazePoller);
            hudState.start();
        }
        if (clusterVideoSession == null) {
            clusterVideoSession = new io.aawireless.wirelessaamanipulator.hud.ClusterVideoSession(this, hudState);
            // The GATSO network update runs ONLY once video is streaming, off the video
            // path (over cellular), so it can never interfere with projection bring-up.
            clusterVideoSession.setOnStreaming(() ->
                    io.aawireless.wirelessaamanipulator.hud.GatsoUpdater.update(getApplicationContext()));
            clusterVideoSession.start();
        }
        // Cached cameras load immediately (fast, local file - no network).
        Thread gatsoCache = new Thread(() ->
                io.aawireless.wirelessaamanipulator.hud.GatsoUpdater.loadCacheIfPresent(getApplicationContext()),
                "gatso-cache");
        gatsoCache.setPriority(Thread.MIN_PRIORITY);
        gatsoCache.setDaemon(true);
        gatsoCache.start();
        Log.d(TAG, "Police HUD started");
    }

    private synchronized void stopHud() {
        try { if (clusterVideoSession != null) clusterVideoSession.stop(); } catch (Exception ignored) {}
        try { if (hudState != null) hudState.stop(); } catch (Exception ignored) {}
        try { if (wazePoller != null) wazePoller.stop(); } catch (Exception ignored) {}
        clusterVideoSession = null;
        hudState = null;
        wazePoller = null;
    }

    @Override
    public boolean onCarClusterMessage(AAMessage msg) {
        io.aawireless.wirelessaamanipulator.hud.ClusterVideoSession s = clusterVideoSession;
        if (s == null) return false;

        // Observe EVERY car->phone message so the session can watch gearhead's channel-open
        // handshakes and time its own open (does not consume - must still reach gearhead).
        s.observeCarMessage(msg);

        int ch = msg.getChannel() & 0xFF;
        int sink = io.aawireless.wirelessaamanipulator.hud.ClusterInfo.getVideoClusterServiceId();
        int input = io.aawireless.wirelessaamanipulator.hud.ClusterInfo.getVideoClusterInputServiceId();
        // Consume ONLY our own cluster channels (sink + input) - gearhead doesn't know them.
        if ((sink >= 0 && ch == (sink & 0xFF)) || (input >= 0 && ch == (input & 0xFF))) {
            return s.onCarMessage(msg);
        }
        return false;
    }

    @Override
    public Context getContext() {
        return this;
    }

    @Override
    public Network getCarNetwork() {
        CarHandshakeClient hs = handshakeThread;
        return hs != null ? hs.getWifiNetwork() : null;
    }

    @Override
    public String getOwnCarNetworkIp() {
        CarHandshakeClient hs = handshakeThread;
        return hs != null ? hs.getOwnAddressOnCarNetwork() : null;
    }

    @Override
    public boolean isCarready() {
        if (car == null)
            return false;
        else
            return car.isReady();
    }


    @Override
    public boolean isPhoneready() {
        if (phone == null)
            return false;
        else
            return phone.isReady();
    }

    @Override
    public void handshakeready() {
        try {
            handshakeThread.sendWiFiStartCompleted();
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    @Override
    public void setcarSSL(NioSSL ssl) {
        this.carSSL=ssl;
    }

    @Override
    public void setphoneSSL(NioSSL ssl) {
        this.phoneSSL=ssl;
    }

}
