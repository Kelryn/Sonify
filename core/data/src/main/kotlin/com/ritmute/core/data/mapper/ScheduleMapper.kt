package com.ritmute.core.data.mapper

import com.ritmute.core.data.database.entity.ScheduleEntity
import com.ritmute.core.domain.model.Schedule
import com.ritmute.core.domain.model.ScheduleId

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
