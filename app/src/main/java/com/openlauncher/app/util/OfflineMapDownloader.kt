package com.openlauncher.app.util

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.maplibre.android.MapLibre
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition

/** Progress of a region download, or why there is none. */
sealed interface DownloadState {
    data object Idle : DownloadState
    data class Running(
        val percent: Int,
        val megabytes: Long,
        // Reported so a stalled download can be told apart from one that
        // never had anything to fetch: a required count stuck at zero means
        // the style yielded no tiles to enumerate, not that it is slow.
        val completed: Long,
        val required: Long
    ) : DownloadState
    data class Done(val megabytes: Long) : DownloadState
    data class Failed(val reason: String) : DownloadState
}

/**
 * Downloads a square of map around a point for offline use.
 *
 * MapLibre keeps downloaded regions in its own store and serves them ahead of
 * the network, so this needs no separate archive file and no extraction step on
 * a computer — which is what the manual PMTiles import required.
 *
 * The zoom ceiling is the thing to watch. Every level up quadruples the tile
 * count, so a radius that is reasonable at zoom 14 becomes a very long download
 * at 17. The chosen range shows streets clearly without pulling building
 * outlines for a whole region.
 */
class OfflineMapDownloader(private val context: Context) {

    /**
     * Resolved on first use, not at construction.
     *
     * OfflineManager throws unless MapLibre has been initialised first, and this
     * class used to be built while the view model was, before any map code had
     * run — which took the whole launcher down at startup, on a device where the
     * launcher is the home screen. Nothing about the map is allowed to do that,
     * so initialisation is deferred and failure leaves the feature inert rather
     * than propagating.
     */
    private val manager: OfflineManager? by lazy {
        runCatching {
            MapLibre.getInstance(context)
            OfflineManager.getInstance(context)
        }.getOrNull()
    }

    private val _state = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val state: StateFlow<DownloadState> = _state

    fun download(styleUrl: String, centre: LatLng, radiusKm: Double) {
        val manager = this.manager
        if (manager == null) {
            _state.value = DownloadState.Failed("map engine unavailable")
            return
        }
        _state.value = DownloadState.Running(0, 0, 0, 0)

        val definition = OfflineTilePyramidRegionDefinition(
            styleUrl,
            boundsAround(centre, radiusKm),
            MIN_ZOOM,
            MAX_ZOOM,
            // Fixed at 1: these panels report a density of 1, and asking for a
            // higher ratio would download tiles at a detail the screen cannot show.
            1.0f
        )

        manager.createOfflineRegion(
            definition,
            "{\"name\":\"openlauncher\"}".toByteArray(),
            object : OfflineManager.CreateOfflineRegionCallback {
                override fun onCreate(region: OfflineRegion) {
                    region.setObserver(object : OfflineRegion.OfflineRegionObserver {
                        override fun onStatusChanged(status: OfflineRegionStatus) {
                            val megabytes = status.completedResourceSize / 1_048_576
                            _state.value = if (status.isComplete) {
                                // Downloading is left active until complete, then
                                // released: an active region keeps a connection
                                // open and would retry forever on a dead network.
                                region.setDownloadState(OfflineRegion.STATE_INACTIVE)
                                DownloadState.Done(megabytes)
                            } else {
                                val percent = if (status.requiredResourceCount > 0) {
                                    (100.0 * status.completedResourceCount /
                                        status.requiredResourceCount).toInt()
                                } else 0
                                // A required count of one means the style resolved
                                // and yielded no tiles behind it. That is what a
                                // PMTiles source does here: the archive is addressed
                                // by byte range, so there are no per-tile URLs for
                                // this to walk, and waiting longer changes nothing.
                                if (status.requiredResourceCount <= 1L) {
                                    region.setDownloadState(OfflineRegion.STATE_INACTIVE)
                                    // Deliberately not naming a cause: this fires
                                    // for any style that yields no tiles, and
                                    // asserting PMTiles once reported the wrong
                                    // reason for a style that was not one.
                                    DownloadState.Failed(
                                        "style yielded no tiles — check the Tile URL, " +
                                            "or use Offline Map Archive"
                                    )
                                } else DownloadState.Running(
                                    percent.coerceIn(0, 100),
                                    megabytes,
                                    status.completedResourceCount,
                                    status.requiredResourceCount
                                )
                            }
                        }

                        override fun onError(error: OfflineRegionError) {
                            _state.value = DownloadState.Failed(error.message ?: error.reason)
                        }

                        override fun mapboxTileCountLimitExceeded(limit: Long) {
                            _state.value =
                                DownloadState.Failed("area too large (limit $limit tiles)")
                        }
                    })
                    region.setDownloadState(OfflineRegion.STATE_ACTIVE)
                }

                override fun onError(error: String) {
                    _state.value = DownloadState.Failed(error)
                }
            }
        )
    }

    /** Removes every downloaded region, freeing the store. */
    fun clear(onDone: () -> Unit = {}) {
        val manager = this.manager ?: return onDone()
        manager.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(regions: Array<OfflineRegion>?) {
                regions?.forEach { region ->
                    region.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
                        override fun onDelete() {}
                        override fun onError(error: String) {}
                    })
                }
                _state.value = DownloadState.Idle
                onDone()
            }

            override fun onError(error: String) = onDone()
        })
    }

    /**
     * A square around the centre.
     *
     * Longitude degrees shrink towards the poles, so the east-west span is
     * divided by the cosine of the latitude — without that the box would come out
     * narrower than asked for anywhere far from the equator.
     */
    private fun boundsAround(centre: LatLng, radiusKm: Double): LatLngBounds {
        val latDelta = radiusKm / KM_PER_DEGREE_LAT
        val lonDelta = radiusKm /
            (KM_PER_DEGREE_LAT * Math.cos(Math.toRadians(centre.latitude)).coerceAtLeast(0.01))
        return LatLngBounds.from(
            (centre.latitude + latDelta).coerceAtMost(85.0),
            centre.longitude + lonDelta,
            (centre.latitude - latDelta).coerceAtLeast(-85.0),
            centre.longitude - lonDelta
        )
    }

    private companion object {
        const val MIN_ZOOM = 8.0
        const val MAX_ZOOM = 15.0
        const val KM_PER_DEGREE_LAT = 111.32
    }
}
