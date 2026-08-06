package com.openlauncher.app.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/**
 * Where offline map archives live, and how one gets there.
 *
 * osmdroid scans its base directory for tile archives and serves them before
 * reaching for the network, so an archive dropped here makes an area available
 * with no connection at all — unlike the tile cache, which only ever holds
 * roads already driven.
 *
 * The directory sits under external files rather than internal storage: an
 * archive has to be put there by hand, and internal storage cannot be reached
 * by a file manager or from a USB stick, which is the only route onto a head
 * unit with no usable ADB.
 */
object OfflineMapStore {

    /** Extensions osmdroid's archive provider can read. */
    private val SUPPORTED = setOf("mbtiles", "sqlite", "zip", "gemf")

    fun baseDir(context: Context): File {
        val external = context.getExternalFilesDir(null)
        // Falls back to internal storage only if external is genuinely absent,
        // which keeps the map working even though importing would not.
        val parent = external ?: context.filesDir
        return File(parent, "osmdroid").apply { mkdirs() }
    }

    fun tileCacheDir(context: Context): File =
        File(baseDir(context), "tiles").apply { mkdirs() }

    /** Archives currently installed, by file name. The tile cache is not one. */
    fun installedArchives(context: Context): List<String> =
        baseDir(context).listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in SUPPORTED }
            ?.map { it.name }
            .orEmpty()
            .sorted()

    /**
     * Copies an archive into the base directory.
     *
     * Copied rather than referenced in place: osmdroid opens archives by file
     * path, and a document URI handed over by the picker is not one — and would
     * stop resolving once the USB stick it came from is unplugged.
     */
    fun import(context: Context, uri: Uri): Result<String> = runCatching {
        val name = displayName(context, uri)
        require(name.substringAfterLast('.', "").lowercase() in SUPPORTED) {
            "unsupported: .${name.substringAfterLast('.', "")}"
        }
        val target = File(baseDir(context), name)
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: error("cannot read file")
        name
    }

    fun remove(context: Context, name: String): Boolean =
        File(baseDir(context), name).takeIf { it.isFile }?.delete() ?: false

    private fun displayName(context: Context, uri: Uri): String {
        val fromProvider = runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
            }
        }.getOrNull()
        return (fromProvider ?: uri.lastPathSegment ?: "map.mbtiles")
            .substringAfterLast('/')
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
    }
}
