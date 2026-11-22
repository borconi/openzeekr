package io.aawireless.wirelessaamanipulator.connectors;

import android.Manifest;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.util.Log;

import androidx.annotation.RequiresPermission;

import io.aawireless.wirelessaamanipulator.helpers.interfaces.HandshakeCallbacks;

public final class CarHandshakeClientManager {

    private static final Object LOCK = new Object();
    private static final String TAG = "CarHandshakeClientManager";
    private static CarHandshakeClient current;

    private CarHandshakeClientManager() {}

    /**
     * Ensures there is only ONE CarHandshakeClient running at a time.
     * - If the same device is already handled and thread is alive -> returns it.
     * - If a different device arrives -> stops old client, starts new one.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    public static CarHandshakeClient startOrSwitch(BluetoothDevice device,
                                                   Context context,
                                                   HandshakeCallbacks cb) {
        synchronized (LOCK) {
            if (current != null) {
                if (current.isAlive() && current.isSameDevice(device)) {
                    Log.d(TAG,"Same device, returning current");
                    return current;
                }
                // Different device or dead thread -> stop old one
                current.shutdown();
                current = null;
            }
            Log.d(TAG,"New device, spinnign up new thread.");
            current = new CarHandshakeClient(device, context, cb);
            current.start();
            return current;
        }
    }

    /** Stops the currently running client (if any). */
    public static void shutdown() {
        synchronized (LOCK) {
            if (current != null) {
                current.shutdown();
                current = null;
            }
        }
    }

    /** Optional: returns current instance (may be null). */
    public static CarHandshakeClient get() {
        synchronized (LOCK) {
            return current;
        }
    }
}
