package com.sonoritmo.core.data.mapper

import com.sonoritmo.core.data.database.entity.ScheduleEntity
import com.sonoritmo.core.domain.model.Schedule
import com.sonoritmo.core.domain.model.ScheduleId

fun ScheduleEntity.toDomain(): Schedule = Schedule(
    id = ScheduleId(id),
    uuid = uuid,
    profileUuid = profileUuid,
    enabled = enabled,
    startMinuteOfDay = startMinute,
    durationMinutes = durationMinutes,
    daysMask = daysMask,
    label = label,
)

fun Schedule.toEntity(id: Long = this.id.value): ScheduleEntity = ScheduleEntity(
    id = id,
    uuid = uuid,
    profileUuid = profileUuid,
    enabled = enabled,
    startMinute = startMinuteOfDay,
    durationMinutes = durationMinutes,
    daysMask = daysMask,
    label = label,
)
