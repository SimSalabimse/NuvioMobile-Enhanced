package com.nuvio.app.features.analysis

import com.nuvio.app.features.downloads.DownloadsSettingsStorage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What to do when a downloaded episode that is not fully analysed starts playing. */
enum class AnalysisPlayChoice(val key: String, val label: String) {
    Ask("ask", "Ask every time"),
    PlayNow("play", "Play now"),
    FinishFirst("finish", "Finish first"),
    Background("background", "Finish in background"),
    ;

    companion object {
        private const val STORAGE_KEY = "analysis_play_choice"
        private val state = MutableStateFlow<AnalysisPlayChoice?>(null)

        val current: StateFlow<AnalysisPlayChoice?>
            get() {
                load()
                return state
            }

        fun get(): AnalysisPlayChoice {
            load()
            return state.value ?: Ask
        }

        fun set(choice: AnalysisPlayChoice) {
            state.value = choice
            DownloadsSettingsStorage.saveString(STORAGE_KEY, choice.key)
        }

        private fun load() {
            if (state.value != null) return
            val raw = DownloadsSettingsStorage.loadString(STORAGE_KEY)
            state.value = entries.firstOrNull { it.key == raw } ?: Ask
        }
    }
}
