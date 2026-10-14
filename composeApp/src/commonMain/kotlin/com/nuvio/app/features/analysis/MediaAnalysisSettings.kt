package com.nuvio.app.features.analysis

import com.nuvio.app.features.downloads.DownloadsSettingsStorage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Device-level toggles for on-device analysis of downloaded files and for which skip sources
 * the player uses. Stored next to the other download settings.
 */
object MediaAnalysisSettings {
    enum class Flag(val key: String, val defaultValue: Boolean) {
        /** Measure speech in downloaded files and line subtitles up with it. */
        SubtitleSync("analysis_subtitle_sync", true),
        /** Fingerprint episode openings/endings and find intros and credits across a season. */
        IntroDetection("analysis_intro_detection", true),
        /** Measure loudness (EBU R128) and even out the volume between files. */
        Loudness("analysis_loudness", false),
        /** Run the analysis only while the phone is charging. */
        OnlyWhileCharging("analysis_only_while_charging", false),
        /** Skip buttons: TheIntroDB only (IntroDB, AniSkip, Anime-Skip and the chapter/end guesses are ignored). */
        TheIntroDbOnly("skip_source_theintrodb_only", false),
        /** Use the on-device intro/credits detection when the online sources have nothing. */
        OnDeviceSkipFallback("skip_source_on_device_fallback", true),
        /** Make a small thumbnail sheet of each downloaded file for previews while scrubbing. */
        ScrubPreviews("analysis_scrub_previews", true),
        /** Find "Previously on" recaps at the start of downloaded episodes and offer Skip Recap. */
        RecapDetection("analysis_recap_detection", true),
    }

    private val state = MutableStateFlow<Map<Flag, Boolean>>(emptyMap())
    val flags: StateFlow<Map<Flag, Boolean>> = state.asStateFlow()
    private var loaded = false

    fun ensureLoaded() {
        if (loaded) return
        loaded = true
        state.value = Flag.entries.associateWith { DownloadsSettingsStorage.loadFlag(it.key) ?: it.defaultValue }
    }

    fun isEnabled(flag: Flag): Boolean {
        ensureLoaded()
        return state.value[flag] ?: flag.defaultValue
    }

    fun set(flag: Flag, enabled: Boolean) {
        ensureLoaded()
        if (isEnabled(flag) == enabled) return
        state.value = state.value + (flag to enabled)
        DownloadsSettingsStorage.saveFlag(flag.key, enabled)
    }
}
