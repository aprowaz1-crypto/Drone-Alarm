package com.aegisf6.app.map

import androidx.core.content.ContextCompat
import com.aegisf6.app.R
import com.aegisf6.app.model.TargetKind
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

class MapOverlayController(private val mapView: MapView) {
    private val userMarker = Marker(mapView)
    private val targetMarker = Marker(mapView)
    private val trajectory = Polyline()
    private var centeredOnce = false

    init {
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
        mapView.controller.setZoom(14.5)

        userMarker.title = "Ти"
        userMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        userMarker.icon = ContextCompat.getDrawable(mapView.context, R.drawable.map_marker_user)

        targetMarker.title = "Ціль"
        targetMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)

        trajectory.outlinePaint.strokeWidth = 7f

        mapView.overlays.add(trajectory)
        mapView.overlays.add(userMarker)
        mapView.overlays.add(targetMarker)
    }

    fun setStandardTiles() {
        mapView.setTileSource(TileSourceFactory.MAPNIK)
    }

    fun setTopoTiles() {
        mapView.setTileSource(TileSourceFactory.PUBLIC_TRANSPORT)
    }

    fun update(
        userLat: Double,
        userLon: Double,
        targetLat: Double,
        targetLon: Double,
        targetKind: TargetKind,
        accepted: Boolean
    ) {
        val user = GeoPoint(userLat, userLon)
        val target = GeoPoint(targetLat, targetLon)
        userMarker.position = user
        targetMarker.position = target
        targetMarker.icon = ContextCompat.getDrawable(
            mapView.context,
            when (targetKind) {
                TargetKind.SHAHED -> R.drawable.map_marker_shahed
                TargetKind.MISSILE -> R.drawable.map_marker_missile
                TargetKind.UNKNOWN -> R.drawable.map_marker_shahed
            }
        )
        targetMarker.title = when (targetKind) {
            TargetKind.SHAHED -> "Шахед"
            TargetKind.MISSILE -> "Крилата ракета"
            TargetKind.UNKNOWN -> "Ціль"
        }
        targetMarker.setVisible(accepted)
        trajectory.setPoints(listOf(user, target))
        trajectory.isVisible = accepted
        val currentCenter = mapView.mapCenter as? GeoPoint
        val shouldRecenter = !centeredOnce || currentCenter == null || currentCenter.distanceToAsDouble(user) > 250.0
        if (shouldRecenter) {
            mapView.controller.animateTo(user)
            centeredOnce = true
        }
        mapView.invalidate()
    }
}
