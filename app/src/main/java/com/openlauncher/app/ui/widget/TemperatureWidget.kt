package com.openlauncher.app.ui.widget

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The outside temperature, at a size that can be read at a glance.
 *
 * It appears in the vehicle widget too, as one line among a dozen. That is the
 * right place to compare it against coolant and intake, and the wrong place to
 * check whether it is freezing — which is the question actually asked of it, and
 * one that should not require finding a row in a list.
 *
 * The decoder sends half degrees and the display rounds them to whole ones. The
 * half is real, but it is not what the widget is for: the question is whether to
 * take a coat, and a decimal place on a windscreen-level reading suggests a
 * precision the sensor does not have.
 */
@Composable
fun TemperatureWidget(
    ambientTempC: Double?,
    canConnected: Boolean = false,
    tempSensorPresent: Boolean? = null,
    accent: Color,
    metric: Boolean = true,
    isDayMode: Boolean = false,
    modifier: Modifier = Modifier
) {
    val labelColor =
        if (isDayMode) Color(0xFF6E6B66)
        else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f)

    Column(
        modifier = modifier.padding(horizontal = 12.dp, vertical = 10.dp).fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "OUTSIDE",
            color = labelColor,
            fontSize = 9.sp,
            letterSpacing = 2.sp
        )

        if (ambientTempC == null) {
            // Distinguished from a reading of zero, which on this widget would be
            // a plausible winter temperature rather than an obvious blank.
            Text(
                "—",
                color = labelColor,
                fontSize = 34.sp,
                textAlign = TextAlign.Center
            )
            Text(
                when {
                    tempSensorPresent == false -> "no sensor on this car"
                    canConnected -> "CAN silent"
                    else -> "CAN not connected"
                },
                color = labelColor,
                fontSize = 9.sp
            )
        } else {
            val shown =
                if (metric) "%.0f".format(ambientTempC)
                else "%.0f".format(ambientTempC * 9 / 5 + 32)

            Text(
                shown,
                color = accent,
                fontSize = 40.sp,
                fontWeight = FontWeight.Light,
                textAlign = TextAlign.Center
            )
            Text(
                if (metric) "°C" else "°F",
                color = labelColor,
                fontSize = 11.sp,
                letterSpacing = 1.sp
            )

            // Below freezing is the one thing this widget exists to catch, so it
            // says so rather than leaving a small negative sign to carry it.
            if (ambientTempC <= 3.0) {
                Text(
                    if (ambientTempC <= 0.0) "ICE" else "NEAR FREEZING",
                    color = Color(0xFF6FB8E8),
                    fontSize = 10.sp,
                    letterSpacing = 1.5.sp
                )
            }
        }
    }
}
