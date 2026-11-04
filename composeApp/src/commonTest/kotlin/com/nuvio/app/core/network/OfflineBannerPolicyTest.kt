package com.nuvio.app.core.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OfflineBannerPolicyTest {
    @Test
    fun offlineAppearsWithZeroDelayAndOnlineHidesAfterTheHold() {
        assertEquals(0L, OfflineBannerAppearDelayMs)
        val dropped = offlineBannerView(
            condition = NetworkCondition.NoInternet,
            onlineHeldMs = 0L,
            showingBefore = false,
            surface = OfflineBannerSurface.Main,
            reduceMotion = false,
        )
        assertTrue(dropped.visible)
        assertTrue(dropped.animated)
        val servers = offlineBannerView(
            condition = NetworkCondition.ServersUnreachable,
            onlineHeldMs = 0L,
            showingBefore = false,
            surface = OfflineBannerSurface.Main,
            reduceMotion = true,
        )
        assertTrue(servers.visible)
        assertFalse(servers.animated)

        val hidden = offlineBannerView(
            condition = NetworkCondition.Online,
            onlineHeldMs = OfflineBannerHoldMs,
            showingBefore = true,
            surface = OfflineBannerSurface.Main,
            reduceMotion = false,
        )
        assertFalse(hidden.visible)
        assertTrue(hidden.animated)
        val snapped = offlineBannerView(
            condition = NetworkCondition.Online,
            onlineHeldMs = OfflineBannerHoldMs,
            showingBefore = true,
            surface = OfflineBannerSurface.Main,
            reduceMotion = true,
        )
        assertFalse(snapped.visible)
        assertFalse(snapped.animated)
    }

    @Test
    fun playerAndLaunchGateStayHidden() {
        for (surface in listOf(OfflineBannerSurface.Player, OfflineBannerSurface.LaunchGate)) {
            for (reduceMotion in listOf(false, true)) {
                val view = offlineBannerView(
                    condition = NetworkCondition.NoInternet,
                    onlineHeldMs = 0L,
                    showingBefore = false,
                    surface = surface,
                    reduceMotion = reduceMotion,
                )
                assertFalse(view.visible)
                assertFalse(view.animated)
            }
        }
    }

    @Test
    fun checkingAndUnknownStayHiddenUntilAnOfflineMark() {
        for (condition in listOf(NetworkCondition.Checking, NetworkCondition.Unknown, NetworkCondition.Online)) {
            val view = offlineBannerView(
                condition = condition,
                onlineHeldMs = OfflineBannerHoldMs * 5,
                showingBefore = false,
                surface = OfflineBannerSurface.Main,
                reduceMotion = false,
            )
            assertFalse(view.visible)
            assertFalse(view.animated)
        }
    }

    @Test
    fun aDropShowsImmediatelyAndAReturnHidesWithAFade() {
        val marks = listOf(
            OfflineBannerMark(0L, NetworkCondition.NoInternet),
            OfflineBannerMark(400L, NetworkCondition.ServersUnreachable),
            OfflineBannerMark(2_500L, NetworkCondition.Online),
        )
        assertFalse(offlineBannerViewAt(marks, -1L, OfflineBannerSurface.Main, reduceMotion = false).visible)
        val shown = offlineBannerViewAt(marks, 0L, OfflineBannerSurface.Main, reduceMotion = false)
        assertTrue(shown.visible)
        assertFalse(shown.animated)
        assertTrue(offlineBannerViewAt(marks, 1_000L, OfflineBannerSurface.Main, reduceMotion = false).visible)
        assertFalse(offlineBannerViewAt(marks, 0L, OfflineBannerSurface.Player, reduceMotion = false).visible)
        assertFalse(offlineBannerViewAt(marks, 0L, OfflineBannerSurface.LaunchGate, reduceMotion = false).visible)
        val hidden = offlineBannerViewAt(marks, 2_500L, OfflineBannerSurface.Main, reduceMotion = false)
        assertFalse(hidden.visible)
        assertTrue(hidden.animated)
        val hiddenStill = offlineBannerViewAt(marks, 2_500L, OfflineBannerSurface.Main, reduceMotion = true)
        assertFalse(hiddenStill.visible)
        assertFalse(hiddenStill.animated)
    }

    @Test
    fun aNewDropAfterOnlineShowsAgainImmediately() {
        val marks = listOf(
            OfflineBannerMark(0L, NetworkCondition.NoInternet),
            OfflineBannerMark(400L, NetworkCondition.Online),
            OfflineBannerMark(900L, NetworkCondition.NoInternet),
            OfflineBannerMark(2_000L, NetworkCondition.Online),
        )
        assertTrue(offlineBannerViewAt(marks, 0L, OfflineBannerSurface.Main, reduceMotion = false).visible)
        val faded = offlineBannerViewAt(marks, 400L, OfflineBannerSurface.Main, reduceMotion = false)
        assertFalse(faded.visible)
        assertTrue(faded.animated)
        assertTrue(offlineBannerViewAt(marks, 900L, OfflineBannerSurface.Main, reduceMotion = false).visible)
        val fadedAgain = offlineBannerViewAt(marks, 2_000L, OfflineBannerSurface.Main, reduceMotion = false)
        assertFalse(fadedAgain.visible)
        assertTrue(fadedAgain.animated)
    }

    @Test
    fun hideFadesOnTheMainScreenUnlessReduceMotionIsOn() {
        assertEquals(250, OfflineBannerHideFadeMs)
        assertEquals(OfflineTransitionDurationMs, OfflineBannerHideFadeMs)
        assertEquals(OfflineBannerHideFadeMs, OfflineBannerShowMs)
        assertTrue(OfflineTransitionDurationMs < 300)
        assertEquals(0L, OfflineBannerHoldMs)
        assertTrue(OfflineBannerShowMs > 0)
        assertEquals(0L, OfflineBannerAppearDelayMs)
        assertTrue(offlineBannerMotionEnabled(OfflineBannerSurface.Main, reduceMotion = false))
        assertFalse(offlineBannerMotionEnabled(OfflineBannerSurface.Main, reduceMotion = true))
        assertFalse(offlineBannerMotionEnabled(OfflineBannerSurface.Player, reduceMotion = false))
        assertFalse(offlineBannerMotionEnabled(OfflineBannerSurface.LaunchGate, reduceMotion = false))
    }

    @Test
    fun tapTriggersExactlyOneRecheck() {
        var calls = 0
        onOfflineBannerTap { calls += 1 }
        assertEquals(1, calls)
    }

    @Test
    fun latchShowsOfflineAtOnceAndHidesOnlyAfterTheHold() {
        var latch = OfflineBannerLatch()
        latch = reduceOfflineBannerLatch(latch, offline = true, OfflineBannerSurface.Main, nowMs = 10L)
        assertTrue(latch.visible)
        latch = reduceOfflineBannerLatch(latch, offline = false, OfflineBannerSurface.Main, nowMs = 10L)
        assertFalse(latch.visible)
        val player = reduceOfflineBannerLatch(
            OfflineBannerLatch(visible = true),
            offline = true,
            OfflineBannerSurface.Player,
            nowMs = 0L,
        )
        assertFalse(player.visible)
    }
}
