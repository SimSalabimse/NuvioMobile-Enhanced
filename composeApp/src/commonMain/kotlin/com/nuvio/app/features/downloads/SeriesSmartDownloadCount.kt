package com.nuvio.app.features.downloads

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.player.PersistedPlayerTrackPreference
import com.nuvio.app.features.player.PlayerTrackPreferenceStorage

/** Per-series Smart Downloads count. [Default] is unset and follows the global count. */
internal enum class SeriesSmartDownloadCount(val label: String, val count: Int?) {
    Default("Default", null),
    Zero("0", 0),
    One("1", 1),
    Two("2", 2),
    Three("3", 3),
    Five("5", 5),
    ;

    /** Stored value; null removes the override. */
    val stored: String? get() = count?.toString()

    companion object {
        fun fromStored(raw: String?): SeriesSmartDownloadCount =
            entries.firstOrNull { it.count != null && it.stored == raw } ?: Default
    }
}

/**
 * How many next episodes Smart Downloads keep ready for one series.
 * An override replaces the global count; unset ([overrideCount] null) keeps the global count.
 * Zero is a real override and turns next-episode downloads off for that series.
 */
internal fun effectiveSmartDownloadCount(overrideCount: Int?, globalCount: Int): Int =
    overrideCount ?: globalCount

internal data class SmartDownloadCountPickerChoice(
    val count: SeriesSmartDownloadCount,
    val label: String,
    val selected: Boolean,
)

internal data class SmartDownloadCountPickerState(
    val visible: Boolean,
    val title: String,
    val choices: List<SmartDownloadCountPickerChoice>,
)

/** Picker state for a title. Visible for a series; hidden for a movie. */
internal fun smartDownloadCountPickerState(
    metaType: String,
    selected: SeriesSmartDownloadCount,
): SmartDownloadCountPickerState = SmartDownloadCountPickerState(
    visible = metaType.equals("series", ignoreCase = true),
    title = "Smart downloads",
    choices = SeriesSmartDownloadCount.entries.map { choice ->
        SmartDownloadCountPickerChoice(
            count = choice,
            label = choice.label,
            selected = choice == selected,
        )
    },
)

/**
 * Per-series override, stored on the same per-show preference record as speed and auto-skip
 * (one short string per series id, removed when set back to Default).
 */
internal object SeriesSmartDownloadCounts {
    fun selection(seriesId: String): SeriesSmartDownloadCount =
        SeriesSmartDownloadCount.fromStored(PlayerTrackPreferenceStorage.load(seriesId)?.smartDownloadCount)

    fun storedCount(seriesId: String): Int? = selection(seriesId).count

    fun set(seriesId: String, value: SeriesSmartDownloadCount) {
        if (seriesId.isBlank()) return
        val current = PlayerTrackPreferenceStorage.load(seriesId) ?: PersistedPlayerTrackPreference()
        PlayerTrackPreferenceStorage.save(seriesId, current.copy(smartDownloadCount = value.stored))
    }
}

@Composable
internal fun SeriesSmartDownloadCountPicker(
    metaType: String,
    seriesId: String,
    modifier: Modifier = Modifier,
) {
    if (!smartDownloadCountPickerState(metaType, SeriesSmartDownloadCount.Default).visible) return
    var selected by remember(seriesId) { mutableStateOf(SeriesSmartDownloadCounts.selection(seriesId)) }
    val state = smartDownloadCountPickerState(metaType, selected)
    Row(
        modifier = modifier
            .horizontalScroll(rememberScrollState())
            .semantics { contentDescription = state.title },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = state.title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.width(4.dp))
        state.choices.forEach { choice ->
            FilterChip(
                selected = choice.selected,
                onClick = {
                    selected = choice.count
                    SeriesSmartDownloadCounts.set(seriesId, choice.count)
                },
                label = { Text(choice.label) },
                modifier = Modifier.semantics {
                    contentDescription = "${state.title} ${choice.label}"
                    this.selected = choice.selected
                },
            )
        }
    }
}
