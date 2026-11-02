package com.nuvio.app.core.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OfflineBannerPolicyTest {
    @Test
    fun stateTable() {
        val cases = listOf(
            BannerCase("Online", NetworkCondition.Online, heldMs = 0L),
            BannerCase("Offline held <1 s", NetworkCondition.NoInternet, heldMs = OfflineBannerHoldMs - 1),
            BannerCase("Offline held >=1 s", NetworkCondition.NoInternet, heldMs = OfflineBannerHoldMs),
            BannerCase("Unknown", NetworkCondition.Unknown, heldMs = OfflineBannerHoldMs),
        )
        for (case in cases) {
            for (surface in OfflineBannerSurface.entries) {
                for (reduceMotion in listOf(false, true)) {
                    val view = offlineBannerView(
                        condition = case.condition,
                        offlineHeldMs = case.heldMs,
                        surface = surface,
                        reduceMotion = reduceMotion,
                    )
                    val visible = case.shows && surface == OfflineBannerSurface.Main
                    val animated = visible && !reduceMotion
                    assertEquals(visible, view.visible, "${case.name} / $surface / reduceMotion=$reduceMotion visible")
                    assertEquals(animated, view.animated, "${case.name} / $surface / reduceMotion=$reduceMotion animated")
                    if (case.condition == NetworkCondition.NoInternet) {
                        val servers = offlineBannerView(
                            condition = NetworkCondition.ServersUnreachable,
                            offlineHeldMs = case.heldMs,
                            surface = surface,
                            reduceMotion = reduceMotion,
                        )
                        assertEquals(view, servers, "${case.name} servers unreachable matches no internet")
                    }
                }
            }
        }
    }

    @Test
    fun checkingStaysHiddenLikeUnknown() {
        for (surface in OfflineBannerSurface.entries) {
            for (reduceMotion in listOf(false, true)) {
                val view = offlineBannerView(
                    condition = NetworkCondition.Checking,
                    offlineHeldMs = OfflineBannerHoldMs * 5,
                    surface = surface,
                    reduceMotion = reduceMotion,
                )
                assertFalse(view.visible)
                assertFalse(view.animated)
            }
        }
    }

    @Test
    fun flappingWithinOneSecondNeverShowsTheBanner() {
        val marks = listOf(
            OfflineBannerMark(0L, NetworkCondition.NoInternet),
            OfflineBannerMark(900L, NetworkCondition.Online),
            OfflineBannerMark(1_000L, NetworkCondition.ServersUnreachable),
            OfflineBannerMark(1_900L, NetworkCondition.Unknown),
            OfflineBannerMark(2_000L, NetworkCondition.NoInternet),
            OfflineBannerMark(2_900L, NetworkCondition.Checking),
        )
        for (atMs in 0L..3_000L) {
            for (surface in OfflineBannerSurface.entries) {
                for (reduceMotion in listOf(false, true)) {
                    assertFalse(
                        offlineBannerViewAt(marks, atMs, surface, reduceMotion).visible,
                        "visible at $atMs on $surface",
                    )
                }
            }
        }
    }

    @Test
    fun aSteadyOfflineStretchShowsAfterOneSecondAndOnlineHidesImmediately() {
        val marks = listOf(
            OfflineBannerMark(0L, NetworkCondition.NoInternet),
            OfflineBannerMark(400L, NetworkCondition.ServersUnreachable),
            OfflineBannerMark(2_500L, NetworkCondition.Online),
        )
        assertEquals(1_000L, offlineHeldMs(marks, 1_000L))
        assertFalse(offlineBannerViewAt(marks, 999L, OfflineBannerSurface.Main, reduceMotion = false).visible)
        val shown = offlineBannerViewAt(marks, 1_000L, OfflineBannerSurface.Main, reduceMotion = false)
        assertTrue(shown.visible)
        assertTrue(shown.animated)
        val shownStill = offlineBannerViewAt(marks, 1_000L, OfflineBannerSurface.Main, reduceMotion = true)
        assertTrue(shownStill.visible)
        assertFalse(shownStill.animated)
        assertFalse(offlineBannerViewAt(marks, 1_000L, OfflineBannerSurface.Player, reduceMotion = false).visible)
        assertFalse(offlineBannerViewAt(marks, 1_000L, OfflineBannerSurface.LaunchGate, reduceMotion = false).visible)
        val hidden = offlineBannerViewAt(marks, 2_500L, OfflineBannerSurface.Main, reduceMotion = false)
        assertFalse(hidden.visible)
        assertFalse(hidden.animated)
        assertEquals(0L, offlineHeldMs(marks, 2_500L))
    }

    @Test
    fun hideFadesOnTheMainScreenUnlessReduceMotionIsOn() {
        assertTrue(OfflineBannerHideFadeMs in 1 until OfflineBannerHoldMs)
        assertTrue(OfflineBannerShowMs in 1 until OfflineBannerHoldMs)
        assertTrue(offlineBannerMotionEnabled(OfflineBannerSurface.Main, reduceMotion = false))
        assertFalse(offlineBannerMotionEnabled(OfflineBannerSurface.Main, reduceMotion = true))
        assertFalse(offlineBannerMotionEnabled(OfflineBannerSurface.Player, reduceMotion = false))
        assertFalse(offlineBannerMotionEnabled(OfflineBannerSurface.LaunchGate, reduceMotion = false))
    }

    @Test
    fun tapTriggersExactlyOneRecheck() {
        var calls = 0
        offlineBannerView(
            condition = NetworkCondition.NoInternet,
            offlineHeldMs = OfflineBannerHoldMs,
            surface = OfflineBannerSurface.Main,
            reduceMotion = false,
        )
        assertEquals(0, calls)
        onOfflineBannerTap { calls += 1 }
        assertEquals(1, calls)
    }

    private data class BannerCase(
        val name: String,
        val condition: NetworkCondition,
        val heldMs: Long,
    ) {
        val shows: Boolean
            get() = name.startsWith("Offline held >=")
    }
}
