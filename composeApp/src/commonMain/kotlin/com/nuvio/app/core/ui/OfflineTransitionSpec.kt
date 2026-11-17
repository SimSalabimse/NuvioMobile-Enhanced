package com.nuvio.app.core.ui

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import com.nuvio.app.core.network.OfflineTransitionEasing
import com.nuvio.app.core.network.OfflineTransitionMotion

fun OfflineTransitionEasing.asComposeEasing(): Easing = when (this) {
    OfflineTransitionEasing.FastOutSlowIn -> FastOutSlowInEasing
}

/** The one clock [offlineTransitionMotion] describes, as a Compose spec. */
fun OfflineTransitionMotion.finiteSpec(): FiniteAnimationSpec<Float> =
    if (snap || durationMs <= 0) {
        snap()
    } else {
        tween(durationMillis = durationMs, easing = easing.asComposeEasing())
    }
