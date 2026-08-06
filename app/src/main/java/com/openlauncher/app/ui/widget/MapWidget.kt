package com.openlauncher.app.ui.widget

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.openlauncher.app.util.LocationData
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

private const val DEFAULT_ZOOM = 15.0

/** Matches the roughly one second between GPS fixes, so easing is continuous. */
private const val CAMERA_EASE_MS = 1000

/**
 * Keeps the map alive between visits to the home screen.
 *
 * MapView is expensive to build and has to reload its style and tiles from
 * scratch each time, so tying its lifetime to the composition meant a visible
 * rebuild on every return from another screen. One instance is enough: the grid
 * allows a single map widget.
 *
 * Built against the application context rather than the activity, so holding it
 * past the activity cannot leak one.
 */
private object MapViewHolder {
    private var instance: MapView? = null

    fun obtain(context: android.content.Context): MapView {
        MapLibre.getInstance(context.applicationContext)
        return instance ?: MapView(context.applicationContext).also {
            it.onCreate(null)
            instance = it
        }
    }
}

/**
 * Position on a vector map rendered by MapLibre from a PMTiles archive.
 *
 * PMTiles holds a whole region in one file and MapLibre reads it in place,
 * either from storage or from a remote copy by HTTP range request — so an area
 * is available with no connection, which a tile cache can never do since it only
 * ever holds roads already driven. The vector form is also what makes the sizes
 * workable: a region runs to tens of megabytes against tens of gigabytes of
 * equivalent raster tiles.
 *
 * With no archive installed there is nothing to draw, so the widget says so
 * rather than showing an empty grey square.
 */
@Composable
fun MapWidget(
    location: LocationData?,
    bearing: Float,
    styleUri: String,
    hasMapData: Boolean,
    accent: Color,
    isDayMode: Boolean = false,
    isEditing: Boolean = false,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    if (!hasMapData) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                text = "NO MAP ARCHIVE\nSettings › Maintenance › Offline Map Archive",
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                fontSize = 10.sp,
                lineHeight = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(12.dp)
            )
        }
        return
    }

    // A stand-in while the grid is being rearranged. MapView is the one Android
    // view among the widgets, and it consumes touches before Compose can read
    // them as a drag or a resize — so the tile could not be moved or resized at
    // all. Not rendering it during editing hands the gestures back, and also
    // avoids dragging a live GL surface around the screen.
    if (isEditing) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                text = "MAP",
                color = accent,
                fontSize = 14.sp,
                letterSpacing = 2.sp
            )
        }
        return
    }

    // Held outside the composition so leaving the home screen does not destroy
    // it. Scoped to the composable, every trip to settings and back tore the map
    // down and rebuilt it, which meant waiting for tiles to redraw each time.
    val mapView = remember { MapViewHolder.obtain(context) }

    // Started and stopped with the widget, but never destroyed: destroying is
    // what forced the reload. The surface is released on pause, so nothing is
    // held while the map is off screen.
    DisposableEffect(Unit) {
        mapView.onStart()
        mapView.onResume()
        onDispose {
            mapView.onPause()
            mapView.onStop()
        }
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        AndroidView(
            factory = {
                // Re-attaching a view that is still parented from its last use
                // throws, so it is detached before being handed over.
                (mapView.parent as? android.view.ViewGroup)?.removeView(mapView)
                mapView
            },
            modifier = Modifier.fillMaxSize(),
            update = { view ->
                view.getMapAsync { map ->
                    if (map.style == null) {
                        map.setStyle(Style.Builder().fromUri(styleUri))
                        map.uiSettings.apply {
                            isAttributionEnabled = false
                            isLogoEnabled = false
                            isCompassEnabled = false
                            setAllGesturesEnabled(false)
                        }
                    }
                    location?.let {
                        val camera = CameraPosition.Builder()
                            .target(LatLng(it.latitude, it.longitude))
                            .zoom(DEFAULT_ZOOM)
                            // The map turns and the vehicle stays pointing up the
                            // screen, which is what makes a moving map readable at
                            // a glance.
                            .bearing(bearing.toDouble())
                            .build()
                        // Eased across the interval between fixes rather than set
                        // outright. Assigning the position jumped the map once a
                        // second; this makes the same data read as movement.
                        map.easeCamera(
                            CameraUpdateFactory.newCameraPosition(camera),
                            CAMERA_EASE_MS
                        )
                    }
                }
            }
        )

        // Drawn over the map rather than added as a layer: the camera is centred
        // on the vehicle and turned to its heading, so the centre of the widget
        // is the vehicle by construction, and an arrow there is always right.
        Canvas(modifier = Modifier.size(22.dp)) {
            val arrow = Path().apply {
                moveTo(size.width / 2f, 0f)
                lineTo(size.width * 0.18f, size.height)
                lineTo(size.width / 2f, size.height * 0.72f)
                lineTo(size.width * 0.82f, size.height)
                close()
            }
            drawPath(arrow, accent)
            drawPath(arrow, Color.Black, style = Stroke(width = 2f))
        }
    }
}
