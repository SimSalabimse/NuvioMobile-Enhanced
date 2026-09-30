package com.nuvio.app.core.ui

import androidx.compose.foundation.clickable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.nuvio.app.core.build.AppVersionConfig
import com.nuvio.app.core.build.appVersionDetail
import com.nuvio.app.core.build.appVersionRevisionSuffix
import kotlinx.coroutines.delay
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.compose_about_version_copied
import nuvio.composeapp.generated.resources.compose_about_version_copy
import nuvio.composeapp.generated.resources.compose_about_version_format
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun RunningVersionLine(
    modifier: Modifier = Modifier,
    includeVersionWord: Boolean = true,
    style: TextStyle? = null,
    color: Color = MaterialTheme.colorScheme.onSurface,
    textAlign: TextAlign = TextAlign.Start,
    maxLines: Int = 2,
) {
    val clipboard = LocalClipboardManager.current
    val haptic = LocalHapticFeedback.current
    val versionName = AppVersionConfig.VERSION_NAME
    val versionCode = AppVersionConfig.VERSION_CODE
    val gitRevision = AppVersionConfig.GIT_REVISION
    val gitDirty = AppVersionConfig.GIT_DIRTY
    val detail = appVersionDetail(versionName, versionCode, gitRevision, gitDirty)
    val translated = stringResource(
        Res.string.compose_about_version_format,
        versionName,
        versionCode,
    ) + appVersionRevisionSuffix(gitRevision, gitDirty)
    val copiedLabel = stringResource(Res.string.compose_about_version_copied)
    val copyLabel = stringResource(Res.string.compose_about_version_copy)
    var copyCount by remember { mutableIntStateOf(0) }
    LaunchedEffect(copyCount) {
        if (copyCount == 0) return@LaunchedEffect
        delay(1_600)
        copyCount = 0
    }
    Text(
        text = when {
            copyCount > 0 -> copiedLabel
            includeVersionWord -> translated
            else -> detail
        },
        modifier = modifier.clickable(
            role = Role.Button,
            onClickLabel = copyLabel,
            onClick = {
                clipboard.setText(AnnotatedString(detail))
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                copyCount += 1
            },
        ),
        style = style ?: MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        color = color,
        textAlign = textAlign,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}
