package com.openlauncher.app.util

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
import kotlin.math.asinh
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.tan

/**
 * Downloads map tiles to disk, and nothing more clever than that.
 *
 * MapLibre's own offline manager was given several attempts and never fetched
 * so much as the style: one status callback with the style still outstanding,
 * then silence, with no error to act on. Its internals are not observable from
 * here, and there is no ADB on this unit to make them so.
 *
 * The native library turns out to contain no MBTiles support at all, so that
 * container was never an option either. But the glyphs load over asset://, a
 * scheme equally absent from the binary — which places scheme handling in the
 * Java layer, and means plain files under a file:// template are readable. That
 * removes the need for any container: tiles are just files, laid out as the
 * template names them.
 *
 * Every step is therefore visible. A tile either arrived with a status code or
 * it did not, and the log says which.
 */
class TileDownloader(private val context: Context) {

    private val _state = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val state: StateFlow<DownloadState> = _state

    private var job: Job? = null
    private val log = StringBuilder()

    /** Where the template points, and where tiles are written. */
    fun tileDir(context: Context): File =
        File(OfflineMapStore.baseDir(context), "tiles").apply { mkdirs() }

    /** The template a downloaded region is served through. */
    fun localTemplate(context: Context): String =
        "file://${tileDir(context).absolutePath}/{z}/{x}/{y}.mvt"

    /** Whether anything has been downloaded, without walking the whole tree. */
    fun hasTiles(context: Context): Boolean =
        runCatching { tileDir(context).listFiles()?.any { it.isDirectory } == true }
            .getOrDefault(false)

    fun cancel() {
        job?.cancel()
        job = null
        _state.value = DownloadState.Idle
    }

    fun download(
        scope: CoroutineScope,
        template: String,
        centreLat: Double,
        centreLon: Double,
        radiusKm: Double
    ) {
        if (template.isBlank()) {
            _state.value = DownloadState.Failed("no tile URL set — see Maps › Tile URL")
            return
        }
        job?.cancel()

        job = scope.launch(Dispatchers.IO) {
            synchronized(log) { log.setLength(0) }
            note("Tile download")
            note("=".repeat(52))
            note("template : ${template.replace(Regex("key=[^&]*"), "key=…")}")
            note("centre   : $centreLat, $centreLon")
            note("radius   : $radiusKm km")
            note("zoom     : $MIN_ZOOM..$MAX_ZOOM")

            val latDelta = radiusKm / KM_PER_DEGREE_LAT
            // Longitude degrees shrink towards the poles, so the east-west span
            // is divided by the cosine of the latitude — without it the box comes
            // out narrower than asked for anywhere far from the equator.
            val lonDelta = radiusKm /
                (KM_PER_DEGREE_LAT * cos(Math.toRadians(centreLat)).coerceAtLeast(0.01))

            val wanted = mutableListOf<Triple<Int, Int, Int>>()
            for (z in MIN_ZOOM..MAX_ZOOM) {
                val xs = lonToX(centreLon - lonDelta, z)..lonToX(centreLon + lonDelta, z)
                // Y grows southward, so the northern edge gives the lower bound.
                val ys = latToY(centreLat + latDelta, z)..latToY(centreLat - latDelta, z)
                for (x in xs) for (y in ys) wanted += Triple(z, x, y)
            }
            note("tiles    : ${wanted.size}")
            note("")

            val done = AtomicInteger(0)
            val bytes = AtomicLong(0)
            val failures = AtomicInteger(0)
            val total = wanted.size

            // Chunked rather than one job per tile: thousands of simultaneous
            // connections would be refused by the server and exhaust the unit
            // before any of them completed.
            wanted.chunked(PARALLEL).forEach { batch ->
                if (!isActive) return@launch
                batch.map { (z, x, y) ->
                    async {
                        val result = fetch(template, z, x, y)
                        if (result == null) failures.incrementAndGet()
                        else bytes.addAndGet(result.toLong())
                        val n = done.incrementAndGet()
                        if (n % PROGRESS_EVERY == 0 || n == total) {
                            _state.value = DownloadState.Running(
                                percent = (100 * n / total).coerceIn(0, 100),
                                megabytes = bytes.get() / 1_048_576,
                                completed = n.toLong(),
                                required = total.toLong()
                            )
                        }
                        if (n % LOG_EVERY == 0) {
                            note("%6d/%-6d  %8d bytes  %d missing"
                                .format(n, total, bytes.get(), failures.get()))
                        }
                    }
                }.awaitAll()
            }

            val megabytes = bytes.get() / 1_048_576
            note("")
            note("done: ${done.get()} requested, ${failures.get()} missing, $megabytes MB")
            _state.value = when {
                bytes.get() == 0L -> DownloadState.Failed(
                    "every tile failed — check Test Tile URL"
                )
                else -> DownloadState.Done(megabytes)
            }
        }
    }

    /** Returns the bytes written, or null when the tile could not be had. */
    private fun fetch(template: String, z: Int, x: Int, y: Int): Int? {
        val target = File(tileDir(context), "$z/$x/$y.mvt")
        // Already present from an earlier run. Re-fetching would make a resumed
        // download cost as much as a fresh one.
        if (target.isFile && target.length() > 0) return target.length().toInt()

        val url = template
            .replace("{z}", z.toString())
            .replace("{x}", x.toString())
            .replace("{y}", y.toString())

        return runCatching {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("User-Agent", "openlauncher")
            }
            try {
                if (connection.responseCode != 200) return@runCatching null
                val body = connection.inputStream.readBytes()
                // An empty body is a tile with nothing in it — ocean, or outside
                // the data. Writing it would be indistinguishable from a fetch
                // that has not happened yet on the next run.
                if (body.isEmpty()) return@runCatching null
                target.parentFile?.mkdirs()
                target.writeBytes(body)
                body.size
            } finally {
                runCatching { connection.disconnect() }
            }
        }.getOrNull()
    }

    /** Removes every downloaded tile. */
    fun clear() {
        runCatching { tileDir(context).deleteRecursively() }
        _state.value = DownloadState.Idle
    }

    private fun note(line: String) {
        synchronized(log) {
            if (log.length > LOG_LIMIT) return
            log.appendLine(line)
            runCatching {
                val dir = File(context.getExternalFilesDir(null), "vendor").apply { mkdirs() }
                File(dir, "map-download.txt").writeText(log.toString())
            }
        }
    }

    private fun lonToX(lon: Double, z: Int): Int {
        val n = 1 shl z
        return floor((lon + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)
    }

    private fun latToY(lat: Double, z: Int): Int {
        val n = 1 shl z
        val clamped = lat.coerceIn(-85.05112878, 85.05112878)
        val rad = Math.toRadians(clamped)
        val y = (1.0 - asinh(tan(rad)) / PI) / 2.0 * n
        return floor(y).toInt().coerceIn(0, n - 1)
    }

    private companion object {
        const val MIN_ZOOM = 8
        const val MAX_ZOOM = 15
        const val KM_PER_DEGREE_LAT = 111.32

        /** Enough to keep the link busy without the server refusing the burst. */
        const val PARALLEL = 6

        const val PROGRESS_EVERY = 10
        const val LOG_EVERY = 200
        const val TIMEOUT_MS = 15_000
        const val LOG_LIMIT = 24_000
    }
}
