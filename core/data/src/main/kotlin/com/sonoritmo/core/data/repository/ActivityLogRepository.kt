package com.sonoritmo.core.data.repository

import androidx.room.withTransaction
import com.sonoritmo.core.data.database.SonoRitmoDatabase
import com.sonoritmo.core.data.mapper.EnumCodecs
import com.sonoritmo.core.data.mapper.toDomain
import com.sonoritmo.core.data.mapper.toEntity
import com.sonoritmo.core.domain.model.ActivityLogEntry
import com.sonoritmo.core.domain.model.LogType
import com.sonoritmo.core.domain.port.TimeSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** RF-46: the history is filterable. An empty [types] means "all of them". */
data class LogFilter(
    val types: Set<LogType> = emptySet(),
    val profileUuid: String? = null,
    val onlyFailures: Boolean = false,
    val since: Instant? = null,
)

/**
 * RF-38, made concrete.
 *
 * The requirement says "1 000 entries or 30 days", which is ambiguous. The reading
 * implemented here is the conservative one: a row is kept only if it is **both** newer
 * than [maxAge] **and** among the newest [maxEntries]. Anything looser lets a quiet month
 * fill the database with routine watchdog rows.
 *
 * [protectedTypes] are exempt from the count cap and get their own, much longer age
 * limit. Errors and permission changes are rare and are the rows that explain a failure;
 * letting ordinary traffic evict them would gut the one screen that answers "why did this
 * not work".
 */
data class RetentionPolicy(
    val maxEntries: Int = ActivityLogEntry.MAX_ENTRIES,
    val maxAge: Duration = Duration.ofDays(ActivityLogEntry.MAX_AGE_DAYS),
    val protectedTypes: Set<LogType> = setOf(LogType.ERROR, LogType.PERMISSION),
    val protectedMaxAge: Duration = Duration.ofDays(90),
) {
    companion object {
        val DEFAULT = RetentionPolicy()
    }
}

data class PurgeResult(
    val deletedByAge: Int,
    val deletedByCount: Int,
    val protectedDeletedByAge: Int,
) {
    val total: Int get() = deletedByAge + deletedByCount + protectedDeletedByAge
}

interface ActivityLogRepository {

    fun observeRecent(limit: Int = DEFAULT_PAGE): Flow<List<ActivityLogEntry>>

    fun observeFiltered(filter: LogFilter, limit: Int = DEFAULT_PAGE): Flow<List<ActivityLogEntry>>

    suspend fun getPage(limit: Int = DEFAULT_PAGE, offset: Int = 0): List<ActivityLogEntry>

    suspend fun count(): Int

    suspend fun log(entry: ActivityLogEntry)

    /** One transaction for the several lines a single transition produces. */
    suspend fun logAll(entries: List<ActivityLogEntry>)

    suspend fun purge(policy: RetentionPolicy = RetentionPolicy.DEFAULT): PurgeResult

    suspend fun clearAll(): Int

    companion object {
        /**
         * Paging is done with plain limited queries rather than the Paging library: the
         * history is capped at a low four-figure number of rows by [RetentionPolicy], so
         * a whole extra dependency and its lifecycle would buy nothing.
         */
        const val DEFAULT_PAGE = 50
    }
}

@Singleton
class ActivityLogRepositoryImpl @Inject constructor(
    private val database: SonoRitmoDatabase,
    private val timeSource: TimeSource,
) : ActivityLogRepository {

    private val logDao = database.activityLogDao()

    override fun observeRecent(limit: Int): Flow<List<ActivityLogEntry>> =
        logDao.observeRecent(limit).map { list -> list.map { it.toDomain() } }

    override fun observeFiltered(
        filter: LogFilter,
        limit: Int,
    ): Flow<List<ActivityLogEntry>> {
        val codes = filter.types.map { EnumCodecs.code(it) }
        return logDao.observeFiltered(
            typeCodes = codes,
            typeCount = codes.size,
            profileUuid = filter.profileUuid,
            onlyFailures = filter.onlyFailures,
            sinceUtc = filter.since?.toEpochMilli(),
            limit = limit,
        ).map { list -> list.map { it.toDomain() } }
    }

    override suspend fun getPage(limit: Int, offset: Int): List<ActivityLogEntry> =
        logDao.getPage(limit, offset).map { it.toDomain() }

    override suspend fun count(): Int = logDao.count()

    override suspend fun log(entry: ActivityLogEntry) {
        logDao.insert(entry.toEntity().copy(id = 0L))
    }

    override suspend fun logAll(entries: List<ActivityLogEntry>) {
        if (entries.isEmpty()) return
        logDao.insertAll(entries.map { it.toEntity().copy(id = 0L) })
    }

    override suspend fun purge(policy: RetentionPolicy): PurgeResult {
        val now = timeSource.now()
        val protectedCodes = policy.protectedTypes.map { EnumCodecs.code(it) }
        val ordinaryCutoff = now.minus(policy.maxAge).toEpochMilli()
        val protectedCutoff = now.minus(policy.protectedMaxAge).toEpochMilli()

        // One transaction: a purge that half-ran would leave the count cap unapplied and
        // the file already rewritten, and the next pass would do the rest anyway — but
        // the history would be observably inconsistent in between.
        return database.withTransaction {
            val byAge = logDao.purgeOlderThan(ordinaryCutoff, protectedCodes)
            val protectedByAge = logDao.purgeProtectedOlderThan(protectedCutoff, protectedCodes)
            val byCount = logDao.purgeBeyondCount(policy.maxEntries, protectedCodes)
            PurgeResult(
                deletedByAge = byAge,
                deletedByCount = byCount,
                protectedDeletedByAge = protectedByAge,
            )
        }
    }

    override suspend fun clearAll(): Int = logDao.clearAll()
}
