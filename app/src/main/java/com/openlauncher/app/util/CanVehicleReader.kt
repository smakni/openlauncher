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
import kotlinx.coroutines.flow.update

/**
 * Reads live vehicle data from the CAN decoder.
 *
 * Distinct from the diagnostic sweep, which registers four thousand ids to find
 * out what exists. This registers eight, because what exists is now known: the
 * unit's decoder class was read out of the vendor package and it names them.
 *
 * The engine speed proved it. Parked, the value sat at 813 and then 794 — a
 * diesel idle, drifting the way an idle does. Nothing about a wrong id produces
 * a plausible number that moves plausibly.
 *
 * This is what the OBD dongle was for. The decoder already has the same data and
 * needs no adapter, no pairing and no socket, so it is the better source when it
 * answers at all.
 */
class CanVehicleReader(private val context: Context) {

    data class CanVehicle(
        val engineRpm: Int? = null,
        val speedKph: Int? = null,
        /** Raw gear code. Meaning per car; not decoded until confirmed. */
        val gearRaw: Int? = null,
        val outsideTempC: Double? = null,
        /** Fuel remaining, as the decoder sends it. Scale unconfirmed. */
        val fuelRaw: Int? = null,
        val handbrake: Boolean? = null,
        val dippedBeam: Boolean? = null,
        val mainBeam: Boolean? = null,
        val indicatorLeft: Boolean? = null,
        val indicatorRight: Boolean? = null,
        val connected: Boolean = false
    )

    private val _vehicle = MutableStateFlow(CanVehicle())
    val vehicle: StateFlow<CanVehicle> = _vehicle

    private var toolkit: IRemoteToolkit? = null
    private var module: IRemoteModule? = null
    // Kept with the id they were registered under: unregistering needs both,
    // and a callback released against the wrong id leaves the real one live.
    private val callbacks = mutableListOf<Pair<Int, IModuleCallback>>()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            toolkit = IRemoteToolkit.Stub.asInterface(binder)
            subscribe()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            toolkit = null
            module = null
            _vehicle.update { it.copy(connected = false) }
        }
    }

    fun start() {
        val intent = Intent(TOOLKIT_ACTION).apply { setPackage(TOOLKIT_PACKAGE) }
        val bound = runCatching {
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        // Recorded rather than dropped. A failed bind and a silent car produce
        // the same empty screen, and telling them apart is the difference
        // between a wiring bug and a car that does not send the signal.
        if (!bound) _vehicle.update { it.copy(connected = false) }
    }

    fun stop() {
        runCatching {
            val remote = module
            if (remote != null) callbacks.forEach { (id, callback) ->
                runCatching { remote.unregister(callback, id) }
            }
        }
        callbacks.clear()
        runCatching { context.unbindService(connection) }
        toolkit = null
        module = null
    }

    private fun subscribe() {
        val remote = runCatching {
            toolkit?.getRemoteModule(VendorIds.CANBUS_MODULE)
        }.getOrNull() ?: return
        module = remote
        _vehicle.update { it.copy(connected = true) }

        WATCHED.forEach { id ->
            val callback = object : IModuleCallback.Stub() {
                override fun update(
                    updateId: Int,
                    ints: IntArray?,
                    floats: FloatArray?,
                    strings: Array<String>?
                ) {
                    val value = ints?.firstOrNull() ?: return
                    apply(updateId, value)
                }
            }
            runCatching { remote.register(callback, id, 0) }
                .onSuccess { callbacks += id to callback }
        }
    }

    /**
     * Applies one reading, atomically.
     *
     * Each id has its own callback and they arrive on separate binder threads,
     * so reading the state into a local and assigning a copy back loses updates:
     * two threads read the same snapshot and the second write discards the
     * first. That is not a rare race here, it is the normal case, and it fails
     * in one direction — the id that re-sends constantly overwrites the one that
     * does not. Engine speed survived while outside temperature and fuel, which
     * are sent once and then sit still, were wiped within a second of arriving
     * and never came back.
     *
     * `update` retries on a compare-and-set until its write lands on the state
     * it was computed from, so a slow signal cannot be clobbered by a fast one.
     */
    private fun apply(id: Int, value: Int) {
        _vehicle.update { current ->
            when (id) {
                ID_ENGINE -> current.copy(engineRpm = value)
                ID_SPEED -> current.copy(speedKph = value)
                ID_GEAR -> current.copy(gearRaw = value)
                ID_OUTSIDE_TEMP -> current.copy(outsideTempC = VendorIds.outsideTempC(value))
                ID_FUEL -> current.copy(fuelRaw = value)
                ID_HANDBRAKE -> current.copy(handbrake = value != 0)
                ID_DIPPED -> current.copy(dippedBeam = value != 0)
                ID_MAIN_BEAM -> current.copy(mainBeam = value != 0)
                ID_INDICATOR_L -> current.copy(indicatorLeft = value != 0)
                ID_INDICATOR_R -> current.copy(indicatorRight = value != 0)
                else -> current
            }
        }
    }

    private companion object {
        const val TOOLKIT_PACKAGE = "com.syu.ms"
        // The action, not the component name. Binding against
        // "com.syu.ms.ToolkitService" fails and fails quietly: bindService
        // returns without connecting, no callback is ever registered, and every
        // reading stays null exactly as it would on a car that says nothing.
        const val TOOLKIT_ACTION = "com.syu.ms.toolkit"

        const val ID_DIPPED = 98
        const val ID_MAIN_BEAM = 99
        const val ID_INDICATOR_L = 100
        const val ID_INDICATOR_R = 101
        const val ID_HANDBRAKE = 104
        const val ID_SPEED = 105
        const val ID_ENGINE = 107
        const val ID_GEAR = 131
        const val ID_OUTSIDE_TEMP = 123
        const val ID_FUEL = 106

        val WATCHED = listOf(
            ID_DIPPED, ID_MAIN_BEAM, ID_INDICATOR_L, ID_INDICATOR_R,
            ID_HANDBRAKE, ID_SPEED, ID_ENGINE, ID_GEAR, ID_OUTSIDE_TEMP, ID_FUEL
        )
    }
}
