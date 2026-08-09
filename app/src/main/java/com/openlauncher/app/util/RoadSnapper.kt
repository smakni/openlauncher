package com.openlauncher.app.util

import org.maplibre.android.geometry.LatLng
import org.maplibre.geojson.Feature
import org.maplibre.geojson.LineString
import org.maplibre.geojson.MultiLineString
import org.maplibre.geojson.Point
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Pulls a position onto the nearest road, when there is one close enough.
 *
 * A raw fix lands five to fifteen metres out, which puts the vehicle marker
 * beside the street rather than on it. Navigation apps hide this by matching the
 * position to the road network, and the same can be done here from the road
 * geometry MapLibre has already drawn.
 *
 * The threshold is what keeps it honest. Snapping unconditionally would place
 * the marker on a road while crossing a car park or a track, asserting something
 * false; beyond the threshold the raw position is kept, on the grounds that being
 * visibly off a road is better than being confidently on the wrong one.
 */
object RoadSnapper {

    /** Layers in the bundled style that carry road geometry. */
    val ROAD_LAYERS = arrayOf("roads-minor", "roads-medium", "roads-major", "roads-highway")

    private const val METRES_PER_DEGREE_LAT = 111_320.0

    /**
     * The nearest point on any of [roads] to [position], or null when the closest
     * is further than [thresholdMetres].
     */
    fun snap(position: LatLng, roads: List<Feature>, thresholdMetres: Double): LatLng? =
        snapInternal(position, roads, thresholdMetres)
            // Geometry that came back from a pitched camera can carry infinities,
            // and one of those reaching the renderer takes the process down.
            ?.takeIf { it.latitude.isFinite() && it.longitude.isFinite() }

    private fun snapInternal(
        position: LatLng,
        roads: List<Feature>,
        thresholdMetres: Double
    ): LatLng? {
        // Longitude degrees shorten towards the poles, so distances are scaled by
        // the cosine of the latitude. Without it a snap would reach much further
        // east-west than north-south.
        val lonScale = cos(Math.toRadians(position.latitude))

        var best: LatLng? = null
        var bestDistance = Double.MAX_VALUE

        for (road in roads) {
            for (line in linesOf(road)) {
                for (i in 0 until line.size - 1) {
                    val candidate = closestOnSegment(position, line[i], line[i + 1], lonScale)
                    val distance = metres(position, candidate, lonScale)
                    if (distance < bestDistance) {
                        bestDistance = distance
                        best = candidate
                    }
                }
            }
        }
        return if (bestDistance <= thresholdMetres) best else null
    }

    private fun linesOf(feature: Feature): List<List<Point>> =
        when (val geometry = feature.geometry()) {
            is LineString -> listOf(geometry.coordinates())
            is MultiLineString -> geometry.coordinates()
            else -> emptyList()
        }

    /**
     * Closest point on one segment, by projecting the position onto it.
     *
     * The projection is clamped to the segment: without that, a position beside a
     * short street would snap to a point on its infinite extension, somewhere off
     * the end of the road entirely.
     */
    private fun closestOnSegment(
        position: LatLng,
        start: Point,
        end: Point,
        lonScale: Double
    ): LatLng {
        val sx = (start.longitude() - position.longitude) * lonScale
        val sy = start.latitude() - position.latitude
        val ex = (end.longitude() - position.longitude) * lonScale
        val ey = end.latitude() - position.latitude

        val dx = ex - sx
        val dy = ey - sy
        val lengthSquared = dx * dx + dy * dy
        if (lengthSquared == 0.0) return LatLng(start.latitude(), start.longitude())

        val t = (((-sx) * dx + (-sy) * dy) / lengthSquared).coerceIn(0.0, 1.0)
        return LatLng(
            position.latitude + sy + t * dy,
            position.longitude + (sx + t * dx) / lonScale
        )
    }

    private fun metres(a: LatLng, b: LatLng, lonScale: Double): Double {
        val dLat = (b.latitude - a.latitude) * METRES_PER_DEGREE_LAT
        val dLon = (b.longitude - a.longitude) * lonScale * METRES_PER_DEGREE_LAT
        return sqrt(dLat * dLat + dLon * dLon)
    }
}
