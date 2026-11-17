package com.nuvio.app.features.settings

internal enum class LoadingScreenChoice {
    Off,
    Plain,
    WithStatus,
}

internal data class LoadingScreenStored(
    val showOverlay: Boolean,
    val showStatus: Boolean,
)

internal data class PlaybackLoadingPresentation(
    val openingOverlay: Boolean,
    val statusTextInsideOverlay: Boolean,
    val stallReasonWhileBuffering: Boolean,
)

internal fun loadingScreenChoice(showOverlay: Boolean, showStatus: Boolean): LoadingScreenChoice = when {
    !showOverlay -> LoadingScreenChoice.Off
    !showStatus -> LoadingScreenChoice.Plain
    else -> LoadingScreenChoice.WithStatus
}

internal fun loadingScreenStored(
    choice: LoadingScreenChoice,
    current: LoadingScreenStored,
): LoadingScreenStored = when (choice) {
    LoadingScreenChoice.Off -> if (current.showOverlay) {
        LoadingScreenStored(showOverlay = false, showStatus = false)
    } else {
        current
    }
    LoadingScreenChoice.Plain -> LoadingScreenStored(showOverlay = true, showStatus = false)
    LoadingScreenChoice.WithStatus -> LoadingScreenStored(showOverlay = true, showStatus = true)
}

internal fun playbackLoadingPresentation(
    showOverlay: Boolean,
    showStatus: Boolean,
    initialLoadCompleted: Boolean,
): PlaybackLoadingPresentation {
    val openingOverlay = showOverlay && !initialLoadCompleted
    return PlaybackLoadingPresentation(
        openingOverlay = openingOverlay,
        statusTextInsideOverlay = openingOverlay && showStatus,
        stallReasonWhileBuffering = showStatus && initialLoadCompleted,
    )
}

internal fun stallReasonTextVisible(
    showStatus: Boolean,
    initialLoadCompleted: Boolean,
    torrentPlayback: Boolean = false,
): Boolean = showStatus && initialLoadCompleted && !torrentPlayback

internal sealed class HoldSpeedChoice {
    data object Off : HoldSpeedChoice()
    data class Speed(val speed: Float) : HoldSpeedChoice()
}

internal data class HoldSpeedStored(
    val enabled: Boolean,
    val speed: Float,
)

internal data class HoldSpeedBehavior(
    val activatesOnHold: Boolean,
    val speedWhileHeld: Float?,
    val rememberedSpeed: Float,
)

internal val holdToSpeedPresetSpeeds: List<Float> = listOf(1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f)

internal fun holdSpeedChoice(enabled: Boolean, speed: Float): HoldSpeedChoice =
    if (enabled) HoldSpeedChoice.Speed(speed) else HoldSpeedChoice.Off

internal fun holdSpeedStored(choice: HoldSpeedChoice, retainedSpeed: Float): HoldSpeedStored = when (choice) {
    HoldSpeedChoice.Off -> HoldSpeedStored(enabled = false, speed = retainedSpeed)
    is HoldSpeedChoice.Speed -> HoldSpeedStored(enabled = true, speed = choice.speed)
}

internal fun holdSpeedBehavior(enabled: Boolean, speed: Float): HoldSpeedBehavior = HoldSpeedBehavior(
    activatesOnHold = enabled,
    speedWhileHeld = if (enabled) speed else null,
    rememberedSpeed = speed,
)

internal fun holdToSpeedPickerSpeeds(currentSpeed: Float): List<Float> =
    if (holdToSpeedPresetSpeeds.none { it == currentSpeed }) {
        holdToSpeedPresetSpeeds + currentSpeed
    } else {
        holdToSpeedPresetSpeeds
    }

internal enum class TouchGestureChoice {
    Off,
    NoSwipeSeek,
    All,
}

internal data class TouchGestureStored(
    val gesturesEnabled: Boolean,
    val swipeToSeekEnabled: Boolean,
)

internal data class TouchGestureBehavior(
    val gesturesEnabled: Boolean,
    val swipeSeekEnabled: Boolean,
)

internal fun touchGestureChoice(gesturesEnabled: Boolean, swipeToSeekEnabled: Boolean): TouchGestureChoice = when {
    !gesturesEnabled -> TouchGestureChoice.Off
    !swipeToSeekEnabled -> TouchGestureChoice.NoSwipeSeek
    else -> TouchGestureChoice.All
}

internal fun touchGestureStored(
    choice: TouchGestureChoice,
    retainedSwipeToSeek: Boolean,
): TouchGestureStored = when (choice) {
    TouchGestureChoice.Off -> TouchGestureStored(
        gesturesEnabled = false,
        swipeToSeekEnabled = retainedSwipeToSeek,
    )
    TouchGestureChoice.NoSwipeSeek -> TouchGestureStored(
        gesturesEnabled = true,
        swipeToSeekEnabled = false,
    )
    TouchGestureChoice.All -> TouchGestureStored(
        gesturesEnabled = true,
        swipeToSeekEnabled = true,
    )
}

