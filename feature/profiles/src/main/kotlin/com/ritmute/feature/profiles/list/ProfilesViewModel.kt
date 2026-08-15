package com.ritmute.feature.profiles.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ritmute.core.data.repository.AutomationRepository
import com.ritmute.core.data.repository.ProfileRepository
import com.ritmute.core.data.repository.SaveResult
import com.ritmute.core.data.repository.ScheduleRepository
import com.ritmute.core.data.repository.SchedulingWorldRepository
import com.ritmute.core.domain.logic.ConflictResolver
import com.ritmute.core.domain.logic.NextTransitionCalculator
import com.ritmute.core.domain.logic.Templates
import com.ritmute.core.domain.model.DesiredState
import com.ritmute.core.domain.model.ProfileTemplate
import com.ritmute.core.domain.model.SchedulingWorld
import com.ritmute.core.domain.port.TimeSource
import com.ritmute.core.domain.port.UuidGenerator
import com.ritmute.core.system.dnd.ZenRuleCleaner
import com.ritmute.core.system.diagnostics.SystemDiagnostics
import com.ritmute.core.system.scheduler.SchedulerCoordinator
import com.ritmute.core.system.scheduler.Trigger
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Home screen state.
 *
 * Note what this class does **not** do: it never decides which profile is active. It asks
 * [ConflictResolver] with the same world the scheduler uses, so the banner cannot disagree
 * with reality — a class of bug that shows up in this category as "the app says Night is on
 * but my phone is ringing".
 */
