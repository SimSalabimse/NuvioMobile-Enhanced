package com.nuvio.app.core.ui

import androidx.compose.animation.core.SnapSpec
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import kotlin.test.Test
import kotlin.test.assertIs

class PosterZoomMotionTest {
    @Test
    fun reduceMotionUsesASnapAndOtherwiseKeepsTheSpring() {
        assertIs<SnapSpec<Float>>(posterZoomSpec(reduceMotion = true, spec = spring<Float>()))
        assertIs<SpringSpec<Float>>(posterZoomSpec(reduceMotion = false, spec = spring<Float>()))
    }
}
