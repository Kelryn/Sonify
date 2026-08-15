package com.ritmute.feature.tools.diagnostics

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ritmute.core.ui.theme.RitMuteTheme
import com.ritmute.feature.tools.R

/**
 * The health panel.
 *
 * Its job is to convert Android's silent refusals into sentences a person can act on. The
 * category's defining failure is an app that quietly stops working; this screen is the
 * counter-measure, so it errs towards saying too much rather than too little.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    modifier: Modifier = Modifier,
    viewModel: DiagnosticsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // The system permission screens return no result, so the only reliable moment to
    // re-read them is when this screen comes back to the foreground.
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text(stringResource(R.string.diagnostics_title)) }) },
    ) { padding ->
        val report = state.report ?: return@Scaffold

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            // No headline verdict and no repeated section title. The ticks and crosses below
            // already say whether anything needs attention, and each row carries its own
            // button; a sentence announcing the same thing was one more line to scroll past.
            CheckRow(
                label = stringResource(R.string.diagnostics_dnd),
                explanation = stringResource(R.string.diagnostics_dnd_why),
                ok = report.notificationPolicyAccessGranted,
                actionLabel = stringResource(R.string.diagnostics_grant),
                onAction = { context.launch(viewModel.notificationPolicyIntent()) },
            )
            CheckRow(
                label = stringResource(R.string.diagnostics_exact_alarms),
                explanation = stringResource(R.string.diagnostics_exact_alarms_why),
                ok = report.canScheduleExactAlarms,
                actionLabel = stringResource(R.string.diagnostics_grant),
                onAction = { viewModel.exactAlarmIntent()?.let(context::launch) },
            )
            CheckRow(
                label = stringResource(R.string.diagnostics_battery),
                explanation = stringResource(R.string.diagnostics_battery_why),
                ok = report.ignoringBatteryOptimizations,
                actionLabel = stringResource(R.string.diagnostics_open_settings),
                onAction = { context.launch(viewModel.batteryOptimisationIntent()) },
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            Text(
                text = if (report.alarmPending) {
                    stringResource(R.string.diagnostics_alarm_pending)
                } else {
                    stringResource(R.string.diagnostics_alarm_missing)
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(R.string.diagnostics_bucket, report.standbyBucket.name),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(R.string.diagnostics_repairs, report.repairsCount),
                style = MaterialTheme.typography.bodyMedium,
            )

            if (report.volumeFixed) {
                Text(
                    text = stringResource(R.string.diagnostics_volume_fixed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = RitMuteTheme.semantic.degraded,
                )
            }

            if (report.lastForceStopDetectedAt != null) {
                Text(
                    text = stringResource(R.string.diagnostics_force_stop),
                    style = MaterialTheme.typography.bodyMedium,
                    color = RitMuteTheme.semantic.degraded,
                )
            }

            if (report.vendor.aggressive) {
                Text(
                    text = stringResource(R.string.diagnostics_vendor, report.vendor.name),
                    style = MaterialTheme.typography.bodyMedium,
                )
                state.vendorActions.forEach { action ->
                    TextButton(onClick = { context.launch(action.intent) }) {
                        Text(action.topic.name)
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.diagnostics_max_reliability),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(R.string.diagnostics_max_reliability_why),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = state.maxReliability,
                    onCheckedChange = viewModel::setMaxReliability,
                )
            }
        }
    }
}

@Composable
private fun CheckRow(
    label: String,
    explanation: String,
    ok: Boolean,
    actionLabel: String,
    onAction: () -> Unit,
) {
    val semantic = RitMuteTheme.semantic
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = if (ok) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline,
            contentDescription = null,
            tint = if (ok) semantic.active else semantic.degraded,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.titleMedium)
            if (!ok) {
                Text(
                    text = explanation,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (!ok) {
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

private fun android.content.Context.launch(intent: Intent) {
    runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
