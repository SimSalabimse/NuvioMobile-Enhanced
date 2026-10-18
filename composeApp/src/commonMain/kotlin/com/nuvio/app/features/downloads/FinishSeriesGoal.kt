package com.nuvio.app.features.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioToastController

/** Finish a series goal: one toggle, label and badge shared by the hold menus, details and cards. */
internal object FinishSeriesGoal {
    fun label(pinned: Boolean): String =
        if (pinned) "Stop finishing this series" else "Finish this series (keep the next 2 downloaded)"

    fun toggle(type: String, id: String) {
        val pinned = !SmartDownloadSettings.isGoal(type, id)
        SmartDownloadSettings.setGoal(type, id, pinned)
        NuvioToastController.show(
            if (pinned) "Finishing this series: the next 2 episodes stay downloaded" else "Stopped finishing this series",
        )
    }
}

@Composable
internal fun rememberIsGoal(type: String, id: String): Boolean {
    val state by remember { SmartDownloadSettings.ensureLoaded(); SmartDownloadSettings.state }.collectAsStateWithLifecycle()
    return SmartDownloadSettings.isGoal(state, type, id)
}

/** The details screen toggle, above the seasons. */
@Composable
internal fun FinishSeriesGoalChip(type: String, id: String, modifier: Modifier = Modifier) {
    val pinned = rememberIsGoal(type, id)
    FilterChip(
        selected = pinned,
        onClick = { FinishSeriesGoal.toggle(type, id) },
        label = { Text(if (pinned) "Finishing this series" else "Finish this series") },
        leadingIcon = { Icon(Icons.Rounded.Flag, contentDescription = null, modifier = Modifier.size(18.dp)) },
        modifier = modifier,
    )
}

/** Small flag on a card whose show is pinned; nothing otherwise. */
@Composable
internal fun FinishSeriesGoalBadge(type: String, id: String, modifier: Modifier = Modifier) {
    if (!rememberIsGoal(type, id)) return
    Icon(
        Icons.Rounded.Flag,
        contentDescription = "Finishing this series",
        tint = Color.White,
        modifier = modifier
            .padding(6.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.6f))
            .padding(4.dp)
            .size(14.dp),
    )
}
