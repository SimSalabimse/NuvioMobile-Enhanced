package com.nuvio.app.features.home

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HomeColumnTransitionTest {
    @Test
    fun offlineAndOnlineSectionsStayListedAndOfflineOnlyRowsFadeOut() {
        val offline = homeColumnSections(offlineDownloadsHome = true)
        val online = homeColumnSections(offlineDownloadsHome = false)
        assertEquals(
            listOf(
                HomeColumnSection.OpenDownloads,
                HomeColumnSection.ShowHomeAnyway,
                HomeColumnSection.OfflineContinueWatching,
                HomeColumnSection.Downloaded,
            ),
            offline,
        )
        assertEquals(
            listOf(
                HomeColumnSection.Hero,
                HomeColumnSection.OnlineContinueWatching,
                HomeColumnSection.CatalogRows,
            ),
            online,
        )
        assertTrue(offline.none { it in online })
        assertEquals(offline.toSet(), offline.filter(::homeSectionFadesOutWhenLeavingOffline).toSet())
        assertTrue(online.none(::homeSectionFadesOutWhenLeavingOffline))
        HomeColumnSection.entries.forEach { section ->
            assertTrue(section in offline || section in online, section.name)
        }
    }

    @Test
    fun reconnectMotionIsAQuarterSecondAndReduceMotionSnaps() {
        val motion = homeReconnectMotion(reduceMotion = false)
        assertEquals(250, motion.durationMs)
        assertFalse(motion.snap)
        val snapped = homeReconnectMotion(reduceMotion = true)
        assertEquals(0, snapped.durationMs)
        assertTrue(snapped.snap)
        assertTrue(homeCoverSnapsOn(goingOffline = true, reduceMotion = false))
        assertFalse(homeCoverSnapsOn(goingOffline = false, reduceMotion = false))
        assertTrue(homeCoverSnapsOn(goingOffline = false, reduceMotion = true))
    }
}
