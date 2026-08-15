package com.ritmute.core.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** What a profile does to one stream, as far as a glance at the list is concerned. */
enum class VolumeState {
    /** Set to an audible level. */
    SET,

    /** Set, but to zero: the profile deliberately silences this stream. */
    SILENCED,

    /** Left exactly as the user had it. */
    UNCHANGED,
}

/** One stream, reduced to an icon and how the profile treats it. */
data class VolumeBadge(
    val icon: ImageVector,
    val state: VolumeState,
    val contentDescription: String,
)

/** One window: the mask drives the day circles, the range is already formatted. */
data class ScheduleSummary(val daysMask: Int, val range: String)

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
    volumes: List<VolumeBadge>,
    schedules: List<ScheduleSummary>,
    noScheduleLabel: String,
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
    /**
     * Rendered in the same [Box] as the overflow button, which is what makes a menu placed
     * here open beside it. A menu declared as a sibling of the whole card anchors to the
     * card's top-left corner instead, several centimetres from the button that opened it.
     */
    overflowMenu: @Composable () -> Unit = {},
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
            // Top, not centre: the detail column grows with the number of windows, and
            // centring would drift the avatar and the buttons down away from the name.
            verticalAlignment = Alignment.Top,
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

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                // Every writable stream, always, in a fixed order. Showing only the ones a
                // profile touches made the row a different shape on every card and gave no
                // way to see at a glance what a profile leaves alone.
                Row(
                    horizontalArrangement = Arrangement.spacedBy(GLYPH_GAP),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    volumes.forEach { badge ->
                        VolumeGlyph(badge)
                    }
                }

                if (schedules.isEmpty()) {
                    Text(
                        text = noScheduleLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    // One days row and one hours row per window, in that order, with no
                    // labels around either: "23:00 – 07:00" under seven letters needs no
                    // explaining, and any extra word is what pushed this off the card.
                    schedules.forEach { schedule ->
                        DayDots(daysMask = schedule.daysMask)
                        Text(
                            text = schedule.range,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            // 48 dp minimum touch targets, non-negotiable (RNF-08).
            IconButton(onClick = onEdit, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
                Icon(Icons.Filled.Edit, contentDescription = editContentDescription)
            }
            Box {
                IconButton(
                    onClick = onMore,
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                ) {
                    Icon(Icons.Filled.MoreVert, contentDescription = moreContentDescription)
                }
                overflowMenu()
            }
        }
    }
}

/**
 * One stream icon in the three states a profile can leave it in.
 *
 * Silenced is drawn as the icon struck through rather than swapped for a different symbol:
 * the row is meant to be read as a column of the same six streams on every card, and a
 * substituted glyph breaks that scan.
 */
@Composable
private fun VolumeGlyph(badge: VolumeBadge) {
    val tint = when (badge.state) {
        VolumeState.SET, VolumeState.SILENCED -> MaterialTheme.colorScheme.onSurface
        VolumeState.UNCHANGED ->
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = UNCHANGED_ALPHA)
    }

    // requiredSize, not size: `size` is only a preferred value, so when the row ran out of
    // width the last glyphs were squeezed to whatever was left and the final one vanished
    // altogether. `requiredSize` refuses to be compressed, which is what "all the same size"
    // has to mean for a row that is read as a row.
    Box(
        modifier = Modifier
            .requiredSize(GLYPH_SIZE)
            .semantics { contentDescription = badge.contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = badge.icon,
            contentDescription = null,
            modifier = Modifier.requiredSize(GLYPH_SIZE),
            tint = tint,
        )
        if (badge.state == VolumeState.SILENCED) {
            // Inset by a fixed fraction of the box rather than drawn corner to corner, so
            // the slash is the same length and the same angle on every glyph instead of
            // following whatever size the icon happened to get.
            Canvas(modifier = Modifier.requiredSize(GLYPH_SIZE)) {
                val inset = size.minDimension * STRIKE_INSET
                drawLine(
                    color = tint,
                    start = Offset(x = inset, y = size.height - inset),
                    end = Offset(x = size.width - inset, y = inset),
                    strokeWidth = STRIKE_WIDTH.toPx(),
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}

private val GLYPH_SIZE = 18.dp

/** Matches the gap between the day circles, so the two rows read as one block. */
private val GLYPH_GAP = 4.dp
private val STRIKE_WIDTH = 2.dp
private const val STRIKE_INSET = 0.12f
private const val UNCHANGED_ALPHA = 0.38f

/**
 * One stream's slider, with an explicit "don't touch this one" switch.
 *
 * The switch is not decoration: `null` is a first-class value in the model, and a user who
 * only wants to change the ring volume must be able to leave media alone rather than being
 * forced to pick a number for it.
 *
 * The track is continuous. It used to be notched to the device's own step count so that
 * "30 %" could not come back as "29 %" after the round trip through an index; the notches
 * were the more visible cost, so the slider now moves freely and the value is a whole
 * percent, which is the finest unit the model stores.
 */
@Composable
fun VolumeSliderRow(
    label: String,
    icon: ImageVector,
    percent: Int?,
    enabledSwitchDescription: String,
    valueDescription: String,
    /** Just the number, shown beside the track. [valueDescription] is the spoken version. */
    valueLabel: String,
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

        // The track is always laid out, greyed when the stream is left alone, and the
        // number always occupies the same width. Showing it only for the streams that are
        // switched on gave every row a different height and left the tracks starting and
        // ending at different places down the column.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Slider(
                value = (percent ?: 0).toFloat(),
                onValueChange = { onPercentChange(it.toInt()) },
                valueRange = 0f..100f,
                enabled = enabled,
                modifier = Modifier
                    .weight(1f)
                    .semantics { contentDescription = valueDescription },
            )
            Text(
                text = valueLabel,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                maxLines = 1,
                // A fixed width, so that 5 %, 50 % and 100 % all end on the same column
                // instead of shunting the track around as the thumb moves.
                modifier = Modifier.widthIn(min = 52.dp),
            )
        }
    }
}
