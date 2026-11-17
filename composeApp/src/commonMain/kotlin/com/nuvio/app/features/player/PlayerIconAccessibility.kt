package com.nuvio.app.features.player

/** Icon-only player controls whose name is not already on a neighbouring text label. */
internal enum class PlayerIconControl {
    SubtitleDecrease,
    SubtitleIncrease,
    LiveSearchClear,
}

internal fun playerIconControlDescription(
    control: PlayerIconControl,
    decrease: String,
    increase: String,
    clearSearch: String,
): String = when (control) {
    PlayerIconControl.SubtitleDecrease -> decrease
    PlayerIconControl.SubtitleIncrease -> increase
    PlayerIconControl.LiveSearchClear -> clearSearch
}
