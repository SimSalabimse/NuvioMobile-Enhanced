package com.nuvio.app.features.analysis

import com.nuvio.app.features.downloads.DownloadItem
import com.nuvio.app.features.downloads.DownloadStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ChapterChipStatusTest {
    private val item = DownloadItem(
        id = "chapter-chip",
        contentType = "series",
        parentMetaId = "chapter-chip-show",
        parentMetaType = "series",
        videoId = "chapter-chip-show:1:1",
        title = "Show",
        seasonNumber = 1,
        episodeNumber = 1,
        streamTitle = "S1E1",
        providerName = "Test",
        sourceUrl = "https://example.test/e.mkv",
        fileName = "e.mkv",
        status = DownloadStatus.Completed,
        createdAtEpochMs = 1L,
        updatedAtEpochMs = 1L,
    )

    @Test
    fun chapterSourceSetsEachChipFromItsOwnTimestamps() {
        val previousIntro = MediaAnalysisSettings.isEnabled(MediaAnalysisSettings.Flag.IntroDetection)
        val previousRecap = MediaAnalysisSettings.isEnabled(MediaAnalysisSettings.Flag.RecapDetection)
        MediaAnalysisStatus.supportedForTests = true
        MediaAnalysisSettings.set(MediaAnalysisSettings.Flag.IntroDetection, true)
        MediaAnalysisSettings.set(MediaAnalysisSettings.Flag.RecapDetection, true)
        try {
            val rows = listOf(
                ChipRow("intro, credits and recap", intro = true, credits = true, recap = true, AnalysisPart.Ready, AnalysisPart.Ready),
                ChipRow("intro only", intro = true, credits = false, recap = false, AnalysisPart.Ready, AnalysisPart.NeedsMoreEpisodes),
                ChipRow("credits only", intro = false, credits = true, recap = false, AnalysisPart.Ready, AnalysisPart.NeedsMoreEpisodes),
                ChipRow("recap only", intro = false, credits = false, recap = true, AnalysisPart.NeedsMoreEpisodes, AnalysisPart.Ready),
                ChipRow("no chapter times", intro = false, credits = false, recap = false, AnalysisPart.NeedsMoreEpisodes, AnalysisPart.NeedsMoreEpisodes),
            )
            rows.forEach { row ->
                MediaAnalysisStore.save(stored(row.intro, row.credits, row.recap))
                val done = assertIs<ItemAnalysisStatus.Done>(MediaAnalysisStatus.statusFor(item), row.name)
                assertEquals(row.introCredits, done.parts.introCredits, row.name)
                assertEquals(row.recapPart, done.parts.recap, row.name)
            }
            MediaAnalysisStore.save(stored(intro = true, credits = true, recap = true))
            val sources = skipSourcesFor(item).map { it.second }.toSet()
            assertTrue("Chapters" in sources)
            assertEquals(listOf("Intro", "Recap", "Credits", "Preview").filter { label ->
                skipSourcesFor(item).any { it.first == label }
            }.toSet(), setOf("Intro", "Recap", "Credits"))

            MediaAnalysisSettings.set(MediaAnalysisSettings.Flag.IntroDetection, false)
            MediaAnalysisStore.save(stored(intro = true, credits = true, recap = true))
            val detectionOff = assertIs<ItemAnalysisStatus.Done>(MediaAnalysisStatus.statusFor(item))
            assertEquals(AnalysisPart.Off, detectionOff.parts.introCredits)
            assertEquals(AnalysisPart.Ready, detectionOff.parts.recap)

            MediaAnalysisSettings.set(MediaAnalysisSettings.Flag.IntroDetection, true)
            MediaAnalysisSettings.set(MediaAnalysisSettings.Flag.RecapDetection, false)
            val recapOff = assertIs<ItemAnalysisStatus.Done>(MediaAnalysisStatus.statusFor(item))
            assertEquals(AnalysisPart.Ready, recapOff.parts.introCredits)
            assertEquals(AnalysisPart.Off, recapOff.parts.recap)
        } finally {
            MediaAnalysisStatus.supportedForTests = null
            MediaAnalysisSettings.set(MediaAnalysisSettings.Flag.IntroDetection, previousIntro)
            MediaAnalysisSettings.set(MediaAnalysisSettings.Flag.RecapDetection, previousRecap)
            MediaAnalysisStore.reset(MediaAnalysisStore.keyFor(item))
        }
    }

    private fun stored(intro: Boolean, credits: Boolean, recap: Boolean) = MediaAnalysisResult(
        key = MediaAnalysisStore.keyFor(item),
        analyzedAtMs = 1L,
        durationMs = 2_400_000L,
        fileName = item.fileName,
        derivedVersion = MEDIA_ANALYSIS_DERIVED_VERSION,
        introStartMs = if (intro) 20_000L else null,
        introEndMs = if (intro) 110_000L else null,
        creditsStartMs = if (credits) 2_310_000L else null,
        creditsEndMs = if (credits) 2_400_000L else null,
        recapStartMs = if (recap) 0L else null,
        recapEndMs = if (recap) 20_000L else null,
        chapterTypes = buildSet {
            if (intro) add("intro")
            if (credits) add("outro")
            if (recap) add("recap")
        },
    )

    private data class ChipRow(
        val name: String,
        val intro: Boolean,
        val credits: Boolean,
        val recap: Boolean,
        val introCredits: AnalysisPart,
        val recapPart: AnalysisPart,
    )
}
