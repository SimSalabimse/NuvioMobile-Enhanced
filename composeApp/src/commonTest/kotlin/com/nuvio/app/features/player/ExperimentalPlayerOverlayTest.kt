package com.nuvio.app.features.player

import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExperimentalPlayerOverlayTest {
    @Test
    fun `fresh value is off and does not follow legacy layout`() {
        val fresh = PlayerSettingsUiState()
        assertFalse(fresh.useExperimentalPlayerOverlay)
        assertEquals(PlayerOverlayPath.Developer, playerOverlayPath(fresh.useExperimentalPlayerOverlay))

        val legacyOnly = PlayerSettingsUiState(useLegacyPlayerLayout = true)
        assertFalse(legacyOnly.useExperimentalPlayerOverlay)
        assertEquals(PlayerOverlayPath.Developer, playerOverlayPath(legacyOnly.useExperimentalPlayerOverlay))

        val experimentOnly = PlayerSettingsUiState(
            useExperimentalPlayerOverlay = true,
            useLegacyPlayerLayout = false,
        )
        assertTrue(experimentOnly.useExperimentalPlayerOverlay)
        assertFalse(experimentOnly.useLegacyPlayerLayout)
        assertEquals(PlayerOverlayPath.Experimental, playerOverlayPath(true))
    }

    @Test
    fun `false keeps the developer overlay and true selects the experiment`() {
        val phone = 900.dp to 379.dp
        val developer = PlayerLayoutMetrics.forOverlay(phone.first, phone.second, experimental = false)
        val experimental = PlayerLayoutMetrics.forOverlay(phone.first, phone.second, experimental = true)
        assertEquals(PlayerSizeClass.T, developer.sizeClass)
        assertEquals(22f, developer.titleSize.value)
        assertEquals(PlayerSizeClass.S, experimental.sizeClass)
        assertEquals(18f, experimental.titleSize.value)
        assertEquals(PlayerSizeClass.S, playerSizeClassForOverlay(phone.first, phone.second, experimental = true))
        assertEquals(PlayerSizeClass.T, playerSizeClassForOverlay(phone.first, phone.second, experimental = false))

        val landscapePhone = PlayerLayoutMetrics.forOverlay(844.dp, 390.dp, experimental = false)
        assertEquals(22f, landscapePhone.titleSize.value)
        assertEquals(72.dp, landscapePhone.centerGap)
        assertEquals(
            PlayerSizeClass.M,
            PlayerLayoutMetrics.forOverlay(844.dp, 390.dp, experimental = true).sizeClass,
        )

        val margins = pictureMargins(
            frameWidth = 1000.dp,
            frameHeight = 400.dp,
            videoWidth = 1000,
            videoHeight = 1000,
            resizeMode = PlayerResizeMode.Fit,
        )
        assertEquals(
            TransportEdge.OnPicture,
            flagLockCloseEdgeForOverlay(
                margins = margins,
                rowWidth = transportTopRowWidth(includeSubmitIntro = true),
                rowHeight = PlayerTransportTopButtonSize,
                topInset = 0.dp,
                trailingInset = 0.dp,
                experimental = false,
            ),
        )
        assertEquals(
            TransportEdge.TrailingBar,
            flagLockCloseEdgeForOverlay(
                margins = margins,
                rowWidth = transportTopRowWidth(includeSubmitIntro = true),
                rowHeight = PlayerTransportTopButtonSize,
                topInset = 0.dp,
                trailingInset = 0.dp,
                experimental = true,
            ),
        )
        assertFalse(
            bottomRowInBottomBarForOverlay(margins, rowHeight = 56.dp, bottomInset = 0.dp, experimental = false),
        )

        val actions = (1..8).toList()
        assertEquals(actions, legacyProgressShownActions(actions, expanded = false, experimental = false))
        assertEquals(listOf(1, 2, 3, 4), legacyProgressShownActions(actions, expanded = false, experimental = true))
        assertFalse(legacyProgressRowFitsToWidth(false))
        assertTrue(legacyProgressRowFitsToWidth(true))
        assertEquals(5, playerChromeVisibleActionCount(experimental = false))
        assertEquals(4, playerChromeVisibleActionCount(experimental = true))

        assertFalse(
            phonePanelHeaderStacksForOverlay(
                titleWidthPx = 90,
                actionsWidthPx = 248,
                maxWidthPx = 320,
                gapPx = PlayerPanelHeaderGapDp,
                experimental = false,
            ),
        )
        assertTrue(
            phonePanelHeaderStacksForOverlay(
                titleWidthPx = 90,
                actionsWidthPx = 248,
                maxWidthPx = 320,
                gapPx = PlayerPanelHeaderGapDp,
                experimental = true,
            ),
        )
        assertFalse(playerMenuAppliesFrost(false))
        assertTrue(playerMenuAppliesFrost(true))
    }

    @Test
    fun `off keeps the developer overlay details and on keeps the experiment`() {
        val warnings = listOf(
            ParentalWarning("Violence", "Severe"),
            ParentalWarning("Profanity", "Moderate"),
            ParentalWarning("Nudity", "Mild"),
        )
        val shown = { experimental: Boolean ->
            if (parentalGuideShowsEveryWarning(experimental)) warnings else warnings.take(2)
        }
        assertEquals(listOf("Violence", "Profanity", "Nudity"), shown(false).map { it.label })
        assertEquals(listOf("Violence", "Profanity"), shown(true).map { it.label })
        assertFalse(parentalGuideEllipsizes(false))
        assertTrue(parentalGuideEllipsizes(true))
        assertFalse(parentalGuideUsesTopSafeInset(false))
        assertTrue(parentalGuideUsesTopSafeInset(true))
        assertEquals(0.dp, parentalGuideEndClearance(false, 48.dp, includeSubmitIntro = true))
        assertEquals(152.dp, parentalGuideEndClearance(true, 48.dp, includeSubmitIntro = true))

        assertEquals(LegacyProgressBottom.SliderOffset, legacyProgressBottom(false))
        assertEquals(LegacyProgressBottom.TimelineInsets, legacyProgressBottom(true))

        assertFalse(liveCategoryMenuUsesPanelWidth(false))
        assertTrue(liveCategoryMenuUsesPanelWidth(true))
        assertEquals(360.dp, DeveloperLiveCategoryMenuMinWidth)
        assertEquals(480.dp, DeveloperLiveCategoryMenuMaxWidth)

        assertFalse(pauseOverlayPadsSafeContent(false))
        assertTrue(pauseOverlayPadsSafeContent(true))
        assertFalse(gestureLevelBarsUseSafeInsets(false))
        assertTrue(gestureLevelBarsUseSafeInsets(true))
        assertEquals(72.dp, GestureLevelBarBottomOffset)
        assertEquals(40.dp, speedReadoutTopOffset(false))
        assertEquals(8.dp, speedReadoutTopOffset(true))

        assertEquals(30.sp, openingTitleFontSize(599.dp, 320.dp, experimental = false))
        assertEquals(42.sp, openingTitleFontSize(600.dp, 320.dp, experimental = false))
        assertEquals(22.sp, openingTitleFontSize(599.dp, 320.dp, experimental = true))
        assertEquals(42.sp, openingTitleFontSize(1024.dp, 320.dp, experimental = true))

        val developerCard = centeredCardFrame(false)
        assertEquals(520.dp, developerCard.maxWidth)
        assertEquals(0.9f, developerCard.widthFraction)
        assertFalse(developerCard.capsHeight)
        assertFalse(developerCard.usesSafeInsets)
        assertEquals(0.65f, developerCard.veilAlpha)
        assertTrue(developerCard.usesSurfaceFill)
        val experimentalCard = centeredCardFrame(true)
        assertEquals(400.dp, experimentalCard.maxWidth)
        assertEquals(0.9f, experimentalCard.widthFraction)
        assertTrue(experimentalCard.capsHeight)
        assertTrue(experimentalCard.usesSafeInsets)
        assertEquals(PlayerCenteredCardVeilAlpha, experimentalCard.veilAlpha)
        assertFalse(experimentalCard.usesSurfaceFill)

        assertTrue(playerErrorUsesDeveloperFrame(false))
        assertFalse(playerErrorUsesDeveloperFrame(true))
        assertFalse(playerErrorScrimDismisses(false))
        assertTrue(playerErrorScrimDismisses(true))
        assertEquals(0.9f, DeveloperErrorFrame.scrimAlpha)
        assertEquals(32.dp, DeveloperErrorFrame.horizontalPadding)
        assertEquals(16.dp, DeveloperErrorFrame.itemSpacing)
        assertEquals(4, DeveloperErrorFrame.messageMaxLines)
        assertEquals(24.sp, DeveloperErrorFrame.messageLineHeight)
        assertEquals(180.dp, DeveloperErrorFrame.buttonMinWidth)
        assertEquals(260.dp, DeveloperErrorFrame.buttonMaxWidth)
        assertEquals(12.dp, DeveloperErrorFrame.buttonCorner)
        assertEquals(12.dp, DeveloperErrorFrame.buttonVerticalPadding)
        assertEquals(4.dp, DeveloperErrorFrame.buttonTopPadding)

        assertFalse(inPlayerP2pUsesCenteredCard(false))
        assertTrue(inPlayerP2pUsesCenteredCard(true))
        assertEquals(360.dp, DeveloperP2pBodyMaxHeight)

        val developerAudio = developerAudioCardPadding(false)
        assertEquals(44.dp, developerAudio.start)
        assertEquals(44.dp, developerAudio.end)
        assertEquals(28.dp, developerAudio.top)
        assertEquals(64.dp, developerAudio.bottom)
        assertFalse(developerAudio.usesSafeContent)
        val experimentalAudio = developerAudioCardPadding(true)
        assertEquals(0.dp, experimentalAudio.start)
        assertTrue(experimentalAudio.usesSafeContent)

        assertNull(playerStreamNameMaxLines(false))
        assertEquals(2, playerStreamNameMaxLines(true))

        assertTrue(menuFillUsesSurfaceVariant(false))
        assertFalse(menuFillUsesSurfaceVariant(true))
        assertEquals(0.35f, DeveloperVideoOptionFillAlpha)
        assertEquals(0.32f, DeveloperTimeFieldFillAlpha)
        assertEquals(28.dp, submitIntroCloseControlSize(false))
        assertEquals(44.dp, submitIntroCloseControlSize(true))

        assertFalse(playerMenuDismissesSiblings(false))
        assertTrue(playerMenuDismissesSiblings(true))
        val runtime = PlayerScreenRuntime(overlayTestArgs())
        runtime.showAudioModal = true
        runtime.showStreamInfoModal = true
        assertTrue(runtime.beginPlayerMenu(PlayerMenu.Subtitles))
        assertTrue(runtime.showSubtitleModal)
        assertTrue(runtime.showAudioModal)
        assertTrue(runtime.showStreamInfoModal)

        runtime.playerSettingsUiState = PlayerSettingsUiState(useExperimentalPlayerOverlay = true)
        assertTrue(runtime.beginPlayerMenu(PlayerMenu.Subtitles))
        assertTrue(runtime.showSubtitleModal)
        assertFalse(runtime.showAudioModal)
        assertFalse(runtime.showStreamInfoModal)
    }

    private fun overlayTestArgs() = PlayerScreenArgs(
        profileId = 1,
        title = "Title",
        sourceUrl = "https://example.com/video.mp4",
        sourceAudioUrl = null,
        sourceHeaders = emptyMap(),
        sourceResponseHeaders = emptyMap(),
        streamType = null,
        providerName = "Provider",
        streamTitle = "Source",
        streamSubtitle = null,
        initialBingeGroup = null,
        pauseDescription = null,
        onBack = {},
        onOpenInExternalPlayer = null,
        onOpenExternalUrl = null,
        modifier = Modifier,
        logo = null,
        poster = null,
        background = null,
        seasonNumber = null,
        episodeNumber = null,
        episodeTitle = null,
        episodeThumbnail = null,
        contentType = "movie",
        videoId = "tt1234567",
        parentMetaId = "tt1234567",
        parentMetaType = "movie",
        providerAddonId = null,
        torrentInfoHash = null,
        torrentFileIdx = null,
        torrentFilename = null,
        torrentTrackers = emptyList(),
        initialPositionMs = 0L,
        initialProgressFraction = null,
    )
}
