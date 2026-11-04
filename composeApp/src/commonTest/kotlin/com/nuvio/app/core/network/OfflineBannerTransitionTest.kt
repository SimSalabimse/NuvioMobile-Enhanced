package com.nuvio.app.core.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Home and details, online and offline and both transitions, at the three
 * status insets a phone actually reports. The page offset does not change.
 * The strip is one row. The label sits beside the back button. Banner and
 * content fade and slide both ways in under 300ms.
 */
class OfflineBannerTransitionTest {
    @Test
    fun slimRowDoesNotPushContentAndBothDirectionsAnimate() {
        val screens = listOf(OfflineBannerScreen.Home, OfflineBannerScreen.Details)
        val phases = listOf(
            OfflineBannerPhase.Online,
            OfflineBannerPhase.Offline,
            OfflineBannerPhase.GoingOffline,
            OfflineBannerPhase.ComingOnline,
        )
        val insets = listOf(0f, 47f, 59f)
        val motion = offlineTransitionMotion(reduceMotion = false)
        assertTrue(motion.durationMs < 300, "duration ${motion.durationMs}")
        assertEquals(250, motion.durationMs)
        assertEquals(OfflineTransitionDurationMs, motion.durationMs)
        assertEquals(OfflineTransitionEasing.FastOutSlowIn, motion.easing)
        assertEquals(
            setOf(OfflineTransitionDirection.GoingOffline, OfflineTransitionDirection.ComingOnline),
            motion.directions,
        )
        assertEquals(
            setOf(
                OfflineMotionElement.BannerFade,
                OfflineMotionElement.BannerSlide,
                OfflineMotionElement.ContentFade,
                OfflineMotionElement.ContentSlide,
            ),
            motion.elements,
        )
        assertTrue(motion.contentSlideDp > 0f)
        assertTrue(motion.contentFade > 0f)
        val snapped = offlineTransitionMotion(reduceMotion = true)
        assertEquals(0, snapped.durationMs)
        assertTrue(snapped.snap)

        val offsets = mutableListOf<Float>()
        for (screen in screens) {
            for (phase in phases) {
                for (inset in insets) {
                    val sample = offlinePhaseSample(phase)
                    val scene = offlineScene(
                        offline = sample.offline,
                        progress = sample.progress,
                        screen = screen,
                        statusInsetDp = inset,
                        reduceMotion = false,
                        fontScale = 1f,
                        viewportWidthDp = 390f,
                    )
                    val slim = offlineBannerStripHeightDp(fontScale = 1f)
                    val screenWidth = 390f
                    assertEquals(screenWidth, scene.bannerWidthDp, "$screen $phase inset=$inset width")
                    assertTrue(scene.bannerHeightDp <= inset + slim, "$screen $phase inset=$inset height")
                    assertEquals(inset + slim, scene.bannerHeightDp, "$screen $phase inset=$inset")
                    assertEquals(0f, scene.contentTopOffsetDp, "$screen $phase inset=$inset")
                    offsets += scene.contentTopOffsetDp
                    assertFalse(scene.label.overlaps(scene.backButton), "$screen $phase inset=$inset label/back")
                    assertFalse(
                        scene.label.overlaps(scene.trailingControl),
                        "$screen $phase inset=$inset label/trailing",
                    )
                    assertTrue(
                        scene.label.top < scene.backButton.bottom && scene.backButton.top < scene.label.bottom,
                        "$screen $phase inset=$inset label is beside the back button",
                    )
                    assertTrue(scene.label.left >= scene.backButton.right, "$screen $phase label starts after the button")
                    when (phase) {
                        OfflineBannerPhase.Online -> {
                            assertFalse(scene.bannerDrawn)
                            assertEquals(0f, scene.bannerAlpha)
                            assertEquals(1f, scene.contentAlpha)
                            assertEquals(0f, scene.contentSlideDp)
                        }
                        OfflineBannerPhase.Offline -> {
                            assertTrue(scene.bannerDrawn)
                            assertEquals(1f, scene.bannerAlpha)
                            assertEquals(0f, scene.bannerSlideDp)
                            assertEquals(1f, scene.contentAlpha)
                            assertEquals(0f, scene.contentSlideDp)
                        }
                        OfflineBannerPhase.GoingOffline,
                        OfflineBannerPhase.ComingOnline,
                        -> {
                            assertTrue(scene.bannerDrawn, "$screen $phase")
                            assertTrue(scene.bannerAlpha > 0f && scene.bannerAlpha < 1f, "$screen $phase alpha")
                            assertTrue(scene.bannerSlideDp != 0f, "$screen $phase banner slide")
                            assertTrue(scene.contentAlpha < 1f, "$screen $phase content fade")
                            assertTrue(scene.contentSlideDp != 0f, "$screen $phase content slide")
                            assertTrue(scene.motion.durationMs < 300)
                            assertTrue(OfflineMotionElement.BannerFade in scene.motion.elements)
                            assertTrue(OfflineMotionElement.BannerSlide in scene.motion.elements)
                            assertTrue(OfflineMotionElement.ContentFade in scene.motion.elements)
                            assertTrue(OfflineMotionElement.ContentSlide in scene.motion.elements)
                            assertTrue(phase.toDirection() in scene.motion.directions)
                        }
                        OfflineBannerPhase.Reconnecting -> error("not in this matrix")
                    }
                }
            }
        }
        assertEquals(1, offsets.toSet().size)
        assertEquals(0f, offsets.first())
    }

    @Test
    fun easingIsFastOutSlowInAndStaysInsideTheUnitInterval() {
        assertEquals(0f, offlineTransitionEased(0f), 0.001f)
        assertEquals(1f, offlineTransitionEased(1f), 0.001f)
        val mid = offlineTransitionEased(0.5f)
        assertTrue(mid > 0.5f, "fast-out should be ahead of linear at the midpoint, was $mid")
        assertTrue(mid < 1f)
        var previous = 0f
        for (step in 1..10) {
            val value = offlineTransitionEased(step / 10f)
            assertTrue(value + 0.0001f >= previous, "easing went backwards at $step: $value < $previous")
            previous = value
        }
    }

    @Test
    fun reduceMotionSkipsTheCrest() {
        val scene = offlineScene(
            offline = true,
            progress = 0.5f,
            screen = OfflineBannerScreen.Details,
            statusInsetDp = 59f,
            reduceMotion = true,
        )
        assertEquals(1f, scene.bannerAlpha)
        assertEquals(0f, scene.bannerSlideDp)
        assertEquals(1f, scene.contentAlpha)
        assertEquals(0f, scene.contentSlideDp)
        assertEquals(0f, scene.contentTopOffsetDp)
    }
}

private fun OfflineBannerPhase.toDirection(): OfflineTransitionDirection = when (this) {
    OfflineBannerPhase.GoingOffline -> OfflineTransitionDirection.GoingOffline
    OfflineBannerPhase.ComingOnline -> OfflineTransitionDirection.ComingOnline
    else -> error(name)
}
