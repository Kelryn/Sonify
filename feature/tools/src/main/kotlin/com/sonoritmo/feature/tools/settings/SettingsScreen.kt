package com.sonoritmo.feature.tools.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sonoritmo.core.data.backup.ImportMode
import com.sonoritmo.core.data.preferences.ThemeMode
import com.sonoritmo.core.ui.component.SectionHeader
import com.sonoritmo.feature.tools.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onOpenDiagnostics: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(BACKUP_MIME),
    ) { uri -> uri?.let(viewModel::exportTo) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { viewModel.importFrom(it, ImportMode.MERGE) } }

    val outcome = state.backupOutcome
    val outcomeText = outcome?.let { backupOutcomeText(it) }
    LaunchedEffect(outcome) {
        if (outcomeText != null) {
            snackbarHostState.showSnackbar(outcomeText)
            viewModel.dismissOutcome()
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text(stringResource(R.string.settings_title)) }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            SectionHeader(stringResource(R.string.settings_theme))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.entries.forEach { mode ->
                    FilterChip(
                        selected = state.themeMode == mode,
                        onClick = { viewModel.setThemeMode(mode) },
                        label = { Text(themeLabel(mode)) },
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.settings_dynamic_color),
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = state.dynamicColor, onCheckedChange = viewModel::setDynamicColor)
            }

            HorizontalDivider()

            SectionHeader(stringResource(R.string.settings_default_profile))
            Column {
                DefaultProfileRow(
                    label = stringResource(R.string.settings_default_none),
                    selected = state.defaultProfileUuid == null,
                    onClick = { viewModel.setDefaultProfile(null) },
                )
                state.profiles.forEach { profile ->
                    DefaultProfileRow(
                        label = profile.name,
                        selected = state.defaultProfileUuid == profile.uuid,
                        onClick = { viewModel.setDefaultProfile(profile.uuid) },
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            SectionHeader(stringResource(R.string.settings_backup))
            OutlinedButton(
                onClick = { exportLauncher.launch(DEFAULT_BACKUP_NAME) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.backup_export))
            }
            OutlinedButton(
                onClick = { importLauncher.launch(arrayOf(BACKUP_MIME, "*/*")) },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                Text(stringResource(R.string.backup_import))
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            OutlinedButton(onClick = onOpenDiagnostics, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_diagnostics))
            }

            Text(
                text = stringResource(R.string.settings_privacy),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 24.dp),
            )
        }
    }
}

@Composable
private fun DefaultProfileRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.RadioButton(selected = selected, onClick = onClick)
        Text(text = label, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun themeLabel(mode: ThemeMode): String = stringResource(
    when (mode) {
        ThemeMode.SYSTEM -> R.string.settings_theme_system
        ThemeMode.LIGHT -> R.string.settings_theme_light
        ThemeMode.DARK -> R.string.settings_theme_dark
    },
)

@Composable
private fun backupOutcomeText(outcome: BackupOutcome): String = when (outcome) {
    is BackupOutcome.Exported -> stringResource(R.string.backup_export_done, outcome.profiles)
    is BackupOutcome.Imported ->
        if (outcome.corrections > 0) {
            stringResource(R.string.backup_import_corrections, outcome.corrections)
        } else {
            stringResource(R.string.backup_import_done, outcome.profiles)
        }
    BackupOutcome.Failed -> stringResource(R.string.backup_import_failed)
}

private const val BACKUP_MIME = "application/json"
private const val DEFAULT_BACKUP_NAME = "sonoritmo-backup.json"
