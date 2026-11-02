package com.nuvio.app.features.home.components

import com.nuvio.app.features.downloads.DownloadItem
import com.nuvio.app.features.downloads.DownloadStatus
import com.nuvio.app.features.watchprogress.CachedInProgressItem
import com.nuvio.app.features.watchprogress.CachedNextUpItem
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Offline Continue Watching, one row per combination of the cache, the download, progress, and
 * whether the episode is in both sources.
 *
 * A readable cache shows the saved card. It plays only when that episode is downloaded and
 * playable. An empty or unreadable cache shows a downloaded episode only when it already has
 * progress in the same window as before (started, not finished). A duplicate is one card.
 */
class OfflineContinueWatchingTest {

    @Test
    fun stateTable() {
        stateTable.forEach { row ->
            val cards = cardsFor(row)
            val subject = cards.filter { card -> card.title == SubjectTitle }
            val label = "${row.cache} downloaded=${row.downloaded} progress=${row.hasProgress} duplicate=${row.duplicate}"
            if (!row.visible) {
                assertEquals(emptyList(), subject.map { it.title }, label)
                return@forEach
            }
            assertEquals(1, subject.size, label)
            val card = subject.single()
            assertEquals(row.dimmed, card.dimmed, label)
            assertEquals(row.tappable, card.tappable, label)
            if (row.tappable) {
                assertEquals(SubjectTitle, card.download?.title, label)
            } else {
                assertNull(card.download, label)
            }
            if (row.cache == CacheKind.Present) {
                assertTrue(cards.any { card -> card.title == FillerTitle }, label)
            }
        }
    }

    @Test
    fun savedRowsKeepOnlineOrderAndAppendADownloadedEpisodeThatIsNotCached() {
        val cache = OfflineContinueCache.Present(
            nextUp = listOf(
                nextUp(contentId = "pll", name = "Pretty Little Liars", season = 2, episode = 1, sortTimestamp = 300L),
            ),
            inProgress = listOf(
                inProgress(contentId = "daredevil", name = "Daredevil: Born Again", season = 2, episode = 2, lastWatched = 500L),
                inProgress(contentId = "mentalist", name = "The Mentalist", season = 3, episode = 10, lastWatched = 400L),
            ),
        )
        val daredevil = download(contentId = "daredevil", title = "Daredevil: Born Again", season = 2, episode = 2)
        val lost = download(contentId = "lost", title = "Lost", season = 1, episode = 1, updatedAt = 50L)
        val cards = offlineContinueWatchingCards(
            cache = cache,
            downloads = listOf(daredevil, lost),
            progressByVideoId = mapOf(
                daredevil.videoId to progress(daredevil.videoId, updated = 500L),
                lost.videoId to progress(lost.videoId, updated = 50L),
            ),
        )

        assertEquals(
            listOf("Daredevil: Born Again", "The Mentalist", "Pretty Little Liars", "Lost"),
            cards.map { it.title },
        )
        assertFalse(cards[0].dimmed)
        assertTrue(cards[0].tappable)
        assertEquals(daredevil.id, cards[0].download?.id)
        assertTrue(cards[1].dimmed)
        assertFalse(cards[1].tappable)
        assertTrue(cards[2].dimmed)
        assertFalse(cards[2].tappable)
        assertFalse(cards[3].dimmed)
        assertTrue(cards[3].tappable)
        assertEquals(cards.map { it.key }.distinct().size, cards.size)
    }

    @Test
    fun inProgressHidesNextUpForTheSameShowAndADifferentDownloadedEpisodeStillAppears() {
        val cache = OfflineContinueCache.Present(
            nextUp = listOf(nextUp(contentId = "show", name = "Show", season = 1, episode = 3, sortTimestamp = 900L)),
            inProgress = listOf(inProgress(contentId = "show", name = "Show", season = 1, episode = 2, lastWatched = 100L)),
        )
        val downloadedNext = download(contentId = "show", title = "Show", season = 1, episode = 4)
        val cards = offlineContinueWatchingCards(
            cache = cache,
            downloads = listOf(downloadedNext),
            progressByVideoId = mapOf(downloadedNext.videoId to progress(downloadedNext.videoId)),
        )

        assertEquals(listOf(2 to false, 4 to true), cards.map { card -> card.episodeNumber to card.tappable })
        assertTrue(cards.single { it.episodeNumber == 2 }.dimmed)
        assertFalse(cards.single { it.episodeNumber == 4 }.dimmed)
    }

