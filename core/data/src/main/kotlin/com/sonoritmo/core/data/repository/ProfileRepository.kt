package com.sonoritmo.core.data.repository

import androidx.room.withTransaction
import com.sonoritmo.core.data.database.SonoRitmoDatabase
import com.sonoritmo.core.data.mapper.toDomain
import com.sonoritmo.core.data.mapper.toEntity
import com.sonoritmo.core.domain.model.ProfileId
import com.sonoritmo.core.domain.model.Schedule
import com.sonoritmo.core.domain.model.SoundProfile
import com.sonoritmo.core.domain.model.ValidationIssue
import com.sonoritmo.core.domain.port.TimeSource
import com.sonoritmo.core.domain.port.UuidGenerator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** A profile together with its windows, as the UI and the scheduler want them. */
data class ProfileBundle(
    val profile: SoundProfile,
    val schedules: List<Schedule>,
)

/**
 * The result of a write that the domain is allowed to refuse.
 *
 * Invalid input is an expected outcome of a form, not an exceptional condition, so it is
 * a return value rather than a thrown exception: the caller has to deal with it, and the
 * compiler says so.
 */
sealed interface SaveResult<out T> {
    data class Saved<out T>(val value: T) : SaveResult<T>
    data class Invalid(val issues: List<ValidationIssue>) : SaveResult<Nothing>
}

/**
 * What deleting a profile leaves behind for the caller to finish.
 *
 * The cascade takes care of the windows and `SET NULL` takes care of the references, but
 * three consequences live outside the database and cannot be resolved inside the
 * transaction:
 *
 *  - [orphanedZenRuleId] must be handed to `removeAutomaticZenRule` *after* the
 *    transaction commits. Skip it and the user is left with a Mode in the system settings
 *    that keeps firing and that no screen in this app can remove.
 *  - [wasApplied] means the phone's audio is still configured the way the deleted profile
 *    wanted it. The database no longer says so, but the speaker does; the caller has to
 *    restore the baseline.
 *  - [wasManualOverride] means the user's explicit activation just vanished, which is
 *    worth a history entry rather than silence.
 */
data class ProfileDeletion(
    val deletedUuid: String,
    val orphanedZenRuleId: String?,
    val wasApplied: Boolean,
    val wasManualOverride: Boolean,
)

interface ProfileRepository {

    fun observeAll(): Flow<List<SoundProfile>>

    fun observeEnabled(): Flow<List<SoundProfile>>

    fun observeByUuid(uuid: String): Flow<SoundProfile?>

    fun observeBundles(): Flow<List<ProfileBundle>>

    suspend fun getAll(): List<SoundProfile>

    suspend fun getByUuid(uuid: String): SoundProfile?

    suspend fun count(): Int

    /** Insert or update, keyed on the uuid. Validates and normalises before writing. */
    suspend fun save(profile: SoundProfile): SaveResult<ProfileId>

    /** Copy a profile and all of its windows under fresh uuids, in one transaction. */
    suspend fun duplicate(uuid: String, newName: String): SaveResult<ProfileId>

    suspend fun setEnabled(uuid: String, enabled: Boolean)

    /** Records the system zen rule id handed back by the platform. Never exported. */
    suspend fun setZenRuleId(uuid: String, zenRuleId: String?)

    suspend fun reorder(orderedUuids: List<String>)

    /** Returns null when there was no such profile. See [ProfileDeletion]. */
    suspend fun delete(uuid: String): ProfileDeletion?
}

