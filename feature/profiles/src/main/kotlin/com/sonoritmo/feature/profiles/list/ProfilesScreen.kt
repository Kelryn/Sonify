package com.sonoritmo.feature.profiles.list

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sonoritmo.core.domain.model.ActivationSource
import com.sonoritmo.core.domain.model.AudioStream
import com.sonoritmo.core.domain.model.ProfileTemplate
import com.sonoritmo.core.ui.component.ActiveStateBanner
import com.sonoritmo.core.ui.component.ActiveStateKind
import com.sonoritmo.core.ui.component.EmptyState
import com.sonoritmo.core.ui.component.ProfileCard
import com.sonoritmo.core.ui.component.ScheduleSummary
import com.sonoritmo.core.ui.component.VolumeBadge
import com.sonoritmo.core.ui.component.VolumeState
import com.sonoritmo.core.ui.format.ScheduleFormatter
import com.sonoritmo.feature.profiles.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfilesScreen(
    onEditProfile: (String?) -> Unit,
    onOpenDiagnostics: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ProfilesViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var templateSheetOpen by rememberSaveable { mutableStateOf(false) }
    var durationSheetFor by rememberSaveable { mutableStateOf<String?>(null) }

    val message = state.message
    val messageText = message?.let { userMessageText(it) }
    LaunchedEffect(message) {
        if (messageText != null) {
            snackbarHostState.showSnackbar(messageText)
            viewModel.onEvent(ProfilesEvent.DismissMessage)
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.profiles_title)) },
                actions = {
                    // One control, not two: a pause button that still says "pause" while
                    // everything is paused gives the user no way back and no way to tell
                    // which state they are in.
                    val paused = state.status is ActiveStatus.Paused
                    IconButton(
                        onClick = {
                            viewModel.onEvent(
                                if (paused) {
                                    ProfilesEvent.ResumeAll
                                } else {
                                    ProfilesEvent.PauseAll(PAUSE_DEFAULT_MINUTES)
                                },
                            )
                        },
                    ) {
                        Icon(
                            imageVector = if (paused) Icons.Filled.PlayCircle else Icons.Filled.PauseCircle,
                            contentDescription = stringResource(
                                if (paused) R.string.resume_all else R.string.pause_all,
                            ),
                        )
                    }
                    IconButton(onClick = onOpenDiagnostics) {
                        Icon(Icons.Filled.Tune, stringResource(R.string.banner_degraded_action))
                    }
                },
            )
        },
        floatingActionButton = {
            // Opens the template sheet rather than an empty editor: the sheet's first entry
            // is the empty profile, so nothing is lost and the six ready-made starting
            // points stop being hidden behind a second control.
            FloatingActionButton(onClick = { templateSheetOpen = true }) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.profiles_add))
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            StatusBanner(
                status = state.status,
                onResume = { viewModel.onEvent(ProfilesEvent.ResumeAll) },
                onDeactivate = { viewModel.onEvent(ProfilesEvent.Deactivate) },
                onFix = onOpenDiagnostics,
            )

            if (!state.isLoading && state.rows.isEmpty()) {
                // No action of its own: the floating + directly below opens the very same
                // sheet, and two buttons for one thing on an otherwise empty screen reads
                // as though they must do something different.
                EmptyState(
                    icon = Icons.Filled.Add,
                    title = stringResource(R.string.profiles_empty_title),
                    body = stringResource(R.string.profiles_empty_body),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
                ) {
                    items(state.rows, key = { it.profile.uuid }) { row ->
                        ProfileRowItem(
                            row = row,
                            onActivate = { viewModel.onEvent(ProfilesEvent.Activate(row.profile.uuid)) },
                            onEdit = { onEditProfile(row.profile.uuid) },
                            onActivateFor = { durationSheetFor = row.profile.uuid },
                            onDeactivate = { viewModel.onEvent(ProfilesEvent.Deactivate) },
                            onDuplicate = { viewModel.onEvent(ProfilesEvent.Duplicate(row.profile.uuid)) },
                            onDelete = { viewModel.onEvent(ProfilesEvent.Delete(row.profile.uuid)) },
                            onToggleEnabled = {
                                viewModel.onEvent(
                                    ProfilesEvent.ToggleEnabled(row.profile.uuid, !row.profile.enabled),
                                )
                            },
                        )
                    }
                }
            }
        }
    }

    if (templateSheetOpen) {
        ModalBottomSheet(onDismissRequest = { templateSheetOpen = false }) {
            Column(modifier = Modifier.padding(bottom = 32.dp)) {
                // Not a ProfileTemplate: an empty profile changes nothing, so building and
                // saving one would be rejected by validation on the spot. It opens the
                // editor instead, which is what "start from nothing" actually means.
                TextButton(
                    onClick = {
                        templateSheetOpen = false
                        onEditProfile(null)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.template_blank))
                }
                ProfileTemplate.entries.forEach { template ->
                    val label = templateLabel(template)
                    TextButton(
                        onClick = {
                            viewModel.onEvent(ProfilesEvent.CreateFromTemplate(template, label))
                            templateSheetOpen = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(label)
                    }
                }
            }
        }
    }

    val durationTarget = durationSheetFor
    if (durationTarget != null) {
        ModalBottomSheet(onDismissRequest = { durationSheetFor = null }) {
            Column(modifier = Modifier.padding(bottom = 32.dp)) {
                DURATION_CHOICES.forEach { (labelRes, minutes) ->
                    TextButton(
                        onClick = {
                            viewModel.onEvent(ProfilesEvent.ActivateFor(durationTarget, minutes))
                            durationSheetFor = null
                        },
                        // fillMaxWidth, not fillMaxSize: inside a Column every child asking
                        // for the full height fights the others for it.
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(labelRes))
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusBanner(
    status: ActiveStatus,
    onResume: () -> Unit,
    onDeactivate: () -> Unit,
    onFix: () -> Unit,
) {
    when (status) {
        ActiveStatus.Loading -> Unit

        is ActiveStatus.Running -> {
            val reason = when (status.source) {
                ActivationSource.MANUAL -> stringResource(R.string.banner_active_manual)
                ActivationSource.SCHEDULE -> status.schedule?.let { ScheduleFormatter.full(it) }
            }
            val next = status.endsAt?.let { stringResource(R.string.banner_active_until, shortTime(it)) }
                ?: stringResource(R.string.banner_active_indefinite)
            val title = stringResource(R.string.banner_active_title, status.profile.name)
            ActiveStateBanner(
                kind = ActiveStateKind.ACTIVE,
                title = title,
                reason = reason,
                nextUp = next,
                semanticSummary = stringResource(R.string.banner_summary_active, status.profile.name, next),
                // Only a manual activation can be undone: a scheduled one would come
                // straight back on the next reconciliation, so offering the button there
                // would promise something the scheduler is about to overrule.
                actionLabel = if (status.source == ActivationSource.MANUAL) {
                    stringResource(R.string.profile_deactivate)
                } else {
                    null
                },
                onAction = onDeactivate,
            )
        }

        is ActiveStatus.Idle -> {
            val next = status.nextProfile?.let { profile ->
                stringResource(
                    R.string.banner_idle_next,
                    profile.name,
                    status.nextAt?.let { shortTime(it) }.orEmpty(),
                )
            } ?: stringResource(R.string.banner_idle_no_next)
            ActiveStateBanner(
                kind = ActiveStateKind.IDLE,
                title = stringResource(R.string.banner_idle_title),
                reason = null,
                nextUp = next,
                semanticSummary = stringResource(R.string.banner_summary_idle, next),
            )
        }

        is ActiveStatus.Paused -> {
            val until = stringResource(R.string.banner_paused_until, shortTime(status.until))
            ActiveStateBanner(
                kind = ActiveStateKind.PAUSED,
                title = stringResource(R.string.banner_paused_title),
                reason = null,
                nextUp = until,
                semanticSummary = stringResource(R.string.banner_summary_paused, until),
                actionLabel = stringResource(R.string.banner_paused_action),
                onAction = onResume,
            )
        }

        is ActiveStatus.Degraded -> {
            val detail = if (status.missingPolicyAccess) {
                stringResource(R.string.banner_degraded_dnd)
            } else {
                stringResource(R.string.banner_degraded_alarms)
            }
            ActiveStateBanner(
                kind = ActiveStateKind.DEGRADED,
                title = stringResource(R.string.banner_degraded_title),
                reason = detail,
                nextUp = null,
                semanticSummary = stringResource(R.string.banner_summary_degraded, detail),
                actionLabel = stringResource(R.string.banner_degraded_action),
                onAction = onFix,
            )
        }
    }
}

@Composable
private fun ProfileRowItem(
    row: ProfileRow,
    onActivate: () -> Unit,
    onEdit: () -> Unit,
    onActivateFor: () -> Unit,
    onDeactivate: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onToggleEnabled: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val profile = row.profile

    // Every writable stream, in the model's own order, so the row reads the same on every
    // card and a missing icon can only ever mean the stream does not exist.
    val volumes = AudioStream.WRITABLE.map { stream ->
        val percent = profile.volumes[stream]
        VolumeBadge(
            icon = ScheduleFormatter.streamIcon(stream),
            state = when {
                percent == null -> VolumeState.UNCHANGED
                percent == 0 -> VolumeState.SILENCED
                else -> VolumeState.SET
            },
            contentDescription = stringResource(
                R.string.editor_volume_of,
                ScheduleFormatter.streamLabel(stream),
                ScheduleFormatter.volumeLabel(percent),
            ),
        )
    }

    val schedules = row.schedules.map { schedule ->
        ScheduleSummary(
            daysMask = schedule.daysMask,
            range = ScheduleFormatter.range(schedule),
        )
    }

    val stateDescription = when {
        !profile.enabled -> stringResource(R.string.profile_state_disabled)
        row.isActive -> stringResource(R.string.profile_state_active)
        else -> stringResource(R.string.profile_state_inactive)
    }

    ProfileCard(
        name = profile.name,
        emoji = profile.emoji,
        volumes = volumes,
        schedules = schedules,
        noScheduleLabel = stringResource(R.string.profile_no_schedule),
        isActive = row.isActive,
        isEnabled = profile.enabled,
        accentColor = Color(profile.colorSeed),
        activateContentDescription = stringResource(R.string.profile_activate, profile.name),
        activeStateDescription = stateDescription,
        editContentDescription = stringResource(R.string.profile_edit, profile.name),
        moreContentDescription = stringResource(R.string.profile_more, profile.name),
        onActivate = onActivate,
        onEdit = onEdit,
        onMore = { menuOpen = true },
        overflowMenu = {
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.profile_activate_for)) },
                    onClick = { menuOpen = false; onActivateFor() },
                )
                // Only on the profile that is actually on. "Until I turn it off" was
                // offered with nothing anywhere in the app that turned it off.
                if (row.isActive) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.profile_deactivate)) },
                        onClick = { menuOpen = false; onDeactivate() },
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.profile_duplicate)) },
                    onClick = { menuOpen = false; onDuplicate() },
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(
                                if (profile.enabled) R.string.profile_disable else R.string.profile_enable,
                            ),
                        )
                    },
                    onClick = { menuOpen = false; onToggleEnabled() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.profile_delete)) },
                    onClick = { menuOpen = false; onDelete() },
                )
            }
        },
    )
}


