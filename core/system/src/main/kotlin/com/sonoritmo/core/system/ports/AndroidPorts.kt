package com.sonoritmo.core.system.ports

import com.sonoritmo.core.domain.port.TimeSource
import com.sonoritmo.core.domain.port.UuidGenerator
import com.sonoritmo.core.domain.port.ZoneProvider
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Android-side implementations of the domain's ports.
 *
 * There are exactly three of them and they are three one-liners, which is the point: this
 * file is the **only** place in the entire application allowed to call
 * `Instant.now()`, `ZoneId.systemDefault()` or `UUID.randomUUID()`. Everywhere else those
 * calls are banned, so that daylight saving, time-zone changes and identifier generation are
 * all testable with a fixed clock instead of being untestable by construction.
 *
 * `:core:domain` cannot provide them itself: it has no Hilt, by design.
 */
@Singleton
class SystemTimeSource @Inject constructor() : TimeSource {
    override fun now(): Instant = Instant.now()
}

/**
 * Reads the zone on **every** call, and caches nothing.
 *
 * A cached `ZoneId` is the classic bug in this category of app: it survives a
 * `TIMEZONE_CHANGED` broadcast and then quietly misfires every transition for the rest of a
 * trip. The `@Singleton` here is the object, not the value.
 */
@Singleton
class SystemZoneProvider @Inject constructor() : ZoneProvider {
    override fun zone(): ZoneId = ZoneId.systemDefault()
}

/**
 * Random v4 UUIDs, used as the business identity that travels in the export JSON.
 *
 * `randomUUID` and not a time- or counter-based scheme: the identifier must be unique
 * across devices that never talk to each other, and it must not leak when a profile was
 * created or how many the user has. It is also the last tie-breaker in the conflict
 * resolver, so two devices holding the same imported configuration resolve overlaps
 * identically. See docs/02, decision D-C6.
 */
@Singleton
class RandomUuidGenerator @Inject constructor() : UuidGenerator {
    override fun newUuid(): String = UUID.randomUUID().toString()
}
