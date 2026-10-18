package com.nuvio.app.features.settings

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioModalBottomSheet
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.nuvioSafeBottomPadding
import com.nuvio.app.features.player.skip.TheIntroDb
import com.nuvio.app.features.player.skip.TheIntroDbUserSubmission
import com.nuvio.app.features.player.skip.submissionLine

/** Settings > Playback > Your TheIntroDB submissions: everything this key sent, with review status. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TheIntroDbSubmissionsSheet(apiKey: String, onDismiss: () -> Unit) {
    var rows by remember { mutableStateOf<List<TheIntroDbUserSubmission>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(apiKey) {
        rows = TheIntroDb.listMySubmissions(apiKey)
        error = TheIntroDb.lastListError
    }
    NuvioModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = nuvioSafeBottomPadding(16.dp))) {
            item {
                Text("Your TheIntroDB submissions", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(vertical = 8.dp))
            }
            val loaded = rows
            val status = when {
                loaded == null -> "Loading…"
                error != null -> error
                loaded.isEmpty() -> "No submissions yet. Mark a segment in the player (flag button) to send one."
                else -> null
            }
            if (status != null) {
                item { Text(status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.nuvio.colors.textSecondary) }
            }
            items(loaded.orEmpty()) { submission ->
                Text(
                    text = submissionLine(submission, withTitle = true),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
        }
    }
}
