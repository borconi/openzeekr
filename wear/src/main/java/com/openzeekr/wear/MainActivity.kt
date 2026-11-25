package com.openzeekr.wear

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.content.ContextCompat

/**
 * The watch app. No cloud, no proximity — it receives the phone's digital key once
 * (Wear Data Layer) and then locks/unlocks the car over BLE. Notifications are mirrored
 * from the phone automatically by Wear OS (nothing to do here).
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WearKeyState.refresh(this)
        // Extension of the phone app — pull the key automatically, never ask the user to sign in.
        if (!WearKeyState.provisioned.value) KeySyncClient.requestKey(this)
        ensureBlePermissions()
        setContent { OzWearTheme { WearApp() } }
    }

    override fun onResume() {
        super.onResume()
        // The key may have arrived from the phone while we were backgrounded.
        WearKeyState.refresh(this)
        if (!WearKeyState.provisioned.value) KeySyncClient.requestKey(this)
    }

    private fun ensureBlePermissions() {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        val missing = perms.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), REQ_BLE)
    }

    private companion object { const val REQ_BLE = 1 }
}
