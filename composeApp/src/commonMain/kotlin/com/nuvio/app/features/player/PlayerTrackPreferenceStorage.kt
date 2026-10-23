package com.nuvio.app.features.player

data class PersistedPlayerTrackPreference(
    val subtitleType: String? = null,
    val subtitleLanguage: String? = null,
    val subtitleName: String? = null,
    val subtitleTrackId: String? = null,
    val addonSubtitleId: String? = null,
    val addonSubtitleUrl: String? = null,
    val addonSubtitleAddonName: String? = null,
    val audioLanguage: String? = null,
    val audioName: String? = null,
    val audioTrackId: String? = null,
    val subtitleIsForced: Boolean? = null,
    /** Per show: the last speed picked (never a hold-to-speed boost). */
    val playbackSpeed: String? = null,
    /** Per show: auto-skip override, a [ShowAutoSkip] name; null = follow the setting. */
    val autoSkip: String? = null,
)

object PersistedSubtitleSelectionType {
    const val INTERNAL = "INTERNAL"
    const val ADDON = "ADDON"
    const val DISABLED = "DISABLED"
}

internal expect object PlayerTrackPreferenceStorage {
    fun load(contentId: String): PersistedPlayerTrackPreference?
    fun save(contentId: String, preference: PersistedPlayerTrackPreference)
    fun loadSubtitleDelayMs(videoId: String): Int?
    fun saveSubtitleDelayMs(videoId: String, delayMs: Int)
}
