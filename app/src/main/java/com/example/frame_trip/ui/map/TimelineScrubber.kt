package com.example.frame_trip.ui.map

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.frame_trip.R
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Card with a slider that limits the map to photos taken up to the selected date. */
@Composable
fun TimelineScrubber(
    timeline: TimelineState,
    onScrub: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val locale = LocalConfiguration.current.locales[0]
    val dayFormatter = remember(locale) { localizedFormatter(locale, "yMMMd") }
    val monthFormatter = remember(locale) { localizedFormatter(locale, "yMMM") }
    val numberFormat = remember(locale) { NumberFormat.getIntegerInstance(locale) }

    val selectedDate = dayFormatter.formatEpochMillis(timeline.selectedMillis)
    val sliderDescription = stringResource(R.string.timeline_state_description, selectedDate)

    ElevatedCard(modifier = modifier, shape = RoundedCornerShape(24.dp)) {
        Column(modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = selectedDate,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = pluralStringResource(
                        R.plurals.timeline_photo_count,
                        timeline.totalCount,
                        numberFormat.format(timeline.visibleCount),
                        numberFormat.format(timeline.totalCount),
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Slider(
                value = timeline.fraction,
                onValueChange = onScrub,
                enabled = timeline.isScrubbable,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { stateDescription = sliderDescription },
            )
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = monthFormatter.formatEpochMillis(timeline.startMillis),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = monthFormatter.formatEpochMillis(timeline.endMillis),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** A formatter for a date "skeleton" such as yMMMd, laid out the way [locale] expects. */
private fun localizedFormatter(locale: Locale, skeleton: String): DateTimeFormatter =
    DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale)

private fun DateTimeFormatter.formatEpochMillis(millis: Long): String =
    format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
