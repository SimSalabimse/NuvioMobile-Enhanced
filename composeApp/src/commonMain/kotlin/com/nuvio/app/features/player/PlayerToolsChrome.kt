package com.nuvio.app.features.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.app.core.ui.nuvio

internal val PlayerToolSurface = Color(0xFF14161B)
internal val PlayerToolEdge = Color.White.copy(alpha = 0.20f)
internal val PlayerToolRowText = 17.sp
internal val PlayerToolShape = RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp)

/** Swallows a tap so it cannot fall through to the full-screen scrim. */
@Composable
internal fun Modifier.consumeToolTaps(): Modifier = clickable(
    interactionSource = remember { MutableInteractionSource() },
    indication = null,
    onClick = {},
)

@Composable
internal fun PlayerToolRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: String? = null,
    badge: String? = null,
    showCheck: Boolean = selected,
) {
    val tokens = MaterialTheme.nuvio
    val titleColor = if (selected) tokens.colors.onAccent else Color.White
    val secondary = if (selected) tokens.colors.onAccent.copy(alpha = 0.8f) else Color.White.copy(alpha = 0.62f)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .background(if (selected) tokens.colors.accent else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        androidx.compose.foundation.layout.Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = titleColor,
                fontSize = PlayerToolRowText,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    color = secondary,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (!badge.isNullOrBlank()) {
            Text(
                text = badge,
                color = if (selected) tokens.colors.onAccent else tokens.colors.accent,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(
                        if (selected) tokens.colors.onAccent.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.08f),
                    )
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        if (!trailing.isNullOrBlank()) {
            Text(
                text = trailing,
                color = secondary,
                fontSize = 15.sp,
                maxLines = 1,
            )
        }
        if (showCheck) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                tint = titleColor,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
internal fun PlayerToolChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val tokens = MaterialTheme.nuvio
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier = modifier
            .heightIn(min = 44.dp)
            .clip(shape)
            .background(
                when {
                    !enabled -> Color.White.copy(alpha = 0.04f)
                    selected -> tokens.colors.accent
                    else -> Color.White.copy(alpha = 0.08f)
                },
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = when {
                !enabled -> Color.White.copy(alpha = 0.35f)
                selected -> tokens.colors.onAccent
                else -> Color.White
            },
            fontSize = 15.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

@Composable
internal fun PlayerToolIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Color.White,
    enabled: Boolean = true,
) {
    Box(
        modifier = modifier
            .size(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = tint.copy(alpha = if (enabled) 1f else 0.35f),
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
internal fun PlayerToolTextButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
) {
    val tokens = MaterialTheme.nuvio
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = modifier
            .heightIn(min = 44.dp)
            .clip(shape)
            .background(
                when {
                    !enabled -> Color.White.copy(alpha = 0.04f)
                    selected -> tokens.colors.accent
                    else -> Color.White.copy(alpha = 0.08f)
                },
            )
            .border(1.dp, Color.White.copy(alpha = 0.08f), shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = when {
                !enabled -> Color.White.copy(alpha = 0.35f)
                selected -> tokens.colors.onAccent
                else -> Color.White
            },
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

internal fun Modifier.playerToolDivider(): Modifier =
    this.background(Color.White.copy(alpha = 0.08f)).height(1.dp)
