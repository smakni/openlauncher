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
 * Fuel remaining, in litres.
 *
 * A volume, not a percentage — the vendor's own car-information screen prints
 * this id as "%d L" with nothing applied to it. The percentage the earlier
 * version assumed would not have been caught by looking: a quarter of this car's
 * tank is about seventeen litres, and seventeen percent is a believable reading.
 *
 * The bar needs a capacity the car does not send, so it comes from settings. The
 * litres are what the car actually said and are shown as the reading; the bar is
 * a convenience drawn on top of an assumption, which is why the number is the
 * larger of the two.
 */
@Composable
fun FuelWidget(
    fuelLitresCan: Int?,
    fuelLevelPct: Float?,
    tankLitres: Int,
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

    // The decoder is preferred over the dongle here, unlike everywhere else:
    // litres are what the car measures, and the OBD percentage is derived from
    // the same float by a module that does not know the tank size either.
    val fraction = when {
        fuelLitresCan != null && tankLitres > 0 ->
            (fuelLitresCan.toFloat() / tankLitres).coerceIn(0f, 1f)
        fuelLevelPct != null -> (fuelLevelPct / 100f).coerceIn(0f, 1f)
        else -> null
    }

    // Below a sixth of a tank the bar turns amber. The dashboard has its own
    // warning lamp, but that one fires late and this is the widget being looked
    // at while a detour is still a choice.
    val barColor = if (fraction != null && fraction <= 0.15f) Color(0xFFD59A3C) else accent

    Column(
        modifier = modifier.padding(horizontal = 12.dp, vertical = 10.dp).fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("FUEL", color = labelColor, fontSize = 9.sp, letterSpacing = 2.sp)

        when {
            fuelLitresCan != null -> {
                Text(
                    "$fuelLitresCan",
                    color = barColor,
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Light
                )
                Text("LITRES", color = labelColor, fontSize = 9.sp, letterSpacing = 1.5.sp)
            }

            fuelLevelPct != null -> {
                Text(
                    "%.0f%%".format(fuelLevelPct),
                    color = barColor,
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Light
                )
                Text("FROM OBD", color = labelColor, fontSize = 9.sp, letterSpacing = 1.5.sp)
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

        fraction?.let { f ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(trackColor)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(f)
                        .fillMaxSize()
                        .clip(RoundedCornerShape(4.dp))
                        .background(barColor)
                )
            }
            if (fuelLitresCan != null) {
                Text(
                    "of $tankLitres L",
                    color = labelColor,
                    fontSize = 8.sp
                )
            }
        }
    }
}
