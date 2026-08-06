package com.openlauncher.app.ui.widget

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.openlauncher.app.util.LocationData
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import java.io.File

private const val DEFAULT_ZOOM = 16.0

/**
 * Position on an OpenStreetMap view.
 *
 * Tiles come from the standard OSM servers, which are meant to serve map views.
 * An earlier version of this widget pulled from Google's internal tile endpoints
 * instead, outside their terms, and was reverted upstream.
 *
 * Offline behaviour follows from the cache: tiles already fetched keep rendering
 * with no connection. That covers routes driven before, not unvisited areas —
 * osmdroid also reads offline tile archives dropped in its own directory, which
 * is the supported way to have a region available in advance without scraping
 * one out of the public servers.
 */
@Composable
fun MapWidget(
    location: LocationData?,
    bearing: Float,
    accent: Color,
    isDayMode: Boolean = false,
    isEditing: Boolean = false,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val mapView = remember { createMapView(context) }

    // Recentre as fixes arrive. Not animated: on a slow SoC an animated pan for
    // every fix queues up faster than it can render and the map lags behind.
    LaunchedEffect(location?.latitude, location?.longitude) {
        location?.let { mapView.controller.setCenter(GeoPoint(it.latitude, it.longitude)) }
    }

    LaunchedEffect(bearing) { mapView.mapOrientation = -bearing }

    DisposableEffect(Unit) {
        mapView.onResume()
        onDispose { mapView.onPause() }
    }

    Box(modifier = modifier) {
        AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxSize(),
            update = { view ->
                // Panning is disabled while the grid is being rearranged, so a
                // drag moves the widget rather than scrolling the map under it.
                view.setOnTouchListener { _, _ -> isEditing }
                view.overlays.removeAll { it is HeadingMarker }
                location?.let {
                    view.overlays.add(
                        HeadingMarker(GeoPoint(it.latitude, it.longitude), accent.toArgb())
                    )
                }
                view.invalidate()
            }
        )
    }
}

private fun createMapView(context: Context): MapView {
    val config = Configuration.getInstance()
    // OSM's tile policy requires an identifying user agent; the default value
    // gets requests rejected outright.
    config.userAgentValue = context.packageName
    config.osmdroidBasePath = File(context.filesDir, "osmdroid").apply { mkdirs() }
    config.osmdroidTileCache = File(config.osmdroidBasePath, "tiles").apply { mkdirs() }
    // Deliberately modest. A cache large enough to hold a region would amount to
    // bulk downloading, which the tile policy forbids; offline coverage belongs
    // in an offline archive instead.
    config.tileFileSystemCacheMaxBytes = 200L * 1024 * 1024
    config.tileFileSystemCacheTrimBytes = 150L * 1024 * 1024

    return MapView(context).apply {
        setTileSource(TileSourceFactory.MAPNIK)
        setMultiTouchControls(true)
        setUseDataConnection(true)
        zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
        controller.setZoom(DEFAULT_ZOOM)
    }
}

/**
 * Vehicle position, drawn rather than loaded as a marker bitmap so it picks up
 * the accent colour and needs no drawable per theme.
 *
 * The map itself is rotated to the heading, so the arrow stays pointing up the
 * screen and the world turns underneath — which is what makes a moving map
 * readable at a glance while driving.
 */
private class HeadingMarker(
    private val position: GeoPoint,
    private val color: Int
) : Overlay() {

    override fun draw(canvas: android.graphics.Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val point = mapView.projection.toPixels(position, null)

        val fill = android.graphics.Paint().apply {
            isAntiAlias = true
            this.color = this@HeadingMarker.color
            style = android.graphics.Paint.Style.FILL
        }
        val outline = android.graphics.Paint().apply {
            isAntiAlias = true
            this.color = android.graphics.Color.BLACK
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 2f
        }

        val size = 18f
        val path = android.graphics.Path().apply {
            moveTo(point.x.toFloat(), point.y - size)
            lineTo(point.x - size * 0.6f, point.y + size * 0.7f)
            lineTo(point.x.toFloat(), point.y + size * 0.3f)
            lineTo(point.x + size * 0.6f, point.y + size * 0.7f)
            close()
        }
        canvas.drawPath(path, fill)
        canvas.drawPath(path, outline)
    }
}
