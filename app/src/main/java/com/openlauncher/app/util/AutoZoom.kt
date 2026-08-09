package com.openlauncher.app.util

import kotlin.math.abs
import kotlin.math.ln

/**
 * Chooses a zoom from how fast the vehicle is going.
 *
 * The obvious approach — a table of speed bands, each with a zoom level —
 * produces a jump every time a band is crossed and answers the wrong question.
 * What matters is not seeing a fixed distance ahead but seeing a fixed *amount
 * of time* ahead: the useful span of road is a reaction interval, not a length.
 * Hold that interval constant and the zoom follows from the speed rather than
 * being decided against it, which is also why the result feels like nothing is
 * happening — the road ahead simply stays where it was.
 *
 * Everything else here exists to stop it oscillating. Speed fluctuates
 * constantly, and a zoom that tracks it faithfully breathes without pause.
 */
object AutoZoom {

    /**
     * Below this the vehicle is stopped or crawling and the horizon collapses
     * towards zero, which would drive the zoom to its ceiling at every red
     * light. Held instead.
     */
    private const val MOVING_THRESHOLD_MPS = 2.0f

    /**
     * How far the target has to drift before the camera is moved at all.
     *
     * A quarter of a zoom level is under the threshold where a change reads as
     * motion, so corrections below it would be felt without being seen — which
     * is the definition of a distracting animation.
     */
    private const val DEADBAND = 0.25

    /**
     * Below 13 streets stop being named. The ceiling matches the default so
     * that standing still and then moving off does not pull the camera back
     * from where it was left.
     */
    private const val MIN_ZOOM = 13.0
    private const val MAX_ZOOM = 20.0

    /**
     * The zoom that puts [horizonSeconds] of road in the top half of the view,
     * or null when the camera should be left alone.
     *
     * Scale is taken from the projection rather than computed from a mercator
     * constant: the SDK already knows the metres per pixel for the current zoom
     * and screen density, and deriving the target as a ratio against it is
     * correct whatever those turn out to be.
     */
    fun target(
        speedMps: Float,
        horizonSeconds: Int,
        currentZoom: Double,
        currentMetresPerPixel: Double,
        viewportHeightPx: Int
    ): Double? {
        if (horizonSeconds <= 0) return null
        if (speedMps < MOVING_THRESHOLD_MPS) return null
        if (viewportHeightPx <= 0 || currentMetresPerPixel <= 0.0) return null

        // Half the viewport: the vehicle sits at the centre, so the road ahead
        // occupies what is above it.
        val forwardPixels = viewportHeightPx / 2.0
        val desiredMetresPerPixel = (speedMps * horizonSeconds) / forwardPixels

        // Halving the metres per pixel is exactly one zoom level, so the
        // difference in levels is the log base two of the ratio.
        val levels = ln(desiredMetresPerPixel / currentMetresPerPixel) / ln(2.0)
        val target = (currentZoom - levels).coerceIn(MIN_ZOOM, MAX_ZOOM)

        return if (abs(target - currentZoom) < DEADBAND) null else target
    }
}
