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
        // The decoder declares a volume id and this car does not send it: it
        // never appeared while every neighbouring id did. The head unit's own
        // volume lives on the sound module instead — see SOUND_SIGNALS.
        Signal(137, "Volume (not sent by this car)", ""),
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

    /** The toolkit module carrying the head unit's own audio state. */
    const val SOUND_MODULE = 4

    /**
     * Sound-module signals, named from the vendor's own FinalSound.
     *
     * The volume was a guess before — a value on module 4 that sat where a
     * volume would and moved in the right range. It is now a name:
     * U_VOL = 2. The guess happened to be right, which is luck rather than
     * method, and the constant is what makes it a fact.
     */
    val SOUND_SIGNALS: List<Signal> = listOf(
        Signal(2, "Volume", ""),
        Signal(3, "Mute", ""),
        Signal(12, "Audio source", ""),
        Signal(10, "EQ mode", ""),
        Signal(8, "Balance / fade", ""),
        Signal(6, "Default volume", ""),
        Signal(13, "Amplifier", ""),
        Signal(11, "Loudness", "")
    )

    /**
     * The unit's OBD module, which nothing here has ever opened.
     *
     * Undocumented: no constants for it ship in any source found, and the ids
     * below are therefore a range to sweep rather than names to read. It is
     * listed separately from the guessed signals of the past because sweeping a
     * module to see what answers is a question, while naming its ids without a
     * source would be an answer nobody has.
     */
    const val OBD_MODULE = 12
    const val OBD_ID_COUNT = 64

    private val BY_ID: Map<Int, Signal> = SIGNALS.associateBy { it.id }
    private val SOUND_BY_ID: Map<Int, Signal> = SOUND_SIGNALS.associateBy { it.id }

    /**
     * The name for an id, or null when it is not one of the known signals.
     *
     * Only the CAN module is answered for. The same number means something
     * different on every other module, and a confident wrong label is worse than
     * no label at all.
     */
    fun label(module: Int, id: Int): String? = when (module) {
        CANBUS_MODULE -> BY_ID[id]?.name
        SOUND_MODULE -> SOUND_BY_ID[id]?.name
        else -> null
    }

    /**
     * Outside temperature, in degrees.
     *
     * Half-degree steps, confirmed against the car, with the minus-forty offset
     * automotive CAN uses almost universally — the pair puts the 127 read while
     * parked at 23.5°C. The offset is the half of this not directly confirmed:
     * were it absent the reading would sit exactly forty degrees high, which is
     * not a subtle error to spot.
     */
    fun outsideTempC(raw: Int): Double = raw / 2.0 - 40.0

    /**
     * Fuel remaining, in litres.
     *
     * Not a percentage, which is what this was assumed to be. The vendor's own
     * car-information screen formats id 106 as "%d L" with no scaling applied,
     * so the decoder sends litres outright.
     *
     * The assumption would have been believed rather than caught: a quarter of a
     * seventy litre tank reads about 17, which is a plausible percentage and a
     * badly wrong one. It survived only because the value never arrived.
     */
    fun fuelLitres(raw: Int): Int? = raw.takeIf { it in 1..200 }

    data class Signal(val id: Int, val name: String, val unit: String)
}
