package com.sonoritmo.feature.profiles.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sonoritmo.core.domain.model.DndMode
import com.sonoritmo.core.domain.model.ProfileOptions
import com.sonoritmo.core.domain.model.RingerMode
import com.sonoritmo.core.domain.model.SoundProfile
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

    LaunchedEffect(state.saved) {
        if (state.saved) onDone()
    }

    Scaffold(
        modifier = modifier,
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

            Button(
                onClick = { viewModel.onSave() },
                modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
            ) {
                Text(stringResource(R.string.editor_save))
            }
        }
    }
}

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

@Composable
private fun RingerSection(profile: SoundProfile, viewModel: ProfileEditorViewModel) {
    SectionHeader(stringResource(R.string.editor_section_ringer))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        FilterChip(
            selected = profile.ringerMode == null,
            onClick = { viewModel.onRingerChange(null) },
            label = { Text(stringResource(R.string.editor_ringer_unchanged)) },
        )
        RingerMode.entries.forEach { mode ->
            FilterChip(
                selected = profile.ringerMode == mode,
                onClick = { viewModel.onRingerChange(mode) },
                label = { Text(ScheduleFormatter.ringerLabel(mode)) },
            )
        }
    }
}

@Composable
private fun DndSection(profile: SoundProfile, viewModel: ProfileEditorViewModel) {
    SectionHeader(stringResource(R.string.editor_section_dnd))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        FilterChip(
            selected = profile.dnd.mode == null,
            onClick = { viewModel.onDndModeChange(null) },
            label = { Text(stringResource(R.string.editor_ringer_unchanged)) },
        )
        DndMode.entries.forEach { mode ->
            FilterChip(
                selected = profile.dnd.mode == mode,
                onClick = { viewModel.onDndModeChange(mode) },
                label = { Text(dndLabel(mode)) },
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

@Composable
private fun SchedulesSection(state: ProfileEditorUiState, viewModel: ProfileEditorViewModel) {
    SectionHeader(stringResource(R.string.editor_section_schedules))
    state.schedules.forEach { schedule ->
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = ScheduleFormatter.range(schedule), modifier = Modifier.weight(1f))
                Text(
                    text = stringResource(
                        R.string.schedule_duration,
                        ScheduleFormatter.duration(schedule.durationMinutes),
                    ),
                    style = MaterialTheme.typography.labelMedium,
                )
                IconButton(onClick = { viewModel.onScheduleDelete(schedule.uuid) }) {
                    Icon(Icons.Filled.Delete, stringResource(R.string.schedule_delete))
                }
            }
            DayPicker(
                daysMask = schedule.daysMask,
                onMaskChange = { viewModel.onScheduleChange(schedule.copy(daysMask = it)) },
            )
        }
    }
    Button(onClick = viewModel::onAddSchedule, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.editor_add_schedule))
    }
}

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
