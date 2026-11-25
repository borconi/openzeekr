package io.aawireless.wirelessaamanipulator.receivers;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.ParcelUuid;
import android.util.Log;

import androidx.annotation.RequiresPermission;

import io.aawireless.wirelessaamanipulator.services.MainTaskService;
import io.aawireless.wirelessaamanipulator.storage.SelectionStore;

/**
 * The ONLY entry point that starts the Android Auto projection: a real classic-Bluetooth ACL
 * connect/disconnect. Nothing else starts it - no A2DP signal, no cached-UUID broadcast without a
 * live connection, no manual start. On ACL_CONNECTED we start ONLY if the device is the car we want
 * AND it advertises the Android Auto BT profile UUID; a live ACL means we are genuinely at the car.
 * ACTION_UUID only COMPLETES that UUID check for a device whose ACL is still up - it is not an
 * independent trigger, so a stale cached-UUID broadcast out of range can never start us.
 */
public class BluetoothReceiver extends BroadcastReceiver {
    public static final String EXTRA_DEVICE_ADDRESS = "extra_device_address";
    public static final String EXTRA_DEVICE_NAME = "extra_device_name";

    private static final String TAG = "BTReceiver";

    // Android Auto / OpenAuto / AAWireless commonly-used AA BT UUID
    private static final ParcelUuid AA_BT_UUID =
            ParcelUuid.fromString("4de17a00-52cb-11e6-bdf4-0800200c9a66");
    public static BluetoothDevice ourDevice = null;

    /**
     * Addresses whose classic Bluetooth ACL is currently up. Set on ACTION_ACL_CONNECTED,
     * cleared on ACTION_ACL_DISCONNECTED. The projection auto-reconnect in MainTaskService
     * only fires while the car's ACL is still here: the owner's rule is "if we are still
     * connected to the car's Bluetooth". A projection drop normally leaves the ACL up (no
     * ACTION_ACL_DISCONNECTED), so this stays set and the reconnect is allowed; a real BT
     * drop clears it and the reconnect is refused (let a genuine fresh connect restart us).
     */
    private static final java.util.Set<String> ACL_CONNECTED_ADDRS =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** @return true if the given device address currently has its classic BT ACL up. */
    public static boolean isAclConnected(String address) {
        return address != null && ACL_CONNECTED_ADDRS.contains(address);
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    @Override
    public void onReceive(Context context, Intent intent) {
        // The wireless-AA bootstrap, the connectedDevice foreground service, and the typed
        // getParcelableExtra(..., Class) call below all require Android 13+ (TIRAMISU). On an
        // older phone that typed overload does not exist and would throw, so this receiver must
        // no-op entirely - the AA feature is gated to 13+ app-wide. (ACL_CONNECTED fires for every
        // Bluetooth device, so without this guard an old phone would crash on any BT connection.)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;

        // Master on/off gate: the Android Auto Enhancement feature is opt-in (default off). The
        // host app stores it in the same AA_SETTINGS prefs store under "aa_enabled". When off, do
        // nothing at all - no projection service is ever started.
        if (!context.getSharedPreferences("AA_SETTINGS", Context.MODE_PRIVATE)
                .getBoolean("aa_enabled", false)) {
            return;
        }

        String action = intent.getAction();
        if (action == null) return;

        BluetoothDevice device = intent.getParcelableExtra(
                BluetoothDevice.EXTRA_DEVICE,
                BluetoothDevice.class
        );
        if (device == null) return;

        if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)) {
            // THE ONLY start path. A live classic ACL means we are physically at the car.
            ACL_CONNECTED_ADDRS.add(device.getAddress());
            Log.d(TAG, "ACL_CONNECTED: " + device.getName() + " / " + device.getAddress());
            maybeStart(context, device);
            return;
        }

        if (BluetoothDevice.ACTION_UUID.equals(action)) {
            // NOT an independent trigger: this only completes the UUID check for a device whose ACL
            // is STILL up (we fetched its SDP from the ACL path). If the ACL is not up, this is a
            // stale/cached UUID broadcast (e.g. another app woke the adapter while the car is out of
            // range) and must never start us.
            if (!isAclConnected(device.getAddress())) {
                Log.d(TAG, "ACTION_UUID ignored - no live ACL (not at the car): " + device.getAddress());
                return;
            }
            maybeStart(context, device);
            return;
        }

        if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action)) {
            Log.d(TAG, "ACL_DISCONNECTED: " + device.getName() + " / " + device.getAddress());
            ACL_CONNECTED_ADDRS.remove(device.getAddress());
            if (ourDevice != null && device.getAddress().equals(ourDevice.getAddress())) {
                Log.d(TAG, "It was our device, stopping service");
                Intent stop = new Intent(context, MainTaskService.class);
                stop.setAction("io.aawireless.ACTION_STOP");
                context.stopService(stop);
            }
        }
    }

    /**
     * Start MainTaskService only if this just-connected device is the car we want AND it advertises
     * the Android Auto BT profile UUID. Called only from the live-ACL paths, so "we are at the car"
     * is already guaranteed. If the AA UUID is not cached yet, fetch it via SDP; the ACTION_UUID
     * result re-enters here (still ACL-gated), so we never start without a confirmed AA profile.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private void maybeStart(Context context, BluetoothDevice device) {
        // Identity: in SPECIFIC mode fire ONLY for the pinned device; ANY mode accepts any AA device.
        if (!SelectionStore.isAnyMode(context) && !SelectionStore.matches(context, device.getAddress())) {
            Log.d(TAG, "Ignoring " + device.getAddress()
                    + " (specific mode, selected=" + SelectionStore.getSelectedAddress(context) + ")");
            return;
        }
        // Must advertise the Android Auto BT profile UUID.
        if (hasAndroidAutoProfile(device)) {
            Log.d(TAG, "AA device at the car -> starting: " + device.getName() + " / " + device.getAddress());
            startMainService(context, device);
            ourDevice = device;
            return;
        }
        // UUID not cached yet: fetch it. ACTION_UUID re-enters maybeStart (still ACL-gated).
        boolean requested = device.fetchUuidsWithSdp();
        Log.d(TAG, "No AA UUID yet for " + device.getAddress() + " - fetchUuidsWithSdp=" + requested);
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private static boolean hasAndroidAutoProfile(BluetoothDevice device) {
        ParcelUuid[] uuids = device.getUuids();
        if (uuids == null) return false;

        for (ParcelUuid u : uuids) {
            if (AA_BT_UUID.equals(u)) return true;
        }
        return false;
    }

    @SuppressLint("MissingPermission")
    private static void startMainService(Context context, BluetoothDevice device) {
        Intent serviceIntent = new Intent(context, MainTaskService.class);
        serviceIntent.putExtra(EXTRA_DEVICE_ADDRESS, device.getAddress());
        serviceIntent.putExtra(EXTRA_DEVICE_NAME, device.getName());
        // Plain started service, not a foreground service: OpenZeekr's single FGS
        // (ProximityService in :core) owns the foreground. Delivering this broadcast puts the
        // app on the temporary background-start allowlist, so startService() is permitted here.
        context.startService(serviceIntent);
        Log.d(TAG, "MainTaskService started for AA device: " + device.getName() + " / " + device.getAddress());
    }
}
