package com.sonoritmo.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * One profile in the list.
 *
 * ## Tapping the card activates it. It does not open the editor.
 *
 * This is a direct answer to the loudest usability complaint about the apps in this
 * category ("clicking the profile should activate it, clicking the dots should lead to the
 * edit menu"). Activation is the thing a user does daily; editing is the thing they do
 * once. Editing is reachable from an explicit pencil icon and from the overflow menu, and
 * it is never the default gesture.
 *
 * @param isActive drives both the container colour **and** the state description, so the
 *   active profile is obvious visually and unmistakable under TalkBack.
 */
@Composable
fun ProfileCard(
    name: String,
    emoji: String?,
    summary: String,
    scheduleSummary: String?,
    isActive: Boolean,
    isEnabled: Boolean,
    accentColor: Color,
    activateContentDescription: String,
    activeStateDescription: String,
    editContentDescription: String,
    moreContentDescription: String,
    onActivate: () -> Unit,
    onEdit: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .semantics {
                contentDescription = activateContentDescription
                stateDescription = activeStateDescription
            },
        onClick = onActivate,
        enabled = isEnabled,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(accentColor.copy(alpha = if (isEnabled) 0.20f else 0.08f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = emoji ?: name.take(1).uppercase(), style = MaterialTheme.typography.titleMedium)
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!scheduleSummary.isNullOrBlank()) {
                    Text(
                        text = scheduleSummary,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // 48 dp minimum touch targets, non-negotiable (RNF-08).
            IconButton(onClick = onEdit, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
                Icon(Icons.Filled.Edit, contentDescription = editContentDescription)
            }
            IconButton(onClick = onMore, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
                Icon(Icons.Filled.MoreVert, contentDescription = moreContentDescription)
            }
        }
    }
}

/**
 * One stream's slider, with an explicit "don't touch this one" switch.
 *
 * The switch is not decoration: `null` is a first-class value in the model, and a user who
 * only wants to change the ring volume must be able to leave media alone rather than being
 * forced to pick a number for it.
 *
 * @param steps the device's real step count. Anchoring the slider to it means "30 %" does
 *   not come back as "29 %" after a save, which is the rounding artefact users of the
 *   competition report as the app "changing their settings".
 */
@Composable
fun VolumeSliderRow(
    label: String,
    icon: ImageVector,
    percent: Int?,
    steps: Int,
    enabledSwitchDescription: String,
    valueDescription: String,
    supportingText: String? = null,
    isSupported: Boolean = true,
    onPercentChange: (Int) -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val enabled = percent != null && isSupported
    Column(modifier = modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = enabled,
                    enabled = isSupported,
                    role = Role.Switch,
                    onValueChange = onEnabledChange,
                )
                .padding(vertical = 4.dp),
        ) {
            Icon(imageVector = icon, contentDescription = null)
            Column(modifier = Modifier.weight(1f)) {
                Text(text = label, style = MaterialTheme.typography.titleMedium)
                if (supportingText != null) {
                    Text(
                        text = supportingText,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Switch(
                checked = enabled,
                onCheckedChange = onEnabledChange,
                enabled = isSupported,
                modifier = Modifier.semantics { contentDescription = enabledSwitchDescription },
            )
        }

        if (enabled) {
            Slider(
                value = (percent ?: 0).toFloat(),
                onValueChange = { onPercentChange(it.toInt()) },
                valueRange = 0f..100f,
                // One notch per real device step, so the thumb lands where the phone can go.
                steps = (steps - 1).coerceAtLeast(0),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = valueDescription },
            )
        }
    }
}
