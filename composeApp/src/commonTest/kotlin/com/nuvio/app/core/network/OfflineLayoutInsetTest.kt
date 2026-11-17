package com.nuvio.app.core.network

import com.nuvio.app.core.ui.floatingTabContentBottomInsetDp
import com.nuvio.app.core.ui.tabBarContentInsetDp
import com.nuvio.app.features.offline.OfflineReachableAction
import com.nuvio.app.features.offline.reachableOfflineActions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OfflineLayoutInsetTest {
    @Test
    fun bannerStripIsContentSizedAndDoesNotApplyTheStatusInsetTwice() {
        val open = offlineBannerFrame(statusInsetDp = 59f, statusInsetAlreadyApplied = false, fontScale = 1f)
        val consumed = offlineBannerFrame(statusInsetDp = 59f, statusInsetAlreadyApplied = true, fontScale = 1f)
        assertEquals(59f, open.topInsetDp)
        assertEquals(0f, consumed.topInsetDp)
        assertEquals(open.stripHeightDp, consumed.stripHeightDp)
        assertEquals(open.stripHeightDp, consumed.totalHeightDp)
        assertEquals(open.topInsetDp + open.stripHeightDp, open.totalHeightDp)
        assertTrue(open.stripHeightDp < 59f + 48f)
        val largeType = offlineBannerFrame(statusInsetDp = 47f, statusInsetAlreadyApplied = false, fontScale = 2f)
        assertTrue(largeType.stripHeightDp > open.stripHeightDp)
        assertEquals(47f, largeType.topInsetDp)
    }

    @Test
    fun showHomeAnywaySitsBelowTheBannerAtLargeType() {
        for (status in listOf(0f, 20f, 47f, 59f)) {
            for (scale in listOf(1f, 1.3f, 2f, 3.1f)) {
                assertTrue(
                    offlineHomeActionsClearBanner(status, scale),
                    "status=$status scale=$scale",
                )
                val top = offlineDownloadsHomeTopPaddingDp(status, scale)
                val banner = offlineBannerFrame(status, statusInsetAlreadyApplied = false, scale)
                assertTrue(top >= banner.totalHeightDp)
                assertTrue(offlineHomeActionMinHeightDp(scale) >= 48f)
            }
        }
    }

    @Test
    fun contentInsetIsTabBarHeightPlusSafeArea() {
        assertEquals(106f, tabBarContentInsetDp(tabBarHeightDp = 72f, safeAreaBottomDp = 34f))
        assertEquals(72f, tabBarContentInsetDp(tabBarHeightDp = 72f, safeAreaBottomDp = 0f))
        assertEquals(0f, tabBarContentInsetDp(tabBarHeightDp = -4f, safeAreaBottomDp = -8f))

        val phone = floatingTabContentBottomInsetDp(nativeTabs = false, safeAreaBottomDp = 34f, labelFraction = 1f)
        assertEquals(tabBarContentInsetDp(64f + 8f + 16f, 34f), phone)
        val collapsed = floatingTabContentBottomInsetDp(nativeTabs = false, safeAreaBottomDp = 34f, labelFraction = 0f)
        assertTrue(collapsed < phone)
        assertTrue(collapsed >= 34f + 48f)

        val glass = floatingTabContentBottomInsetDp(nativeTabs = true, safeAreaBottomDp = 34f)
        assertTrue(glass >= 62f + 34f)
        assertTrue(glass >= 62f + 20f + 16f)
        val glassWithoutInset = floatingTabContentBottomInsetDp(nativeTabs = true, safeAreaBottomDp = 0f)
        assertEquals(62f + 20f + 16f, glassWithoutInset)
    }

    @Test
    fun everyOfflineActionStaysReachableOnTheDownloadsHome() {
        val actions = reachableOfflineActions(onDownloadsHome = true, bannerVisible = true)
        assertEquals(OfflineReachableAction.entries.toSet(), actions)
        val withoutBanner = reachableOfflineActions(onDownloadsHome = true, bannerVisible = false)
        assertFalse(OfflineReachableAction.BannerRecheck in withoutBanner)
        assertTrue(OfflineReachableAction.ShowHomeAnyway in withoutBanner)
        assertTrue(OfflineReachableAction.OpenDownloads in withoutBanner)
        assertEquals(
            emptySet(),
            reachableOfflineActions(onDownloadsHome = false, bannerVisible = false),
        )
    }
}
