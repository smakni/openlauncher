package com.openlauncher.app.ui.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Fuel remaining, as a bar.
 *
 * The scale is the open question here, not the reading. The decoder declares a
 * fuel id and this car answers it, but nothing establishes what the number
 * means — a percentage, a byte fraction, litres, or a count of bars on the
 * dashboard gauge. A percentage is assumed where the value could be one, and
 * the raw number is printed underneath so the assumption can be checked against
 * the dashboard rather than trusted.
 *
 * Where the value cannot be a percentage the bar is withheld entirely. Drawing
 * a gauge from a scale known to be wrong is worse than drawing none: a wrong
 * fuel gauge is believed until the car stops.
 */
@Composable
fun FuelWidget(
    fuelLevelPct: Float?,
    fuelRawCan: Int?,
    canConnected: Boolean = false,
    accent: Color,
    isDayMode: Boolean = false,
    modifier: Modifier = Modifier
) {
    val labelColor =
        if (isDayMode) Color(0xFF6E6B66)
        else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f)
    val trackColor =
        if (isDayMode) Color(0xFFD9D5CE)
        else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.10f)

    // Below a sixth of a tank the bar turns amber. The dashboard has its own
    // warning lamp, but that one fires late and this is the widget that is
    // being looked at when a detour is still a choice.
    val low = fuelLevelPct != null && fuelLevelPct <= 15f
    val barColor = if (low) Color(0xFFD59A3C) else accent

    Column(
        modifier = modifier.padding(horizontal = 12.dp, vertical = 10.dp).fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("FUEL", color = labelColor, fontSize = 9.sp, letterSpacing = 2.sp)

        when {
            fuelLevelPct != null -> {
                Text(
                    "%.0f%%".format(fuelLevelPct),
                    color = barColor,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Light
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(trackColor)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth((fuelLevelPct / 100f).coerceIn(0f, 1f))
                            .fillMaxSize()
                            .clip(RoundedCornerShape(4.dp))
                            .background(barColor)
                    )
                }

                // Printed until the scale is confirmed, then removed. It is the
                // only thing that lets the assumption be tested: read it against
                // the dashboard gauge once, and either it agrees or it does not.
                fuelRawCan?.let {
                    Text("raw $it", color = labelColor, fontSize = 8.sp)
                }
            }

            // A value the decoder sent that cannot be a percentage. Shown as
            // itself rather than forced into a gauge, because knowing the scale
            // is wrong is the useful part.
            fuelRawCan != null -> {
                Text(
                    "$fuelRawCan",
                    color = accent,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Light
                )
                Text("raw · scale unknown", color = labelColor, fontSize = 8.sp)
            }

            else -> {
                Text("—", color = labelColor, fontSize = 30.sp)
                Text(
                    if (canConnected) "CAN silent on fuel" else "CAN not connected",
                    color = labelColor,
                    fontSize = 9.sp
                )
            }
        }
    }
}
