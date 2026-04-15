package com.aegisf6.app.device

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import androidx.core.content.ContextCompat
import com.aegisf6.app.util.DiagnosticsLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class LocationSnapshot(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val timestamp: Long,
    val sourceLabel: String
)

class LocationProvider(private val context: Context) {
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val _location = MutableStateFlow<LocationSnapshot?>(null)
    val location: StateFlow<LocationSnapshot?> = _location.asStateFlow()

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            _location.value = LocationSnapshot(
                latitude = location.latitude,
                longitude = location.longitude,
                accuracy = location.accuracy,
                timestamp = location.time,
                sourceLabel = normalizeProviderLabel(location.provider, isLastKnown = false)
            )
        }

        override fun onProviderEnabled(provider: String) {
            DiagnosticsLog.toFix("Location provider enabled: $provider")
        }

        override fun onProviderDisabled(provider: String) {
            DiagnosticsLog.toFixOnce(
                key = "location_provider_disabled_$provider",
                message = "Location provider disabled: $provider"
            )
        }

        @Deprecated("Deprecated in API 31")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    fun start(): Boolean {
        return try {
            // Перевірити дозволи
            val fineLocationGranted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

            val coarseLocationGranted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

            if (!fineLocationGranted && !coarseLocationGranted) {
                DiagnosticsLog.toFixOnce(
                    key = "location_permissions_missing",
                    message = "ACCESS_FINE_LOCATION and ACCESS_COARSE_LOCATION permissions are missing"
                )
                return false
            }

            getLastKnownLocation()?.let { snapshot ->
                _location.value = snapshot
                DiagnosticsLog.toFix("Loaded last known location: ${snapshot.latitude}, ${snapshot.longitude}")
            }

            // Спробувати GPS спочатку
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    1000,  // мінімум 1 сек
                    5f,    // мінімум 5 метрів
                    locationListener
                )
                DiagnosticsLog.toFix("GPS provider started")
            }

            // Фалбек на мережу
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    2000,  // мінімум 2 сек
                    10f,   // мінімум 10 метрів
                    locationListener
                )
                DiagnosticsLog.toFix("Network provider started")
            }

            true
        } catch (e: Exception) {
            DiagnosticsLog.bugOnce(
                key = "location_start_error",
                message = "Failed to start location provider: ${e.message}"
            )
            false
        }
    }

    fun stop() {
        locationManager.removeUpdates(locationListener)
        DiagnosticsLog.toFix("Location provider stopped")
    }

    fun getLastKnownLocation(): LocationSnapshot? {
        return try {
            val fineLocationGranted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

            if (fineLocationGranted) {
                val gpsLoc = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                if (gpsLoc != null) {
                    return LocationSnapshot(
                        latitude = gpsLoc.latitude,
                        longitude = gpsLoc.longitude,
                        accuracy = gpsLoc.accuracy,
                        timestamp = gpsLoc.time,
                        sourceLabel = normalizeProviderLabel(LocationManager.GPS_PROVIDER, isLastKnown = true)
                    )
                }

                val networkLoc = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                if (networkLoc != null) {
                    return LocationSnapshot(
                        latitude = networkLoc.latitude,
                        longitude = networkLoc.longitude,
                        accuracy = networkLoc.accuracy,
                        timestamp = networkLoc.time,
                        sourceLabel = normalizeProviderLabel(LocationManager.NETWORK_PROVIDER, isLastKnown = true)
                    )
                }
            }
            null
        } catch (e: Exception) {
            DiagnosticsLog.bugOnce(
                key = "get_last_location_error",
                message = "Failed to get last known location: ${e.message}"
            )
            null
        }
    }

    private fun normalizeProviderLabel(provider: String?, isLastKnown: Boolean): String {
        val base = when (provider) {
            LocationManager.GPS_PROVIDER -> "GPS"
            LocationManager.NETWORK_PROVIDER -> "Мережа"
            else -> "Невідоме джерело"
        }
        return if (isLastKnown) "$base • last known" else base
    }
}
