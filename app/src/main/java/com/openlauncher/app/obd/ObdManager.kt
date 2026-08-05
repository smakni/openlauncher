package com.openlauncher.app.obd

import android.content.Context
import com.openlauncher.app.model.FuelType
import com.openlauncher.app.model.ObdStatus
import com.openlauncher.app.model.VehicleState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Owns the OBD-II link: connects to the adapter, works out which PIDs the car
 * answers, and polls them into [vehicle].
 *
 * The loop is built to survive the normal life of a head unit, where the dongle
 * loses power with the ignition and the launcher keeps running. A dropped link
 * is an expected state, not an error: the manager falls back to reconnect
 * attempts with a growing delay and picks the data back up on its own.
 */
class ObdManager(context: Context) {

    private val connection = Elm327Connection(context)
    private val scope      = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null

    private val _vehicle = MutableStateFlow(VehicleState())
    private val _status  = MutableStateFlow(ObdStatus.DISABLED)
    val vehicle: StateFlow<VehicleState> = _vehicle
    val status:  StateFlow<ObdStatus>    = _status

    /** PIDs this particular car actually answered during the probe. */
    private var supported: Set<ObdPid> = emptySet()
    /** Ambient pressure, needed to turn manifold absolute pressure into boost. */
    private var barometricKpa: Float = SEA_LEVEL_KPA

    fun bondedAdapters() = connection.bondedDevices()

    fun start(macAddress: String, fuelType: FuelType = FuelType.PETROL) {
        stop()
        if (macAddress.isBlank()) return
        pollJob = scope.launch { runLink(macAddress, fuelType) }
    }

    fun stop() {
        scope.coroutineContext.cancelChildren()
        pollJob = null
        connection.close()
        supported = emptySet()
        _vehicle.value = VehicleState()
        _status.value  = ObdStatus.DISABLED
    }

    private suspend fun runLink(macAddress: String, fuelType: FuelType) {
        var backoffMs = MIN_BACKOFF_MS
        // The scope outlives any single link, so this tracks *this* coroutine's
        // job instead: scope.isActive would still read true after stop(), and the
        // blocking socket calls below are not cancellable, so without this guard a
        // connect already in flight could publish a status over the reset state.
        while (currentCoroutineContext().isActive) {
            _status.value = ObdStatus.CONNECTING
            if (connection.connect(macAddress)) {
                backoffMs = MIN_BACKOFF_MS
                supported = probeSupportedPids()
                if (!currentCoroutineContext().isActive) return
                _status.value = ObdStatus.CONNECTED
                pollUntilDropped(fuelType)
            }
            // Either the connect failed or the link dropped mid-poll.
            connection.close()
            if (!currentCoroutineContext().isActive) return
            _status.value  = ObdStatus.DISCONNECTED
            _vehicle.value = VehicleState()
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
        }
    }

    /**
     * Asks for every PID once and keeps the ones that answer. This costs a dozen
     * round trips per connection but is more dependable than reading the mode-01
     * support bitmasks, which a fair number of clone adapters report incorrectly.
     */
    private fun probeSupportedPids(): Set<ObdPid> =
        ObdPid.entries.filterTo(mutableSetOf()) { read(it) != null }

    private suspend fun pollUntilDropped(fuelType: FuelType) {
        var cycle = 0
        while (currentCoroutineContext().isActive && connection.isConnected) {
            // Fast group: everything that moves with the throttle. The slow group
            // is interleaved once every SLOW_EVERY cycles so that temperatures and
            // fuel level do not steal round trips from the RPM readout.
            val fast = readGroup(FAST_GROUP)
            val slow = if (cycle % SLOW_EVERY == 0) readGroup(SLOW_GROUP) else emptyMap()
            if (fast.isEmpty() && slow.isEmpty() && !connection.isConnected) return

            slow[ObdPid.BAROMETRIC]?.let { barometricKpa = it }
            _vehicle.value = merge(_vehicle.value, fast + slow, fuelType)

            cycle++
            delay(POLL_INTERVAL_MS)
        }
    }

    private fun readGroup(group: List<ObdPid>): Map<ObdPid, Float> =
        group.filter { it in supported }
            .mapNotNull { pid -> read(pid)?.let { pid to it } }
            .toMap()

    private fun read(pid: ObdPid): Float? =
        connection.send(pid.command)?.let { parse(pid, it) }

