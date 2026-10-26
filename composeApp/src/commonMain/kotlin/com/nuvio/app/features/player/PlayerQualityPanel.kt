package com.nuvio.app.features.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio

@Composable
internal fun PlayerQualityPanel(
    visible: Boolean,
    state: PlayerQualitySelectionState,
    selectedQualityId: String?,
    currentResolutionLabel: String? = null,
    onQualitySelected: (String?) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio

    PlayerToolPanel(
        visible = visible,
        title = "Quality",
        onDismiss = onDismiss,
        modifier = modifier,
    ) {
                        when {
                            state.isLoading -> {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = NuvioTokens.Space.s40),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    CircularProgressIndicator(
                                        color = tokens.colors.accent,
                                        strokeWidth = tokens.borders.medium,
                                        modifier = Modifier.size(tokens.icons.lg + NuvioTokens.Space.s4),
                                    )
                                }
                            }

                            !state.hasSelectableQualities -> {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = tokens.spacing.sheetPadding)
                                        .padding(bottom = tokens.spacing.sheetPadding),
                                    verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s10),
                                ) {
                                    val currentQualityTitle = currentResolutionLabel
                                        ?.takeIf { it.isNotBlank() }
                                        ?: "Current quality"
                                    QualityOptionRow(
                                        title = currentQualityTitle,
                                        subtitle = "Currently playing",
                                        isSelected = true,
                                        hasWarning = false,
                                        enabled = false,
                                        onClick = {},
                                    )
                                    Text(
                                        text = state.errorMessage
                                            ?: "This stream does not expose selectable quality variants.",
                                        color = tokens.colors.textMuted,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                            }

                            else -> {
                                LazyColumn(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s6),
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                        bottom = tokens.spacing.cardPadding,
                                    ),
                                ) {
                                    item(key = "auto") {
                                        QualityOptionRow(
                                            title = state.labelFor(null)?.let { "Auto ($it)" } ?: "Auto",
                                            subtitle = "Recommended compatible quality",
                                            isSelected = selectedQualityId == null,
                                            hasWarning = false,
                                            onClick = { onQualitySelected(null) },
                                        )
                                    }
                                    items(
                                        items = state.variants,
                                        key = { it.id },
                                    ) { variant ->
                                        QualityOptionRow(
                                            title = variant.qualityName,
                                            subtitle = variant.displayLabel.removePrefix(variant.qualityName).trimStart(' ', '·'),
                                            isSelected = selectedQualityId == variant.id,
                                            hasWarning = !variant.isLikelyHardwareDecodable,
                                            onClick = { onQualitySelected(variant.id) },
                                        )
                                    }
                                }
                            }
                        }
    }
}

@Composable
private fun QualityOptionRow(
    title: String,
    subtitle: String,
    isSelected: Boolean,
    hasWarning: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    PlayerToolRow(
        label = title,
        supporting = subtitle.takeIf { it.isNotBlank() },
        supportingColor = if (hasWarning) MaterialTheme.nuvio.colors.danger else null,
        selected = isSelected,
        enabled = enabled,
        onClick = onClick,
    )
}