@HiltViewModel
class ProfilesViewModel @Inject constructor(
    private val profileRepository: ProfileRepository,
    private val scheduleRepository: ScheduleRepository,
    private val automationRepository: AutomationRepository,
    private val worldRepository: SchedulingWorldRepository,
    private val coordinator: SchedulerCoordinator,
    private val diagnostics: SystemDiagnostics,
    private val zenRuleRegistrar: ZenRuleCleaner,
    private val timeSource: TimeSource,
    private val uuidGenerator: UuidGenerator,
) : ViewModel() {

    private val messages = MutableStateFlow<UserMessage?>(null)

    val uiState: StateFlow<ProfilesUiState> =
        combine(
            worldRepository.observe(),
            profileRepository.observeBundles(),
            messages,
        ) { world, bundles, message ->
            val now = timeSource.now()
            val desired = ConflictResolver.resolve(world, now)
            ProfilesUiState(
                isLoading = false,
                rows = bundles.map { bundle ->
                    ProfileRow(
                        profile = bundle.profile,
                        schedules = bundle.schedules,
                        isActive = desired.activeProfileUuid == bundle.profile.uuid,
                    )
                },
                status = statusFor(world, desired, now),
                message = message,
            )
        }
            // statusFor() makes blocking binder calls (alarm manager, usage stats,
            // notification policy). viewModelScope is Main.immediate, so without this the
            // home screen would do system IPC on the UI thread on every emission.
            .flowOn(Dispatchers.Default)
            .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = ProfilesUiState(),
        )

    fun onEvent(event: ProfilesEvent) {
        when (event) {
            is ProfilesEvent.Activate -> activate(event.uuid, null)
            is ProfilesEvent.ActivateFor -> activate(event.uuid, event.minutes)
            ProfilesEvent.Deactivate -> viewModelScope.launch {
                automationRepository.clearManualOverride()
                reconcile(Trigger.USER_INTERACTION)
                messages.value = UserMessage(UserMessage.Kind.PROFILE_DEACTIVATED)
            }
            is ProfilesEvent.ToggleEnabled -> viewModelScope.launch {
                profileRepository.setEnabled(event.uuid, event.enabled)
                reconcile(Trigger.CONFIG_CHANGED)
            }
            is ProfilesEvent.Duplicate -> viewModelScope.launch {
                val original = profileRepository.getByUuid(event.uuid) ?: return@launch
                when (profileRepository.duplicate(event.uuid, "${original.name} 2")) {
                    is SaveResult.Saved -> messages.value =
                        UserMessage(UserMessage.Kind.PROFILE_DUPLICATED, original.name)
                    is SaveResult.Invalid -> messages.value =
                        UserMessage(UserMessage.Kind.SAVE_FAILED, original.name)
                }
            }
            is ProfilesEvent.Delete -> viewModelScope.launch {
                val profile = profileRepository.getByUuid(event.uuid)
                val deletion = profileRepository.delete(event.uuid)
                // The row is gone, but the AutomaticZenRule it registered lives in system
                // settings, not in our database. Left behind it would keep firing, be
                // impossible to remove from inside the app, and burn one of the 100 slots
                // the platform allows per package (docs/02, E-11 and risk N8).
                deletion?.orphanedZenRuleId?.let { zenRuleRegistrar.removeRule(it) }
                // Deleting the applied profile leaves the device on its settings; a
                // reconciliation puts things back where they belong.
                reconcile(Trigger.CONFIG_CHANGED)
                messages.value = UserMessage(UserMessage.Kind.PROFILE_DELETED, profile?.name)
            }
            is ProfilesEvent.CreateFromTemplate -> createFromTemplate(event.template, event.name)
            is ProfilesEvent.PauseAll -> viewModelScope.launch {
                val until = event.minutes?.let { timeSource.now().plus(Duration.ofMinutes(it.toLong())) }
                automationRepository.setGlobalPause(until ?: FAR_FUTURE_PAUSE(timeSource.now()))
                reconcile(Trigger.USER_INTERACTION)
                messages.value = UserMessage(UserMessage.Kind.PAUSE_STARTED)
            }
            ProfilesEvent.ResumeAll -> viewModelScope.launch {
                automationRepository.clearGlobalPause()
                reconcile(Trigger.USER_INTERACTION)
                messages.value = UserMessage(UserMessage.Kind.PAUSE_CLEARED)
            }
            ProfilesEvent.DismissMessage -> messages.value = null
            ProfilesEvent.Refresh -> viewModelScope.launch { reconcile(Trigger.USER_INTERACTION) }
        }
    }

    private fun activate(uuid: String, minutes: Int?) = viewModelScope.launch {
        val now = timeSource.now()
        val until = minutes?.let { now.plus(Duration.ofMinutes(it.toLong())) }
        automationRepository.setManualOverride(uuid, until, now)
        reconcile(Trigger.USER_INTERACTION)
        val profile = profileRepository.getByUuid(uuid)
        messages.value = UserMessage(UserMessage.Kind.PROFILE_ACTIVATED, profile?.name)
    }

    private fun createFromTemplate(template: ProfileTemplate, name: String) = viewModelScope.launch {
        val result = Templates.build(template, name, timeSource, uuidGenerator)
        when (profileRepository.save(result.profile)) {
            is SaveResult.Saved -> {
                result.schedules.forEach { scheduleRepository.save(it) }
                reconcile(Trigger.CONFIG_CHANGED)
                // Without this the sheet closed and the list took a moment to update, which
                // reads as "the button did nothing" — the same complaint as the editor.
                messages.value = UserMessage(UserMessage.Kind.PROFILE_CREATED, name)
            }
            is SaveResult.Invalid -> messages.value = UserMessage(UserMessage.Kind.SAVE_FAILED)
        }
    }

    private suspend fun reconcile(trigger: Trigger) {
        coordinator.reconcile(trigger)
    }

    private suspend fun statusFor(
        world: SchedulingWorld,
        desired: DesiredState,
        now: Instant,
    ): ActiveStatus {
        val health = diagnostics.report(world.profile(world.automation.appliedProfileUuid))
        if (!health.notificationPolicyAccessGranted || !health.canScheduleExactAlarms) {
            return ActiveStatus.Degraded(
                missingPolicyAccess = !health.notificationPolicyAccessGranted,
                inexactAlarms = !health.canScheduleExactAlarms,
            )
        }
        val next = NextTransitionCalculator.nextTransition(world, now)
        return when (desired) {
            is DesiredState.Paused -> ActiveStatus.Paused(Instant.ofEpochMilli(desired.untilEpochMillis))
            is DesiredState.Active -> ActiveStatus.Running(
                profile = desired.profile,
                source = desired.source,
                schedule = world.schedules.firstOrNull { it.uuid == desired.scheduleUuid },
                endsAt = next,
            )
            DesiredState.Idle -> {
                val upcoming = ConflictResolver.resolve(world, next)
                ActiveStatus.Idle(
                    nextProfile = (upcoming as? DesiredState.Active)?.profile,
                    nextAt = next,
                )
            }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L

        /**
         * "Pause indefinitely" is stored as a far-future instant rather than `null`,
         * because `null` already means "not paused" and a nullable-with-two-meanings field
         * is how this kind of state ends up stuck on.
         */
        val FAR_FUTURE_PAUSE: (Instant) -> Instant = { it.plus(Duration.ofDays(365)) }
    }
}