    @Test
    fun theSameEpisodeInTheCacheAndInDownloadsIsOnePlayableCard() {
        val cache = OfflineContinueCache.Present(
            nextUp = listOf(nextUp(contentId = "show", name = "Show", season = 2, episode = 2, sortTimestamp = 10L)),
            inProgress = listOf(inProgress(contentId = "show", name = "Show", season = 2, episode = 2, lastWatched = 20L)),
        )
        val item = download(contentId = "show", title = "Show", season = 2, episode = 2)
        val cards = offlineContinueWatchingCards(
            cache = cache,
            downloads = listOf(item, item.copy(id = "copy", updatedAtEpochMs = 1L)),
            progressByVideoId = mapOf(item.videoId to progress(item.videoId)),
        )

        assertEquals(1, cards.size)
        assertEquals(2, cards.single().episodeNumber)
        assertFalse(cards.single().dimmed)
        assertTrue(cards.single().tappable)
        assertEquals(item.id, cards.single().download?.id)
    }

    @Test
    fun aCachedEpisodeThatIsDownloadedButNotPlayableIsDimmed() {
        val cache = OfflineContinueCache.Present(
            nextUp = listOf(nextUp(contentId = "show", name = "Show", season = 1, episode = 1, sortTimestamp = 10L)),
            inProgress = emptyList(),
        )
        val item = download(contentId = "show", title = "Show", season = 1, episode = 1, playable = false)
        val cards = offlineContinueWatchingCards(
            cache = cache,
            downloads = listOf(item),
            progressByVideoId = mapOf(item.videoId to progress(item.videoId)),
        )

        assertEquals(1, cards.size)
        assertTrue(cards.single().dimmed)
        assertFalse(cards.single().tappable)
        assertNull(cards.single().download)
    }

    @Test
    fun progressOutsideTheResumeWindowDoesNotAddADownloadAndDoesNotHideACachedCard() {
        val barelyStarted = download(contentId = "barely", title = "Barely", season = 1, episode = 1)
        val finished = download(contentId = "finished", title = "Finished", season = 1, episode = 2)
        val progress = mapOf(
            barelyStarted.videoId to progress(barelyStarted.videoId, position = 100L, duration = 100_000L),
            finished.videoId to progress(finished.videoId, completed = true),
        )
        val empty = offlineContinueWatchingCards(
            cache = OfflineContinueCache.Empty,
            downloads = listOf(barelyStarted, finished),
            progressByVideoId = progress,
        )
        assertEquals(emptyList(), empty.map { it.title })

        val cached = offlineContinueWatchingCards(
            cache = OfflineContinueCache.Present(
                nextUp = listOf(nextUp(contentId = "barely", name = "Barely", season = 1, episode = 1, sortTimestamp = 5L)),
                inProgress = emptyList(),
            ),
            downloads = listOf(barelyStarted),
            progressByVideoId = progress,
        )
        assertEquals(1, cached.size)
        assertFalse(cached.single().dimmed)
        assertTrue(cached.single().tappable)
    }

    @Test
    fun anEmptyLibraryHidesTheRow() {
        assertEquals(
            emptyList(),
            offlineContinueWatchingCards(OfflineContinueCache.Empty, emptyList(), emptyMap()),
        )
        assertEquals(
            emptyList(),
            offlineContinueWatchingCards(OfflineContinueCache.Unreadable, emptyList(), emptyMap()),
        )
    }

    @Test
    fun downloadedResumeStaysCappedAtTenNewest() {
        val downloads = (1..11).map { index ->
            download(contentId = "show-$index", title = "Show $index", season = 1, episode = index, updatedAt = index.toLong())
        }
        val progress = downloads.associate { item ->
            item.videoId to progress(item.videoId, updated = item.episodeNumber!!.toLong() * 10L)
        }
        val cards = offlineContinueWatchingCards(OfflineContinueCache.Unreadable, downloads, progress)

        assertEquals((11 downTo 2).map { "Show $it" }, cards.map { it.title })
        assertTrue(cards.all { it.tappable && !it.dimmed })
    }

