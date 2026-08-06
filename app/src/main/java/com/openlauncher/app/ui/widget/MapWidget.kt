package com.openlauncher.app.ui.widget

import android.content.Context
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
import com.openlauncher.app.util.OfflineMapStore
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

private const val DEFAULT_ZOOM = 15.0

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
    accent: Color,
    isDayMode: Boolean = false,
    isEditing: Boolean = false,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val archive = remember { OfflineMapStore.installedPmTiles(context) }

    if (archive == null) {
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

    // Must run before any MapView is constructed. It is idempotent, so calling
    // it each time the widget appears is safe.
    remember { MapLibre.getInstance(context) }
    val mapView = remember { MapView(context) }

    // MapView is a plain Android view with its own lifecycle contract; without
    // these calls it leaks its GL surface and renders nothing after the launcher
    // has been backgrounded once — which on a head unit is constant.
    DisposableEffect(Unit) {
        mapView.onCreate(null)
        mapView.onStart()
        mapView.onResume()
        onDispose {
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    Box(modifier = modifier) {
        AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxSize(),
            update = { view ->
                // Gestures off while rearranging, so a drag moves the widget
                // rather than panning the map underneath it.
                view.setOnTouchListener { _, _ -> isEditing }
                view.getMapAsync { map ->
                    map.setStyle(Style.Builder().fromJson(styleJson(context, archive)))
                    map.uiSettings.apply {
                        isAttributionEnabled = false
                        isLogoEnabled = false
                        isCompassEnabled = false
                        setAllGesturesEnabled(false)
                    }
                    location?.let {
                        map.cameraPosition = CameraPosition.Builder()
                            .target(LatLng(it.latitude, it.longitude))
                            .zoom(DEFAULT_ZOOM)
                            // The map turns and the vehicle stays pointing up the
                            // screen, which is what makes a moving map readable at
                            // a glance.
                            .bearing(bearing.toDouble())
                            .build()
                    }
                }
            }
        )
    }
}

/**
 * Loads the bundled style and points its source at the installed archive.
 *
 * The path is only known at runtime, so the asset carries a placeholder rather
 * than a real URL. The pmtiles:// prefix is what tells MapLibre to read the file
 * as an archive instead of treating it as a tile endpoint.
 */
private fun styleJson(context: Context, archive: java.io.File): String =
    context.assets.open("map-style.json").bufferedReader().use { it.readText() }
        .replace("__PMTILES_URL__", "pmtiles://file://${archive.absolutePath}")
