package com.openlauncher.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openlauncher.app.util.SyuProbe
import kotlinx.coroutines.delay

/** How long an id stays highlighted after moving. */
private const val HIGHLIGHT_MS = 3000L

/**
 * Live view of the vendor service's values.
 *
 * Identifying an id from exported files needs one action per run and a round
 * trip to read the result, which falls apart as soon as two things are changed
 * together — as it did. Watching the values move while turning the knob that
 * causes them does the same job in seconds: whichever line lights up is the
 * answer.
 */
@Composable
fun SyuLiveDialog(probe: SyuProbe, accent: Color, onDismiss: () -> Unit) {
    val readings by probe.live.collectAsState()
    val changedAt by probe.lastChangedAt.collectAsState()
    val status by probe.status.collectAsState()

    // Drives the highlight fading out; without a ticker the rows would only
    // repaint when some other value happened to arrive.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(250)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "SYU LIVE — most recent change first",
                fontSize = 12.sp,
                letterSpacing = 2.sp,
                color = accent
            )
        },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(status, fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = accent)

                // Newest change first, so operating a control puts its id at the
                // top of the list rather than leaving it to be spotted somewhere
                // in a fixed ordering. Ids that have never moved sink to the
                // bottom and stay out of the way.
                val ordered = readings.sortedByDescending { changedAt[it.module to it.id] ?: 0L }

                ordered.forEach { reading ->
                    val changed = changedAt[reading.module to reading.id] ?: 0L
                    val age = now - changed
                    val recent = changed > 0L && age < HIGHLIGHT_MS
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            if (changed > 0L) "%4.1fs".format(age / 1000.0) else "   —",
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            color = if (recent) accent
                                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                            modifier = Modifier.fillMaxWidth(0.14f)
                        )
                        Text(
                            "m${reading.module} id${reading.id}",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            // Recently changed lines take the accent, so the one
                            // reacting to the knob is obvious without reading values.
                            color = if (recent) accent
                                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                            modifier = Modifier.fillMaxWidth(0.3f)
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
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = if (recent) accent else MaterialTheme.colorScheme.onSurface
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