@Singleton
class ProfileRepositoryImpl @Inject constructor(
    private val database: SonoRitmoDatabase,
    private val timeSource: TimeSource,
    private val uuidGenerator: UuidGenerator,
) : ProfileRepository {

    private val profileDao = database.profileDao()
    private val scheduleDao = database.scheduleDao()
    private val automationDao = database.automationStateDao()

    override fun observeAll(): Flow<List<SoundProfile>> =
        profileDao.observeAll().map { list -> list.map { it.toDomain() } }

    override fun observeEnabled(): Flow<List<SoundProfile>> =
        profileDao.observeEnabled().map { list -> list.map { it.toDomain() } }

    override fun observeByUuid(uuid: String): Flow<SoundProfile?> =
        profileDao.observeByUuid(uuid).map { it?.toDomain() }

    override fun observeBundles(): Flow<List<ProfileBundle>> =
        profileDao.observeAllWithSchedules().map { rows ->
            rows.map { row ->
                ProfileBundle(
                    profile = row.profile.toDomain(),
                    schedules = row.schedules.map { it.toDomain() },
                )
            }
        }

    override suspend fun getAll(): List<SoundProfile> = profileDao.getAll().map { it.toDomain() }

    override suspend fun getByUuid(uuid: String): SoundProfile? =
        profileDao.getByUuid(uuid)?.toDomain()

    override suspend fun count(): Int = profileDao.count()

    override suspend fun save(profile: SoundProfile): SaveResult<ProfileId> {
        // Normalise first, then validate. The order matters: normalisation is what
        // resolves the ringer/ring-volume contradiction, so validating first would
        // reject configurations the model knows how to fix by itself.
        val normalized = profile.normalized().copy(updatedAt = timeSource.now())
        val issues = normalized.validate()
        if (issues.isNotEmpty()) return SaveResult.Invalid(issues)

        val id = profileDao.upsertByUuid(normalized.toEntity())
        return SaveResult.Saved(ProfileId(id))
    }

    override suspend fun duplicate(uuid: String, newName: String): SaveResult<ProfileId> {
        val source = profileDao.getByUuid(uuid)?.toDomain()
            ?: return SaveResult.Invalid(listOf(ValidationIssue.UUID_BLANK))

        val now = timeSource.now()
        val newUuid = uuidGenerator.newUuid()
        // A copy is a new profile, not a variant of an old one: fresh uuid, fresh
        // creation date, and no zen rule id — the copy owns no system rule yet, and
        // sharing one would let deleting either profile remove the other's Mode.
        val copy = source.copy(
            id = ProfileId.UNSAVED,
            uuid = newUuid,
            name = newName,
            zenRuleId = null,
            sortOrder = (profileDao.maxSortOrder() ?: -1) + 1,
            createdAt = now,
            updatedAt = now,
        ).normalized()

        val issues = copy.validate()
        if (issues.isNotEmpty()) return SaveResult.Invalid(issues)

        val sourceSchedules = scheduleDao.getByProfile(uuid)

        return database.withTransaction {
            val newId = profileDao.insert(copy.toEntity(id = 0L))
            val copiedSchedules = sourceSchedules.map { entity ->
                entity.copy(
                    id = 0L,
                    uuid = uuidGenerator.newUuid(),
                    profileUuid = newUuid,
                )
            }
            if (copiedSchedules.isNotEmpty()) scheduleDao.insertAll(copiedSchedules)
            SaveResult.Saved(ProfileId(newId))
        }
    }

    override suspend fun setEnabled(uuid: String, enabled: Boolean) {
        profileDao.setEnabled(uuid, enabled, timeSource.now().toEpochMilli())
    }

    override suspend fun setZenRuleId(uuid: String, zenRuleId: String?) {
        profileDao.setZenRuleId(uuid, zenRuleId)
    }

    override suspend fun reorder(orderedUuids: List<String>) {
        profileDao.reorder(orderedUuids)
    }

    override suspend fun delete(uuid: String): ProfileDeletion? = database.withTransaction {
        val existing = profileDao.getByUuid(uuid) ?: return@withTransaction null
        // Read the collateral *before* the delete: afterwards the zen rule id is gone
        // with the row, and the automation state's references have been nulled by the
        // foreign keys, so there would be no way left to know what the caller must undo.
        val automation = automationDao.get()
        val deletion = ProfileDeletion(
            deletedUuid = uuid,
            orphanedZenRuleId = existing.zenRuleId,
            wasApplied = automation?.appliedProfileUuid == uuid,
            wasManualOverride = automation?.manualProfileUuid == uuid,
        )
        profileDao.deleteByUuid(uuid)
        deletion
    }
}
