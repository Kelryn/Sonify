package com.ritmute.core.data.backup

/**
 * The `NotificationManager.Policy.SUPPRESSED_EFFECT_*` bitmask, as a list of names.
 *
 * The bit values are written out here rather than referenced from `NotificationManager`
 * for two reasons. First, this file's contract is that a given name always means the same
 * thing — if the platform ever renumbered a constant, reading the framework value would
 * silently reinterpret every backup ever written, while these numbers cannot move.
 * Second, it keeps the whole export/import path free of Android types, so the round-trip
 * test that guards the exit criterion for this phase runs on the plain JVM in
 * milliseconds instead of needing an emulator.
 *
 * The names match the framework constants with the `SUPPRESSED_EFFECT_` prefix dropped.
 */
object SuppressedEffects {

    private val BY_NAME: Map<String, Int> = linkedMapOf(
        "SCREEN_OFF" to (1 shl 0),
        "SCREEN_ON" to (1 shl 1),
        "FULL_SCREEN_INTENT" to (1 shl 2),
        "LIGHTS" to (1 shl 3),
        "PEEK" to (1 shl 4),
        "STATUS_BAR" to (1 shl 5),
        "BADGE" to (1 shl 6),
        "AMBIENT" to (1 shl 7),
        "NOTIFICATION_LIST" to (1 shl 8),
    )

    /** Every bit this build understands; anything else in a stored value is unknown. */
    val KNOWN_MASK: Int = BY_NAME.values.fold(0) { acc, bit -> acc or bit }

    /**
     * Bitmask to names, in a fixed order so exports are byte-stable.
     *
     * Bits outside [KNOWN_MASK] are dropped from the file. They can only come from a
     * newer platform, and inventing a name for them would be worse than omitting them —
     * the importer already reports the loss as a correction.
     */
    fun toNames(mask: Int): List<String> =
        BY_NAME.entries.filter { (mask and it.value) != 0 }.map { it.key }

    /** True when the stored mask contains bits this build cannot name. */
    fun hasUnknownBits(mask: Int): Boolean = (mask and KNOWN_MASK.inv()) != 0

    /**
     * Names back to a bitmask, plus the names that were not recognised.
     *
     * Unknown names are reported rather than ignored: silently dropping "do not light up
     * the screen at night" is exactly the kind of quiet loss that makes a user stop
     * trusting the export.
     */
    fun fromNames(names: List<String>): Decoding {
        var mask = 0
        val unknown = mutableListOf<String>()
        for (name in names) {
            val bit = BY_NAME[name]
            if (bit == null) unknown += name else mask = mask or bit
        }
        return Decoding(mask, unknown)
    }

    data class Decoding(val mask: Int, val unknownNames: List<String>)
}
