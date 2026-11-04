package com.nuvio.app.features.details

import androidx.compose.runtime.Composable
import platform.UIKit.UIApplication

@Composable
internal actual fun seasonOverviewFontScale(): Float {
    val category = UIApplication.sharedApplication.preferredContentSizeCategory.toString()
    return fontScaleForIosContentCategory(category)
}
