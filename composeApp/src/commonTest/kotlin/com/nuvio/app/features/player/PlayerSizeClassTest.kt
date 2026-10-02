package com.nuvio.app.features.player

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerSizeClassTest {
    @Test
    fun `667 by 375 is a small phone with phone metrics`() {
        val metrics = PlayerLayoutMetrics.fromSize(667.dp, 375.dp)
        assertEquals(PlayerSizeClass.S, metrics.sizeClass)
        assertPhoneMetrics(metrics)
        assertFalse(metrics.showsTransportSeekButtons())
        assertEquals(16.dp, metrics.transportSeekGap())
    }

    @Test
    fun `360 by 360 is a small phone with phone metrics`() {
        val metrics = PlayerLayoutMetrics.fromSize(360.dp, 360.dp)
        assertEquals(PlayerSizeClass.S, metrics.sizeClass)
        assertPhoneMetrics(metrics)
    }

    @Test
    fun `landscape phone wider than 768 stays on phone metrics`() {
        val medium = PlayerLayoutMetrics.fromSize(844.dp, 390.dp)
        assertEquals(PlayerSizeClass.M, medium.sizeClass)
        assertPhoneMetrics(medium)
        assertTrue(medium.showsTransportSeekButtons())
        assertEquals(16.dp, medium.transportSeekGap())

        val large = PlayerLayoutMetrics.fromSize(932.dp, 430.dp)
        assertEquals(PlayerSizeClass.L, large.sizeClass)
        assertPhoneMetrics(large)
        assertTrue(large.showsTransportSeekButtons())
    }

    @Test
    fun `class T keeps the tablet width steps`() {
        val wideShort = PlayerLayoutMetrics.fromSize(1024.dp, 400.dp)
        assertEquals(PlayerSizeClass.T, wideShort.sizeClass)
        assertEquals(24f, wideShort.titleSize.value)
        assertEquals(88.dp, wideShort.centerGap)

        val tablet = PlayerLayoutMetrics.fromSize(1024.dp, 768.dp)
        assertEquals(PlayerSizeClass.T, tablet.sizeClass)
        assertEquals(24f, tablet.titleSize.value)
        assertEquals(32.dp, tablet.sideIconSize)
        assertEquals(42.dp, tablet.playIconSize)

        val desktop = PlayerLayoutMetrics.fromSize(1440.dp, 900.dp)
        assertEquals(PlayerSizeClass.T, desktop.sizeClass)
        assertEquals(28f, desktop.titleSize.value)
        assertEquals(112.dp, desktop.centerGap)

        val compactTablet = PlayerLayoutMetrics.fromSize(800.dp, 500.dp)
        assertEquals(PlayerSizeClass.T, compactTablet.sizeClass)
        assertEquals(22f, compactTablet.titleSize.value)
        assertEquals(72.dp, compactTablet.centerGap)
        assertEquals(72.dp, compactTablet.transportSeekGap())
    }

    @Test
    fun `size class boundaries use the shorter edge`() {
        assertEquals(PlayerSizeClass.S, playerSizeClass(900.dp, 379.dp))
        assertEquals(PlayerSizeClass.M, playerSizeClass(900.dp, 380.dp))
        assertEquals(PlayerSizeClass.M, playerSizeClass(900.dp, 429.dp))
        assertEquals(PlayerSizeClass.L, playerSizeClass(900.dp, 430.dp))
        assertEquals(PlayerSizeClass.L, playerSizeClass(900.dp, 499.dp))
        assertEquals(PlayerSizeClass.T, playerSizeClass(700.dp, 500.dp))
        assertEquals(PlayerSizeClass.T, playerSizeClass(1024.dp, 320.dp))
    }

    @Test
    fun `trailing panel keeps video visible and centered cards use the safe frame`() {
        assertEquals(400.dp, playerTrailingPanelWidth(800.dp, 0.dp))
        assertEquals(300.dp, playerTrailingPanelWidth(500.dp, 0.dp))
        assertEquals(272.dp, playerTrailingPanelWidth(400.dp, 80.dp))
        assertEquals(152.dp, playerTrailingPanelWidth(200.dp, 0.dp))
        assertEquals(360.dp, playerCenteredCardWidth(400.dp))
        assertEquals(400.dp, playerCenteredCardWidth(500.dp))
        assertEquals(343.dp, playerCenteredCardMaxHeight(375.dp))
    }

    @Test
    fun `fromWidth still resolves numeric metrics for existing host tests`() {
        val metrics = PlayerLayoutMetrics.fromWidth(640.dp)
        assertPhoneMetrics(metrics)
    }

    private fun assertPhoneMetrics(metrics: PlayerLayoutMetrics) {
        assertEquals(18f, metrics.titleSize.value)
        assertEquals(26.dp, metrics.sideIconSize)
        assertEquals(34.dp, metrics.playIconSize)
        assertEquals(56.dp, metrics.centerGap)
    }
}
