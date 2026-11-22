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
 * Starts MainTaskService ONLY for BT devices that advertise the Android Auto BT SDP UUID.
 *
 * ACTION_ACL_CONNECTED fires for any BT connection (headset, watch, etc.).
 * We filter based on UUIDs (SDP) so only AA-capable devices trigger the service.
 */
public class BluetoothReceiver extends BroadcastReceiver {
    public static final String EXTRA_DEVICE_ADDRESS = "extra_device_address";
    public static final String EXTRA_DEVICE_NAME = "extra_device_name";

    private static final String TAG = "BTReceiver";

    // Android Auto / OpenAuto / AAWireless commonly-used AA BT UUID
    private static final ParcelUuid AA_BT_UUID =
            ParcelUuid.fromString("4de17a00-52cb-11e6-bdf4-0800200c9a66");
    public static BluetoothDevice ourDevice = null;

    /** BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED (hard-coded so no extra import is needed). */
    public static final String A2DP_CONNECTION_STATE_CHANGED =
            "android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED";

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
            Log.d(TAG, "ACL_CONNECTED: " + device.getName() + " / " + device.getAddress());
            // Track the ACL so the projection re-start can tell "still on the car's BT" from a
            // genuine BT drop.
            ACL_CONNECTED_ADDRS.add(device.getAddress());
            handleDeviceConnected(context, device);
            return;
        }

        // A2DP (audio) re-connecting is, per the owner, how the car signals a fresh Android Auto
        // session when the classic ACL stayed up through a projection drop (so no ACL_CONNECTED
        // fires). Treat an A2DP STATE_CONNECTED for our device exactly like a fresh connect: the
        // same start path a cold start uses (-> startOrSwitch). NOTE: this profile broadcast is not
        // manifest-exempt from the implicit-broadcast ban on API 26+, so MainTaskService also
        // registers this receiver dynamically while alive to catch it reliably.
        if (A2DP_CONNECTION_STATE_CHANGED.equals(action)) {
            int state = intent.getIntExtra(android.bluetooth.BluetoothProfile.EXTRA_STATE,
                    android.bluetooth.BluetoothProfile.STATE_DISCONNECTED);
            Log.d(TAG, "A2DP state change: " + device.getName() + " / " + device.getAddress()
                    + " state=" + state);
            if (state == android.bluetooth.BluetoothProfile.STATE_CONNECTED) {
                ACL_CONNECTED_ADDRS.add(device.getAddress());
                Log.d(TAG, "A2DP connected -> treating as a fresh session signal");
                handleDeviceConnected(context, device);
            }
            return;
        }

        if (BluetoothDevice.ACTION_UUID.equals(action)) {
            // In specific mode the ACL branch already decided; only relevant in ANY mode.
            if (!SelectionStore.isAnyMode(context)) {
                if (SelectionStore.matches(context, device.getAddress())) {
                    startMainService(context, device);
                    ourDevice = device;
                }
                return;
            }
            if (hasAndroidAutoProfile(device)) {
                Log.d(TAG, "ACTION_UUID matched AA device: " + device.getName() + " / " + device.getAddress());
                startMainService(context, device);
                ourDevice=device;
            } else {
                Log.d(TAG, "ACTION_UUID ignored (no AA UUID): " + device.getName() + " / " + device.getAddress());
            }
            return;
        }

        if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action)) {
            Log.d(TAG, "ACL_DISCONNECTED: " + device.getName() + " / " + device.getAddress() );
            // The car's BT is really gone now: drop it from the ACL set so the projection
            // auto-reconnect refuses (and the full teardown below runs).
            ACL_CONNECTED_ADDRS.remove(device.getAddress());

            if (ourDevice != null && device.getAddress().equals(ourDevice.getAddress()))
            {
                Log.d(TAG,"It was our devices, stopping service");
                Intent stop = new Intent(context, MainTaskService.class);
                stop.setAction("io.aawireless.ACTION_STOP");
                context.stopService(stop);
            }
            else
            {
                if (ourDevice == null)
                    Log.d(TAG,"ourDevice is null, not stopping service");
                else
                    Log.d(TAG,"address didn't match");
            }

        }
    }

    /**
     * Decide whether to (re)start MainTaskService for a device that just connected (classic ACL or
     * A2DP). Shared by the ACL_CONNECTED and A2DP-connected paths. startMainService() funnels into
     * onStartCommand -> CarHandshakeClientManager.startOrSwitch, which reuses a live handshake
     * client for the same device or spins up a fresh one - so a car-driven re-start lands here the
     * same way a cold start does.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private void handleDeviceConnected(Context context, BluetoothDevice device) {
        // SPECIFIC mode: a device is pinned in the UI. Fire ONLY for that exact device, and don't
        // bother with UUID/SDP checks - the user chose it.
        if (!SelectionStore.isAnyMode(context)) {
            if (SelectionStore.matches(context, device.getAddress())) {
                Log.d(TAG, "Selected device connected -> starting: " + device.getAddress());
                startMainService(context, device);
                ourDevice = device;
            } else {
                Log.d(TAG, "Ignoring " + device.getAddress()
                        + " (specific mode, selected=" + SelectionStore.getSelectedAddress(context) + ")");
            }
            return;
        }

        // ANY mode (default): fire for any device that advertises the Android Auto BT profile.
        // We do NOT gate on the digital key being nearby: by the time AA's Bluetooth connects you
        // are already seated in the car, PAST the approach window, so the key is no longer in its
        // "near" proximity state and that check wrongly skipped the real car (log: "our DK BLE is
        // not nearby -> skipping"). The AA manipulator is self-limiting anyway - it only bootstraps
        // against a Zeekr head unit (cert + head-unit-server), so a stray AA device just no-ops.
        //Previously used device, use it even if no UUID as UUID might become available later.
        if (io.aawireless.wirelessaamanipulator.storage.KnowDevicesStore.contains(context, device.getAddress())) {
            Log.d(TAG, "Known device -> skipping UUID check: " + device.getAddress());
            startMainService(context, device);
            ourDevice = device; // so disconnect logic works consistently
            return;
        }

        // If UUIDs already known, filter immediately
        if (hasAndroidAutoProfile(device)) {
            startMainService(context, device);
            ourDevice = device;
            return;
        }

        // Otherwise fetch SDP UUIDs asynchronously; ACTION_UUID will arrive
        boolean requested = device.fetchUuidsWithSdp();
        Log.d(TAG, "No AA UUID yet. fetchUuidsWithSdp requested=" + requested);
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
