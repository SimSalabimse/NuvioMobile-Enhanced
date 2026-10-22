package com.nuvio.app.features.player

import com.nuvio.app.features.player.skip.AutoSkipSegmentType

/** Per-show auto-skip: follow the setting, or always / never skip on this show. */
internal enum class ShowAutoSkip(val label: String) {
    Default("Default"),
    Always("Always"),
    Never("Never"),
    ;

    /** Short enough for one line in the hold menus and sheets. */
    val menuLabel: String get() = "Auto-skip: $label"

    fun next(): ShowAutoSkip = entries[(ordinal + 1) % entries.size]

    /** The segment types to auto-skip on this show, given the global [selected] ones. */
    fun segmentTypes(selected: Set<AutoSkipSegmentType>): Set<AutoSkipSegmentType> = when (this) {
        Default -> selected
        Always -> AutoSkipSegmentType.entries.toSet()
        Never -> emptySet()
    }
}

/**
 * Per-show playback memory kept next to the per-show track choices: the last picked speed and
 * the auto-skip override. Subtitle and audio choices are remembered there already.
 */
internal object ShowPlaybackMemory {
    fun speed(showId: String): Float? = load(showId)?.playbackSpeed?.toFloatOrNull()

    fun saveSpeed(showId: String, speed: Float) = update(showId) { it.copy(playbackSpeed = speed.toString()) }

    fun autoSkip(showId: String): ShowAutoSkip =
        load(showId)?.autoSkip?.let { name -> ShowAutoSkip.entries.firstOrNull { it.name == name } } ?: ShowAutoSkip.Default

    fun setAutoSkip(showId: String, value: ShowAutoSkip) =
        update(showId) { it.copy(autoSkip = value.name.takeIf { value != ShowAutoSkip.Default }) }

    private fun load(showId: String): PersistedPlayerTrackPreference? =
        showId.takeIf { it.isNotBlank() }?.let(PlayerTrackPreferenceStorage::load)

    private fun update(showId: String, change: (PersistedPlayerTrackPreference) -> PersistedPlayerTrackPreference) {
        if (showId.isBlank()) return
        PlayerTrackPreferenceStorage.save(showId, change(load(showId) ?: PersistedPlayerTrackPreference()))
    }
}