@Composable
internal fun templateLabel(template: ProfileTemplate): String = stringResource(
    when (template) {
        ProfileTemplate.NIGHT -> R.string.template_night
        ProfileTemplate.WORK -> R.string.template_work
        ProfileTemplate.MEETING -> R.string.template_meeting
        ProfileTemplate.CINEMA -> R.string.template_cinema
        ProfileTemplate.DRIVING -> R.string.template_driving
        ProfileTemplate.WEEKEND -> R.string.template_weekend
    },
)

@Composable
private fun userMessageText(message: UserMessage): String = when (message.kind) {
    UserMessage.Kind.PROFILE_ACTIVATED -> stringResource(R.string.msg_activated, message.argument.orEmpty())
    UserMessage.Kind.PROFILE_DEACTIVATED -> stringResource(R.string.msg_deactivated)
    UserMessage.Kind.PROFILE_DELETED -> stringResource(R.string.msg_deleted, message.argument.orEmpty())
    UserMessage.Kind.PROFILE_DUPLICATED -> stringResource(R.string.msg_duplicated, message.argument.orEmpty())
    UserMessage.Kind.PROFILE_CREATED -> stringResource(R.string.msg_created, message.argument.orEmpty())
    UserMessage.Kind.PAUSE_STARTED -> stringResource(R.string.msg_pause_started)
    UserMessage.Kind.PAUSE_CLEARED -> stringResource(R.string.msg_pause_cleared)
    UserMessage.Kind.SAVE_FAILED -> stringResource(R.string.msg_save_failed)
}

private fun shortTime(instant: Instant): String =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
        .withZone(ZoneId.systemDefault())
        .format(instant)

private const val PAUSE_DEFAULT_MINUTES = 60

private val DURATION_CHOICES = listOf(
    R.string.duration_30_min to 30,
    R.string.duration_1_hour to 60,
    R.string.duration_2_hours to 120,
    R.string.duration_indefinite to null,
)