    @Test
    fun nullSnapshotsAreUnreadableAndBlankSnapshotsAreEmpty() {
        assertTrue(offlineContinueCacheFromSnapshots(null, null) is OfflineContinueCache.Unreadable)
        assertTrue(offlineContinueCacheFromSnapshots(emptyList(), null) is OfflineContinueCache.Unreadable)
        assertTrue(offlineContinueCacheFromSnapshots(emptyList(), emptyList()) is OfflineContinueCache.Empty)
        val row = nextUp(contentId = "show", name = "Show", season = 1, episode = 1, sortTimestamp = 1L)
        val present = offlineContinueCacheFromSnapshots(listOf(row), emptyList())
        assertTrue(present is OfflineContinueCache.Present)
    }

    private fun cardsFor(row: StateRow): List<OfflineContinueCard> {
        val inCache = row.cache == CacheKind.Present && (row.duplicate || !row.downloaded)
        val nextUp = buildList {
            if (row.cache == CacheKind.Present) {
                add(nextUp(contentId = "filler", name = FillerTitle, season = 1, episode = 1, sortTimestamp = 1L))
                if (inCache) {
                    add(subjectNextUp())
                    if (row.duplicate && !row.downloaded) add(subjectNextUp())
                }
            }
        }
        val downloads = if (row.downloaded) listOf(subjectDownload()) else emptyList()
        val progress = if (row.hasProgress) {
            mapOf(SubjectVideo to progress(SubjectVideo))
        } else {
            emptyMap()
        }
        val cache = when (row.cache) {
            CacheKind.Present -> offlineContinueCacheFromSnapshots(nextUp, emptyList())
            CacheKind.Empty -> OfflineContinueCache.Empty
            CacheKind.Unreadable -> OfflineContinueCache.Unreadable
        }
        return offlineContinueWatchingCards(cache, downloads, progress)
    }
}

private enum class CacheKind { Present, Empty, Unreadable }

private data class StateRow(
    val cache: CacheKind,
    val downloaded: Boolean,
    val hasProgress: Boolean,
    val duplicate: Boolean,
    val visible: Boolean,
    val dimmed: Boolean,
    val tappable: Boolean,
)

private const val SubjectTitle = "Subject"
private const val FillerTitle = "Filler"
private const val SubjectVideo = "subject-video"

/**
 * Readable cache: the card is shown when it is saved, or when it is only a download that already
 * has progress. Saved cards that are not downloaded are dimmed. Empty and unreadable caches ignore
 * the saved copy and keep the old downloaded-only rule. Duplicate means the episode is listed twice
 * and still produces one card.
 */
private val stateTable = listOf(
    StateRow(CacheKind.Present, downloaded = false, hasProgress = false, duplicate = false, visible = true, dimmed = true, tappable = false),
    StateRow(CacheKind.Present, downloaded = false, hasProgress = true, duplicate = false, visible = true, dimmed = true, tappable = false),
    StateRow(CacheKind.Present, downloaded = false, hasProgress = false, duplicate = true, visible = true, dimmed = true, tappable = false),
    StateRow(CacheKind.Present, downloaded = false, hasProgress = true, duplicate = true, visible = true, dimmed = true, tappable = false),
    StateRow(CacheKind.Present, downloaded = true, hasProgress = false, duplicate = false, visible = false, dimmed = false, tappable = false),
    StateRow(CacheKind.Present, downloaded = true, hasProgress = true, duplicate = false, visible = true, dimmed = false, tappable = true),
    StateRow(CacheKind.Present, downloaded = true, hasProgress = false, duplicate = true, visible = true, dimmed = false, tappable = true),
    StateRow(CacheKind.Present, downloaded = true, hasProgress = true, duplicate = true, visible = true, dimmed = false, tappable = true),
    StateRow(CacheKind.Empty, downloaded = false, hasProgress = false, duplicate = false, visible = false, dimmed = false, tappable = false),
    StateRow(CacheKind.Empty, downloaded = false, hasProgress = true, duplicate = false, visible = false, dimmed = false, tappable = false),
    StateRow(CacheKind.Empty, downloaded = false, hasProgress = false, duplicate = true, visible = false, dimmed = false, tappable = false),
    StateRow(CacheKind.Empty, downloaded = false, hasProgress = true, duplicate = true, visible = false, dimmed = false, tappable = false),
    StateRow(CacheKind.Empty, downloaded = true, hasProgress = false, duplicate = false, visible = false, dimmed = false, tappable = false),
    StateRow(CacheKind.Empty, downloaded = true, hasProgress = true, duplicate = false, visible = true, dimmed = false, tappable = true),
    StateRow(CacheKind.Empty, downloaded = true, hasProgress = false, duplicate = true, visible = false, dimmed = false, tappable = false),
    StateRow(CacheKind.Empty, downloaded = true, hasProgress = true, duplicate = true, visible = true, dimmed = false, tappable = true),
    StateRow(CacheKind.Unreadable, downloaded = false, hasProgress = false, duplicate = false, visible = false, dimmed = false, tappable = false),
    StateRow(CacheKind.Unreadable, downloaded = false, hasProgress = true, duplicate = false, visible = false, dimmed = false, tappable = false),
    StateRow(CacheKind.Unreadable, downloaded = false, hasProgress = false, duplicate = true, visible = false, dimmed = false, tappable = false),
    StateRow(CacheKind.Unreadable, downloaded = false, hasProgress = true, duplicate = true, visible = false, dimmed = false, tappable = false),
    StateRow(CacheKind.Unreadable, downloaded = true, hasProgress = false, duplicate = false, visible = false, dimmed = false, tappable = false),
    StateRow(CacheKind.Unreadable, downloaded = true, hasProgress = true, duplicate = false, visible = true, dimmed = false, tappable = true),
    StateRow(CacheKind.Unreadable, downloaded = true, hasProgress = false, duplicate = true, visible = false, dimmed = false, tappable = false),
    StateRow(CacheKind.Unreadable, downloaded = true, hasProgress = true, duplicate = true, visible = true, dimmed = false, tappable = true),
)

