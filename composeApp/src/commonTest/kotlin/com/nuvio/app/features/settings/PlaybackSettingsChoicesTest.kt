package com.nuvio.app.features.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaybackSettingsChoicesTest {
    @Test
    fun loadingScreenPairsMapToOneChoiceAndRoundTripTheSameBehavior() {
        val table = listOf(false, true).flatMap { overlay ->
            listOf(false, true).map { status ->
                Triple(overlay, status, loadingScreenChoice(overlay, status))
            }
        }
        assertEquals(4, table.size)
        assertEquals(
            listOf(
                Triple(false, false, LoadingScreenChoice.Off),
                Triple(false, true, LoadingScreenChoice.Off),
                Triple(true, false, LoadingScreenChoice.Plain),
                Triple(true, true, LoadingScreenChoice.WithStatus),
            ),
            table,
        )
        assertEquals(LoadingScreenChoice.WithStatus, loadingScreenChoice(showOverlay = true, showStatus = true))

        table.forEach { (overlay, status, choice) ->
            val current = LoadingScreenStored(showOverlay = overlay, showStatus = status)
            val written = loadingScreenStored(choice, current)
            assertEquals(choice, loadingScreenChoice(written.showOverlay, written.showStatus))
            listOf(false, true).forEach { started ->
                assertEquals(
                    playbackLoadingPresentation(overlay, status, started),
                    playbackLoadingPresentation(written.showOverlay, written.showStatus, started),
                )
            }
        }

        LoadingScreenChoice.entries.forEach { choice ->
            val written = loadingScreenStored(choice, LoadingScreenStored(showOverlay = false, showStatus = false))
            assertEquals(choice, loadingScreenChoice(written.showOverlay, written.showStatus))
            val again = loadingScreenStored(
                loadingScreenChoice(written.showOverlay, written.showStatus),
                written,
            )
            assertEquals(written, again)
        }
    }

    @Test
    fun withStatusStillShowsStallReasonTextAfterStart() {
        val withStatus = loadingScreenStored(
            LoadingScreenChoice.WithStatus,
            LoadingScreenStored(showOverlay = false, showStatus = false),
        )
        assertEquals(LoadingScreenStored(showOverlay = true, showStatus = true), withStatus)
        assertTrue(stallReasonTextVisible(withStatus.showStatus, initialLoadCompleted = true))
        assertFalse(stallReasonTextVisible(withStatus.showStatus, initialLoadCompleted = false))
        assertFalse(stallReasonTextVisible(withStatus.showStatus, initialLoadCompleted = true, torrentPlayback = true))
        val opening = playbackLoadingPresentation(
            withStatus.showOverlay,
            withStatus.showStatus,
            initialLoadCompleted = false,
        )
        assertTrue(opening.openingOverlay)
        assertTrue(opening.statusTextInsideOverlay)
        assertFalse(opening.stallReasonWhileBuffering)
        val afterStart = playbackLoadingPresentation(
            withStatus.showOverlay,
            withStatus.showStatus,
            initialLoadCompleted = true,
        )
        assertFalse(afterStart.openingOverlay)
        assertFalse(afterStart.statusTextInsideOverlay)
        assertTrue(afterStart.stallReasonWhileBuffering)

        val plain = loadingScreenStored(
            LoadingScreenChoice.Plain,
            LoadingScreenStored(showOverlay = true, showStatus = true),
        )
        assertEquals(LoadingScreenStored(showOverlay = true, showStatus = false), plain)
        assertFalse(stallReasonTextVisible(plain.showStatus, initialLoadCompleted = true))
        assertFalse(
            playbackLoadingPresentation(plain.showOverlay, plain.showStatus, initialLoadCompleted = false)
                .statusTextInsideOverlay,
        )

        val turnedOff = loadingScreenStored(LoadingScreenChoice.Off, withStatus)
        assertEquals(LoadingScreenStored(showOverlay = false, showStatus = false), turnedOff)
        assertFalse(stallReasonTextVisible(turnedOff.showStatus, initialLoadCompleted = true))

        val orphan = LoadingScreenStored(showOverlay = false, showStatus = true)
        assertEquals(LoadingScreenChoice.Off, loadingScreenChoice(orphan.showOverlay, orphan.showStatus))
        assertEquals(orphan, loadingScreenStored(LoadingScreenChoice.Off, orphan))
        assertTrue(stallReasonTextVisible(orphan.showStatus, initialLoadCompleted = true))
        assertFalse(
            playbackLoadingPresentation(orphan.showOverlay, orphan.showStatus, initialLoadCompleted = false)
                .statusTextInsideOverlay,
        )
    }

    @Test
    fun holdSpeedPairsMapToOneChoiceAndRoundTripTheSameBehavior() {
        val speeds = holdToSpeedPresetSpeeds + listOf(1f, 2.25f, 4f)
        val table = listOf(false, true).flatMap { enabled ->
            speeds.map { speed -> Triple(enabled, speed, holdSpeedChoice(enabled, speed)) }
        }
        assertEquals(listOf(false, true).size * speeds.size, table.size)
        table.forEach { (enabled, speed, choice) ->
            val expected = if (enabled) HoldSpeedChoice.Speed(speed) else HoldSpeedChoice.Off
            assertEquals(expected, choice)
            val written = holdSpeedStored(choice, retainedSpeed = speed)
            assertEquals(choice, holdSpeedChoice(written.enabled, written.speed))
            assertEquals(holdSpeedBehavior(enabled, speed), holdSpeedBehavior(written.enabled, written.speed))
        }
        assertEquals(HoldSpeedChoice.Speed(2f), holdSpeedChoice(enabled = true, speed = 2f))
        speeds.forEach { speed ->
            val written = holdSpeedStored(HoldSpeedChoice.Speed(speed), retainedSpeed = 2f)
            assertEquals(HoldSpeedStored(enabled = true, speed = speed), written)
            assertEquals(HoldSpeedChoice.Speed(speed), holdSpeedChoice(written.enabled, written.speed))
        }
        assertEquals(holdToSpeedPresetSpeeds, holdToSpeedPickerSpeeds(2f))
        assertEquals(holdToSpeedPresetSpeeds + 2.25f, holdToSpeedPickerSpeeds(2.25f))
        val kept = holdSpeedStored(HoldSpeedChoice.Off, retainedSpeed = 3f)
        assertEquals(HoldSpeedStored(enabled = false, speed = 3f), kept)
        assertEquals(
            HoldSpeedBehavior(activatesOnHold = false, speedWhileHeld = null, rememberedSpeed = 3f),
            holdSpeedBehavior(kept.enabled, kept.speed),
        )
    }

    @Test
    fun touchGesturePairsMapToOneChoiceAndRoundTripTheSameBehavior() {
        val table = listOf(false, true).flatMap { gestures ->
            listOf(false, true).map { swipe ->
                Triple(gestures, swipe, touchGestureChoice(gestures, swipe))
            }
        }
        assertEquals(4, table.size)
        assertEquals(
            listOf(
                Triple(false, false, TouchGestureChoice.Off),
                Triple(false, true, TouchGestureChoice.Off),
                Triple(true, false, TouchGestureChoice.NoSwipeSeek),
                Triple(true, true, TouchGestureChoice.All),
            ),
            table,
        )
        assertEquals(TouchGestureChoice.All, touchGestureChoice(gesturesEnabled = true, swipeToSeekEnabled = true))
        table.forEach { (gestures, swipe, choice) ->
            val written = touchGestureStored(choice, retainedSwipeToSeek = swipe)
            assertEquals(choice, touchGestureChoice(written.gesturesEnabled, written.swipeToSeekEnabled))
            assertEquals(
                touchGestureBehavior(gestures, swipe),
                touchGestureBehavior(written.gesturesEnabled, written.swipeToSeekEnabled),
            )
        }
        TouchGestureChoice.entries.forEach { choice ->
            val written = touchGestureStored(choice, retainedSwipeToSeek = true)
            assertEquals(choice, touchGestureChoice(written.gesturesEnabled, written.swipeToSeekEnabled))
        }
        assertEquals(
            TouchGestureStored(gesturesEnabled = false, swipeToSeekEnabled = true),
            touchGestureStored(TouchGestureChoice.Off, retainedSwipeToSeek = true),
        )
        assertEquals(
            touchGestureBehavior(gesturesEnabled = false, swipeToSeekEnabled = true),
            touchGestureBehavior(gesturesEnabled = false, swipeToSeekEnabled = false),
        )
        assertFalse(touchGestureBehavior(gesturesEnabled = true, swipeToSeekEnabled = false).swipeSeekEnabled)
        assertTrue(touchGestureBehavior(gesturesEnabled = true, swipeToSeekEnabled = true).swipeSeekEnabled)
    }

    @Test
    fun iosHidesShowChaptersAndAdvancedHasTheMovedRows() {
        assertNull(playbackSettingsBand(PlaybackSettingsControl.ShowChapters, isIos = true))
        assertEquals(
            PlaybackSettingsBand.Player,
            playbackSettingsBand(PlaybackSettingsControl.ShowChapters, isIos = false),
        )
        assertEquals(
            listOf(
                PlaybackSettingsControl.LegacyLayout,
                PlaybackSettingsControl.DebugReadout,
            ),
            advancedControls(isIos = false),
        )
        assertEquals(
            listOf(
                PlaybackSettingsControl.LegacyLayout,
                PlaybackSettingsControl.DebugReadout,
                PlaybackSettingsControl.ExtendedDynamicRange,
                PlaybackSettingsControl.DisplayColorHint,
                PlaybackSettingsControl.TargetPrimaries,
                PlaybackSettingsControl.TargetTransfer,
            ),
            advancedControls(isIos = true),
        )
        assertEquals(
            listOf(PlaybackSettingsControl.HardwareDecoder),
            playbackSettingsLayout(isIos = true)
                .filter { it.band == PlaybackSettingsBand.IosVideo }
                .map { it.control },
        )
        assertEquals(
            emptyList(),
            playbackSettingsLayout(isIos = false)
                .filter { it.band == PlaybackSettingsBand.IosVideo }
                .map { it.control },
        )
        assertEquals(
            PlaybackSettingsBand.Player,
            playbackSettingsBand(PlaybackSettingsControl.ContentWarnings, isIos = false),
        )
        assertEquals(
            PlaybackSettingsBand.Player,
            playbackSettingsBand(PlaybackSettingsControl.ContentWarnings, isIos = true),
        )
    }

    @Test
    fun everyRemovedMainRowAppearsOnce() {
        listOf(false, true).forEach { isIos ->
            val layout = playbackSettingsLayout(isIos)
            assertEquals(layout.map { it.control }.distinct(), layout.map { it.control })
            PlaybackSettingsControl.entries.forEach { control ->
                val places = layout.filter { it.control == control }
                val band = playbackSettingsBand(control, isIos)
                if (band == null) {
                    assertEquals(0, places.size, control.name)
                } else {
                    assertEquals(listOf(band), places.map { it.band }, control.name)
                }
            }
            RetiredPlaybackControl.entries.forEach { retired ->
                val successor = retiredPlaybackControlSuccessor(retired)
                val count = layout.count { it.control == successor }
                if (playbackSettingsBand(successor, isIos) == null) {
                    assertEquals(0, count, retired.name)
                } else {
                    assertEquals(1, count, retired.name)
                }
            }
            assertFalse(layout.any { it.band == PlaybackSettingsBand.Player && it.control == PlaybackSettingsControl.LegacyLayout })
            assertFalse(layout.any { it.band == PlaybackSettingsBand.Player && it.control == PlaybackSettingsControl.DebugReadout })
            assertFalse(
                layout.any {
                    it.band == PlaybackSettingsBand.Advanced && it.control == PlaybackSettingsControl.HardwareDecoder
                },
            )
        }
        assertEquals(
            listOf(
                PlaybackSettingsControl.LoadingScreen,
                PlaybackSettingsControl.ShowChapters,
                PlaybackSettingsControl.ContentWarnings,
                PlaybackSettingsControl.TouchGestures,
                PlaybackSettingsControl.HoldToSpeed,
            ),
            playbackSettingsLayout(isIos = false)
                .filter { it.band == PlaybackSettingsBand.Player }
                .map { it.control },
        )
        assertEquals(
            listOf(
                PlaybackSettingsControl.LoadingScreen,
                PlaybackSettingsControl.ContentWarnings,
                PlaybackSettingsControl.TouchGestures,
                PlaybackSettingsControl.HoldToSpeed,
            ),
            playbackSettingsLayout(isIos = true)
                .filter { it.band == PlaybackSettingsBand.Player }
                .map { it.control },
        )
    }

    private fun advancedControls(isIos: Boolean): List<PlaybackSettingsControl> =
        playbackSettingsLayout(isIos)
            .filter { it.band == PlaybackSettingsBand.Advanced }
            .map { it.control }
}
