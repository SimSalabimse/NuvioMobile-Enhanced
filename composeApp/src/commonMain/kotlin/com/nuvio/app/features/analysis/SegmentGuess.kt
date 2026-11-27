package com.nuvio.app.features.analysis

/**
 * Stage 2 seam. A general model may later label segments on shows with no marks.
 * It stays off, so it never adds a range and never skips. Stage 1 does not call it
 * to change a result.
 */
internal object SegmentGuess {
    const val ENABLED: Boolean = false

    fun rangesFor(episode: MediaAnalysisResult): List<LearnedRange> {
        if (!ENABLED) return emptyList()
        if (episode.key.isEmpty()) return emptyList()
        return emptyList()
    }
}
