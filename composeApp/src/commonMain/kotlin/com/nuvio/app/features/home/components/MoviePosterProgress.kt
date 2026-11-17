package com.nuvio.app.features.home.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.watching.application.WatchingState
import com.nuvio.app.features.watchprogress.WatchProgressRepository

/** One observed progress map for a poster shelf or grid. No per-poster lookup work. */
@Composable
internal fun rememberMoviePosterProgressById(): Map<String, Float> {
    val progressState by remember {
        WatchProgressRepository.ensureLoaded()
        WatchProgressRepository.uiState
    }.collectAsStateWithLifecycle()
    val entries = progressState.entries
    return remember(entries) { WatchingState.moviePosterProgressById(entries) }
}
