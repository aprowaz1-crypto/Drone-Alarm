package com.aegisf6.app.device

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.aegisf6.app.util.DiagnosticsLog

class BluetoothProbe(private val context: Context) {
    fun connectedAudioMicDevices(): Int {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: run {
            DiagnosticsLog.missingOnce(
                key = "bluetooth_adapter_unavailable",
                message = "Bluetooth adapter is unavailable on this device/emulator"
            )
            return 0
        }
        if (!adapter.isEnabled) return 0

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                DiagnosticsLog.toFixOnce(
                    key = "missing_bluetooth_connect_permission",
                    message = "BLUETOOTH_CONNECT permission is missing; external mic count will stay 0"
                )
                return 0
            }
        }

        var count = 0
        if (adapter.getProfileConnectionState(BluetoothProfile.HEADSET) == BluetoothProfile.STATE_CONNECTED) {
            count++
        }
        if (adapter.getProfileConnectionState(BluetoothProfile.A2DP) == BluetoothProfile.STATE_CONNECTED) {
            count++
        }
        return count
    }
}
