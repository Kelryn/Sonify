package com.ritmute.core.data.repository

import com.ritmute.core.data.database.RitMuteDatabase
import com.ritmute.core.data.mapper.toDomain
import com.ritmute.core.data.mapper.toEntity
import com.ritmute.core.domain.model.AudioSnapshot
import com.ritmute.core.domain.model.AutomationState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The runtime state of automation: the singleton [AutomationState] row and the single
 * audio baseline.
 *
 * The two live together on purpose. They are read and written by the same components at
 * the same moments — an activation writes "applied" and may capture a baseline; a
 * deactivation clears "applied" and consumes it — and splitting them across two
 * repositories would only invite one to be updated without the other.
 *
 * None of this is in DataStore. Every field here either references a profile or takes
 * part in conflict resolution, and DataStore shares no transaction with the database, so
 * deleting a profile could not clean up after itself (docs/02, amendment E-08).
 */
interface AutomationRepository {

    fun observe(): Flow<AutomationState>

    suspend fun get(): AutomationState

    /** @param until `null` clears the pause; a value in the past is simply already over. */
    suspend fun setGlobalPause(until: Instant?)

    suspend fun clearGlobalPause()

    /**
     * @param until `null` with a profile set means an indefinite activation. There is no
     *   sentinel date: the model's single convention is that `null` means "unbounded".
     */
    suspend fun setManualOverride(profileUuid: String, until: Instant?, activatedAt: Instant)

    suspend fun clearManualOverride()

    suspend fun recordApplied(profileUuid: String?, scheduleUuid: String?, at: Instant?)

    suspend fun recordNextTransition(at: Instant?)

    /** @param repaired true when the pass found the device in the wrong state and fixed it. */
    suspend fun recordReconciliation(at: Instant, repaired: Boolean)

    // ── Baseline ─────────────────────────────────────────────────────────────

    fun observeBaseline(): Flow<AudioSnapshot?>

    suspend fun getBaseline(): AudioSnapshot?

    /**
     * Capture the pre-automation state, but only if nothing is captured yet.
     *
     * Returns false when a baseline already existed, which is the normal outcome of a
     * profile-to-profile transition. Overwriting there would replace the user's real
     * settings with the outgoing profile's, permanently (docs/02, amendment E-07).
     */
    suspend fun captureBaselineIfAbsent(
        snapshot: AudioSnapshot,
        deviceFingerprint: String,
    ): Boolean

    /**
     * Read and delete the baseline in one transaction, discarding it if it was captured
     * on another device.
     *
     * Returns null both when there was nothing to restore and when the snapshot belonged
     * to different hardware; either way the caller must not restore, and either way the
     * stale row is gone.
     */
    suspend fun consumeBaseline(deviceFingerprint: String): AudioSnapshot?

    suspend fun clearBaseline()
}

@Singleton
class AutomationRepositoryImpl @Inject constructor(
    database: RitMuteDatabase,
) : AutomationRepository {

    private val automationDao = database.automationStateDao()
    private val snapshotDao = database.snapshotDao()

    /**
     * A missing row reads as [AutomationState.EMPTY], never as an error.
     *
     * The row is absent exactly once — on a fresh install, before anything has been
     * scheduled — and "nothing is paused, nothing is applied" is the truthful answer for
     * that state. Forcing every caller to handle a nullable here would add a branch that
     * can only ever be taken in that one harmless case.
     */
    override fun observe(): Flow<AutomationState> =
        automationDao.observe().map { it?.toDomain() ?: AutomationState.EMPTY }

    override suspend fun get(): AutomationState =
        automationDao.get()?.toDomain() ?: AutomationState.EMPTY

    override suspend fun setGlobalPause(until: Instant?) {
        automationDao.setGlobalPause(until?.toEpochMilli())
    }

    override suspend fun clearGlobalPause() {
        automationDao.setGlobalPause(null)
    }

    override suspend fun setManualOverride(
        profileUuid: String,
        until: Instant?,
        activatedAt: Instant,
    ) {
        automationDao.setManualOverride(
            profileUuid = profileUuid,
            untilUtc = until?.toEpochMilli(),
            activatedAtUtc = activatedAt.toEpochMilli(),
        )
    }

    override suspend fun clearManualOverride() {
        automationDao.setManualOverride(profileUuid = null, untilUtc = null, activatedAtUtc = null)
    }

    override suspend fun recordApplied(
        profileUuid: String?,
        scheduleUuid: String?,
        at: Instant?,
    ) {
        automationDao.setApplied(profileUuid, scheduleUuid, at?.toEpochMilli())
    }

    override suspend fun recordNextTransition(at: Instant?) {
        automationDao.setNextTransition(at?.toEpochMilli())
    }

    override suspend fun recordReconciliation(at: Instant, repaired: Boolean) {
        automationDao.recordReconciliation(at.toEpochMilli(), repaired)
    }

    override fun observeBaseline(): Flow<AudioSnapshot?> =
        snapshotDao.observeBaseline().map { it?.toDomain() }

    override suspend fun getBaseline(): AudioSnapshot? = snapshotDao.getBaseline()?.toDomain()

    override suspend fun captureBaselineIfAbsent(
        snapshot: AudioSnapshot,
        deviceFingerprint: String,
    ): Boolean = snapshotDao.captureIfAbsent(snapshot.toEntity(deviceFingerprint))

    override suspend fun consumeBaseline(deviceFingerprint: String): AudioSnapshot? =
        snapshotDao.consumeBaselineForDevice(deviceFingerprint)?.toDomain()

    override suspend fun clearBaseline() {
        snapshotDao.clearBaseline()
    }
}
