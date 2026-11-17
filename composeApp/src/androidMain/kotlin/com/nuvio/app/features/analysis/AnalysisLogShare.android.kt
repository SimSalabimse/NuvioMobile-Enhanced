package com.nuvio.app.features.analysis

import android.content.Intent
import com.nuvio.app.features.player.PlayerSettingsStorage

internal actual object AnalysisLogShare {
    actual fun share(document: AnalysisExportDocument): Boolean {
        val context = PlayerSettingsStorage.applicationContext() ?: return false
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Analysis log")
            putExtra(Intent.EXTRA_TEXT, document.text)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooser = Intent.createChooser(send, "Export analysis log").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
        return true
    }
}
