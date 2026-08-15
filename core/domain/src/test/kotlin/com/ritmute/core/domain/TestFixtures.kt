package com.ritmute.core.domain

import com.ritmute.core.domain.model.AudioSnapshot
import com.ritmute.core.domain.model.AudioStream
import com.ritmute.core.domain.model.AutomationState
import com.ritmute.core.domain.model.DayMask
import com.ritmute.core.domain.model.RingerMode
import com.ritmute.core.domain.model.Schedule
import com.ritmute.core.domain.model.SchedulingWorld
import com.ritmute.core.domain.model.SoundProfile
import com.ritmute.core.domain.model.StreamLevel
import com.ritmute.core.domain.model.VolumeSettings
import com.ritmute.core.domain.port.UuidGenerator
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

val MADRID: ZoneId = ZoneId.of("Europe/Madrid")
val SANTIAGO: ZoneId = ZoneId.of("America/Santiago")
val UTC: ZoneId = ZoneId.of("UTC")

val EPOCH_BASE: Instant = Instant.parse("2026-01-01T00:00:00Z")

/** Deterministic uuids so assertions can name them. */
class SequentialUuids(private val prefix: String = "uuid-") : UuidGenerator {
    private var counter = 0
    override fun newUuid(): String = "$prefix${++counter}"
}

fun localInstant(
    date: String,
    time: String,
    zone: ZoneId = MADRID,
): Instant = LocalDate.parse(date)
    .atTime(LocalTime.parse(time))
    .atZone(zone)
    .toInstant()

fun profile(
    uuid: String,
    name: String = uuid,
    priority: Int = SoundProfile.DEFAULT_PRIORITY,
    enabled: Boolean = true,
    createdAt: Instant = EPOCH_BASE,
    volumes: VolumeSettings = VolumeSettings(music = 30),
    ringerMode: RingerMode? = null,
    restoreOnExit: Boolean = true,
): SoundProfile = SoundProfile(
    uuid = uuid,
    name = name,
    priority = priority,
    enabled = enabled,
    volumes = volumes,
    ringerMode = ringerMode,
    options = com.ritmute.core.domain.model.ProfileOptions(restoreOnExit = restoreOnExit),
    createdAt = createdAt,
    updatedAt = createdAt,
)

fun schedule(
    uuid: String,
    profileUuid: String,
    start: String,
    end: String,
    daysMask: Int = DayMask.ALL,
    enabled: Boolean = true,
): Schedule {
    val s = LocalTime.parse(start)
    val e = LocalTime.parse(end)
    return Schedule.fromWallClock(
        uuid = uuid,
        profileUuid = profileUuid,
        startHour = s.hour,
        startMinute = s.minute,
        endHour = e.hour,
        endMinute = e.minute,
        daysMask = daysMask,
        enabled = enabled,
    )
}

fun world(
    profiles: List<SoundProfile> = emptyList(),
    schedules: List<Schedule> = emptyList(),
    automation: AutomationState = AutomationState.EMPTY,
    zone: ZoneId = MADRID,
    defaultProfileUuid: String? = null,
    baseline: AudioSnapshot? = null,
): SchedulingWorld = SchedulingWorld.of(
    zoneId = zone,
    profiles = profiles,
    schedules = schedules,
    automation = automation,
    defaultProfileUuid = defaultProfileUuid,
    baseline = baseline,
)

fun snapshot(
    capturedAt: Instant,
    ownerProfileUuid: String? = null,
): AudioSnapshot = AudioSnapshot(
    capturedAt = capturedAt,
    levels = mapOf(
        AudioStream.RING to StreamLevel(steps = 5, minSteps = 0, maxSteps = 7),
        AudioStream.MUSIC to StreamLevel(steps = 10, minSteps = 0, maxSteps = 15),
    ),
    ringerMode = RingerMode.NORMAL,
    interruptionFilter = 1,
    ownerProfileUuid = ownerProfileUuid,
)
