package com.openlauncher.app.ui.widget

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.openlauncher.app.util.LocationData
import com.openlauncher.app.util.RoadSnapper
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

/**
 * Building scale.
 *
 * The tiles stop at zoom 15, so everything past it is overzoom — the same
 * geometry drawn larger. Vector data survives that where a raster tile would
 * blur, since the shapes are rendered rather than magnified, but no new detail
 * appears above 15 and labels thin out as they are spaced for a smaller scale.
 * Twenty is close, and close is what was asked for; it is worth knowing that the
 * gain over 18 is size rather than information.
 */
private const val DEFAULT_ZOOM = 20.0

/** The marker grows with the scale, between these bounds. */
private val MARKER_MIN_DP = 12.dp
private val MARKER_MAX_DP = 26.dp
private const val MARKER_MIN_ZOOM = 11.0
private const val MARKER_MAX_ZOOM = 19.0

/** Long enough for a re-attached GL surface to be live before it is queried. */
private const val SURFACE_SETTLE_MS = 700L

/**
 * Bounds on the ease, which otherwise follows the measured gap between fixes.
 *
 * The floor stops a burst of fixes becoming a flicker of overlapping eases; the
 * ceiling stops a long gap — a tunnel, a cold start — committing the camera to a
 * slow crawl across the distance it covered while blind.
 */
private const val MIN_EASE_MS = 400
private const val MAX_EASE_MS = 2000

/**
 * The box searched for roads, as offsets from the marker in pixels.
 *
 * It barely rises above the vehicle and reaches well below it. Symmetry was the
 * bug: with the camera pitched, the upper edge of a centred box can fall beyond
 * the horizon, where a screen point has no ground position at all. MapLibre
 * unprojects it to an infinite longitude and the render thread dies —
 * "longitude must not be infinite", which is exactly what the crash log says.
 *
 * Nothing is lost by the asymmetry. Snapping places the marker on the road it is
 * standing on, not on one ahead, so the road above it was never the question.
 */
