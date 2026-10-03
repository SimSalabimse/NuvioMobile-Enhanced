package com.nuvio.app.features.p2p

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtonStyle
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.DialogSurface
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.player.LocalExperimentalPlayerOverlay
import com.nuvio.app.features.player.PlayerCenteredCard
import com.nuvio.app.features.player.PlayerDialogButton
import com.nuvio.app.features.player.centeredCardFrame
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.p2p_consent_body
import nuvio.composeapp.generated.resources.p2p_consent_cancel
import nuvio.composeapp.generated.resources.p2p_consent_enable
import nuvio.composeapp.generated.resources.p2p_consent_title
import org.jetbrains.compose.resources.stringResource

@Composable
fun P2pConsentDialog(
    onEnableP2p: () -> Unit,
    onDismiss: () -> Unit,
    usePlayerCard: Boolean = false,
) {
    if (usePlayerCard) {
        PlayerCenteredCard(onDismiss = onDismiss) {
            Text(
                text = stringResource(Res.string.p2p_consent_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.nuvio.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(Res.string.p2p_consent_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.nuvio.colors.textSecondary,
                modifier = if (centeredCardFrame(LocalExperimentalPlayerOverlay.current).capsHeight) {
                    Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                } else {
                    Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState())
                },
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PlayerDialogButton(
                    label = stringResource(Res.string.p2p_consent_cancel),
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
                PlayerDialogButton(
                    label = stringResource(Res.string.p2p_consent_enable),
                    onClick = onEnableP2p,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        return
    }
    DialogSurface(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.p2p_consent_title),
    ) {
        Text(
            text = stringResource(Res.string.p2p_consent_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.nuvio.colors.textSecondary,
            modifier = Modifier
                .heightIn(max = 360.dp)
                .verticalScroll(rememberScrollState()),
        )
        DialogButtons {
            DialogButton(
                text = stringResource(Res.string.p2p_consent_cancel),
                onClick = onDismiss,
            )
            DialogButton(
                text = stringResource(Res.string.p2p_consent_enable),
                onClick = onEnableP2p,
                style = DialogButtonStyle.Primary,
            )
        }
    }
}
