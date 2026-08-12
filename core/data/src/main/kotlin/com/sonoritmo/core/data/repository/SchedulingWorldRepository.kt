package com.sonoritmo.core.data.repository

import com.sonoritmo.core.data.preferences.UserPreferences
import com.sonoritmo.core.domain.model.SchedulingWorld
import com.sonoritmo.core.domain.port.ZoneProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Assembles the one and only input to the domain's decision functions.
 *
 * `:core:system` deliberately does not depend on `:core:data`: the reconciler is *given*
 * the world, it does not go looking for it. This repository is the single place where
 * that world is built, which is what keeps the whole decision path a pure function of
 * one value plus an instant — and therefore reproducible in a unit test with no
 * database, no clock and no device (docs/02, section 5.1).
 */
interface SchedulingWorldRepository {

    /** A fresh read. Used by the alarm receiver and the watchdog. */
    suspend fun load(): SchedulingWorld

    /** Re-emits whenever anything that could change a decision changes. */
    fun observe(): Flow<SchedulingWorld>
}

@Singleton
class SchedulingWorldRepositoryImpl @Inject constructor(
    private val profileRepository: ProfileRepository,
    private val scheduleRepository: ScheduleRepository,
    private val automationRepository: AutomationRepository,
    private val userPreferences: UserPreferences,
    private val zoneProvider: ZoneProvider,
) : SchedulingWorldRepository {

    override suspend fun load(): SchedulingWorld = SchedulingWorld.of(
        // Read fresh on every load, never cached. A cached zone is the classic bug that
        // survives a TIMEZONE_CHANGED broadcast and then quietly misfires for the rest
        // of a trip.
        zoneId = zoneProvider.zone(),
        profiles = profileRepository.getAll(),
        schedules = scheduleRepository.getAll(),
        automation = automationRepository.get(),
        defaultProfileUuid = userPreferences.get().defaultProfileUuid,
        baseline = automationRepository.getBaseline(),
    )

    /**
     * All five sources are combined rather than merged into one query.
     *
     * They live in different places — four tables and a preferences file — and only Room
     * can invalidate its own flows. Combining is what guarantees that a change to *any*
     * of them produces a new world, which is the property the level-triggered design
     * depends on: every emission is a complete answer, never a delta.
     */
    override fun observe(): Flow<SchedulingWorld> = combine(
        profileRepository.observeAll(),
        scheduleRepository.observeAll(),
        automationRepository.observe(),
        automationRepository.observeBaseline(),
        userPreferences.settings.map { it.defaultProfileUuid },
    ) { profiles, schedules, automation, baseline, defaultProfileUuid ->
        SchedulingWorld.of(
            zoneId = zoneProvider.zone(),
            profiles = profiles,
            schedules = schedules,
            automation = automation,
            defaultProfileUuid = defaultProfileUuid,
            baseline = baseline,
        )
    }
}
