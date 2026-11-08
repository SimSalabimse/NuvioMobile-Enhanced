package com.nuvio.app.features.analysis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReferenceClipPlanTest {
    @Test
    fun oneLocalFileOfTheTargetDoesNotBlockARemoteSibling() {
        val onlyThisEpisode = planReference(
            targetEpisode = 4,
            localOtherEpisodes = emptyList(),
            remoteOtherEpisodes = listOf(3, 4, 5),
        )
        assertEquals(ReferenceChoice.Remote, onlyThisEpisode.choice)
        assertEquals(3, onlyThisEpisode.episode)
        assertEquals("remote-sibling", onlyThisEpisode.reason)

        val local = planReference(4, localOtherEpisodes = listOf(4, 8), remoteOtherEpisodes = listOf(3))
        assertEquals(ReferenceChoice.Local, local.choice)
        assertEquals(8, local.episode)
    }

    @Test
    fun noSiblingRecordsTheExactReason() {
        val none = planReference(1, emptyList(), emptyList())
        assertEquals(ReferenceChoice.None, none.choice)
        assertEquals("no-eligible-local-or-remote-sibling", none.reason)
        assertNull(none.episode)
    }

    @Test
    fun cacheIdentityIgnoresUrlsAndDoesNotServeAnotherWindow() {
        val file = referenceIdentity("Episode.mkv", "tt1", 3, 4)
        val release = referenceIdentity(null, "TT1", 3, 4)
        assertEquals("file:Episode.mkv", file)
        assertEquals("release:tt1:3:4", release)
        assertTrue(!file.contains("http"))
        val first = ReferenceFingerprintKey(release, 0L, 40_000L, REFERENCE_FINGERPRINT_ALGORITHM)
        val otherWindow = first.copy(startMs = 80_000L, endMs = 120_000L)
        val cache = ReferenceFingerprintCache()
        assertEquals(ReferenceReadKind.Decoded, cache.beginDecode(release))
        cache.store(first, "print-a")
        assertEquals(ReferenceReadKind.CacheHit, cache.lookup(first)?.second)
        assertEquals(null, cache.lookup(otherWindow))
        assertEquals(ReferenceReadKind.Decoded, cache.beginDecode(release))
        val joined = cache.beginDecode(release)
        assertEquals(ReferenceReadKind.JoinedSession, joined)
        assertEquals(null, cache.lookup(otherWindow))
    }

    @Test
    fun aLaterMarkOrVersionDropsTheBackgroundWrite() {
        val current = MediaAnalysisResult(
            key = "show__s3e4",
            analyzedAtMs = 10L,
            durationMs = 1_000L,
            fileName = "a.mkv",
            userSegments = mapOf("recap" to UserSegment(0L, 5_000L, markedAtMs = 3L, durationMs = 1_000L)),
            derivedVersion = 3,
        )
        val guard = referenceWriteGuard(current)
        val proposed = current.copy(introStartMs = 1L, introEndMs = 20_000L, foundUsing = mapOf("intro" to 3))
        val merged = mergeReferenceWrite(current, proposed, guard, cancelled = false)
        assertEquals(20_000L, merged?.introEndMs)
        assertEquals(current.userSegments, merged?.userSegments)

        val marked = current.copy(
            userSegments = current.userSegments + ("intro" to UserSegment(0L, 9_000L, durationMs = 1_000L)),
        )
        assertNull(mergeReferenceWrite(marked, proposed, guard, cancelled = false))
        assertNull(mergeReferenceWrite(current.copy(analyzedAtMs = 11L), proposed, guard, cancelled = false))
        assertNull(mergeReferenceWrite(current, proposed, guard, cancelled = true))
        val kept = mergeReferenceWrite(
            current.copy(userSegments = current.userSegments + ("intro" to UserSegment(0L, 9_000L, markedAtMs = 3L, durationMs = 1_000L))),
            proposed,
            referenceWriteGuard(current.copy(userSegments = current.userSegments + ("intro" to UserSegment(0L, 9_000L, markedAtMs = 3L, durationMs = 1_000L)))),
            cancelled = false,
        )
        assertEquals(null, kept?.introEndMs)
    }

    @Test
    fun localWorkRanksAheadOfAReferenceAndTheStatusNamesTheWindow() {
        assertTrue(analysisQueueRank(needsLocalWork = true, needsReference = true) < analysisQueueRank(false, true))
        val token = referenceActivityToken("fetch", 40, 3, 4)
        assertEquals(ReferenceActivity("fetch", 40, 3, 4), parseReferenceActivity(token))
        assertEquals(null, parseReferenceActivity("clip"))
    }
}
