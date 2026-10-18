package com.nuvio.app.features.details

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.foldForSearch
import com.nuvio.app.features.downloads.DownloadsRepository
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.library.LibraryRepository
import com.nuvio.app.features.watchprogress.WatchProgressRepository

/**
 * What the user already has, keyed by id and by folded title (person credits use TMDB ids while
 * library, progress and downloads usually use IMDb ids, so the title is the practical match).
 */
internal class OwnedTitles(
    private val downloaded: Set<String>,
    private val watching: Set<String>,
    private val library: Set<String>,
) {
    fun statusLabel(item: MetaPreview): String? {
        val keys = ownedKeys(item.id, item.name)
        return when {
            keys.any(downloaded::contains) -> "Downloaded"
            keys.any(watching::contains) -> "Watching"
            keys.any(library::contains) -> "In library"
            else -> null
        }
    }
}

internal fun ownedKeys(id: String, title: String): List<String> =
    listOf(id, foldForSearch(title.trim())).filter { it.isNotBlank() }

@Composable
internal fun rememberOwnedTitles(): OwnedTitles {
    val library by remember { LibraryRepository.ensureLoaded(); LibraryRepository.uiState }.collectAsStateWithLifecycle()
    val progress by remember { WatchProgressRepository.ensureLoaded(); WatchProgressRepository.uiState }.collectAsStateWithLifecycle()
    val downloads by remember { DownloadsRepository.ensureLoaded(); DownloadsRepository.uiState }.collectAsStateWithLifecycle()
    return remember(library.items, progress.entries, downloads.items) {
        OwnedTitles(
            downloaded = downloads.completedItems.flatMapTo(HashSet()) { ownedKeys(it.parentMetaId, it.title) },
            watching = progress.entries.filterNot { it.isEffectivelyCompleted }
                .flatMapTo(HashSet()) { ownedKeys(it.parentMetaId, it.title) },
            library = library.items.flatMapTo(HashSet()) { ownedKeys(it.id, it.name) },
        )
    }
}
