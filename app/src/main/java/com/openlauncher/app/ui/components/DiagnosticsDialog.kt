package com.openlauncher.app.ui.components

import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * On-screen replacement for tools/headunit-recon.sh.
 *
 * Head units expose their USB ports in host mode, so they cannot be reached over
 * a USB cable, and network ADB needs a pairing code the unit does not always
 * offer. This shows the same facts the recon script collects — display geometry,
 * sensors, vendor radio packages, Bluetooth — so they can be read straight off
 * the dashboard and photographed.
 */
@Composable
fun DiagnosticsDialog(accent: Color, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val rows = remember { collectDiagnostics(context) }

    // Compose reports these two, and they are the numbers the widget grid is
    // actually laid out against — worth keeping next to the raw metrics in case
    // the two disagree.
    val allRows = remember(rows) {
        listOf("compose dp" to "${configuration.screenWidthDp} x ${configuration.screenHeightDp}") + rows
    }
    var exportResult by remember { mutableStateOf<String?>(null) }

    // CreateDocument rather than a fixed path: it needs no storage permission and
    // lets the destination be picked, including a USB stick — which on a head
    // unit is usually the only practical way to get a file off the device.
    val exporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        exportResult = if (uri == null) null else runCatching {
            context.contentResolver.openOutputStream(uri)?.use { stream ->
                stream.write(asPlainText(allRows).toByteArray())
            } ?: error("no stream")
            "saved"
        }.getOrElse { "save failed" }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("HEAD UNIT DIAGNOSTICS", fontSize = 12.sp, letterSpacing = 2.sp, color = accent) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                exportResult?.let {
                    Text(it, color = accent, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.height(4.dp))
                }
                allRows.forEach { (label, value) -> DiagRow(label, value, accent) }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("CLOSE", color = accent, fontSize = 11.sp) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                    clipboard?.setPrimaryClip(
                        android.content.ClipData.newPlainText("diagnostics", asPlainText(allRows))
                    )
                    exportResult = "copied to clipboard"
                }) { Text("COPY", color = accent, fontSize = 11.sp) }

                TextButton(onClick = {
                    // Launching the picker is what can fail: head unit ROMs often
                    // ship without a documents provider, and the intent then
                    // resolves to nothing and throws. Falling back to a fixed
                    // path means the file still gets written on those units.
                    exportResult = runCatching {
                        exporter.launch("headunit-diagnostics.txt")
                        null
                    }.getOrElse { writeToAppStorage(context, allRows) }
                }) { Text("EXPORT", color = accent, fontSize = 11.sp) }
            }
        }
    )
}

/**
 * Writes the diagnostics to app storage and returns where they landed.
 *
 * Used when no documents provider answers the picker intent. External files are
 * reachable from a file manager and over USB, unlike internal storage, so the
 * result can still be got off the unit — which is the whole point of exporting.
 */
private fun writeToAppStorage(context: Context, rows: List<Pair<String, String>>): String =
    runCatching {
        val dir = context.getExternalFilesDir(null) ?: error("no external storage")
        val file = java.io.File(dir, "headunit-diagnostics.txt")
        file.writeText(asPlainText(rows))
        "saved to ${file.absolutePath}"
    }.getOrElse { "no file picker, and saving failed: ${it.message}" }

/**
 * Flattens the rows into a text file.
 *
 * Multi-line values are indented under their label rather than run together, so
 * a package list or a settings dump stays readable once out of the dialog.
 */
private fun asPlainText(rows: List<Pair<String, String>>): String = buildString {
    append("Open Launcher — head unit diagnostics\n")
    append("=".repeat(44)).append('\n')
    for ((label, value) in rows) {
        val lines = value.split('\n')
        append(label.padEnd(18)).append(lines.first()).append('\n')
        for (extra in lines.drop(1)) append(" ".repeat(18)).append(extra).append('\n')
    }
}

