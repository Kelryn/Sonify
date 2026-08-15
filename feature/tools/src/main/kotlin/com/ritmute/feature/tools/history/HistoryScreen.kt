package com.ritmute.feature.tools.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ritmute.core.domain.model.ActivityLogEntry
import com.ritmute.core.domain.model.LogReason
import com.ritmute.core.ui.component.EmptyState
import com.ritmute.core.ui.theme.RitMuteTheme
import com.ritmute.feature.tools.R
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    modifier: Modifier = Modifier,
    viewModel: HistoryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text(stringResource(R.string.history_title)) }) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = !state.onlyFailures,
                    onClick = { viewModel.setOnlyFailures(false) },
                    label = { Text(stringResource(R.string.history_filter_all)) },
                )
                FilterChip(
                    selected = state.onlyFailures,
                    onClick = { viewModel.setOnlyFailures(true) },
                    label = { Text(stringResource(R.string.history_filter_failures)) },
                )
            }

            if (state.entries.isEmpty() && !state.isLoading) {
                EmptyState(
                    icon = Icons.Filled.History,
                    title = stringResource(R.string.history_empty_title),
                    body = stringResource(R.string.history_empty_body),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
                ) {
                    items(state.entries, key = { it.id }) { entry -> LogRow(entry) }
                }
            }
        }
    }
}

@Composable
private fun LogRow(entry: ActivityLogEntry) {
    val semantic = RitMuteTheme.semantic
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = if (entry.success) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline,
            contentDescription = stringResource(
                if (entry.success) R.string.history_success else R.string.history_failure,
            ),
            tint = if (entry.success) semantic.active else semantic.degraded,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(text = reasonLabel(entry.reason), style = MaterialTheme.typography.titleMedium)
            entry.profileNameSnapshot?.let {
                Text(text = it, style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                // Rendered in the offset that was in force when it happened, not today's.
                // "Why did it go silent at 3am?" is unanswerable otherwise once the user
                // has travelled or the clock has changed.
                text = localTimestamp(entry),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun localTimestamp(entry: ActivityLogEntry): String {
    val zone = runCatching { ZoneId.of(entry.zoneId) }
        .getOrElse { ZoneOffset.ofTotalSeconds(entry.utcOffsetSeconds) }
    return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withZone(zone).format(entry.timestamp)
}

@Composable
private fun reasonLabel(reason: LogReason): String = stringResource(
    when (reason) {
        LogReason.SCHEDULE_START -> R.string.reason_schedule_start
        LogReason.SCHEDULE_END -> R.string.reason_schedule_end
        LogReason.MANUAL_ACTIVATION -> R.string.reason_manual_activation
        LogReason.MANUAL_EXPIRED -> R.string.reason_manual_expired
        LogReason.GLOBAL_PAUSE_START -> R.string.reason_pause_start
        LogReason.GLOBAL_PAUSE_END -> R.string.reason_pause_end
        LogReason.BOOT_RECONCILE -> R.string.reason_boot
        LogReason.LOCKED_BOOT_RECONCILE -> R.string.reason_locked_boot
        LogReason.WATCHDOG_REPAIR -> R.string.reason_watchdog
        LogReason.TIME_CHANGED -> R.string.reason_time_changed
        LogReason.TIMEZONE_CHANGED -> R.string.reason_timezone_changed
        LogReason.APP_UPDATED -> R.string.reason_app_updated
        LogReason.PERMISSION_LOST -> R.string.reason_permission_lost
        LogReason.PERMISSION_GRANTED -> R.string.reason_permission_granted
        LogReason.SKIPPED_IN_CALL -> R.string.reason_skipped_call
        LogReason.SKIPPED_MEDIA_PLAYING -> R.string.reason_skipped_media
        LogReason.SKIPPED_STALE_BASELINE -> R.string.reason_skipped_stale
        LogReason.SECURITY_EXCEPTION -> R.string.reason_security
        LogReason.SILENTLY_IGNORED_BY_SYSTEM -> R.string.reason_silently_ignored
        LogReason.VOLUME_FIXED_DEVICE -> R.string.reason_volume_fixed
        LogReason.ZEN_RULE_LIMIT_REACHED -> R.string.reason_zen_limit
        LogReason.ZEN_RULE_OVERRIDDEN -> R.string.reason_zen_overridden
        LogReason.FORCE_STOP_DETECTED -> R.string.reason_force_stop
        LogReason.IMPORT_APPLIED -> R.string.reason_import
        LogReason.PROFILE_UNCHANGED -> R.string.reason_unchanged
    },
)
