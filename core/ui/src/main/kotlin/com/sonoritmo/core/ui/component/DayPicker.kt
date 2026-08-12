package com.sonoritmo.core.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sonoritmo.core.domain.model.DayMask
import java.time.DayOfWeek
import java.time.format.TextStyle
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
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        orderedDays.forEach { day ->
            val selected = DayMask.has(daysMask, day)
            val fullName = day.getDisplayName(TextStyle.FULL_STANDALONE, locale)
            val short = day.getDisplayName(TextStyle.NARROW_STANDALONE, locale)

            Surface(
                shape = CircleShape,
                color = if (selected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                contentColor = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier
                    .size(48.dp)
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
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = short.uppercase(locale),
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

private fun weekOrder(locale: Locale): List<DayOfWeek> {
    val first = java.time.temporal.WeekFields.of(locale).firstDayOfWeek
    return (0..6).map { first.plus(it.toLong()) }
}