    /**
     * Applies a batch of fresh readings over the previous snapshot. Values from
     * the slow group are carried forward between refreshes rather than blanked,
     * so a coolant gauge does not flicker every cycle it is not polled.
     */
    private fun merge(
        previous: VehicleState,
        readings: Map<ObdPid, Float>,
        fuelType: FuelType
    ): VehicleState {
        val manifoldKpa = readings[ObdPid.MANIFOLD_PRESSURE]
        return previous.copy(
            rpm           = readings[ObdPid.RPM]?.toInt()          ?: previous.rpm,
            speedKph      = readings[ObdPid.SPEED]?.toInt()        ?: previous.speedKph,
            coolantTempC  = readings[ObdPid.COOLANT_TEMP]?.toInt() ?: previous.coolantTempC,
            intakeTempC   = readings[ObdPid.INTAKE_TEMP]?.toInt()  ?: previous.intakeTempC,
            oilTempC      = readings[ObdPid.OIL_TEMP]?.toInt()     ?: previous.oilTempC,
            engineLoadPct = readings[ObdPid.ENGINE_LOAD]           ?: previous.engineLoadPct,
            throttlePct   = readings[ObdPid.THROTTLE]              ?: previous.throttlePct,
            fuelLevelPct  = readings[ObdPid.FUEL_LEVEL]            ?: previous.fuelLevelPct,
            batteryVolts  = readings[ObdPid.MODULE_VOLTAGE]        ?: previous.batteryVolts,
            boostBar      = manifoldKpa?.let { (it - barometricKpa) / KPA_PER_BAR } ?: previous.boostBar,
            consumptionLph = readings[ObdPid.MAF]?.let { consumptionFrom(it, fuelType) }
                ?: previous.consumptionLph
        )
    }

    /**
     * Air mass flow to fuel volume per hour:
     *
     *     litres/h = MAF(g/s) x 3600 / (stoichiometric ratio x fuel density g/l)
     *
     * This assumes the engine is running closed-loop at its stoichiometric ratio,
     * which holds while cruising but overstates consumption under hard
     * acceleration, when the ECU enriches the mixture. It is a live gauge, not a
     * basis for fuel economy figures.
     */
    private fun consumptionFrom(mafGramsPerSecond: Float, fuelType: FuelType): Float =
        mafGramsPerSecond * SECONDS_PER_HOUR /
            (fuelType.airFuelRatio * fuelType.densityGramsPerLitre)

    /**
     * Pulls the value out of an adapter reply.
     *
     * Splits into lines and keeps only pure-hex ones rather than stripping
     * non-hex characters from the whole string: a reply can also carry
     * "SEARCHING...", "NO DATA" or "?", and several of those letters are valid
     * hex digits, so character-level filtering would quietly turn an error
     * message into a plausible-looking reading.
     */
    private fun parse(pid: ObdPid, raw: String): Float? {
        val line = raw.split('\r', '\n', '>')
            .map { it.trim().replace(" ", "").uppercase() }
            .lastOrNull { candidate ->
                candidate.startsWith(pid.responseMarker) &&
                    candidate.all { it.isDigit() || it in 'A'..'F' }
            } ?: return null

        val start = pid.responseMarker.length
        val end   = start + pid.byteCount * 2
        if (line.length < end) return null

        val payload = IntArray(pid.byteCount) { i ->
            line.substring(start + i * 2, start + i * 2 + 2).toInt(16)
        }
        return runCatching { pid.decode(payload) }.getOrNull()
    }

    private companion object {
        val FAST_GROUP = listOf(
            ObdPid.RPM, ObdPid.SPEED, ObdPid.THROTTLE, ObdPid.MANIFOLD_PRESSURE
        )
        val SLOW_GROUP = listOf(
            ObdPid.COOLANT_TEMP, ObdPid.INTAKE_TEMP, ObdPid.OIL_TEMP, ObdPid.ENGINE_LOAD,
            ObdPid.FUEL_LEVEL, ObdPid.MODULE_VOLTAGE, ObdPid.MAF, ObdPid.BAROMETRIC
        )
        const val SLOW_EVERY       = 10
        const val POLL_INTERVAL_MS = 100L
        const val MIN_BACKOFF_MS   = 2_000L
        const val MAX_BACKOFF_MS   = 30_000L
        const val SEA_LEVEL_KPA    = 101.3f
        const val KPA_PER_BAR      = 100f
        const val SECONDS_PER_HOUR = 3600f
    }
}
