package com.openlauncher.app.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
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

    /**
     * Archive formats MapLibre can read in place.
     *
     * PMTiles only. The native library carries no MBTiles support whatever —
     * searching the binary finds the one and not the other — so accepting an
     * .mbtiles import only ever produced a file nothing could open.
     */
    private val SUPPORTED = setOf("pmtiles")

    fun baseDir(context: Context): File {
        val external = context.getExternalFilesDir(null)
        // Falls back to internal storage only if external is genuinely absent,
        // which keeps the map working even though importing would not.
        val parent = external ?: context.filesDir
        return File(parent, "maps").apply { mkdirs() }
    }

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

    /**
     * The archive the map should render, or null when none is installed.
     *
     * PMTiles wins over MBTiles when both are present: it is the vector format,
     * so it covers far more ground per megabyte and is what the bundled style is
     * written against.
     */
    fun installedPmTiles(context: Context): File? =
        baseDir(context).listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in SUPPORTED }
            ?.minByOrNull { if (it.extension.lowercase() == "pmtiles") 0 else 1 }

    /**
     * Writes the bundled style out with its source resolved, and returns a URI
     * MapLibre can fetch.
     *
     * Both the map view and the region downloader need to name the same style,
     * and the downloader can only take a URL — an asset with an unresolved
     * placeholder is no use to it. Materialising the patched copy once gives
     * both a single file to point at.
     *
     * An installed archive wins over the remote build: it is already on the
     * device, so it costs nothing to read and works with no connection at all.
     */
    /**
     * Whether anything can be drawn: an archive on disk, or a remote build to
     * pull from. A downloaded region lives in MapLibre's own store rather than
     * as a file here, so the archive alone is not the test.
     */
    fun hasAnySource(
        context: Context,
        remotePmTilesUrl: String,
        tileUrlTemplate: String = ""
    ): Boolean =
        installedPmTiles(context) != null ||
            remotePmTilesUrl.isNotBlank() ||
            tileUrlTemplate.isNotBlank() ||
            // Downloaded tiles are a source in their own right, and the only one
            // that survives losing the connection.
            File(baseDir(context), "tiles").listFiles()?.any { it.isDirectory } == true

    fun resolvedStyleUri(
        context: Context,
        remotePmTilesUrl: String,
        tileUrlTemplate: String = "",
        tilted: Boolean = false,
        showPlaces: Boolean = false,
        localTileTemplate: String = ""
    ): String {
        val archive = installedPmTiles(context)
        // A tile template wins over everything: it is the only source the region
        // downloader can enumerate, so choosing it is choosing that feature.
        // Otherwise an installed archive beats the remote build, being local.
        val sourceJson = when {
            // Downloaded tiles win outright: having them is the whole point of
            // downloading them, and preferring the network would leave the map
            // as dependent on a connection as before.
            localTileTemplate.isNotBlank() ->
                // The declared maximum has to be the deepest level actually on
                // disk. Claiming fifteen when the download stopped at thirteen
                // makes the renderer ask for tiles that were never fetched, and
                // those areas come out blank instead of being drawn larger from
                // the level that does exist.
                """"tiles": ["$localTileTemplate"], "maxzoom": ${
                    localTileMaxZoom(context)
                }"""
            tileUrlTemplate.isNotBlank() ->
                """"tiles": ["${tileUrlTemplate.replace("\"", "\\\"")}"], "maxzoom": 15"""
            archive != null -> """"url": "pmtiles://file://${archive.absolutePath}""""
            else            -> """"url": "pmtiles://$remotePmTilesUrl""""
        }
        val styleFile = File(baseDir(context), "style.json")
        val json = context.assets.open("map-style.json").bufferedReader().use { it.readText() }
            .replace("\"url\": \"__PMTILES_URL__\"", sourceJson)

        // Layer surgery is done on the parsed document rather than by patching
        // text: the flat and raised building layers are not interchangeable
        // strings, and a style that fails to parse renders as nothing at all.
        val patched = runCatching { withLayers(json, tilted, showPlaces) }.getOrDefault(json)
        styleFile.writeText(patched)
        return "file://${styleFile.absolutePath}"
    }

    /**
     * Raises the buildings and adds points of interest, as asked for.
     *
     * Buildings are only extruded when the camera is tilted. Seen from directly
     * above, an extrusion is indistinguishable from the flat shape it replaces,
     * so drawing one would cost frames and change nothing — and frames are not
     * abundant on this hardware.
     */
    private fun withLayers(json: String, tilted: Boolean, showPlaces: Boolean): String {
        val root = JSONObject(json)
        val layers = root.getJSONArray("layers")
        val rebuilt = JSONArray()

        for (i in 0 until layers.length()) {
            val layer = layers.getJSONObject(i)
            if (tilted && layer.optString("id") == "buildings") {
                rebuilt.put(JSONObject(BUILDINGS_3D))
            } else {
                rebuilt.put(layer)
            }
        }

        if (showPlaces) {
            // Labels only, appended last so they sit above everything.
            //
            // There was a circle layer under them and it had to go. Circles carry
            // no collision detection while labels do, so at close range almost
            // every name was dropped for overlapping and the map filled with
            // orphaned dots — and a dot with no name says nothing at all.
            rebuilt.put(JSONObject(POI_LABELS))
        }

        root.put("layers", rebuilt)
        return root.toString()
    }

    /**
     * Heights come from the data where it has them and fall back to a storey or
     * two — a building drawn at zero height is a hole in the skyline, which
     * reads worse than a wrong but plausible guess.
     */
    private const val BUILDINGS_3D = """
        {"id": "buildings", "type": "fill-extrusion", "source": "protomaps",
         "source-layer": "buildings", "minzoom": 14,
         "paint": {
           "fill-extrusion-color": "#2b323d",
           "fill-extrusion-height": ["coalesce", ["get", "height"], 8],
           "fill-extrusion-base": ["coalesce", ["get", "min_height"], 0],
           "fill-extrusion-opacity": 0.92
         }}
    """

    private const val POI_LABELS = """
        {"id": "poi-labels", "type": "symbol", "source": "protomaps",
         "source-layer": "pois", "minzoom": 16,
         "filter": ["has", "name"],
         "layout": {
           "text-field": ["get", "name"],
           "text-padding": 6,
           "text-font": ["Noto Sans Regular"],
           "text-size": 10,
           "text-anchor": "top",
           "text-offset": [0, 0.6],
           "text-max-width": 8
         },
         "paint": {
           "text-color": "#a8b2c0",
           "text-halo-color": "#12151a",
           "text-halo-width": 1.2
         }}
    """

    /** The deepest zoom directory present under the downloaded tiles. */
    fun localTileMaxZoom(context: Context): Int =
        File(baseDir(context), "tiles").listFiles()
            ?.mapNotNull { it.name.toIntOrNull() }
            ?.maxOrNull()
            ?: 15

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
