package com.nuvio.app.features.details.components

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtonStyle
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.DialogSurface
import com.nuvio.app.core.ui.NuvioModalBottomSheet
import com.nuvio.app.core.ui.dismissNuvioBottomSheet
import com.nuvio.app.core.ui.dismissPresentedNuvioBottomSheet
import com.nuvio.app.core.ui.nuvioSafeBottomPadding
import com.nuvio.app.core.ui.systemReduceMotionEnabled
import com.nuvio.app.core.ui.usesNativeNuvioBottomSheet
import com.nuvio.app.core.ui.background.isGlassUiActive
import com.nuvio.app.core.uifixture.currentUiFixture
import com.nuvio.app.features.analysis.MediaAnalysisStatus
import com.nuvio.app.features.analysis.MediaAnalysisStore
import com.nuvio.app.features.details.EpisodeCatalogFacts
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.details.OverviewOpenStep
import com.nuvio.app.features.details.SeasonEpisodeRef
import com.nuvio.app.features.details.SeasonOwnCache
import com.nuvio.app.features.details.SeasonStatusRequest
import com.nuvio.app.features.details.catalogueDurationMs
import com.nuvio.app.features.details.currentOwnListing
import com.nuvio.app.features.details.factsForEpisode
import com.nuvio.app.features.details.loadSeasonCatalog
import com.nuvio.app.features.details.localDetectionTypes
import com.nuvio.app.features.details.mergeOwnListing
import com.nuvio.app.features.details.overviewEpisodeOpenSteps
import com.nuvio.app.features.details.overviewLedgerScope
import com.nuvio.app.features.details.productionPublicEpisode
import com.nuvio.app.features.details.seasonCatalogReloadKey
import com.nuvio.app.features.details.seasonEpisodeContentKey
import com.nuvio.app.features.details.seasonOverviewFillsNativeDetent
import com.nuvio.app.features.details.seasonOverviewFontScale
import com.nuvio.app.features.details.seasonOverviewListUi
import com.nuvio.app.features.details.seasonOverviewModel
import com.nuvio.app.features.details.seasonOverviewNativeList
import com.nuvio.app.features.details.seasonOverviewPayload
import com.nuvio.app.features.details.seasonOverviewSkipsPartialDetent
import com.nuvio.app.features.details.seasonPublicCache
import com.nuvio.app.features.details.seasonSegmentOverview
import com.nuvio.app.features.details.usesFixtureLedger
import com.nuvio.app.features.downloads.AnalysisTempDownloads
import com.nuvio.app.features.downloads.downloadEpisodesForAnalysis
import com.nuvio.app.features.downloads.seasonSheetDismissDurationMs
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.player.skip.IntroSubmitService
import com.nuvio.app.features.player.skip.OwnAccountMarks
import com.nuvio.app.features.player.skip.OwnTheIntroDbLedger
import com.nuvio.app.features.player.skip.accountScopeForService
import com.nuvio.app.features.player.skip.ownListBucketsForTitle
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_cancel
import nuvio.composeapp.generated.resources.season_overview_confirm_body
import nuvio.composeapp.generated.resources.season_overview_confirm_download
import nuvio.composeapp.generated.resources.season_overview_confirm_title
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SeasonOverviewSheet(
    meta: MetaDetails,
    season: Int,
    seasonLabel: String,
    episodes: List<MetaVideo>,
    isWatched: (MetaVideo) -> Boolean,
    loading: Boolean,
    offline: Boolean,
    fromDownloads: Boolean = false,
    onDismiss: () -> Unit,
    onOpenEpisode: (MetaVideo) -> Unit,
    onWatchPrivately: (MetaVideo) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = seasonOverviewSkipsPartialDetent(),
    )
    val coroutineScope = rememberCoroutineScope()
    val analysisRevision by MediaAnalysisStatus.revision.collectAsStateWithLifecycle()
    val ownRevision by OwnTheIntroDbLedger.revision.collectAsStateWithLifecycle()
    val tempRevision by AnalysisTempDownloads.revision.collectAsStateWithLifecycle()
    val playerSettings by PlayerSettingsRepository.uiState.collectAsStateWithLifecycle()
    val accountScope = if (currentUiFixture().usesFixtureLedger()) {
        overviewLedgerScope()
    } else {
        accountScopeForService(
            IntroSubmitService.THE_INTRODB,
            playerSettings.introDbApiKey,
            playerSettings.theIntroDbApiKey,
        )
    }
    val refs = remember(episodes, isWatched, meta.id, meta.imdbId) {
        episodes.mapNotNull { video ->
            val episode = video.episode ?: return@mapNotNull null
            val seasonNumber = video.season ?: 0
            SeasonEpisodeRef(
                season = seasonNumber,
                episode = episode,
                title = video.title,
                watched = isWatched(video),
                contentKey = seasonEpisodeContentKey(
                    metaId = meta.id,
                    imdbId = meta.imdbId,
                    videoId = video.id,
                    videoImdbId = video.imdbId,
                    season = seasonNumber,
                    episode = episode,
                ),
            )
        }
    }
    var catalogFacts by remember(meta.id, season) { mutableStateOf<Map<String, EpisodeCatalogFacts>>(emptyMap()) }
    val reloadKey = seasonCatalogReloadKey(
        metaId = meta.id,
        imdbId = meta.imdbId,
        offline = offline,
        accountScope = accountScope,
        contentKeys = refs.map { it.contentKey },
    )
    LaunchedEffect(reloadKey) {
        val scope = accountScope
        val stored = OwnTheIntroDbLedger.load(scope)
        val fixtureLedger = currentUiFixture().usesFixtureLedger()
        if (offline || fixtureLedger) {
            catalogFacts = refs.associate { ref ->
                ref.contentKey to factsForEpisode(
                    contentKey = ref.contentKey,
                    publicStatus = null,
                    media = null,
                    ownListed = fixtureLedger,
                    ownFailed = false,
                    merged = stored,
                )
            }
            return@LaunchedEffect
        }
        val imdb = meta.imdbId?.takeIf { it.startsWith("tt", ignoreCase = true) }
            ?: meta.id.takeIf { it.startsWith("tt", ignoreCase = true) }
            ?: refs.firstNotNullOfOrNull { ref ->
                ref.contentKey.substringBefore(':').takeIf { it.startsWith("tt") }
            }
            .orEmpty()
        val requests = refs.map { ref ->
            val video = episodes.firstOrNull { it.episode == ref.episode && (it.season ?: 0) == ref.season }
            val episodeImdb = ref.contentKey.substringBefore(':').takeIf { it.startsWith("tt") } ?: imdb
            SeasonStatusRequest(
                imdbId = episodeImdb,
                season = ref.season,
                episode = ref.episode,
                durationMs = catalogueDurationMs(video?.runtime),
                contentKey = ref.contentKey,
            )
        }
        val listing = SeasonOwnCache.get(scope) ?: currentOwnListing(imdb)?.also { SeasonOwnCache.put(scope, it) }
        if (listing != null && !listing.failed && listing.tmdbId > 0) {
            requests.forEach { request ->
                val buckets = ownListBucketsForTitle(
                    listing.records,
                    listing.tmdbId,
                    isMovie = false,
                    season = request.season,
                    episode = request.episode,
                )
                OwnTheIntroDbLedger.applyListing(
                    scope = scope,
                    contentKey = request.contentKey,
                    confirmed = buckets.confirmed,
                    pending = buckets.pending,
                    rejected = buckets.rejected,
                    confirmedRows = buckets.confirmedRows,
                    pendingRows = buckets.pendingRows,
                )
            }
        }
        val merged = if (listing != null) {
            mergeOwnListing(stored, OwnAccountMarks(), requests, listing).first
        } else {
            stored
        }
        catalogFacts = loadSeasonCatalog(
            requests = requests,
            ownListing = listing,
            merged = OwnTheIntroDbLedger.load(scope).let { latest ->
                if (latest.confirmed.isEmpty() && latest.pending.isEmpty()) merged else latest
            },
            publicFetch = ::productionPublicEpisode,
            cache = seasonPublicCache,
            nowMs = com.nuvio.app.features.streams.epochMs(),
        ).associateBy { it.contentKey }
    }
    val rows = remember(refs, analysisRevision, ownRevision, tempRevision, meta.id, accountScope) {
        val own = OwnTheIntroDbLedger.load(accountScope)
        val detected = refs.associate { ref ->
            val stored = MediaAnalysisStore.load(MediaAnalysisStore.keyFor(meta.id, ref.season, ref.episode))
            ref.contentKey to localDetectionTypes(stored)
        }
        seasonSegmentOverview(refs, own, detected)
    }
    val model = seasonOverviewModel(
        loading = loading,
        offline = offline,
        episodesKnown = !loading && (episodes.isNotEmpty() || !offline),
        rows = rows,
        fromDownloads = fromDownloads,
    )
    var selected by remember(meta.id, season) { mutableStateOf(setOf<String>()) }
    var selecting by remember(meta.id, season) { mutableStateOf(false) }
    var confirm by remember(meta.id, season) { mutableStateOf(false) }
    var sessionTagged by remember(meta.id, season) { mutableStateOf(setOf<String>()) }
    val running = sessionTagged.any { AnalysisTempDownloads.isTagged(it) }
    val hasSelection = selected.isNotEmpty()
    val narrowing = selecting || hasSelection
    val chosen = if (narrowing) {
        episodes.filter { video ->
            val episode = video.episode ?: return@filter false
            seasonEpisodeContentKey(
                metaId = meta.id,
                imdbId = meta.imdbId,
                videoId = video.id,
                videoImdbId = video.imdbId,
                season = video.season ?: 0,
                episode = episode,
            ) in selected
        }
    } else {
        episodes
    }
    val copy = seasonOverviewSheetCopy()
    val listUi = seasonOverviewListUi(
        title = seasonLabel,
        state = model.state,
        rows = model.rows,
        facts = catalogFacts,
        selecting = selecting,
        hasSelection = hasSelection,
        selectedCount = if (narrowing) chosen.size else 0,
        episodeCount = episodes.size,
        running = running,
        reduceMotion = systemReduceMotionEnabled(),
        fromDownloads = fromDownloads,
        downloadAllowed = model.downloadEnabled,
        selectedKeys = selected,
        copy = copy,
    )
    val payload = if (seasonOverviewNativeList) seasonOverviewPayload(listUi) else ""
    val reduceMotion = systemReduceMotionEnabled()
    var openingEpisode by remember(meta.id, season) { mutableStateOf(false) }
    fun dismiss() {
        coroutineScope.launch {
            dismissNuvioBottomSheet(sheetState = sheetState, onDismiss = onDismiss)
        }
    }
    fun openEpisode(video: MetaVideo, open: () -> Unit = { onOpenEpisode(video) }) {
        if (openingEpisode) return
        openingEpisode = true
        coroutineScope.launch {
            val steps = overviewEpisodeOpenSteps()
            if (OverviewOpenStep.DismissOverlay in steps) {
                val ms = seasonSheetDismissDurationMs(reduceMotion)
                if (usesNativeNuvioBottomSheet) {
                    dismissPresentedNuvioBottomSheet(ms)
                } else if (ms > 0 && sheetState.isVisible) {
                    sheetState.hide()
                }
                onDismiss()
            }
            if (OverviewOpenStep.OpenEpisode in steps) open()
        }
    }
    val windowPx = LocalWindowInfo.current.containerSize.height
    val sheetCap = with(LocalDensity.current) {
        if (windowPx > 0) windowPx.toDp() else 852.dp
    }
    val fontScale = seasonOverviewFontScale()
    val density = LocalDensity.current

    NuvioModalBottomSheet(
        onDismissRequest = { dismiss() },
        sheetState = sheetState,
        showDragHandle = true,
    ) {
        val actions = @Composable { modifier: Modifier ->
            SeasonOverviewSheetContent(
                list = listUi,
                payload = payload,
                onSelecting = { selecting = it },
                onDownload = { confirm = true },
                onCancel = {
                    AnalysisTempDownloads.cancelSession(sessionTagged)
                    sessionTagged = emptySet()
                },
                onOpen = { key ->
                    episodeForOverviewKey(episodes, meta, key)?.let { video -> openEpisode(video) }
                },
                onWatchPrivately = { key ->
                    episodeForOverviewKey(episodes, meta, key)?.let { video ->
                        openEpisode(video) { onWatchPrivately(video) }
                    }
                },
                onInclude = { key ->
                    selected = if (key in selected) selected - key else selected + key
                },
                modifier = modifier,
            )
        }
        val hostedOnNativeSheet = usesNativeNuvioBottomSheet && !isGlassUiActive()
        if (seasonOverviewFillsNativeDetent(seasonOverviewNativeList, hostedOnNativeSheet)) {
            actions(Modifier.fillMaxSize())
        } else if (seasonOverviewNativeList) {
            actions(
                Modifier
                    .fillMaxWidth()
                    .height(sheetCap)
                    .padding(bottom = nuvioSafeBottomPadding(16.dp)),
            )
        } else {
            CompositionLocalProvider(
                LocalDensity provides Density(density = density.density, fontScale = fontScale),
            ) {
                actions(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = sheetCap)
                        .padding(bottom = nuvioSafeBottomPadding(16.dp)),
                )
            }
        }
    }

    if (confirm) {
        DialogSurface(
            onDismissRequest = { confirm = false },
            title = stringResource(Res.string.season_overview_confirm_title),
            message = stringResource(Res.string.season_overview_confirm_body),
        ) {
            DialogButtons {
                DialogButton(
                    text = stringResource(Res.string.action_cancel),
                    onClick = { confirm = false },
                )
                DialogButton(
                    text = stringResource(Res.string.season_overview_confirm_download),
                    style = DialogButtonStyle.Primary,
                    onClick = {
                        confirm = false
                        val batch = chosen
                        coroutineScope.launch {
                            val outcome = downloadEpisodesForAnalysis(meta, batch)
                            sessionTagged = outcome.taggedIds
                        }
                    },
                )
            }
        }
    }
}

private fun episodeForOverviewKey(
    episodes: List<MetaVideo>,
    meta: MetaDetails,
    key: String,
): MetaVideo? = episodes.firstOrNull { video ->
    val episode = video.episode ?: return@firstOrNull false
    seasonEpisodeContentKey(
        metaId = meta.id,
        imdbId = meta.imdbId,
        videoId = video.id,
        videoImdbId = video.imdbId,
        season = video.season ?: 0,
        episode = episode,
    ) == key
}
