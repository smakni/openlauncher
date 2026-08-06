package com.openlauncher.app.ui.theme

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlin.math.abs

/**
 * GPS fixes land about once a second, and applying each one the moment it
 * arrives makes every readout jump in steps. Animating across the gap between
 * fixes is what turns that into continuous movement.
 */
private const val FIX_INTERVAL_MS = 1000

/** Smooths a value that changes with each GPS fix. */
@Composable
fun animatedFix(target: Float, durationMs: Int = FIX_INTERVAL_MS): State<Float> =
    animateFloatAsState(
        targetValue = target,
        // Linear, not eased: fixes arrive at a steady rate, so easing each step
        // would produce a visible pulse per second rather than steady motion.
        animationSpec = tween(durationMillis = durationMs, easing = { it }),
        label = "fix"
    )

/**
 * Smooths a compass heading without letting it unwind the long way round.
 *
 * Interpolating degrees directly sends the needle backwards through south on
 * every crossing of north, because 359 to 1 reads as a fall of 358 rather than a
 * rise of 2. Accumulating an unbounded angle instead — adding the shortest
 * signed step each time — keeps the rotation continuous, and a rotation of 721
 * degrees looks identical to one of 1.
 */
@Composable
fun animatedBearing(target: Float, durationMs: Int = FIX_INTERVAL_MS): State<Float> {
    var continuous by remember { mutableFloatStateOf(target) }

    val shortestStep = ((target - continuous) % 360f + 540f) % 360f - 180f
    if (abs(shortestStep) > 0.01f) continuous += shortestStep

    return animateFloatAsState(
        targetValue = continuous,
        animationSpec = tween(durationMillis = durationMs, easing = { it }),
        label = "bearing"
    )
}
