package com.nuvio.app.features.player

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
}
