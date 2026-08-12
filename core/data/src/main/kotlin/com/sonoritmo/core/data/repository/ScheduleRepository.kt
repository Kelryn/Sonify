package com.sonoritmo.core.data.repository

import com.sonoritmo.core.data.database.SonoRitmoDatabase
import com.sonoritmo.core.data.mapper.toDomain
import com.sonoritmo.core.data.mapper.toEntity
import com.sonoritmo.core.domain.model.DayMask
import com.sonoritmo.core.domain.model.Schedule
import com.sonoritmo.core.domain.model.ScheduleId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.DayOfWeek
import javax.inject.Inject
import javax.inject.Singleton

interface ScheduleRepository {

    fun observeAll(): Flow<List<Schedule>>

    fun observeByProfile(profileUuid: String): Flow<List<Schedule>>

    suspend fun getAll(): List<Schedule>

    suspend fun getByUuid(uuid: String): Schedule?

    suspend fun getByProfile(profileUuid: String): List<Schedule>

    /**
     * Windows that can *start* on the given ISO day, filtered in SQL by the day bitmask.
     *
     * Callers that need everything covering an instant must also ask for the previous
     * day, because a window that crosses midnight is still running the next morning.
     * That expansion is the domain's job — this method only narrows the rows read.
     */
    suspend fun candidatesForDay(day: DayOfWeek): List<Schedule>

    fun observeCandidatesForDay(day: DayOfWeek): Flow<List<Schedule>>

    suspend fun save(schedule: Schedule): SaveResult<ScheduleId>

    suspend fun setEnabled(uuid: String, enabled: Boolean)

    suspend fun delete(uuid: String)

    /** Replace a profile's entire set of windows atomically. */
    suspend fun replaceForProfile(
        profileUuid: String,
        schedules: List<Schedule>,
    ): SaveResult<Unit>
}

@Singleton
class ScheduleRepositoryImpl @Inject constructor(
    database: SonoRitmoDatabase,
) : ScheduleRepository {

    private val scheduleDao = database.scheduleDao()

    override fun observeAll(): Flow<List<Schedule>> =
        scheduleDao.observeAll().map { list -> list.map { it.toDomain() } }

    override fun observeByProfile(profileUuid: String): Flow<List<Schedule>> =
        scheduleDao.observeByProfile(profileUuid).map { list -> list.map { it.toDomain() } }

    override suspend fun getAll(): List<Schedule> = scheduleDao.getAll().map { it.toDomain() }

    override suspend fun getByUuid(uuid: String): Schedule? =
        scheduleDao.getByUuid(uuid)?.toDomain()

    override suspend fun getByProfile(profileUuid: String): List<Schedule> =
        scheduleDao.getByProfile(profileUuid).map { it.toDomain() }

    override suspend fun candidatesForDay(day: DayOfWeek): List<Schedule> =
        scheduleDao.candidatesForDayMask(DayMask.bit(day)).map { it.toDomain() }

    override fun observeCandidatesForDay(day: DayOfWeek): Flow<List<Schedule>> =
        scheduleDao.observeCandidatesForDayMask(DayMask.bit(day))
            .map { list -> list.map { it.toDomain() } }

    override suspend fun save(schedule: Schedule): SaveResult<ScheduleId> {
        // The database cannot help here: Room has no way to declare the CHECK
        // constraints the schema design calls for (days_mask 1..127, start 0..1439,
        // duration 1..1440), so validation at this boundary *is* the constraint.
        val issues = schedule.validate()
        if (issues.isNotEmpty()) return SaveResult.Invalid(issues)

        val id = scheduleDao.upsertByUuid(schedule.toEntity())
        return SaveResult.Saved(ScheduleId(id))
    }

    override suspend fun setEnabled(uuid: String, enabled: Boolean) {
        scheduleDao.setEnabled(uuid, enabled)
    }

    override suspend fun delete(uuid: String) {
        scheduleDao.deleteByUuid(uuid)
    }

    override suspend fun replaceForProfile(
        profileUuid: String,
        schedules: List<Schedule>,
    ): SaveResult<Unit> {
        // Validate the whole set before touching anything: a partially rejected edit
        // would leave the profile with some of the windows the user meant to save, which
        // is worse than saving none of them.
        val issues = schedules.flatMap { it.validate() }.distinct()
        if (issues.isNotEmpty()) return SaveResult.Invalid(issues)

        scheduleDao.replaceForProfile(profileUuid, schedules.map { it.toEntity(id = 0L) })
        return SaveResult.Saved(Unit)
    }
}
