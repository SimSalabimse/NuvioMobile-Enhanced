package com.nuvio.app.features.player

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Phone headers and transport edges. Widths are the ones [PlayerPanelHeader]
 * compares: the title's full one-line width, then the pill row.
 */
class PlayerOverlayPlacementTest {
    @Test
    fun `phone header keeps Subtitles whole when Back Style and Close share the row`() {
        val subtitlesWidthPx = 90
        val backWidthPx = 76
        val styleWidthPx = 84
        val closeWidthPx = 88
        val pillGapPx = 8
        val actionsWidthPx = backWidthPx + styleWidthPx + closeWidthPx + pillGapPx * 2
        val titleGapPx = PlayerPanelHeaderGapDp

        assertTrue(
            phonePanelHeaderStacksActions(
                titleWidthPx = subtitlesWidthPx,
                actionsWidthPx = actionsWidthPx,
                maxWidthPx = 320,
                gapPx = titleGapPx,
            ),
        )
        assertFalse(
            phonePanelHeaderStacksActions(
                titleWidthPx = subtitlesWidthPx,
                actionsWidthPx = actionsWidthPx,
                maxWidthPx = 400,
                gapPx = titleGapPx,
            ),
        )
        assertFalse(
            phonePanelHeaderStacksActions(
                titleWidthPx = subtitlesWidthPx,
                actionsWidthPx = 0,
                maxWidthPx = 200,
                gapPx = titleGapPx,
            ),
        )
    }

    @Test
    fun `pillarboxed frame places flag lock and close in the trailing black bar`() {
        val margins = pictureMargins(
            frameWidth = 1000.dp,
            frameHeight = 400.dp,
            videoWidth = 1000,
            videoHeight = 1000,
            resizeMode = PlayerResizeMode.Fit,
        )
        assertEquals(300f, margins.trailing.value, 0.01f)
        assertEquals(300f, margins.leading.value, 0.01f)
        assertEquals(0f, margins.top.value, 0.01f)
        assertEquals(0f, margins.bottom.value, 0.01f)
        assertEquals(
            TransportEdge.TrailingBar,
            flagLockCloseEdge(
                margins = margins,
                rowWidth = transportTopRowWidth(includeSubmitIntro = true),
                rowHeight = PlayerTransportTopButtonSize,
                topInset = 0.dp,
                trailingInset = 0.dp,
            ),
        )
        assertFalse(
            bottomRowInBottomBar(
                margins = margins,
                rowHeight = 56.dp,
                bottomInset = 0.dp,
            ),
        )
    }

    @Test
    fun `full frame picture keeps flag lock and close on the picture`() {
        val margins = pictureMargins(
            frameWidth = 1920.dp,
            frameHeight = 1080.dp,
            videoWidth = 1920,
            videoHeight = 1080,
            resizeMode = PlayerResizeMode.Fit,
        )
        assertEquals(PictureMargins.None, margins)
        assertEquals(
            TransportEdge.OnPicture,
            flagLockCloseEdge(
                margins = margins,
                rowWidth = transportTopRowWidth(includeSubmitIntro = true),
                rowHeight = PlayerTransportTopButtonSize,
                topInset = 0.dp,
                trailingInset = 0.dp,
            ),
        )
    }

    @Test
    fun `a pillar too narrow for the row leaves flag lock and close on the picture`() {
        val margins = pictureMargins(
            frameWidth = 800.dp,
            frameHeight = 400.dp,
            videoWidth = 1920,
            videoHeight = 1080,
            resizeMode = PlayerResizeMode.Fit,
        )
        assertTrue(margins.trailing > 0.dp)
        assertTrue(margins.trailing < transportTopRowWidth(includeSubmitIntro = true))
        assertEquals(
            TransportEdge.OnPicture,
            flagLockCloseEdge(
                margins = margins,
                rowWidth = transportTopRowWidth(includeSubmitIntro = true),
                rowHeight = PlayerTransportTopButtonSize,
                topInset = 0.dp,
                trailingInset = 0.dp,
            ),
        )
    }

    @Test
    fun `letterbox bars tall enough take the top row and the bottom action row`() {
        val margins = pictureMargins(
            frameWidth = 400.dp,
            frameHeight = 800.dp,
            videoWidth = 1920,
            videoHeight = 1080,
            resizeMode = PlayerResizeMode.Fit,
        )
        assertEquals(0f, margins.trailing.value, 0.01f)
        assertTrue(margins.top >= PlayerTransportTopButtonSize)
        assertEquals(
            TransportEdge.TopBar,
            flagLockCloseEdge(
                margins = margins,
                rowWidth = transportTopRowWidth(includeSubmitIntro = true),
                rowHeight = PlayerTransportTopButtonSize,
                topInset = 0.dp,
                trailingInset = 0.dp,
            ),
        )
        assertTrue(bottomRowInBottomBar(margins, rowHeight = 56.dp, bottomInset = 0.dp))
    }

    @Test
    fun `fill zoom and an unknown size invent no bar`() {
        assertEquals(
            PictureMargins.None,
            pictureMargins(1000.dp, 400.dp, 1000, 1000, PlayerResizeMode.Fill),
        )
        assertEquals(
            PictureMargins.None,
            pictureMargins(1000.dp, 400.dp, 1000, 1000, PlayerResizeMode.Zoom),
        )
        assertEquals(
            PictureMargins.None,
            pictureMargins(1000.dp, 400.dp, 0, 0, PlayerResizeMode.Fit),
        )
    }

    @Test
    fun `a bar that the safe inset eats keeps the row on the picture`() {
        val margins = PictureMargins(leading = 200.dp, trailing = 160.dp, top = 0.dp, bottom = 40.dp)
        assertEquals(
            TransportEdge.OnPicture,
            flagLockCloseEdge(
                margins = margins,
                rowWidth = 144.dp,
                rowHeight = 48.dp,
                topInset = 0.dp,
                trailingInset = 40.dp,
            ),
        )
        assertFalse(bottomRowInBottomBar(margins, rowHeight = 56.dp, bottomInset = 0.dp))
    }
}
