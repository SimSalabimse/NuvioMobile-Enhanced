package com.nuvio.app.features.player.seekpreview

import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.nuvio.app.features.analysis.MediaAnalysisFiles
import com.nuvio.app.features.analysis.MediaAnalysisResult
import com.nuvio.app.features.analysis.thumbnailSheetFileName
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.decodeToImageBitmap

/**
 * Seek preview from the thumbnail sheets the background analysis made of a downloaded file:
 * a tile crop, no decoding of the video while scrubbing. One decoded sheet is kept.
 */
internal class ThumbnailSheetFrameSource(private val analysis: MediaAnalysisResult) : SeekPreviewFrameSource {
    private var sheetIndex = -1
    private var sheet: ImageBitmap? = null

    @OptIn(ExperimentalResourceApi::class)
    override fun frameAt(positionMs: Long, maxWidthPx: Int): ImageBitmap? {
        val sheets = analysis.thumbnails ?: return null
        val index = (positionMs / sheets.intervalMs).toInt().coerceIn(0, sheets.count - 1)
        val wantedSheet = index / sheets.perSheet
        if (wantedSheet >= sheets.sheets) return null
        if (wantedSheet != sheetIndex || sheet == null) {
            val bytes = MediaAnalysisFiles.readBytes(thumbnailSheetFileName(analysis.key, wantedSheet)) ?: return null
            sheet = runCatching { bytes.decodeToImageBitmap() }.getOrNull() ?: return null
            sheetIndex = wantedSheet
        }
        val source = sheet ?: return null
        val inSheet = index % sheets.perSheet
        val column = inSheet % sheets.columns
        val row = inSheet / sheets.columns
        val tile = ImageBitmap(sheets.tileWidth, sheets.tileHeight)
        Canvas(tile).drawImageRect(
            image = source,
            srcOffset = IntOffset(column * sheets.tileWidth, row * sheets.tileHeight),
            srcSize = IntSize(sheets.tileWidth, sheets.tileHeight),
            dstOffset = IntOffset.Zero,
            dstSize = IntSize(sheets.tileWidth, sheets.tileHeight),
            paint = Paint(),
        )
        return tile
    }

    override fun close() {
        sheet = null
        sheetIndex = -1
    }
}
