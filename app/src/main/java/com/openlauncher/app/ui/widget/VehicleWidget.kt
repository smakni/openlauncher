package com.openlauncher.app.ui.widget

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openlauncher.app.model.ObdStatus
import com.openlauncher.app.model.VehicleState

/**
 * Live engine data read from the OBD-II port.
 *
 * Only readings the car actually answered are drawn. Every field of
 * [VehicleState] is nullable because an ECU answers a different subset of PIDs
 * depending on engine and model year, and a missing sensor rendered as 0 would
 * read as a real measurement — a coolant gauge showing 0°C looks like a fault
 * rather than an absent sensor.
 */
@Composable
fun VehicleWidget(
    state: VehicleState,
    status: ObdStatus,
    accent: Color,
    metric: Boolean = true,
    isDayMode: Boolean = false,
    modifier: Modifier = Modifier
) {
    val labelColor = if (isDayMode) Color(0xFF888888) else Color(0xFF7A7A7A)
    val valueColor = if (isDayMode) Color(0xFF111111) else MaterialTheme.colorScheme.onBackground

    val readings = buildList {
        state.rpm?.let { add("RPM" to "$it") }
        state.speedKph?.let {
            if (metric) add("SPEED" to "$it km/h")
            else add("SPEED" to "${(it * 0.621371f).toInt()} mph")
        }
        state.coolantTempC?.let { add("COOLANT" to tempText(it, metric)) }
        state.oilTempC?.let { add("OIL" to tempText(it, metric)) }
        state.boostBar?.let { add("BOOST" to "%+.2f bar".format(it)) }
        state.consumptionLph?.let { add("FUEL" to "%.1f L/h".format(it)) }
        state.engineLoadPct?.let { add("LOAD" to "%.0f%%".format(it)) }
        state.throttlePct?.let { add("THROTTLE" to "%.0f%%".format(it)) }
        state.intakeTempC?.let { add("INTAKE" to tempText(it, metric)) }
        state.fuelLevelPct?.let { add("TANK" to "%.0f%%".format(it)) }
        state.batteryVolts?.let { add("BATTERY" to "%.1f V".format(it)) }
    }

    Box(modifier = modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
        if (readings.isEmpty()) {
            // Nothing to show yet. Which of these it is matters to the driver:
            // an unreachable adapter usually just means the ignition is off.
            Text(
                text = when (status) {
                    ObdStatus.DISABLED     -> "OBD OFF\nEnable it in Settings"
                    ObdStatus.CONNECTING   -> "CONNECTING"
                    ObdStatus.CONNECTED    -> "NO DATA\nCar not answering"
                    ObdStatus.DISCONNECTED -> "NO ADAPTER\nIgnition off?"
                },
                color         = labelColor,
                fontSize      = 10.sp,
                lineHeight    = 14.sp,
                letterSpacing = 1.sp,
                textAlign     = TextAlign.Center,
                modifier      = Modifier.align(Alignment.Center)
            )
        } else {
            // Scrollable: how many readings appear depends on what the car
            // supports, so the list can outgrow a single grid cell.
            Column(
                modifier            = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                readings.forEach { (label, value) ->
                    Row(
                        modifier              = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment     = Alignment.CenterVertically
                    ) {
                        Text(
                            label,
                            color         = labelColor,
                            fontSize      = 9.sp,
                            letterSpacing = 1.sp,
                            fontFamily    = FontFamily.Monospace
                        )
                        Text(
                            value,
                            color      = valueColor,
                            fontSize   = 12.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}

private fun tempText(celsius: Int, metric: Boolean): String =
    if (metric) "$celsius°C" else "${(celsius * 9 / 5) + 32}°F"
