package com.sonoritmo.core.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sonoritmo.core.domain.model.DayMask
import java.time.DayOfWeek
// Aliased because Compose's TextStyle is the one this file uses for typography, and two
// unrelated types called TextStyle in one file is a trap for whoever edits it next.
import java.time.format.TextStyle as DayNameStyle
import java.util.Locale

/**
 * Seven toggles for the day mask.
 *
 * The week starts on whatever day the user's locale says it starts on, taken from
 * [java.time.temporal.WeekFields], rather than hard-coded to Monday: getting this wrong is
 * a small thing that makes an app feel foreign.
 *
 * Each toggle shows a one-letter abbreviation but announces the full localised day name,
 * because "M" is useless to a screen reader and ambiguous in several languages.
 */
@Composable
fun DayPicker(
    daysMask: Int,
    onMaskChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    locale: Locale = Locale.getDefault(),
) {
    val orderedDays = remember(locale) { weekOrder(locale) }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(DAY_GAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        orderedDays.forEach { day ->
            val selected = DayMask.has(daysMask, day)
            val fullName = day.getDisplayName(DayNameStyle.FULL_STANDALONE, locale)

            // Seven fixed 48 dp circles need 336 dp, which is more than a 360 dp phone has
            // left after the screen's own margins, so the last one got squeezed. Equal
            // weights make all seven the same size whatever the width, and the touch target
            // keeps its 48 dp height even when the circle drawn inside is smaller.
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .toggleable(
                        value = selected,
                        role = Role.Checkbox,
                        onValueChange = { checked ->
                            val bit = DayMask.bit(day)
                            val next = if (checked) daysMask or bit else daysMask and bit.inv()
                            // A schedule with no days is not a schedule. Refuse to empty it.
                            if (DayMask.isValid(next)) onMaskChange(next)
                        },
                    )
                    .semantics { contentDescription = fullName },
                contentAlignment = Alignment.Center,
            ) {
                DayCircle(
                    letter = day.getDisplayName(DayNameStyle.NARROW_STANDALONE, locale).uppercase(locale),
                    container = if (selected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                    content = if (selected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}

/**
 * The same seven circles, read-only, for a list row.
 *
 * Sharing [DayCircle] with the editor is the point: two renderings of the same week that
 * drift apart is how a list ends up disagreeing with the screen that wrote it.
 */
@Composable
fun DayDots(
    daysMask: Int,
    modifier: Modifier = Modifier,
    locale: Locale = Locale.getDefault(),
) {
    val orderedDays = remember(locale) { weekOrder(locale) }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(DAY_GAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        orderedDays.forEach { day ->
            val selected = DayMask.has(daysMask, day)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .semantics {
                        contentDescription = day.getDisplayName(DayNameStyle.FULL_STANDALONE, locale)
                    },
                contentAlignment = Alignment.Center,
            ) {
                DayCircle(
                    letter = day.getDisplayName(DayNameStyle.NARROW_STANDALONE, locale).uppercase(locale),
                    container = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                    content = if (selected) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = INACTIVE_ALPHA)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    maxDiameter = 24.dp,
                )
            }
        }
    }
}

@Composable
private fun DayCircle(
    letter: String,
    container: Color,
    content: Color,
    style: TextStyle,
    maxDiameter: Dp = 48.dp,
) {
    Surface(
        shape = CircleShape,
        color = container,
        contentColor = content,
        modifier = Modifier
            .fillMaxWidth()
            .sizeIn(maxWidth = maxDiameter, maxHeight = maxDiameter)
            .aspectRatio(1f),
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(text = letter, style = style, textAlign = TextAlign.Center, maxLines = 1)
        }
    }
}

private val DAY_GAP = 4.dp
private const val INACTIVE_ALPHA = 0.45f

private fun weekOrder(locale: Locale): List<DayOfWeek> {
    val first = java.time.temporal.WeekFields.of(locale).firstDayOfWeek
    return (0..6).map { first.plus(it.toLong()) }
}