private const val SNAP_LEFT_PX = 110f
private const val SNAP_ABOVE_PX = 20f
private const val SNAP_BELOW_PX = 150f


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

    /** The style currently applied, so a changed one is noticed. */
    var appliedStyle: String? = null

    /**
     * Whether the camera follows the vehicle, and the zoom it is at.
     *
     * Both live here rather than in the composition. The gesture listener can
     * only be registered once for the life of the map, so it captured the state
     * of whatever composition happened to be first — and every return from
     * another screen built a new one. Panning then switched off a state nothing
     * was reading while the live one stayed true, and the map recentred itself
     * out from under the finger.
     */
    val following = mutableStateOf(true)
    val zoom = mutableStateOf(DEFAULT_ZOOM)

    /**
     * Where the camera is heading, written once a fix and read every frame.
     *
     * Held here for the same reason as the rest: the follow loop outlives any
     * one composition, and a target owned by a composition that has been
     * replaced is a target nothing updates.
     */
    val targetLat = androidx.compose.runtime.mutableDoubleStateOf(0.0)
    val targetLon = androidx.compose.runtime.mutableDoubleStateOf(0.0)
    val targetBearing = androidx.compose.runtime.mutableFloatStateOf(0f)
    val targetZoom = androidx.compose.runtime.mutableDoubleStateOf(DEFAULT_ZOOM)
    val hasTarget = mutableStateOf(false)

    /** When the last fix was applied, to size the next ease against it. */
    var lastFixAtMs = android.os.SystemClock.elapsedRealtime()

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
    roadSnapMetres: Int = 0,
    lastKnown: LatLng? = null,
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

    // Drawn only once the style has actually loaded and a fix exists. The marker
    // used to sit over a blank widget while tiles were still arriving, claiming a
    // position on a map that was not there.
    var styleReady by remember { mutableStateOf(false) }

    // Held outside the composition — see MapViewHolder.
    var following by MapViewHolder.following
    val zoomNow by MapViewHolder.zoom
    // Held so the overlay controls can drive the camera; getMapAsync only
    // delivers it inside the view's own callback.
    var mapRef by remember { mutableStateOf<org.maplibre.android.maps.MapLibreMap?>(null) }

    // Rendered-feature queries are held off until the surface has had time to
    // come back. Returning from another screen re-attaches the retained MapView
    // and rebuilds its GL surface, and querying across that gap kills the
    // process in native code — below the level any handler can reach, which is
    // why such a crash leaves no report at all.
    var surfaceSettled by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        surfaceSettled = false
        com.openlauncher.app.util.CrashLog.step(context, "map: attaching view")
        kotlinx.coroutines.delay(SURFACE_SETTLE_MS)
        surfaceSettled = true
        com.openlauncher.app.util.CrashLog.step(context, "map: surface settled")
    }

    // Driven by the inputs rather than by recomposition. The AndroidView update
    // block runs on every recomposition, and the home screen recomposes
    // constantly — engine data, clock, speed. Each pass restarted a one-second
    // ease from wherever the camera had got to, so the previous one was
    // cancelled after covering a few percent of its path and the camera jittered
    // in place instead of travelling. The road query paid the same price, several
    // times a second instead of once a fix.
    LaunchedEffect(
        mapRef, location, bearing, following, styleReady, surfaceSettled
    ) {
        val map = mapRef ?: return@LaunchedEffect
        if (!following) return@LaunchedEffect
        val fix = location ?: return@LaunchedEffect

        val raw = LatLng(fix.latitude, fix.longitude)
        // Queried against what is already on screen, so this costs no network and
        // no extra geometry — the roads under the marker have necessarily been
        // drawn already.
        // Every condition here is a way the query has been seen to take the
        // process down: no surface yet, or a view with no dimensions to query
        // within.
        val canQuery = roadSnapMetres > 0 && styleReady && surfaceSettled &&
            mapView.width > 0 && mapView.height > 0
        val target = if (canQuery) {
            val cx = mapView.width / 2f
            val cy = mapView.height / 2f
            val box = android.graphics.RectF(
                cx - SNAP_LEFT_PX, cy - SNAP_ABOVE_PX,
                cx + SNAP_LEFT_PX, cy + SNAP_BELOW_PX
            )
            val roads = runCatching {
                map.queryRenderedFeatures(box, *RoadSnapper.ROAD_LAYERS)
            }.getOrDefault(emptyList())
            RoadSnapper.snap(raw, roads, roadSnapMetres.toDouble()) ?: raw
        } else raw

        // Whatever level is in effect is kept, so following again after a pinch
        // does not snap back to the default.
        val zoom = map.cameraPosition.zoom.takeIf { z -> z > 1.0 } ?: DEFAULT_ZOOM

        // Last line of defence. A non-finite coordinate reaching the renderer
        // kills the render thread outright rather than raising anything catchable
        // here, so it is refused before being handed over.
        if (!target.latitude.isFinite() || !target.longitude.isFinite()) return@LaunchedEffect

        // Published for the follow loop rather than applied here. Snapping is
        // expensive — it queries rendered features — so it stays on the fix,
        // once a second, while the motion it feeds runs every frame.
        MapViewHolder.targetLat.doubleValue = target.latitude
        MapViewHolder.targetLon.doubleValue = target.longitude
        MapViewHolder.targetBearing.floatValue = bearing
        MapViewHolder.targetZoom.doubleValue = zoom
        MapViewHolder.hasTarget.value = true
    }

    /**
     * Glides to the last fix, with a linear ease per fix.
     *
     * Three attempts, and the reasoning behind each is worth keeping. The
     * default easeCamera is an accelerate-then-decelerate curve, so a fresh one
     * every second cut the previous mid-flight and restarted it from a
     * standstill: the camera slowed and lunged, once a second.
     *
     * Driving the camera myself every frame fixed the curve and introduced a
     * worse problem. moveCamera forces an immediate camera set and a redraw, and
     * sixty of those a second on this hardware saturated the main thread — the
     * map rendered no better and the rest of the interface stopped updating with
     * it, which is what made the temperature widget appear to go blank.
     *
     * MapLibre already had the answer: easeCamera takes a flag that swaps the
     * curve for a linear one. Constant velocity, chained fix to fix, at one
     * camera call a second instead of sixty.
     */
    LaunchedEffect(mapRef, location, bearing, following) {
        val map = mapRef ?: return@LaunchedEffect
        if (!following || !MapViewHolder.hasTarget.value) return@LaunchedEffect

        val target = LatLng(
            MapViewHolder.targetLat.doubleValue,
            MapViewHolder.targetLon.doubleValue
        )
        if (!target.latitude.isFinite() || !target.longitude.isFinite()) return@LaunchedEffect

        // Measured rather than assumed. GPS caps near 1 Hz but delivers late
        // under a poor sky, and an ease shorter than the real gap finishes early
        // and leaves the map still for the remainder — the stutter this is meant
        // to remove.
        val now = android.os.SystemClock.elapsedRealtime()
        val gap = (now - MapViewHolder.lastFixAtMs).toInt()
        MapViewHolder.lastFixAtMs = now
        val duration = gap.coerceIn(MIN_EASE_MS, MAX_EASE_MS)

        runCatching {
            map.easeCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder()
                        .target(target)
                        .zoom(MapViewHolder.targetZoom.doubleValue)
                        // The map turns and the vehicle stays pointing up the
                        // screen, which is what makes a moving map readable at a
                        // glance.
                        .bearing(MapViewHolder.targetBearing.floatValue.toDouble())
                        .build()
                ),
                duration,
                // Linear. This flag is the whole fix: with the default curve the
                // camera decelerates into every fix and accelerates out of the
                // next, which is the juddering.
                false
            )
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
                    mapRef = map
                    // The retained MapView keeps its style across navigation, so
                    // the branch below runs once for the life of the process while
                    // styleReady is remembered per composition. Returning to the
                    // home screen therefore left the flag false over a map that
                    // was perfectly loaded — and the marker simply vanished.
                    if (map.style != null) styleReady = true

                    // Re-applied when the file changes, which it does on a switch
                    // between day and night: the palette lives in the style, and
                    // testing only for a missing style left the map black under a
                    // light interface.
                    if (map.style != null && MapViewHolder.appliedStyle != styleUri) {
                        MapViewHolder.appliedStyle = styleUri
                        styleReady = false
                        map.setStyle(Style.Builder().fromUri(styleUri)) { styleReady = true }
                    }

                    if (map.style == null) {
                        MapViewHolder.appliedStyle = styleUri
                        map.setStyle(Style.Builder().fromUri(styleUri)) { styleReady = true }
                        // Placed before any fix exists. The camera was only ever
                        // touched once a position arrived, so until then the map
                        // sat at MapLibre's own default — the whole planet — which
                        // on a cold start is minutes of looking broken.
                        runCatching {
                            map.moveCamera(
                                CameraUpdateFactory.newCameraPosition(
                                    CameraPosition.Builder()
                                        .target(lastKnown ?: LatLng(0.0, 0.0))
                                        .zoom(DEFAULT_ZOOM)
                                        .build()
                                )
                            )
                        }
                        map.uiSettings.apply {
                            isAttributionEnabled = false
                            isLogoEnabled = false
                            isCompassEnabled = false
                            // Rotation stays off: the map is turned to the
                            // heading, so letting it be rotated by hand fights
                            // that on the next fix.
                            isScrollGesturesEnabled = true
                            isZoomGesturesEnabled = true
                            isRotateGesturesEnabled = false
                            isTiltGesturesEnabled = false
                        }
                        // Reason 1 is a gesture; animations driven from here
                        // report their own reason and must not stop the follow.
                        map.addOnCameraMoveStartedListener { reason ->
                            if (reason == org.maplibre.android.maps.MapLibreMap
                                    .OnCameraMoveStartedListener.REASON_API_GESTURE
                            ) MapViewHolder.following.value = false
                        }
                        // Read when movement settles rather than per frame: the
                        // marker only needs resizing once the scale has stopped
                        // changing, and a per-frame state write would recompose
                        // the widget on every frame of every pan.
                        map.addOnCameraIdleListener {
                            MapViewHolder.zoom.value = map.cameraPosition.zoom
                        }
                    }
                }
            }
        )

        // Drawn over the map rather than added as a layer: the camera is centred
        // on the vehicle and turned to its heading, so the centre of the widget
        // is the vehicle by construction, and an arrow there is always right.
        // Only the recentre control. Zoom keeps its pinch gesture, and buttons
        // for it took room on a tile that is already small.
        if (!following) {
            MapButton(
                label = "◉",
                accent = accent,
                onClick = { following = true },
                modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp)
            )
        }

        // Sized against the scale it stands on. At street level the arrow should
        // cover roughly a vehicle's width of road; zoomed out to a region that
        // same arrow would blanket a town, which reads as a claim about where
        // the car is that the map cannot support.
        val markerSize = androidx.compose.ui.unit.lerp(
            MARKER_MIN_DP,
            MARKER_MAX_DP,
            ((zoomNow - MARKER_MIN_ZOOM) / (MARKER_MAX_ZOOM - MARKER_MIN_ZOOM))
                .coerceIn(0.0, 1.0)
                .toFloat()
        )
        if (styleReady && location != null) Canvas(modifier = Modifier.size(markerSize)) {
            val w = size.width
            val h = size.height

            val arrow = Path().apply {
                moveTo(w * 0.5f, h * 0.04f)
                lineTo(w * 0.88f, h * 0.90f)
                lineTo(w * 0.5f, h * 0.68f)
                lineTo(w * 0.12f, h * 0.90f)
                close()
            }

            // Outlined first with a thick round-joined stroke, then filled over
            // the top. That rounds the corners without any curve arithmetic, and
            // the border is what keeps the arrow legible over a road of the same
            // colour — the accent alone disappears against its own palette.
            //
            // The outline does the whole job. There was a shadow disc under this
            // as well and it only muddied the map: a dark blob under a small
            // marker reads as dirt on the screen rather than as depth.
            drawPath(
                arrow,
                if (isDayMode) Color.White else Color.Black,
                style = Stroke(
                    width = 6f,
                    join = StrokeJoin.Round,
                    cap = StrokeCap.Round
                )
            )
            drawPath(arrow, accent)
        }
    }
}

@Composable
private fun MapButton(
    label: String,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(30.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = accent, fontSize = 15.sp)
    }
}
