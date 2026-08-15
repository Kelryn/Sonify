package com.ritmute.core.data.mapper

import com.ritmute.core.data.database.entity.ActivityLogEntity
import com.ritmute.core.domain.model.ActivityLogEntry

fun ActivityLogEntity.toDomain(): ActivityLogEntry = ActivityLogEntry(
    id = id,
    timestamp = timestamp,
    zoneId = zoneId,
    utcOffsetSeconds = utcOffsetSeconds,
    type = type,
    reason = reason,
    paramsJson = paramsJson,
    profileUuid = profileUuid,
    profileNameSnapshot = profileName,
    scheduleUuid = scheduleUuid,
    success = success,
    detail = detail,
)

/**
 * Domain to entity, with the two size limits applied here rather than at the call sites.
 *
 * [ActivityLogEntry.paramsJson] and [ActivityLogEntry.detail] are the only free-form
 * fields in the schema, and both are written from exception handlers — the one context
 * where a pathological string (a stack trace, a vendor error blob) is most likely and
 * least expected. Truncating at the persistence boundary means no caller has to remember,
 * and the history cannot become the reason the database grows.
 */
fun ActivityLogEntry.toEntity(): ActivityLogEntity = ActivityLogEntity(
    id = id,
    timestamp = timestamp,
    zoneId = zoneId,
    utcOffsetSeconds = utcOffsetSeconds,
    type = type,
    reason = reason,
    paramsJson = paramsJson?.take(MAX_PARAMS_LENGTH),
    profileUuid = profileUuid,
    profileName = profileNameSnapshot,
    scheduleUuid = scheduleUuid,
    success = success,
    detail = detail?.take(MAX_DETAIL_LENGTH),
)

/** 2 KB of structured parameters is already far more than any message needs. */
const val MAX_PARAMS_LENGTH = 2_048

/** 1 KB of technical detail: an exception class and message, never a stack trace. */
const val MAX_DETAIL_LENGTH = 1_024
