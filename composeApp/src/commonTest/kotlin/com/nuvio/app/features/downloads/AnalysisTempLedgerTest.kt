package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AnalysisTempLedgerTest {
    @Test
    fun tagsOnlyADownloadCreatedByTheFeature() {
        val created = AnalysisTempLedger().withAcquisition(
            downloadId = "temp-1",
            createdByFeature = true,
            userAlreadyOwned = false,
        )
        assertEquals(setOf("temp-1"), created.taggedIds)
        val untouched = AnalysisTempLedger(setOf("temp-1")).withAcquisition(
            downloadId = "other",
            createdByFeature = false,
            userAlreadyOwned = false,
        )
        assertEquals(setOf("temp-1"), untouched.taggedIds)
        assertFalse("other" in untouched.taggedIds)
    }

    @Test
    fun userOwnedDownloadIsNeverTagged() {
        val owned = AnalysisTempLedger().withAcquisition(
            downloadId = "user-1",
            createdByFeature = false,
            userAlreadyOwned = true,
        )
        assertEquals(emptySet(), owned.taggedIds)
        val refused = AnalysisTempLedger().withAcquisition(
            downloadId = "user-1",
            createdByFeature = true,
            userAlreadyOwned = true,
        )
        assertEquals(emptySet(), refused.taggedIds)
        assertFalse("user-1" in refused.taggedIds)
    }

    @Test
    fun userManualDownloadClearsTheTagAndDoesNotTagTheNewFile() {
        val tagged = AnalysisTempLedger(setOf("temp-1"))
        val after = tagged.withAcquisition(
            downloadId = "user-2",
            createdByFeature = false,
            userAlreadyOwned = true,
            replacedId = "temp-1",
        )
        assertEquals(emptySet(), after.taggedIds)
        assertFalse("temp-1" in after.taggedIds)
        assertFalse("user-2" in after.taggedIds)
    }

    @Test
    fun sweepDeletesOnlyTaggedDownloads() {
        val ledger = AnalysisTempLedger(setOf("temp-1", "temp-gone"))
        val present = setOf("temp-1", "user-1", "smart-1")
        assertEquals(setOf("temp-1"), ledger.idsToDelete(present))
        assertFalse("user-1" in ledger.idsToDelete(present))
        assertFalse("smart-1" in ledger.idsToDelete(present))
        assertFalse("temp-gone" in ledger.idsToDelete(present))
    }

    @Test
    fun cancelMidWayDeletesOnlyTaggedDownloads() {
        val ledger = AnalysisTempLedger(setOf("temp-running", "temp-done"))
        val session = setOf("temp-running", "temp-done", "user-reused", "user-already")
        val deleted = ledger.idsToDelete(session)
        assertEquals(setOf("temp-running", "temp-done"), deleted)
        assertFalse("user-reused" in deleted)
        assertFalse("user-already" in deleted)
    }

    @Test
    fun planReusesAUserDownloadAndDoesNotAskToTagIt() {
        val plan = planAnalysisDownloads(
            listOf(
                AnalysisDownloadChoice("show|1|1", existingDownloadId = "user-1", existingTagged = false),
                AnalysisDownloadChoice("show|1|2", existingDownloadId = "temp-2", existingTagged = true),
                AnalysisDownloadChoice("show|1|3", existingDownloadId = null, existingTagged = false),
            ),
        )
        assertEquals(setOf("user-1"), plan.reuseUntagged)
        assertEquals(setOf("temp-2"), plan.alreadyTagged)
        assertEquals(setOf("show|1|3"), plan.needsDownload)
        assertFalse("user-1" in plan.alreadyTagged)
        assertFalse("user-1" in plan.needsDownload)
    }

    @Test
    fun taggedFileIsNotLeftInPlaceAsTheUsers() {
        assertFalse(leaveExistingDownloadInPlace(DownloadStatus.Completed, analysisTemp = true))
        assertFalse(leaveExistingDownloadInPlace(DownloadStatus.Downloading, analysisTemp = true))
        assertTrue(leaveExistingDownloadInPlace(DownloadStatus.Completed, analysisTemp = false))
        assertTrue(leaveExistingDownloadInPlace(DownloadStatus.Paused, analysisTemp = false))
        assertFalse(leaveExistingDownloadInPlace(DownloadStatus.Failed, analysisTemp = false))
        assertFalse(leaveExistingDownloadInPlace(null, analysisTemp = false))
    }

    @Test
    fun ledgerRoundTripsAndDropsBlankIds() {
        val encoded = encodeAnalysisTempLedger(setOf("b", "a", " ", ""))
        assertEquals("a\nb", encoded)
        assertEquals(setOf("a", "b"), decodeAnalysisTempLedger(encoded))
        assertEquals(emptySet(), decodeAnalysisTempLedger("  "))
    }
}
