package com.openlauncher.app.obd

/**
 * The mode-01 PIDs the launcher polls, with their SAE J1979 conversion formulas.
 *
 * [decode] receives the payload bytes that follow the `41 <pid>` response echo,
 * already parsed from hex, and returns the value in the unit named on each entry.
 * [byteCount] is how many payload bytes that PID returns — the response parser
 * needs it to know where the value ends, since consecutive frames run together
 * once spaces are switched off.
 */
enum class ObdPid(
    val pid: String,
    val byteCount: Int,
    val decode: (IntArray) -> Float
) {
    /** % */
    ENGINE_LOAD("04", 1, { it[0] * 100f / 255f }),
    /** °C */
    COOLANT_TEMP("05", 1, { it[0] - 40f }),
    /** kPa absolute */
    MANIFOLD_PRESSURE("0B", 1, { it[0].toFloat() }),
    /** rev/min */
    RPM("0C", 2, { ((it[0] * 256) + it[1]) / 4f }),
    /** km/h */
    SPEED("0D", 1, { it[0].toFloat() }),
    /** °C */
    INTAKE_TEMP("0F", 1, { it[0] - 40f }),
    /** grams/second */
    MAF("10", 2, { ((it[0] * 256) + it[1]) / 100f }),
    /** % */
    THROTTLE("11", 1, { it[0] * 100f / 255f }),
    /** % */
    FUEL_LEVEL("2F", 1, { it[0] * 100f / 255f }),
    /** kPa absolute */
    BAROMETRIC("33", 1, { it[0].toFloat() }),
    /** volts */
    MODULE_VOLTAGE("42", 2, { ((it[0] * 256) + it[1]) / 1000f }),
    /** °C */
    OIL_TEMP("5C", 1, { it[0] - 40f });

    /** Mode 01 request line, e.g. "010C" for engine RPM. */
    val command: String get() = "01$pid"

    /** Prefix a positive response carries, e.g. "410C". */
    val responseMarker: String get() = "41$pid"
}
