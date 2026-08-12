package com.sonoritmo.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.sonoritmo.core.ui.theme.SonoRitmoTheme

/** What the banner is currently announcing. */
enum class ActiveStateKind { ACTIVE, IDLE, PAUSED, DEGRADED }

/**
 * The identity element of the app: a persistent banner at the top of the home screen that
 * answers, in under a second, the three questions no competitor answers at all —
 * *what is running now*, *why*, and *what comes next*.
 *
 * It is deliberately not a chart or a status dot. The failure mode of this category is
 * silence: an app that changed the volume and never said so. This is the opposite of that.
 *
 * @param title what is active ("Noche" / "Nada activo")
 * @param reason why it is active ("L–D · 23:00 – 07:00")
 * @param nextUp what happens next ("Termina a las 07:00")
 * @param semanticSummary a single sentence for TalkBack, replacing the three visual lines
 */
@Composable
fun ActiveStateBanner(
    kind: ActiveStateKind,
    title: String,
    reason: String?,
    nextUp: String?,
    semanticSummary: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val semantic = SonoRitmoTheme.semantic
    val (container, content, icon) = when (kind) {
        ActiveStateKind.ACTIVE ->
            Triple(semantic.activeContainer, semantic.onActiveContainer, Icons.Filled.CheckCircle)
        ActiveStateKind.IDLE ->
            Triple(
                MaterialTheme.colorScheme.surfaceVariant,
                MaterialTheme.colorScheme.onSurfaceVariant,
                Icons.Filled.Bedtime,
            )
        ActiveStateKind.PAUSED ->
            Triple(semantic.pausedContainer, semantic.onPausedContainer, Icons.Filled.PauseCircle)
        ActiveStateKind.DEGRADED ->
            Triple(semantic.degradedContainer, semantic.onDegradedContainer, Icons.Filled.Warning)
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            // One announcement, not four fragments read in sequence.
            .clearAndSetSemantics { contentDescription = semanticSummary },
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
        shape = RoundedCornerShape(20.dp),
    ) {
        Row(
            modifier = Modifier.padding(Spacing16),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(28.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleLarge)
                if (!reason.isNullOrBlank()) {
                    Text(text = reason, style = MaterialTheme.typography.bodyMedium)
                }
                if (!nextUp.isNullOrBlank()) {
                    Text(text = nextUp, style = MaterialTheme.typography.labelMedium)
                }
            }
            if (actionLabel != null && onAction != null) {
                TextButton(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}

private val Spacing16 = 16.dp

/**
 * A small labelled marker. Always icon **plus** text — never colour on its own, so it
 * survives colour blindness, greyscale device effects and TalkBack.
 */
@Composable
fun StatusChip(
    label: String,
    icon: ImageVector,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(containerColor)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = contentColor,
            )
        }
    }
}

/** Empty states carry an action, never just a shrug. */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}
