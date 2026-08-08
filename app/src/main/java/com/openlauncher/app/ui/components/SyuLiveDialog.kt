package com.openlauncher.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openlauncher.app.util.SyuProbe
import kotlinx.coroutines.delay

/**
 * Protocol numbers the vendor CAN database lists for Land Rover, by id_value.
 *
 * The MCU holds the selected protocol as a plain number, and these are the only
 * two the database offers for this brand. Naming them turns one line of the
 * sweep into an answer the factory menu would otherwise have to be opened to
 * read.
 *
 * The distinction matters beyond curiosity — only the second routes the car's
 * own reversing camera, so a unit set to the first shows a black screen with
 * nothing wrong in the wiring.
 */
private val KNOWN_PROTOCOLS = mapOf(
    411 to "LAND ROVER — base",
    65947 to "LAND ROVER — high, original reversing camera"
)

/**
 * Identifies a vendor id by isolating one action.
 *
 * A live list of every value the service reports was the first attempt and does
 * not work in a car: dozens of ids re-send constantly, the interesting one is on
 * screen for a moment, and it has to be spotted while operating the control that
 * caused it — eyes on the screen, in the driver's seat.
 *
 * So the dialog is built around a window instead. Arm it, do the one thing,
 * stop it, and what remains is whatever moved in between. That turns reading
 * into a question with an answer, and it can be done by feel without watching.
 */
@Composable
fun SyuLiveDialog(probe: SyuProbe, accent: Color, onDismiss: () -> Unit) {
    val capturing by probe.capturing.collectAsState()
    val captured by probe.captured.collectAsState()
    val status by probe.status.collectAsState()

    // Elapsed time while armed, so an untouched capture is visibly running
    // rather than looking like the button did nothing.
    var elapsed by remember { mutableLongStateOf(0L) }
    LaunchedEffect(capturing) {
        elapsed = 0L
        while (capturing) {
            delay(250)
            elapsed += 250
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (capturing) "RECORDING — DO THE ACTION NOW" else "IDENTIFY A CONTROL",
                fontSize = 12.sp,
                letterSpacing = 2.sp,
                color = accent
            )
        },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    if (capturing) {
                        "Turn the volume, or switch the lights, or select reverse — " +
                            "one thing only. Then press STOP.\n\n%.1fs".format(elapsed / 1000.0)
                    } else {
                        "Press START, perform one action, press STOP. " +
                            "Only what changed in between is listed."
                    },
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )

                CaptureButton(
                    label = if (capturing) "STOP" else "START",
                    accent = accent,
                    onClick = { if (capturing) probe.endCapture() else probe.beginCapture() }
                )

                Text(status, fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = accent)

                if (!capturing && captured.isEmpty()) {
                    Text(
                        "No capture yet.",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )
                }

                // A short list is the point: if it holds one line, that line is
                // the answer. A long one means more than one thing moved, and the
                // capture is worth repeating rather than puzzled over.
                captured.forEach { reading ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "m${reading.module} id${reading.id}",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = accent,
                            modifier = Modifier.fillMaxWidth(0.35f)
                        )
                        Text(
                            buildString {
                                if (reading.ints.isNotEmpty()) append(reading.ints.joinToString(","))
                                if (reading.floats.isNotEmpty()) {
                                    if (isNotEmpty()) append("  ")
                                    append(reading.floats.joinToString(","))
                                }
                                if (reading.strings.isNotEmpty()) {
                                    if (isNotEmpty()) append("  ")
                                    append(reading.strings.joinToString(","))
                                }
                            },
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    reading.ints.firstNotNullOfOrNull { KNOWN_PROTOCOLS[it] }?.let { protocol ->
                        Text(
                            "      └ $protocol",
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            color = accent
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("CLOSE", color = accent, fontSize = 11.sp) }
        }
    )
}

@Composable
private fun CaptureButton(label: String, accent: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(accent.copy(alpha = 0.18f))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = accent, fontSize = 15.sp, letterSpacing = 3.sp)
    }
}
