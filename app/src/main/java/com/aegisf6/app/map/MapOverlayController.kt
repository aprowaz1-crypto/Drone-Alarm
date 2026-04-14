package com.aegisf6.app.map

import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

class MapOverlayController(private val mapView: MapView) {
    private val userMarker = Marker(mapView)
    private val targetMarker = Marker(mapView)
    private val trajectory = Polyline()

    init {
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
        mapView.controller.setZoom(14.5)

        userMarker.title = "Ти"
        userMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)

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

    fun update(userLat: Double, userLon: Double, targetLat: Double, targetLon: Double) {
        val user = GeoPoint(userLat, userLon)
        val target = GeoPoint(targetLat, targetLon)
        userMarker.position = user
        targetMarker.position = target
        trajectory.setPoints(listOf(user, target))
        mapView.controller.animateTo(user)
        mapView.invalidate()
    }
}
