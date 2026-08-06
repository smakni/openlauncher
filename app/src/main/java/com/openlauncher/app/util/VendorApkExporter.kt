package com.openlauncher.app.util

import android.content.Context
import java.io.File

/**
 * Copies a vendor APK out of the system image so its interfaces can be read.
 *
 * The vehicle data on these units sits behind com.syu.ms/ToolkitService, whose
 * AIDL is not published anywhere. Its method signatures cannot be guessed —
 * a binder transaction built against the wrong ones fails silently rather than
 * reporting a mismatch — and the unit has no reachable ADB to pull the APK with.
 *
 * The APK is world readable and its path comes from the package manager, so the
 * launcher can copy it somewhere the file can be collected by hand, and the
 * interface read from the dex instead of guessed at.
 */
object VendorApkExporter {

    /** Packages worth exporting, in the order they are most likely to help. */
    val CANDIDATES = listOf("com.syu.ms", "com.syu.canbus", "com.syu.carradio")

    fun exportDir(context: Context): File =
        File(context.getExternalFilesDir(null), "vendor").apply { mkdirs() }

    /**
     * Copies every candidate that is installed, returning a line per package
     * describing what happened — including failures, since a package being
     * absent or unreadable is itself worth knowing.
     */
    fun exportAll(context: Context): List<String> = CANDIDATES.map { pkg ->
        runCatching {
            val sourceDir = context.packageManager
                .getApplicationInfo(pkg, 0)
                .sourceDir
            val source = File(sourceDir)
            val target = File(exportDir(context), "$pkg.apk")
            source.inputStream().use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            "$pkg -> ${target.length() / 1024} KB"
        }.getOrElse { "$pkg -> ${it.javaClass.simpleName}" }
    }
}