@Composable
private fun DiagRow(label: String, value: String, accent: Color) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            label,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            modifier = Modifier.fillMaxWidth(0.38f)
        )
        Text(
            value,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/**
 * Substrings worth surfacing from the settings provider. "ill" catches the
 * illumination signal — the headlight line every car stereo is wired to, and how
 * the Evoque's own system knows to switch its display to night colours.
 */
private val VEHICLE_KEY_HINTS = listOf(
    "ill", "light", "lamp", "night", "day", "dim",
    "speed", "rpm", "canbus", "can_", "mcu", "acc_", "reverse", "brake", "door", "temp",
    "sys_", "car"
)

private fun collectDiagnostics(context: Context): List<Pair<String, String>> = buildList {
    val metrics = context.resources.displayMetrics
    // First line on purpose: the version name carries the build timestamp, and
    // this is the only way to confirm which build is actually on the unit —
    // every APK shares a package name and installs over the last one.
    add("build" to runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
    }.getOrDefault("unknown"))
    add("model" to "${Build.MANUFACTURER} ${Build.MODEL}")
    add("board" to "${Build.BOARD} / ${Build.HARDWARE}")
    add("android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")

    add("resolution" to "${metrics.widthPixels} x ${metrics.heightPixels} px")
    add("densityDpi" to "${metrics.densityDpi}")
    add("density" to "%.2f".format(metrics.density))
    add("size in dp" to "${(metrics.widthPixels / metrics.density).toInt()} x " +
        "${(metrics.heightPixels / metrics.density).toInt()}")

    // The app window can be smaller than the panel on units that keep a system
    // bar; the grid has to be sized against the window, not the panel.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        runCatching {
            val bounds = context.getSystemService(android.view.WindowManager::class.java)
                .maximumWindowMetrics.bounds
            add("panel bounds" to "${bounds.width()} x ${bounds.height()} px")
        }
    }

    val sensors = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    fun sensorState(type: Int) = if (sensors?.getDefaultSensor(type) != null) "yes" else "NO"
    // No magnetometer means the compass widget can never work and heading has to
    // come from consecutive GPS fixes instead.
    add("magnetometer" to sensorState(Sensor.TYPE_MAGNETIC_FIELD))
    add("accelerometer" to sensorState(Sensor.TYPE_ACCELEROMETER))
    add("gyroscope" to sensorState(Sensor.TYPE_GYROSCOPE))

    val bt = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    add("bluetooth" to if (bt != null) "present" else "NONE")
    add("bonded devices" to runCatching { "${bt?.bondedDevices?.size ?: 0}" }.getOrDefault("no permission"))

    val pm = context.packageManager
    val installed = runCatching {
        pm.getInstalledPackages(0).map { it.packageName }
    }.getOrDefault(emptyList())
    add("packages total" to "${installed.size}")

    // The radio backend the launcher picks depends entirely on which of these
    // exist, so list them verbatim rather than reducing to a yes/no.
    val vendor = installed.filter { pkg ->
        listOf("radio", "mcu", "canbus", "tuner", "szchoiceway", "fyt", "carplay", "autokit", "zlink")
            .any { pkg.contains(it, ignoreCase = true) }
    }
    add("vendor pkgs" to if (vendor.isEmpty()) "none found" else vendor.take(6).joinToString("\n"))
    add("szchoiceway" to if (installed.any { it.startsWith("com.szchoiceway") }) "YES" else "no")

    // The FYT/SYU platform talks to its MCU and CAN decoder over bound services
    // and broadcasts whose names are not documented anywhere. Listing the actual
    // exported components is what makes it possible to integrate against real
    // names instead of guessing at an interface.
    for (pkg in listOf("com.syu.canbus", "com.syu.carradio", "com.syu.ipc", "com.fyt.screenbutton")) {
        val components = runCatching {
            @Suppress("DEPRECATION")
            val info = pm.getPackageInfo(
                pkg,
                PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS or
                    PackageManager.GET_PROVIDERS
            )
            buildList {
                info.services?.forEach { add("S ${it.name.substringAfterLast('.')}") }
                info.receivers?.forEach { add("R ${it.name.substringAfterLast('.')}") }
                info.providers?.forEach { add("P ${it.authority}") }
            }
        }.getOrNull()

        add(
            pkg.substringAfterLast('.') to when {
                components == null      -> "not installed"
                components.isEmpty()    -> "no components"
                else                    -> components.take(8).joinToString("\n")
            }
        )
    }

    // Vendor state often lands in the settings provider rather than in an API —
    // the upstream szchoiceway radio support already reads SYS_MEDIA_INFO_JSON
    // that way. With no provider or bindable interface on this unit's CAN
    // package, this is the remaining place vehicle state could be readable.
    // System is listed in full rather than filtered. handbrake_status showed up
    // there, which proves the CAN decoder publishes into this table — and a
    // keyword filter would hide any vendor key that happens not to match. The
    // table is short enough to read whole.
    for ((name, uri, filtered) in listOf(
        Triple("settings.system", android.provider.Settings.System.CONTENT_URI, false),
        Triple("settings.global", android.provider.Settings.Global.CONTENT_URI, true)
    )) {
        val hits = runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameCol = cursor.getColumnIndex("name")
                val valueCol = cursor.getColumnIndex("value")
                if (nameCol < 0) return@use emptyList<String>()
                buildList {
                    while (cursor.moveToNext()) {
                        val key = cursor.getString(nameCol) ?: continue
                        if (filtered && VEHICLE_KEY_HINTS.none { key.contains(it, ignoreCase = true) }) {
                            continue
                        }
                        val value = if (valueCol >= 0) cursor.getString(valueCol) else null
                        add(if (value.isNullOrEmpty()) key else "$key=${value.take(20)}")
                    }
                }
            }
        }.getOrNull()

        add(
            name to when {
                hits == null   -> "not readable"
                hits.isEmpty() -> "no keys"
                else           -> hits.sorted().joinToString("\n")
            }
        )
    }

    // Every com.syu / com.fyt package, unfiltered. The earlier keyword filter
    // matched on radio, canbus, mcu and the like, which would never have matched
    // com.syu.ms — the package that actually owns the vehicle data service, and
    // of which com.syu.canbus is itself only a client.
    add("syu/fyt packages" to run {
        val vendor = installed.filter { it.startsWith("com.syu.") || it.startsWith("com.fyt.") }
        if (vendor.isEmpty()) "none" else vendor.sorted().joinToString("\n")
    })

    // Resolving the action says whether the toolkit service exists here without
    // binding to it, which keeps this dialog synchronous.
    add("syu toolkit" to runCatching {
        val services = pm.queryIntentServices(android.content.Intent("com.syu.ms.toolkit"), 0)
        if (services.isEmpty()) "action resolves to nothing" else services.joinToString("\n") {
            "${it.serviceInfo.packageName}/${it.serviceInfo.name.substringAfterLast('.')}" +
                if (it.serviceInfo.exported) " exported" else " NOT exported"
        }
    }.getOrElse { "query failed" })

    // The system status bar shows an outside temperature, so something on the
    // device supplies it, but no key named for it turned up. Search by value
    // instead: anything sitting in a plausible temperature range, in degrees or
    // in tenths, across all three settings tables. This finds the key whatever
    // it happens to be called.
    val temperatureCandidates = buildList {
        for ((table, uri) in listOf(
            "sys" to android.provider.Settings.System.CONTENT_URI,
            "sec" to android.provider.Settings.Secure.CONTENT_URI,
            "glb" to android.provider.Settings.Global.CONTENT_URI
        )) {
            runCatching {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameCol = cursor.getColumnIndex("name")
                    val valueCol = cursor.getColumnIndex("value")
                    if (nameCol < 0 || valueCol < 0) return@use
                    while (cursor.moveToNext()) {
                        val key = cursor.getString(nameCol) ?: continue
                        val raw = cursor.getString(valueCol) ?: continue
                        val number = raw.replace(',', '.').toFloatOrNull() ?: continue
                        val looksLikeCelsius = number in 5f..45f && raw.contains('.')
                        val looksLikeTenths = number in 50f..450f && !raw.contains('.')
                        if (looksLikeCelsius || looksLikeTenths) add("$table $key=$raw")
                    }
                }
            }
        }
    }
    add(
        "temp candidates" to
            if (temperatureCandidates.isEmpty()) "none" else temperatureCandidates.sorted().joinToString("\n")
    )

    // Secure was never listed, unlike System and Global. Shown whole for the
    // same reason System is: a vendor key can be named anything.
    add("settings.secure" to runCatching {
        context.contentResolver.query(
            android.provider.Settings.Secure.CONTENT_URI, null, null, null, null
        )?.use { cursor ->
            val nameCol = cursor.getColumnIndex("name")
            val valueCol = cursor.getColumnIndex("value")
            if (nameCol < 0) return@use "unreadable"
            buildList {
                while (cursor.moveToNext()) {
                    val key = cursor.getString(nameCol) ?: continue
                    val value = if (valueCol >= 0) cursor.getString(valueCol) else null
                    add(if (value.isNullOrEmpty()) key else "$key=${value.take(16)}")
                }
            }.sorted().joinToString("\n")
        } ?: "no rows"
    }.getOrElse { "not readable" })

    // The radio deck mirrors whatever the vendor radio app publishes as a media
    // session, and parses band and frequency out of its title and artist. A
    // session is not a declared component, so the only way to know whether one
    // exists — and what its metadata actually looks like — is to read it here.
    add("media sessions" to runCatching {
        val manager = context.getSystemService(android.media.session.MediaSessionManager::class.java)
        val listener = android.content.ComponentName(
            context, com.openlauncher.app.service.MediaListenerService::class.java
        )
        val sessions = manager.getActiveSessions(listener)
        if (sessions.isEmpty()) "none active" else sessions.joinToString("\n") { controller ->
            val md = controller.metadata
            val title = md?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE).orEmpty()
            val artist = md?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
            val state = controller.playbackState?.state?.toString() ?: "-"
            "${controller.packageName}\n  t=${title.take(22)}\n  a=${artist.take(22)}\n  st=$state"
        }
    }.getOrElse { "needs notification access" })

    add("home app" to runCatching {
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_HOME)
        pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName ?: "unknown"
    }.getOrDefault("unknown"))
}
