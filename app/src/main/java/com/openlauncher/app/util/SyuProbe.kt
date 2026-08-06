package com.openlauncher.app.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.syu.ipc.IModuleCallback
import com.syu.ipc.IRemoteModule
import com.syu.ipc.IRemoteToolkit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Sweeps the vendor service for whatever it will report.
 *
 * The interface is now known exactly, but the meaning of its identifiers is not:
 * module numbers and data ids differ between CAN box models, so the id carrying
 * outside temperature on one vehicle means nothing on another. There is no table
 * to look them up in — they have to be found on the car itself.
 *
 * So this registers a callback across a range of modules and ids and records
 * every value that arrives. Reading the result twice, with something changed in
 * between — headlights on, volume turned, engine started — is what identifies an
 * id: only the one that tracks the change is the one being looked for.
 */
class SyuProbe(private val context: Context) {

    data class Reading(
        val module: Int,
        val id: Int,
        val ints: List<Int>,
        val floats: List<Float>,
        val strings: List<String>
    )

    private val _status = MutableStateFlow("idle")
    val status: StateFlow<String> = _status

    /**
     * Current values, published as they arrive.
     *
     * Watching a value move while turning the knob that causes it identifies an
     * id immediately, where comparing exported files cannot: it needs one action
     * at a time and a round trip per guess.
     */
    private val _live = MutableStateFlow<List<Reading>>(emptyList())
    val live: StateFlow<List<Reading>> = _live

    /** When each id last changed, so the display can show what just moved. */
    private val _lastChangedAt = MutableStateFlow<Map<Pair<Int, Int>, Long>>(emptyMap())
    val lastChangedAt: StateFlow<Map<Pair<Int, Int>, Long>> = _lastChangedAt

    private val readings = linkedMapOf<Pair<Int, Int>, Reading>()

    /**
     * Every update in the order it arrived, with the time since the sweep began.
     *
     * Two snapshots only separate values that differ between them, which fails
     * the moment more than one thing is changed — and changing one thing at a
     * time, six times over, is not a reasonable thing to ask of someone sitting
     * in a car. A timeline lets everything happen in one session: say roughly
     * when each thing was done and the ids that moved at that moment are the
     * answer.
     */
    private val timeline = mutableListOf<Pair<Long, Reading>>()
    private var startedAtMs = 0L
    private var toolkit: IRemoteToolkit? = null
    private val callbacks = mutableListOf<Pair<IRemoteModule, IModuleCallback>>()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            toolkit = IRemoteToolkit.Stub.asInterface(binder)
            _status.value = "connected, sweeping"
            sweep()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            toolkit = null
            _status.value = "disconnected"
        }
    }

    fun start() {
        readings.clear()
        timeline.clear()
        startedAtMs = System.currentTimeMillis()
        _status.value = "binding"
        val intent = Intent(TOOLKIT_ACTION).apply { setPackage(TOOLKIT_PACKAGE) }
        val bound = runCatching {
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        if (!bound) _status.value = "bind refused"
    }

    private fun sweep() {
        val toolkit = this.toolkit ?: return
        var registered = 0

        for (module in 0 until MODULE_COUNT) {
            val remote = runCatching { toolkit.getRemoteModule(module) }.getOrNull() ?: continue

            for (id in 0 until ID_COUNT) {
                val callback = object : IModuleCallback.Stub() {
                    override fun update(
                        updateId: Int,
                        ints: IntArray?,
                        floats: FloatArray?,
                        strings: Array<String>?
                    ) {
                        // Values arrive on a binder thread; the map is only read
                        // when the report is written, after the sweep is done.
                        val reading = Reading(
                            module = module,
                            id = updateId,
                            ints = ints?.toList().orEmpty(),
                            floats = floats?.toList().orEmpty(),
                            strings = strings?.filterNotNull().orEmpty()
                        )
                        synchronized(readings) {
                            val previous = readings[module to updateId]
                            readings[module to updateId] = reading
                            // Only changes go in the timeline. Some ids re-send an
                            // unchanged value steadily, and logging those buries
                            // the handful that actually respond to something.
                            if (previous == null || previous != reading) {
                                timeline += (System.currentTimeMillis() - startedAtMs) to reading
                                _lastChangedAt.value = _lastChangedAt.value +
                                    ((module to updateId) to System.currentTimeMillis())
                            }
                            _live.value = readings.values.sortedWith(
                                compareBy({ it.module }, { it.id })
                            )
                        }
                    }
                }
                // The third argument is a flag whose meaning is not known; 0 is
                // the conservative choice and the service answers to it.
                runCatching { remote.register(callback, id, 0) }
                    .onSuccess {
                        registered++
                        callbacks += remote to callback
                    }
            }
        }
        _status.value = "registered $registered ids, waiting for values"
    }

    /** Writes what has arrived so far, and returns where it landed. */
    fun writeReport(): String = runCatching {
        val dir = File(context.getExternalFilesDir(null), "vendor").apply { mkdirs() }
        val file = File(dir, "syu-probe.txt")
        val (snapshot, events) = synchronized(readings) {
            readings.values.toList() to timeline.toList()
        }

        fun StringBuilder.appendReading(r: Reading) {
            append("m%-3d id%-5d".format(r.module, r.id))
            if (r.ints.isNotEmpty()) append(" ints=${r.ints}")
            if (r.floats.isNotEmpty()) append(" floats=${r.floats}")
            if (r.strings.isNotEmpty()) append(" strings=${r.strings}")
        }

        file.writeText(buildString {
            append("SYU probe — ${snapshot.size} ids, ${events.size} changes\n")
            append("=".repeat(52)).append('\n')

            // The timeline is the part that identifies anything: an id that moved
            // at the moment something was switched is the id for that thing.
            append("\nTIMELINE (seconds from start)\n")
            events.forEach { (elapsed, r) ->
                append("%7.1f  ".format(elapsed / 1000.0))
                appendReading(r)
                append('\n')
            }

            append("\nFINAL VALUES\n")
            snapshot.sortedWith(compareBy({ it.module }, { it.id })).forEach { r ->
                appendReading(r)
                append('\n')
            }
        })
        "${snapshot.size} ids, ${events.size} changes → ${file.absolutePath}"
    }.getOrElse { "report failed: ${it.javaClass.simpleName}" }

    fun stop() {
        synchronized(readings) {
            callbacks.forEach { (remote, callback) ->
                runCatching { remote.unregister(callback, 0) }
            }
            callbacks.clear()
        }
        runCatching { context.unbindService(connection) }
        toolkit = null
        _status.value = "stopped"
    }

    private companion object {
        const val TOOLKIT_PACKAGE = "com.syu.ms"
        const val TOOLKIT_ACTION = "com.syu.ms.toolkit"

        /**
         * Covers the four documented interfaces: 0 Main, 4 Sound, 7 Canbus and
         * 14 CanUp. The earlier ceiling of 12 stopped short of CanUp entirely,
         * so nothing it carries was ever seen.
         */
        const val MODULE_COUNT = 16
        const val ID_COUNT = 256
    }
}
