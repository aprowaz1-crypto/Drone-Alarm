package com.aegisf6.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.aegisf6.app.audio.AudioProcessor
import com.aegisf6.app.device.BluetoothProbe
import com.aegisf6.app.device.LocationProvider

class AegisViewModelFactory(
    private val bluetoothProbe: BluetoothProbe,
    private val audioProcessor: AudioProcessor,
    private val locationProvider: LocationProvider
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(AegisViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return AegisViewModel(bluetoothProbe, audioProcessor, locationProvider) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