private fun subjectNextUp(): CachedNextUpItem =
    nextUp(contentId = "subject", name = SubjectTitle, season = 2, episode = 2, sortTimestamp = 100L)

private fun subjectDownload(): DownloadItem =
    download(contentId = "subject", title = SubjectTitle, season = 2, episode = 2, videoId = SubjectVideo)

private fun nextUp(
    contentId: String,
    name: String,
    season: Int?,
    episode: Int?,
    sortTimestamp: Long,
): CachedNextUpItem = CachedNextUpItem(
    contentId = contentId,
    contentType = "series",
    name = name,
    poster = "https://example.test/$contentId-poster.jpg",
    backdrop = "https://example.test/$contentId-backdrop.jpg",
    videoId = "$contentId-video",
    season = season,
    episode = episode,
    episodeTitle = "Episode",
    episodeThumbnail = "https://example.test/$contentId-thumb.jpg",
    lastWatched = sortTimestamp,
    sortTimestamp = sortTimestamp,
)

private fun inProgress(
    contentId: String,
    name: String,
    season: Int?,
    episode: Int?,
    lastWatched: Long,
): CachedInProgressItem = CachedInProgressItem(
    contentId = contentId,
    contentType = "series",
    name = name,
    poster = "https://example.test/$contentId-poster.jpg",
    videoId = "$contentId-video",
    season = season,
    episode = episode,
    episodeTitle = "Episode",
    position = 40_000L,
    duration = 100_000L,
    lastWatched = lastWatched,
)

private fun progress(
    videoId: String,
    updated: Long = 1_000L,
    position: Long = 50_000L,
    duration: Long = 100_000L,
    completed: Boolean = false,
): WatchProgressEntry = WatchProgressEntry(
    contentType = "series",
    parentMetaId = "subject",
    parentMetaType = "series",
    videoId = videoId,
    title = SubjectTitle,
    seasonNumber = 2,
    episodeNumber = 2,
    lastPositionMs = position,
    durationMs = duration,
    lastUpdatedEpochMs = updated,
    isCompleted = completed,
)

private fun download(
    contentId: String,
    title: String,
    season: Int?,
    episode: Int?,
    playable: Boolean = true,
    videoId: String = "$contentId:${season ?: 0}:${episode ?: 0}",
    updatedAt: Long = 1L,
): DownloadItem = DownloadItem(
    id = "$contentId-$season-$episode",
    contentType = "series",
    parentMetaId = contentId,
    parentMetaType = "series",
    videoId = videoId,
    title = title,
    seasonNumber = season,
    episodeNumber = episode,
    episodeTitle = "Episode",
    streamTitle = title,
    providerName = "addon",
    sourceUrl = "https://example.test/stream",
    fileName = "$contentId.mp4",
    status = DownloadStatus.Completed,
    localFileUri = if (playable) "file://$contentId.mp4" else null,
    createdAtEpochMs = updatedAt,
    updatedAtEpochMs = updatedAt,
)
