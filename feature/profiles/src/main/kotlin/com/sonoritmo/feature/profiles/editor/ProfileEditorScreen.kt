package com.sonoritmo.feature.profiles.editor

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sonoritmo.core.domain.model.DndMode
import com.sonoritmo.core.domain.model.ProfileOptions
import com.sonoritmo.core.domain.model.RingerMode
import com.sonoritmo.core.domain.model.Schedule
import com.sonoritmo.core.domain.model.SoundProfile
import com.sonoritmo.core.domain.model.ValidationIssue
import com.sonoritmo.core.ui.component.DayPicker
import com.sonoritmo.core.ui.component.SectionHeader
import com.sonoritmo.core.ui.component.VolumeSliderRow
import com.sonoritmo.core.ui.format.ScheduleFormatter
import com.sonoritmo.feature.profiles.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileEditorScreen(
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ProfileEditorViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.saved) {
        if (state.saved) onDone()
    }

    // Keyed on the rejection count, not on the issue list: the same problems twice in a row
    // still deserve a second answer. The card below says what is wrong; this is what tells
    // someone who tapped the save action in the top bar that anything happened at all.
    val issueTitle = stringResource(R.string.issue_title)
    LaunchedEffect(state.rejectedSaves) {
        if (state.rejectedSaves > 0) snackbarHostState.showSnackbar(issueTitle)
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (state.isNew) R.string.editor_title_new else R.string.editor_title_edit,
                        ),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.onSave() }) {
                        Icon(Icons.Filled.Check, stringResource(R.string.editor_save))
                    }
                },
            )
        },
    ) { padding ->
        val profile = state.profile
        if (profile == null) return@Scaffold

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            OutlinedTextField(
                value = profile.name,
                onValueChange = viewModel::onNameChange,
                label = { Text(stringResource(R.string.editor_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            VolumesSection(profile, state.capabilities, state.ringNotificationCoupled, viewModel)
            RingerSection(profile, viewModel)
            DndSection(profile, viewModel)
            SchedulesSection(state, viewModel)
            OptionsSection(profile, viewModel)

            IssuesCard(state.issues)

            Button(
                onClick = { viewModel.onSave() },
                modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
            ) {
                Text(stringResource(R.string.editor_save))
            }
        }
    }
}

/**
 * What is blocking the save, in words.
 *
 * The editor has always refused to write an invalid profile; until this card existed it
 * refused in complete silence. That was not a cosmetic gap: a profile that has only been
 * given a name trips `PROFILE_CHANGES_NOTHING` by construction, so the first save of every
 * new profile failed, and creating one at all was impossible without guessing why.
 */
@Composable
private fun IssuesCard(issues: List<ValidationIssue>) {
    if (issues.isEmpty()) return

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp)
            // Assertive rather than polite: the user has just pressed a button that did
            // nothing visible, and TalkBack should say why without waiting its turn.
            .semantics { liveRegion = LiveRegionMode.Assertive },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.issue_title),
                style = MaterialTheme.typography.titleSmall,
            )
            issues.forEach { issue ->
                Text(
                    text = issueText(issue),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun issueText(issue: ValidationIssue): String = stringResource(
    when (issue) {
        ValidationIssue.NAME_BLANK -> R.string.issue_name_blank
        ValidationIssue.NAME_TOO_LONG -> R.string.issue_name_too_long
        ValidationIssue.UUID_BLANK -> R.string.issue_uuid_blank
        ValidationIssue.PRIORITY_OUT_OF_RANGE -> R.string.issue_priority_out_of_range
        ValidationIssue.EMOJI_TOO_LONG -> R.string.issue_emoji_too_long
        ValidationIssue.TRANSITION_OUT_OF_RANGE -> R.string.issue_transition_out_of_range
        ValidationIssue.VOLUME_OUT_OF_RANGE -> R.string.issue_volume_out_of_range
        ValidationIssue.RINGER_CONTRADICTS_VOLUME -> R.string.issue_ringer_contradicts_volume
        ValidationIssue.PROFILE_CHANGES_NOTHING -> R.string.issue_profile_changes_nothing
        ValidationIssue.SCHEDULE_NO_DAYS -> R.string.issue_schedule_no_days
        ValidationIssue.SCHEDULE_START_OUT_OF_RANGE -> R.string.issue_schedule_start_out_of_range
        ValidationIssue.SCHEDULE_DURATION_OUT_OF_RANGE -> R.string.issue_schedule_duration_out_of_range
        ValidationIssue.SCHEDULE_ORPHANED -> R.string.issue_schedule_orphaned
    },
)

@Composable
private fun VolumesSection(
    profile: SoundProfile,
    capabilities: List<StreamCapability>,
    coupled: Boolean,
    viewModel: ProfileEditorViewModel,
) {
    SectionHeader(stringResource(R.string.editor_section_volumes))

    if (coupled) {
        Text(
            text = stringResource(R.string.editor_ring_coupled),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    capabilities.forEach { capability ->
        val stream = capability.stream
        val label = ScheduleFormatter.streamLabel(stream)
        val percent = profile.volumes[stream]
        VolumeSliderRow(
            label = label,
            icon = ScheduleFormatter.streamIcon(stream),
            percent = percent,
            steps = capability.steps,
            isSupported = capability.supported,
            // Accessibility is listed and explained rather than hidden: silently dropping a
            // stream would look like a bug, and saying why is the honest version.
            supportingText = if (!capability.supported) {
                stringResource(R.string.editor_stream_unsupported)
            } else {
                null
            },
            enabledSwitchDescription = stringResource(R.string.editor_volume_toggle, label),
            valueDescription = stringResource(
                R.string.editor_volume_of,
                label,
                ScheduleFormatter.volumeLabel(percent),
            ),
            onPercentChange = { viewModel.onVolumeChange(stream, it) },
            onEnabledChange = { viewModel.onVolumeEnabledChange(stream, it) },
        )
    }
}

/**
 * Chips in equal-width pairs that wrap.
 *
 * A single [Row] gave the last chip whatever space the others had left over, which on a
 * narrow screen is almost none — the rightmost option came out crushed and unreadable.
 * Two per row with equal weights means every option is the same size and none of them
 * depends on how long its neighbours' labels are in the current language.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipGrid(content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        maxItemsInEachRow = 2,
        content = content,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RingerSection(profile: SoundProfile, viewModel: ProfileEditorViewModel) {
    SectionHeader(stringResource(R.string.editor_section_ringer))
    ChipGrid {
        FilterChip(
            selected = profile.ringerMode == null,
            onClick = { viewModel.onRingerChange(null) },
            label = { Text(stringResource(R.string.editor_ringer_unchanged)) },
            modifier = Modifier.weight(1f),
        )
        RingerMode.entries.forEach { mode ->
            FilterChip(
                selected = profile.ringerMode == mode,
                onClick = { viewModel.onRingerChange(mode) },
                label = { Text(ScheduleFormatter.ringerLabel(mode)) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DndSection(profile: SoundProfile, viewModel: ProfileEditorViewModel) {
    SectionHeader(stringResource(R.string.editor_section_dnd))
    ChipGrid {
        FilterChip(
            selected = profile.dnd.mode == null,
            onClick = { viewModel.onDndModeChange(null) },
            label = { Text(stringResource(R.string.editor_ringer_unchanged)) },
            modifier = Modifier.weight(1f),
        )
        DndMode.entries.forEach { mode ->
            FilterChip(
                selected = profile.dnd.mode == mode,
                onClick = { viewModel.onDndModeChange(mode) },
                label = { Text(dndLabel(mode)) },
                modifier = Modifier.weight(1f),
            )
        }
    }
    // Stating the platform limit up front beats letting the user discover it as a bug.
    Text(
        text = stringResource(R.string.editor_dnd_note),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun dndLabel(mode: DndMode): String = stringResource(
    when (mode) {
        DndMode.RELEASE -> R.string.editor_dnd_release
        DndMode.PRIORITY -> R.string.editor_dnd_priority
        DndMode.ALARMS_ONLY -> R.string.editor_dnd_alarms
        DndMode.TOTAL_SILENCE -> R.string.editor_dnd_total
    },
)

/** Which end of which window the time dialog is currently editing. */
private data class TimeEdit(val uuid: String, val isStart: Boolean, val minuteOfDay: Int)

@Composable
private fun SchedulesSection(state: ProfileEditorUiState, viewModel: ProfileEditorViewModel) {
    var editing by remember { mutableStateOf<TimeEdit?>(null) }

    SectionHeader(stringResource(R.string.editor_section_schedules))
    state.schedules.forEach { schedule ->
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TimeButton(
                    label = stringResource(R.string.schedule_from),
                    minuteOfDay = schedule.startMinuteOfDay,
                    onClick = {
                        editing = TimeEdit(schedule.uuid, true, schedule.startMinuteOfDay)
                    },
                    modifier = Modifier.weight(1f),
                )
                TimeButton(
                    label = stringResource(R.string.schedule_to),
                    minuteOfDay = schedule.endMinuteOfDay,
                    onClick = {
                        editing = TimeEdit(schedule.uuid, false, schedule.endMinuteOfDay)
                    },
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { viewModel.onScheduleDelete(schedule.uuid) }) {
                    Icon(Icons.Filled.Delete, stringResource(R.string.schedule_delete))
                }
            }
            Text(
                text = stringResource(
                    R.string.schedule_duration,
                    ScheduleFormatter.duration(schedule.durationMinutes),
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            DayPicker(
                daysMask = schedule.daysMask,
                onMaskChange = { viewModel.onScheduleChange(schedule.copy(daysMask = it)) },
            )
        }
    }
    Button(onClick = viewModel::onAddSchedule, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.editor_add_schedule))
    }

    val edit = editing
    if (edit != null) {
        TimeEditDialog(
            initialMinuteOfDay = edit.minuteOfDay,
            onDismiss = { editing = null },
            onConfirm = { minuteOfDay ->
                state.schedules.firstOrNull { it.uuid == edit.uuid }?.let { schedule ->
                    viewModel.onScheduleChange(
                        if (edit.isStart) {
                            schedule.withStart(minuteOfDay)
                        } else {
                            schedule.withEnd(minuteOfDay)
                        },
                    )
                }
                editing = null
            },
        )
    }
}

@Composable
private fun TimeButton(
    label: String,
    minuteOfDay: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(onClick = onClick, modifier = modifier) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = label, style = MaterialTheme.typography.labelSmall)
            Text(text = ScheduleFormatter.time(minuteOfDay), style = MaterialTheme.typography.titleMedium)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeEditDialog(
    initialMinuteOfDay: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    // TimeInput rather than the dial: the dial is about 360 dp wide and gets clipped inside
    // an AlertDialog on a narrow screen, which is the shape this app is used in.
    val timeState = rememberTimePickerState(
        initialHour = initialMinuteOfDay / 60,
        initialMinute = initialMinuteOfDay % 60,
        is24Hour = DateFormat.is24HourFormat(LocalContext.current),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(timeState.hour * 60 + timeState.minute) }) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
        text = { TimeInput(state = timeState) },
    )
}

