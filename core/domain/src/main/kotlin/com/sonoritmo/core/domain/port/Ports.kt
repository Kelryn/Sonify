package com.sonoritmo.core.domain.port

import java.time.Instant
import java.time.ZoneId

/**
 * The only way domain code is allowed to learn the time.
 *
 * `System.currentTimeMillis()` and `ZoneId.systemDefault()` are banned everywhere else in
 * `:core:domain`. Without that rule the DST and time-zone behaviour of the app is
 * untestable, and those are exactly the cases that break scheduling apps.
 */
fun interface TimeSource {
    fun now(): Instant

    companion object {
        fun fixed(instant: Instant): TimeSource = TimeSource { instant }
    }
}

/**
 * Read on every calculation, never cached: caching the zone is the classic bug that
 * survives a `TIMEZONE_CHANGED` broadcast and then silently misfires for the rest of a trip.
 */
fun interface ZoneProvider {
    fun zone(): ZoneId
}

/** Injected so tests can produce stable identifiers. */
fun interface UuidGenerator {
    fun newUuid(): String
}
