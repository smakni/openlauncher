package com.openlauncher.app.util

/**
 * What each data id means on this unit's decoder.
 *
 * Read out of the vendor's own code rather than guessed. Of the six hundred or
 * so CAN box classes shipped, exactly one is ZHTD — which is what this unit
 * reports fitted — and it carries U_LANDROVER_LVDS_TYPE and
 * U_LANDROVER_TOUCH_TYPE, so it is the class serving this car rather than a
 * coincidence of naming.
 *
 * These are therefore names, not inferences. Watching a value move gives a
 * number and leaves the meaning to be worked out; this gives the meaning and
 * leaves only confirmation, which is a much smaller thing to be wrong about.
 *
 * Confirmation is still owed. The class is the right one, but whether every
 * signal is populated depends on the car, and an id the decoder never sends
 * looks identical to one read wrongly.
 */
object VendorIds {

    /** The toolkit module these belong to. */
    const val CANBUS_MODULE = 7

    /**
     * Signals worth showing, in the order they are worth reading.
     *
     * Deliberately not the whole class. It declares seventy-odd ids and most
     * concern a screen-replacement installation this car does not have; listing
     * them would bury the handful that answer a question.
     */
    val SIGNALS: List<Signal> = listOf(
        Signal(105, "Speed", "km/h"),
        Signal(107, "Engine", "rpm"),
        Signal(131, "Gear", "P/R/N/D"),
        Signal(137, "Volume", ""),
        Signal(123, "Outside temp", "°C"),
        Signal(106, "Fuel remaining", ""),
        Signal(108, "Total mileage", "km"),
        Signal(104, "Handbrake", ""),
        Signal(103, "Foot brake", ""),
        Signal(102, "Seatbelt", ""),
        Signal(98, "Dipped beam", ""),
        Signal(99, "Main beam", ""),
        Signal(100, "Indicator left", ""),
        Signal(101, "Indicator right", ""),
        Signal(119, "Reversing camera type", ""),
        Signal(133, "Mute on reverse", ""),
        Signal(163, "Low fuel warning", ""),
        Signal(164, "Seatbelt warning", ""),
        Signal(109, "Head unit UI state", ""),
        Signal(132, "Jump to page", ""),
        Signal(171, "Land Rover touch type", ""),
        Signal(172, "Land Rover LVDS type", "")
    )

    private val BY_ID: Map<Int, Signal> = SIGNALS.associateBy { it.id }

    /**
     * The name for an id, or null when it is not one of the known signals.
     *
     * Only the CAN module is answered for. The same number means something
     * different on every other module, and a confident wrong label is worse than
     * no label at all.
     */
    fun label(module: Int, id: Int): String? =
        if (module != CANBUS_MODULE) null else BY_ID[id]?.name

    data class Signal(val id: Int, val name: String, val unit: String)
}
