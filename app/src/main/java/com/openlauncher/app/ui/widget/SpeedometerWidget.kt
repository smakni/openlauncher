package com.openlauncher.app.ui.widget

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openlauncher.app.ui.theme.animatedFix
import com.openlauncher.app.util.LocationData
import kotlin.math.cos
import kotlin.math.sin

/**
 * Full scale for the rev counter.
 *
 * A diesel of this generation runs out of usable range around 4500, so a dial
 * drawn to a petrol's 7000 would spend its life in the first half and waste the
 * resolution where it is actually needed.
 */
private const val MAX_RPM = 5000f

@Composable
fun SpeedometerWidget(
    location: LocationData?,
    isMetric: Boolean,
    accent: Color,
    isDayMode: Boolean = false,
    digitalOnly: Boolean = false,
    /** Wheel speed from the CAN decoder; preferred over GPS where present. */
    canSpeedKph: Int? = null,
    rpm: Int? = null,
    showTacho: Boolean = false,
    modifier: Modifier = Modifier
) {
    val maxSpeed     = if (isMetric) 200f else 124f
    // The decoder wins over GPS. It reads the wheels, so it answers in a tunnel
    // and under trees, and it answers now rather than at the next fix — GPS
    // speed lags by about a second, which is visible under braking.
    val speedTarget = canSpeedKph?.let { kph ->
        (if (isMetric) kph.toFloat() else kph * 0.621371f)
    } ?: ((location?.speedMps ?: 0f) * if (isMetric) 3.6f else 2.237f)
    // Animated between readings: the raw value arrives about once a second and
    // stepped the needle and the digits in visible jumps.
    val speedDisplay by animatedFix(speedTarget.coerceAtLeast(0f))
    val unitLabel    = if (isMetric) "KM/H" else "MPH"
    val trackAlpha   = if (isDayMode) 0.18f else 0.07f
    val tickAlphaMaj = if (isDayMode) 0.50f else 0.28f
    val tickAlphaMin = if (isDayMode) 0.25f else 0.13f

    val contentColor = if (isDayMode) Color(0xFF111111) else MaterialTheme.colorScheme.onBackground
    val subAlpha     = if (isDayMode) 0.55f else 0.32f
    val tickBaseColor = if (isDayMode) Color(0xFF222222) else MaterialTheme.colorScheme.onBackground

    // Only drawn once the engine has actually answered. Without this the ring
    // sits pinned at zero on a car with no CAN reply, which reads as an engine
    // that has stalled rather than as a signal that never arrived.
    val tachoVisible = showTacho && rpm != null
    val rpmDisplay by animatedFix((rpm ?: 0).toFloat())

    Box(
        modifier         = modifier,
        contentAlignment = Alignment.Center
    ) {
        if (digitalOnly) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier            = Modifier.fillMaxSize()
            ) {
                Text(
                    text          = "%.0f".format(speedDisplay),
                    color         = contentColor,
                    fontSize      = 54.sp,
                    fontWeight    = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    letterSpacing = (-1.5).sp
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text          = unitLabel,
                    color         = contentColor.copy(alpha = subAlpha * 1.5f),
                    fontSize      = 10.sp,
                    fontWeight    = androidx.compose.ui.text.font.FontWeight.Bold,
                    letterSpacing = 2.sp
                )
                if (tachoVisible) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text          = "%.0f RPM".format(rpmDisplay),
                        color         = accent,
                        fontSize      = 12.sp,
                        letterSpacing = 1.5.sp
                    )
                }
            }
        } else {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val cx    = size.width  / 2f
                val cy    = size.height / 2f
                // The speed dial gives up a little radius when the rev ring is
                // shown, so the two are concentric rather than overlapping.
                val outerR = minOf(size.width, size.height) * 0.37f
                val arcR  = if (tachoVisible) outerR * 0.80f else outerR
                val trackW = arcR * 0.13f
                val startAngle    = 150f
                val sweepTotal    = 240f
                val progressSweep = (speedDisplay / maxSpeed).coerceIn(0f, 1f) * sweepTotal

                val tl   = Offset(cx - arcR, cy - arcR)
                val sz   = Size(arcR * 2f, arcR * 2f)

                drawArc(
                    color      = contentColor.copy(alpha = trackAlpha),
                    startAngle = startAngle,
                    sweepAngle = sweepTotal,
                    useCenter  = false,
                    topLeft    = tl,
                    size       = sz,
                    style      = Stroke(width = trackW, cap = StrokeCap.Round)
                )

                if (progressSweep > 0.5f) {
                    drawArc(
                        color      = accent,
                        startAngle = startAngle,
                        sweepAngle = progressSweep,
                        useCenter  = false,
                        topLeft    = tl,
                        size       = sz,
                        style      = Stroke(width = trackW, cap = StrokeCap.Round)
                    )
                }

                if (tachoVisible) {
                    val revR  = outerR
                    val revW  = revR * 0.075f
                    val revTl = Offset(cx - revR, cy - revR)
                    val revSz = Size(revR * 2f, revR * 2f)
                    val revSweep = (rpmDisplay / MAX_RPM).coerceIn(0f, 1f) * sweepTotal

                    drawArc(
                        color      = contentColor.copy(alpha = trackAlpha),
                        startAngle = startAngle,
                        sweepAngle = sweepTotal,
                        useCenter  = false,
                        topLeft    = revTl,
                        size       = revSz,
                        style      = Stroke(width = revW, cap = StrokeCap.Round)
                    )

                    // The upper third is drawn in warning red. On a diesel that
                    // is not a redline so much as the point past which the gear
                    // is simply the wrong one, which is the thing a dial can say
                    // and a number cannot.
                    val warnFrom = 0.66f
                    drawArc(
                        color      = Color(0xFF7A2E2E).copy(alpha = if (isDayMode) 0.35f else 0.5f),
                        startAngle = startAngle + warnFrom * sweepTotal,
                        sweepAngle = (1f - warnFrom) * sweepTotal,
                        useCenter  = false,
                        topLeft    = revTl,
                        size       = revSz,
                        style      = Stroke(width = revW, cap = StrokeCap.Round)
                    )

                    if (revSweep > 0.5f) {
                        drawArc(
                            color = if (rpmDisplay / MAX_RPM > warnFrom) Color(0xFFD05050) else accent,
                            startAngle = startAngle,
                            sweepAngle = revSweep,
                            useCenter  = false,
                            topLeft    = revTl,
                            size       = revSz,
                            style      = Stroke(width = revW, cap = StrokeCap.Round)
                        )
                    }
                }

                // Ticks belong to the speed dial, so they follow its radius and
                // sit inside it whether or not the rev ring took the outside.
                for (i in 0..10) {
                    val angle   = startAngle + i * (sweepTotal / 10f)
                    val rad     = Math.toRadians(angle.toDouble())
                    val isMajor = i % 2 == 0
                    val tickOuterR = arcR - trackW / 2f - 3.dp.toPx()
                    val innerR  = tickOuterR - if (isMajor) 7.dp.toPx() else 4.dp.toPx()
                    drawLine(
                        color       = tickBaseColor.copy(alpha = if (isMajor) tickAlphaMaj else tickAlphaMin),
                        start       = Offset(cx + (tickOuterR * cos(rad)).toFloat(), cy + (tickOuterR * sin(rad)).toFloat()),
                        end         = Offset(cx + (innerR * cos(rad)).toFloat(), cy + (innerR * sin(rad)).toFloat()),
                        strokeWidth = if (isMajor) 1.5.dp.toPx() else 0.8.dp.toPx()
                    )
                }
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier            = Modifier.offset(y = (-4).dp)
            ) {
                Text(
                    text          = "%.0f".format(speedDisplay),
                    color         = contentColor,
                    fontSize      = if (tachoVisible) 30.sp else 34.sp,
                    letterSpacing = (-1).sp
                )
                Text(
                    text          = unitLabel,
                    color         = contentColor.copy(alpha = subAlpha),
                    fontSize      = 8.sp,
                    letterSpacing = 2.sp
                )
                if (tachoVisible) {
                    Text(
                        text          = "%.0f".format(rpmDisplay),
                        color         = accent.copy(alpha = 0.8f),
                        fontSize      = 11.sp,
                        letterSpacing = 1.sp
                    )
                }
            }
        }
    }
}
