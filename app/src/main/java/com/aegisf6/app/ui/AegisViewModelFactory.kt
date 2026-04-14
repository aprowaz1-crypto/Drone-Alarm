package com.aegisf6.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.aegisf6.app.device.BluetoothProbe

class AegisViewModelFactory(private val bluetoothProbe: BluetoothProbe) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(AegisViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return AegisViewModel(bluetoothProbe) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