internal fun touchGestureBehavior(gesturesEnabled: Boolean, swipeToSeekEnabled: Boolean): TouchGestureBehavior =
    TouchGestureBehavior(
        gesturesEnabled = gesturesEnabled,
        swipeSeekEnabled = gesturesEnabled && swipeToSeekEnabled,
    )

internal enum class PlaybackSettingsBand {
    Player,
    IosVideo,
    Advanced,
}

internal enum class PlaybackSettingsControl {
    LoadingScreen,
    ShowChapters,
    ContentWarnings,
    TouchGestures,
    HoldToSpeed,
    LegacyLayout,
    DebugReadout,
    HardwareDecoder,
    ExtendedDynamicRange,
    DisplayColorHint,
    TargetPrimaries,
    TargetTransfer,
}

internal data class PlacedPlaybackSettingsControl(
    val control: PlaybackSettingsControl,
    val band: PlaybackSettingsBand,
)

internal enum class RetiredPlaybackControl {
    LoadingOverlay,
    ShowLoadingStatus,
    HoldToSpeedSwitch,
    HoldSpeedValue,
    TouchGesturesSwitch,
    SwipeToSeek,
    DebugReadout,
    LegacyLayout,
    ExtendedDynamicRange,
    DisplayColorHint,
    TargetPrimaries,
    TargetTransfer,
}

internal fun playbackSettingsLayout(isIos: Boolean): List<PlacedPlaybackSettingsControl> {
    val rows = mutableListOf<PlacedPlaybackSettingsControl>()
    fun add(control: PlaybackSettingsControl, band: PlaybackSettingsBand) {
        rows += PlacedPlaybackSettingsControl(control, band)
    }
    add(PlaybackSettingsControl.LoadingScreen, PlaybackSettingsBand.Player)
    if (!isIos) add(PlaybackSettingsControl.ShowChapters, PlaybackSettingsBand.Player)
    add(PlaybackSettingsControl.ContentWarnings, PlaybackSettingsBand.Player)
    add(PlaybackSettingsControl.TouchGestures, PlaybackSettingsBand.Player)
    add(PlaybackSettingsControl.HoldToSpeed, PlaybackSettingsBand.Player)
    add(PlaybackSettingsControl.LegacyLayout, PlaybackSettingsBand.Advanced)
    add(PlaybackSettingsControl.DebugReadout, PlaybackSettingsBand.Advanced)
    if (isIos) {
        add(PlaybackSettingsControl.HardwareDecoder, PlaybackSettingsBand.IosVideo)
        add(PlaybackSettingsControl.ExtendedDynamicRange, PlaybackSettingsBand.Advanced)
        add(PlaybackSettingsControl.DisplayColorHint, PlaybackSettingsBand.Advanced)
        add(PlaybackSettingsControl.TargetPrimaries, PlaybackSettingsBand.Advanced)
        add(PlaybackSettingsControl.TargetTransfer, PlaybackSettingsBand.Advanced)
    }
    return rows
}

internal fun playbackSettingsBand(
    control: PlaybackSettingsControl,
    isIos: Boolean,
): PlaybackSettingsBand? = playbackSettingsLayout(isIos)
    .firstOrNull { it.control == control }
    ?.band

internal fun retiredPlaybackControlSuccessor(row: RetiredPlaybackControl): PlaybackSettingsControl = when (row) {
    RetiredPlaybackControl.LoadingOverlay,
    RetiredPlaybackControl.ShowLoadingStatus,
    -> PlaybackSettingsControl.LoadingScreen
    RetiredPlaybackControl.HoldToSpeedSwitch,
    RetiredPlaybackControl.HoldSpeedValue,
    -> PlaybackSettingsControl.HoldToSpeed
    RetiredPlaybackControl.TouchGesturesSwitch,
    RetiredPlaybackControl.SwipeToSeek,
    -> PlaybackSettingsControl.TouchGestures
    RetiredPlaybackControl.DebugReadout -> PlaybackSettingsControl.DebugReadout
    RetiredPlaybackControl.LegacyLayout -> PlaybackSettingsControl.LegacyLayout
    RetiredPlaybackControl.ExtendedDynamicRange -> PlaybackSettingsControl.ExtendedDynamicRange
    RetiredPlaybackControl.DisplayColorHint -> PlaybackSettingsControl.DisplayColorHint
    RetiredPlaybackControl.TargetPrimaries -> PlaybackSettingsControl.TargetPrimaries
    RetiredPlaybackControl.TargetTransfer -> PlaybackSettingsControl.TargetTransfer
}
