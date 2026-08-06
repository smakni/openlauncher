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

    private val readings = linkedMapOf<Pair<Int, Int>, Reading>()
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
                        synchronized(readings) {
                            readings[module to updateId] = Reading(
                                module = module,
                                id = updateId,
                                ints = ints?.toList().orEmpty(),
                                floats = floats?.toList().orEmpty(),
                                strings = strings?.filterNotNull().orEmpty()
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
        val snapshot = synchronized(readings) { readings.values.toList() }
        file.writeText(buildString {
            append("SYU probe — ${snapshot.size} responding ids\n")
            append("=".repeat(44)).append('\n')
            snapshot.sortedWith(compareBy({ it.module }, { it.id })).forEach { r ->
                append("m%-3d id%-5d".format(r.module, r.id))
                if (r.ints.isNotEmpty()) append(" ints=${r.ints}")
                if (r.floats.isNotEmpty()) append(" floats=${r.floats}")
                if (r.strings.isNotEmpty()) append(" strings=${r.strings}")
                append('\n')
            }
        })
        "${snapshot.size} ids → ${file.absolutePath}"
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

        /** Swept blind: neither the module count nor the id range is documented. */
        const val MODULE_COUNT = 12
        const val ID_COUNT = 256
    }
}