/**
 * Moving one end of a window leaves the other where it is; the duration absorbs the change.
 *
 * `end == start` is a full day rather than an empty window, which is the same reading
 * [Schedule.fromWallClock] uses, and it is why the arithmetic is written to land in
 * `1..1440` instead of `0..1439` — a zero-length window would loop forever.
 */
private fun Schedule.withStart(minuteOfDay: Int): Schedule =
    copy(startMinuteOfDay = minuteOfDay, durationMinutes = spanBetween(minuteOfDay, endMinuteOfDay))

private fun Schedule.withEnd(minuteOfDay: Int): Schedule =
    copy(durationMinutes = spanBetween(startMinuteOfDay, minuteOfDay))

private fun spanBetween(start: Int, end: Int): Int =
    ((end - start + Schedule.MINUTES_PER_DAY - 1) % Schedule.MINUTES_PER_DAY) + 1

@Composable
private fun OptionsSection(profile: SoundProfile, viewModel: ProfileEditorViewModel) {
    SectionHeader(stringResource(R.string.editor_section_options))

    ToggleRow(
        label = stringResource(R.string.editor_restore_on_exit),
        checked = profile.options.restoreOnExit,
        onCheckedChange = { checked -> viewModel.onOptionsChange { it.copy(restoreOnExit = checked) } },
    )
    ToggleRow(
        label = stringResource(R.string.editor_skip_during_call),
        checked = profile.options.skipDuringCall,
        onCheckedChange = { checked -> viewModel.onOptionsChange { it.copy(skipDuringCall = checked) } },
    )
    ToggleRow(
        label = stringResource(R.string.editor_skip_media),
        checked = profile.options.skipIfMediaPlaying,
        onCheckedChange = { checked -> viewModel.onOptionsChange { it.copy(skipIfMediaPlaying = checked) } },
    )

    Text(
        text = if (profile.options.transitionSeconds == 0) {
            stringResource(R.string.editor_transition_none)
        } else {
            stringResource(R.string.editor_transition, profile.options.transitionSeconds)
        },
        style = MaterialTheme.typography.bodyMedium,
    )
    Slider(
        value = profile.options.transitionSeconds.toFloat(),
        onValueChange = { seconds ->
            viewModel.onOptionsChange { it.copy(transitionSeconds = seconds.toInt()) }
        },
        valueRange = 0f..ProfileOptions.MAX_TRANSITION_SECONDS.toFloat(),
        steps = ProfileOptions.MAX_TRANSITION_SECONDS - 1,
        modifier = Modifier.fillMaxWidth(),
    )

    Text(
        text = stringResource(R.string.editor_priority, profile.priority),
        style = MaterialTheme.typography.bodyMedium,
    )
    Text(
        text = stringResource(R.string.editor_priority_help),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Slider(
        value = profile.priority.toFloat(),
        onValueChange = { viewModel.onPriorityChange(it.toInt()) },
        valueRange = SoundProfile.MIN_PRIORITY.toFloat()..SoundProfile.MAX_PRIORITY.toFloat(),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
