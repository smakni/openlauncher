package com.openlauncher.app.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.syu.ipc.IModuleCallback
import com.syu.ipc.IRemoteModule
import com.syu.ipc.IRemoteToolkit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
        /** Fuel remaining, in litres. This car sends zero and nothing else. */
        val fuelRaw: Int? = null,
        /**
         * The car's own low-fuel lamp.
         *
         * Worth carrying precisely because the level is not: it is the only
         * fuel information this decoder gets from this car, and a warning that
         * the car itself raised is worth more than a level it never sent.
         */
        val lowFuelWarning: Boolean? = null,
        val handbrake: Boolean? = null,
        val dippedBeam: Boolean? = null,
        val mainBeam: Boolean? = null,
        val indicatorLeft: Boolean? = null,
        val indicatorRight: Boolean? = null,
        val connected: Boolean = false,
        /** Head unit volume, from the sound module. Not a CAN signal. */
        val volume: Int? = null,
        val muted: Boolean? = null,
        /** Whether the car has an outside temperature sensor; null until asked. */
        val tempSensorPresent: Boolean? = null
    )

    private val _vehicle = MutableStateFlow(CanVehicle())
    val vehicle: StateFlow<CanVehicle> = _vehicle

    private var toolkit: IRemoteToolkit? = null
    private var module: IRemoteModule? = null
    private var soundModule: IRemoteModule? = null
    private var obdModule: IRemoteModule? = null
    // Kept with the id they were registered under: unregistering needs both,
    // and a callback released against the wrong id leaves the real one live.
    //
    // Guarded by its own monitor: the refresh runs on a background thread while
    // stop() runs on the main one, and iterating this while the other mutates it
    // throws. With no ADB on this unit that crash would surface as the launcher
    // vanishing, with nothing to read afterwards.
    private val callbacks = mutableListOf<Pair<Int, IModuleCallback>>()

    private val soundCallbacks = mutableListOf<Pair<Int, IModuleCallback>>()
    private val obdCallbacks = mutableListOf<Pair<Int, IModuleCallback>>()

    /** Whatever the sound and OBD modules report, for the written diagnostic. */
    private val observed = linkedMapOf<Pair<Int, Int>, List<Int>>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var refreshJob: Job? = null

    @Volatile private var diagnosed = false

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
        refreshJob?.cancel()
        refreshJob = null
        runCatching {
            val remote = module
            synchronized(callbacks) {
                if (remote != null) callbacks.forEach { (id, callback) ->
                    runCatching { remote.unregister(callback, id) }
                }
                callbacks.clear()
            }
        }
        synchronized(callbacks) {
            soundModule?.let { remote ->
                soundCallbacks.forEach { (id, cb) -> runCatching { remote.unregister(cb, id) } }
            }
            soundCallbacks.clear()
            obdModule?.let { remote ->
                obdCallbacks.forEach { (id, cb) -> runCatching { remote.unregister(cb, id) } }
            }
            obdCallbacks.clear()
        }
        runCatching { context.unbindService(connection) }
        toolkit = null
        module = null
        soundModule = null
        obdModule = null
    }

    private fun subscribe() {
        val remote = runCatching {
            toolkit?.getRemoteModule(VendorIds.CANBUS_MODULE)
        }.getOrNull() ?: return
        module = remote
        _vehicle.update { it.copy(connected = true) }

        // Two ranges, because the ids fall into two families.
        //
        // Below 256 are the per-car signals the decoder class names, which
        // differ between CAN boxes. From 1000 up is a block common to all of
        // them — capabilities, and a speed and engine speed that do not depend
        // on the car at all. Only the first range was ever subscribed, so
        // everything in the second was invisible: whether this car even has an
        // outside sensor, and a second source for two readings that matter.
        //
        // Updates for ids nobody asked about fall through the when in apply()
        // and cost nothing beyond a binder call.
        (0 until ID_COUNT).forEach { id -> subscribeId(remote, id) }
        (COMMON_ID_FIRST..COMMON_ID_LAST).forEach { id -> subscribeId(remote, id) }

        subscribeSound()
        subscribeObd()
        startRefresh()
    }

    /**
     * The head unit's own volume, which the car does not send.
     *
     * The CAN decoder declares a volume id and this car never fills it, so the
     * figure comes from the sound module instead. Named rather than guessed:
     * the vendor's FinalSound puts the volume at id 2, the mute at 3 and the
     * audio source at 12.
     */
    private fun subscribeSound() {
        val remote = runCatching {
            toolkit?.getRemoteModule(VendorIds.SOUND_MODULE)
        }.getOrNull() ?: return
        soundModule = remote
        VendorIds.SOUND_SIGNALS.forEach { signal ->
            val callback = object : IModuleCallback.Stub() {
                override fun update(
                    updateId: Int,
                    ints: IntArray?,
                    floats: FloatArray?,
                    strings: Array<String>?
                ) {
                    val value = ints?.firstOrNull() ?: return
                    when (updateId) {
                        SOUND_ID_VOLUME -> _vehicle.update { it.copy(volume = value) }
                        SOUND_ID_MUTE -> _vehicle.update { it.copy(muted = value != 0) }
                    }
                    observe(VendorIds.SOUND_MODULE, updateId, ints)
                }
            }
            runCatching { remote.register(callback, signal.id, 1) }
                .onSuccess { soundCallbacks += signal.id to callback }
        }
    }

    /**
     * Opens the OBD module to find out whether anything is behind it.
     *
     * Nothing here reads a value from it, because nothing knows what its ids
     * mean — no constants for module 12 ship in any source found. What arrives
     * is recorded for the diagnostic and nothing else. A module that answers
     * with the engine running and no dongle fitted would be worth a great deal;
     * one that stays silent closes the question, and both are findings.
     */
    private fun subscribeObd() {
        val remote = runCatching {
            toolkit?.getRemoteModule(VendorIds.OBD_MODULE)
        }.getOrNull() ?: return
        obdModule = remote
        (0 until VendorIds.OBD_ID_COUNT).forEach { id ->
            val callback = object : IModuleCallback.Stub() {
                override fun update(
                    updateId: Int,
                    ints: IntArray?,
                    floats: FloatArray?,
                    strings: Array<String>?
                ) {
                    observe(VendorIds.OBD_MODULE, updateId, ints)
                }
            }
            runCatching { remote.register(callback, id, 1) }
                .onSuccess { obdCallbacks += id to callback }
        }
    }

    /**
     * Writes what the sound and OBD modules have said.
     *
     * The sound side is a check on names now taken from the vendor's constants;
     * the OBD side is the open question, and an empty section under it is the
     * answer that module 12 needs a dongle rather than merely needing asking.
     */
    private fun writeObservedReport() {
        runCatching {
            val dir = java.io.File(context.getExternalFilesDir(null), "vendor")
                .apply { mkdirs() }
            val file = java.io.File(dir, "syu-modules.txt")
            val snapshot = synchronized(observed) { observed.toMap() }
            file.writeText(buildString {
                appendLine("Sound and OBD modules")
                appendLine("=".repeat(52))
                appendLine()
                appendLine("Values arrive by subscription; get() answers nothing here.")
                appendLine("An empty section means the module reported nothing at all.")
                appendLine()

                appendLine("--- module ${VendorIds.SOUND_MODULE}: sound ---")
                val sound = snapshot.filterKeys { it.first == VendorIds.SOUND_MODULE }
                if (sound.isEmpty()) appendLine("nothing reported")
                sound.toSortedMap(compareBy { it.second }).forEach { (key, values) ->
                    val name = VendorIds.label(key.first, key.second) ?: "unnamed"
                    appendLine("id %-4d %-22s %s".format(key.second, name, values.joinToString(",")))
                }

                appendLine()
                appendLine("--- module ${VendorIds.OBD_MODULE}: OBD ---")
                val obd = snapshot.filterKeys { it.first == VendorIds.OBD_MODULE }
                if (obd.isEmpty()) {
                    appendLine("nothing reported")
                    appendLine()
                    appendLine("No constants for this module ship in any published")
                    appendLine("source, so silence here is the whole answer: without a")
                    appendLine("dongle there is nothing behind it to read.")
                }
                obd.toSortedMap(compareBy { it.second }).forEach { (key, values) ->
                    appendLine("id %-4d %s".format(key.second, values.joinToString(",")))
                }
            })
        }
    }

    /** Records a reading from a module whose ids have no established meaning. */
    private fun observe(module: Int, id: Int, ints: IntArray?) {
        val values = ints?.toList().orEmpty()
        synchronized(observed) { observed[module to id] = values }
    }

    private fun subscribeId(remote: IRemoteModule, id: Int) {
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
        // The third argument, one rather than zero.
        //
        // get() is not the way in: the service answers null for every canbus
        // data id, and the vendor's own application never calls it for them
        // either — it keeps a local array filled entirely by these callbacks.
        // Its UI layer registers with a flag of one, which it defines as "fire
        // immediately with what is held" rather than waiting for a change. The
        // same argument exists on the service call, and the same meaning is the
        // obvious reading of it.
        //
        // It stays a subscription either way, so a wrong guess costs a flag the
        // service ignores. Nothing here can reconfigure the CAN box.
        runCatching { remote.register(callback, id, 1) }
            .onSuccess { synchronized(callbacks) { callbacks += id to callback } }
    }

    /**
     * Re-subscribes the ids that have never produced a value.
     *
     * The service appears to push only on change. Engine speed therefore arrives
     * constantly while outside temperature and fuel — which are sent once and
     * then sit still for hours — are never seen at all: the launcher starts
     * after they were last sent and waits for a change that does not come.
     *
     * Re-registering is the way to ask again without sending the decoder a
     * command. It is subscription traffic and nothing else, so it cannot alter
     * how the CAN box is configured — which matters, because a wrong command to
     * this MCU costs the steering wheel controls or the camera.
     *
     * Only ids still missing are retried, so this stops by itself once the car
     * has answered rather than churning subscriptions for the rest of the drive.
     */
    private fun startRefresh() {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            repeat(REFRESH_ATTEMPTS) { attempt ->
                // Brisk at first, then patient. A car that answers normally is
                // done inside the first minute; proving the get() layout needs
                // the engine running, which may be several minutes after the
                // launcher starts, and giving up before then would waste the
                // one chance to do it.
                // Quick, then brisk, then patient. The seconds after a cold
                // start are when the decoder has not yet polled the car, and
                // they are also exactly when someone is watching the screen.
                delay(
                    when {
                        attempt < FAST_ATTEMPTS -> FAST_INTERVAL_MS
                        attempt < 15 -> REFRESH_INTERVAL_MS
                        else -> SLOW_INTERVAL_MS
                    }
                )
                val remote = module ?: return@launch
                val missing = missingIds()
                if (missing.isEmpty()) return@launch

                // Once, after the car has had time to answer. A signal still
                // absent by now is worth a written answer rather than another
                // silent retry.
                if (attempt == DIAGNOSE_AFTER_ATTEMPT && !diagnosed) {
                    diagnosed = true
                    SyuGet.report(context, remote, DIAGNOSTIC_IDS)
                    writeObservedReport()
                }
                missing.forEach { id ->
                    // Released first: registering twice for the same id would
                    // leave a callback behind that nothing ever unregisters.
                    val held = synchronized(callbacks) {
                        callbacks.filter { it.first == id }
                            .also { callbacks.removeAll { c -> c.first == id } }
                    }
                    held.forEach { (heldId, cb) ->
                        runCatching { remote.unregister(cb, heldId) }
                    }
                    subscribeId(remote, id)
                }
            }
        }
    }

    private fun missingIds(): List<Int> {
        val v = _vehicle.value
        return buildList {
            if (v.outsideTempC == null) add(ID_OUTSIDE_TEMP)
            if (v.fuelRaw == null) add(ID_FUEL)
            if (v.gearRaw == null) add(ID_GEAR)
            if (v.speedKph == null) add(ID_SPEED)
            if (v.engineRpm == null) add(ID_ENGINE)
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
            ID_FUEL_WARNING -> current.copy(lowFuelWarning = value != 0)
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
        const val ID_FUEL_WARNING = 163

        /** From the vendor's FinalSound: U_VOL and U_MUTE on the sound module. */
        const val SOUND_ID_VOLUME = 2
        const val SOUND_ID_MUTE = 3

        /**
         * The block common to every CAN box, above the per-car ids.
         *
         * Documented up to 1036 by an independent implementation of this same
         * interface, and the vendor sizes its own array at 1200. Subscribing a
         * hundred covers the documented range with room for what is not.
         */
        const val COMMON_ID_FIRST = 1000
        const val COMMON_ID_LAST = 1099

        /** Whether the car reports an outside temperature at all. */
        const val ID_EXIST_TEMP_OUT = 1012

        /** Speed and engine speed that do not depend on the car fitted. */
        const val ID_COMMON_SPEED = 1031
        const val ID_COMMON_ENGINE = 1032

        /** The id range the diagnostic sweep covers, and that it works over. */
        const val ID_COUNT = 256

        /** The first seconds after a start, while the decoder is still waking. */
        const val FAST_ATTEMPTS = 8
        const val FAST_INTERVAL_MS = 700L

        /** Long enough that a car which answers normally never retries. */
        const val REFRESH_INTERVAL_MS = 4_000L
        /** Once the first minute is up; the engine may not be running yet. */
        const val SLOW_INTERVAL_MS = 30_000L
        /** A minute brisk, then twenty patient. Past that nothing is coming. */
        const val REFRESH_ATTEMPTS = 55

        /** Roughly twenty seconds in: long enough to be a real answer. */
        const val DIAGNOSE_AFTER_ATTEMPT = 5

        /** Asked about by name, so the report reads without a lookup table. */
        val DIAGNOSTIC_IDS = listOf(
            ID_FUEL to "fuel remaining (litres)",
            ID_COMMON_SPEED to "speed (common block)",
            ID_COMMON_ENGINE to "engine speed (common block)",
            ID_OUTSIDE_TEMP to "outside temperature",
            ID_ENGINE to "engine speed",
            ID_SPEED to "vehicle speed",
            ID_GEAR to "gear",
            108 to "total mileage",
            163 to "low fuel warning",
            ID_EXIST_TEMP_OUT to "car has outside sensor"
        )

    }
}
