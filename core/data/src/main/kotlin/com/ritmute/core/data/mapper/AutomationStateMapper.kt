package com.ritmute.core.data.mapper

import com.ritmute.core.data.database.entity.AutomationStateEntity
import com.ritmute.core.domain.model.AutomationState

fun AutomationStateEntity.toDomain(): AutomationState = AutomationState(
    globalPauseUntil = globalPauseUntil,
    manualProfileUuid = manualProfileUuid,
    manualUntil = manualUntil,
    manualActivatedAt = manualActivatedAt,
    appliedProfileUuid = appliedProfileUuid,
    appliedScheduleUuid = appliedScheduleUuid,
    appliedAt = appliedAt,
    nextTransitionAt = nextTransitionAt,
    lastReconciliationAt = lastReconciliationAt,
    repairCount = repairCount,
)

/**
 * Only used to seed the singleton row and in tests.
 *
 * Production writes go through the column-scoped `UPDATE`s on
 * [com.ritmute.core.data.database.dao.AutomationStateDao]: this row is written from
 * four different components, and a whole-row write would let one of them silently undo
 * another's field.
 */
fun AutomationState.toEntity(): AutomationStateEntity = AutomationStateEntity(
    id = AutomationStateEntity.SINGLETON_ID,
    globalPauseUntil = globalPauseUntil,
    manualProfileUuid = manualProfileUuid,
    manualUntil = manualUntil,
    manualActivatedAt = manualActivatedAt,
    appliedProfileUuid = appliedProfileUuid,
    appliedScheduleUuid = appliedScheduleUuid,
    appliedAt = appliedAt,
    nextTransitionAt = nextTransitionAt,
    lastReconciliationAt = lastReconciliationAt,
    repairCount = repairCount,
)
