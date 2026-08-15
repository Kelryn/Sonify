package com.ritmute.core.domain.model

/**
 * Ringer mode a profile wants to enforce.
 *
 * There is no `UNCHANGED` member on purpose: the field that holds this type is
 * nullable and `null` means "do not touch" (docs/02, amendment E-03). A sentinel
 * would contaminate every exhaustive `when` in the audio layer with a branch that
 * is not actually a mode.
 *
 * There is also no separate "vibration" axis. `Settings.System.VIBRATE_WHEN_RINGING`
 * is deprecated, requires the special `WRITE_SETTINGS` access, and its own javadoc
 * states apps should not apply it. [VIBRATE] is the only portable, legitimate
 * vibration control. See docs/02, amendment E-14.
 */
enum class RingerMode {
    NORMAL,
    VIBRATE,
    SILENT,
    ;

    /** True when the mode makes the ring stream inaudible, so `volumes.ring` is moot. */
    val silencesRing: Boolean get() = this == VIBRATE || this == SILENT
}
