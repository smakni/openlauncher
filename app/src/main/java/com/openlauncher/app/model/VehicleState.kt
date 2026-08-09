package com.openlauncher.app.model

/**
 * A snapshot of live engine data read from the OBD-II port.
 *
 * Every field is nullable on purpose. An ECU only answers the PIDs it actually
 * supports, and which ones those are varies by engine, market and model year —
 * a 2.2 diesel and a 2.0 petrol of the same car expose different sets. null
 * means "this car never answered that PID", which the UI must render as a blank
 * readout rather than a zero, otherwise a missing sensor looks like a reading of 0.
 */
data class VehicleState(
    val rpm: Int? = null,
    val speedKph: Int? = null,
    val coolantTempC: Int? = null,
    val intakeTempC: Int? = null,
    /**
     * Outside air, from the CAN decoder rather than the ECU.
     *
     * A half rather than a whole degree because that is the resolution the
     * decoder sends; rounding it here would throw away a step the car took the
     * trouble to report.
     */
    val ambientTempC: Double? = null,
    val oilTempC: Int? = null,
    val engineLoadPct: Float? = null,
    val throttlePct: Float? = null,
    /** Gauge pressure: manifold absolute minus barometric. Negative under vacuum. */
    val boostBar: Float? = null,
    val fuelLevelPct: Float? = null,
    /**
     * Litres left in the tank, from the CAN decoder.
     *
     * A volume rather than the percentage the OBD PID reports, and kept separate
     * because they are different measurements: turning one into the other needs
     * a tank capacity the car does not send.
     */
    val fuelLitresCan: Int? = null,
    /**
     * Whether the CAN decoder service is bound.
     *
     * Shown by the widgets that depend on it, because "not connected" and
     * "connected and saying nothing" look identical on screen and have entirely
     * different causes — one is a bug here, the other is the car.
     */
    val canConnected: Boolean = false,
    /**
     * Whether the car reports an outside temperature at all.
     *
     * Null until the decoder has been asked. False is a permanent answer and
     * deserves different wording on screen from a reading that has simply not
     * arrived yet.
     */
    val tempSensorPresent: Boolean? = null,
    val batteryVolts: Float? = null,
    /** Instantaneous consumption derived from MAF — see ObdManager.consumptionFrom. */
    val consumptionLph: Float? = null
) {
    val hasAnyReading: Boolean
        get() = rpm != null || speedKph != null || coolantTempC != null ||
                ambientTempC != null ||
                boostBar != null || batteryVolts != null
}

enum class ObdStatus {
    /** No adapter selected in settings — the feature is off. */
    DISABLED,
    CONNECTING,
    CONNECTED,
    /** Adapter selected but unreachable: dongle unplugged, or ignition off. */
    DISCONNECTED
}

/**
 * Fuel chemistry used to turn air mass flow into a volume burn rate. The
 * stoichiometric ratio and density differ enough between the two that sharing
 * one constant would put the diesel reading out by roughly 10%.
 */
enum class FuelType(val airFuelRatio: Float, val densityGramsPerLitre: Float) {
    PETROL(14.7f, 745f),
    DIESEL(14.5f, 832f)
}
