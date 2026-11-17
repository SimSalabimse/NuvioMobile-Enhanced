package com.nuvio.app.core.ui

import kotlin.math.abs

/** Custom detent id for a sheet that is as tall as its content, and no taller than the large detent. */
internal const val FIT_SHEET_DETENT_ID = "nuvio.fitContent"

internal enum class FitSheetAction {
    /** Content is shorter than the sheet. Use [FitSheetDecision.points]. */
    Shrink,
    /** Content fills the maximum sheet. Stay on the large detent. */
    KeepMaximum,
    Unchanged,
}

internal data class FitSheetDecision(
    val action: FitSheetAction,
    val points: Double,
)

/**
 * [sheetPoints] is the height on screen now. [contentPoints] is the measured content,
 * including the grabber padding. A sheet starts at the large detent ([lockedToContent] false).
 * Shorter content shrinks once. A list that fills that detent stays there, so the last row
 * is cut off instead of the sheet growing and shrinking in a loop.
 */
internal fun fitSheetMeasurement(
    sheetPoints: Double,
    contentPoints: Double,
    lockedToContent: Boolean,
): FitSheetDecision {
    if (sheetPoints <= 1.0 || contentPoints <= 1.0) {
        return FitSheetDecision(FitSheetAction.Unchanged, sheetPoints)
    }
    val slack = sheetPoints - contentPoints
    if (slack > 8.0) return FitSheetDecision(FitSheetAction.Shrink, contentPoints)
    if (!lockedToContent) return FitSheetDecision(FitSheetAction.KeepMaximum, Double.POSITIVE_INFINITY)
    return FitSheetDecision(FitSheetAction.Unchanged, sheetPoints)
}

/** Requested height for the custom detent. A non-finite request means the large detent. */
internal fun fitSheetDetentPoints(requestedPoints: Double, maximumPoints: Double): Double {
    if (maximumPoints <= 0.0) return 0.0
    if (!requestedPoints.isFinite() || requestedPoints <= 1.0) return maximumPoints
    return minOf(requestedPoints, maximumPoints)
}

internal class FitSheetHeightHost {
    var requestedPoints: Double = Double.POSITIVE_INFINITY
    var lockedToContent: Boolean = false
    var invalidate: (() -> Unit)? = null

    fun onMeasured(sheetPoints: Double, contentPoints: Double) {
        val decision = fitSheetMeasurement(sheetPoints, contentPoints, lockedToContent)
        when (decision.action) {
            FitSheetAction.Unchanged,
            FitSheetAction.KeepMaximum,
            -> Unit
            FitSheetAction.Shrink -> {
                val next = decision.points
                if (lockedToContent && requestedPoints.isFinite() && abs(requestedPoints - next) < 0.5) return
                lockedToContent = true
                requestedPoints = next
                invalidate?.invoke()
            }
        }
    }
}
