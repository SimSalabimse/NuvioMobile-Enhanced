package com.nuvio.app.features.player

enum class PlayerToolbarButton(val storageId: String) {
    NEXT_EPISODE("next_episode"),
    SPEED("speed"),
    RESIZE("resize"),
    SUBTITLES("subtitles"),
    CHAPTERS("chapters"),
    AUDIO("audio"),
    NIGHT_MODE("night_mode"),
    QUALITY("quality"),
    SOURCES("sources"),
    EPISODES("episodes"),
    RATE("rate"),
    EXTERNAL_PLAYER("external_player"),
    PLAYBACK_INFO("playback_info"),
    SWITCH_ENGINE("switch_engine"),
    VIDEO_SETTINGS("video_settings"),
    SUBMIT_INTRO("submit_intro"),
    ;

    companion object {
        fun fromStorageId(value: String): PlayerToolbarButton? =
            entries.firstOrNull { it.storageId == value }
    }
}

data class PlayerToolbarEntry(
    val button: PlayerToolbarButton,
    val visible: Boolean,
)

data class PlayerToolbarLayout(val entries: List<PlayerToolbarEntry>) {
    val visibleButtons: List<PlayerToolbarButton>
        get() = entries.filter { it.visible }.map { it.button }

    val hiddenButtons: List<PlayerToolbarButton>
        get() = entries.filterNot { it.visible }.map { it.button }

    fun withVisibility(button: PlayerToolbarButton, visible: Boolean): PlayerToolbarLayout =
        PlayerToolbarLayout(entries.map { if (it.button == button) it.copy(visible = visible) else it })

    fun moved(fromIndex: Int, toIndex: Int): PlayerToolbarLayout {
        if (fromIndex !in entries.indices || toIndex !in entries.indices || fromIndex == toIndex) return this
        val updated = entries.toMutableList()
        updated.add(toIndex, updated.removeAt(fromIndex))
        return PlayerToolbarLayout(updated)
    }

    fun encode(): String = entries.joinToString(",") { entry ->
        "${entry.button.storageId}:${if (entry.visible) "v" else "h"}"
    }

    companion object {
        val Default = PlayerToolbarLayout(
            listOf(
                PlayerToolbarButton.NEXT_EPISODE,
                PlayerToolbarButton.SPEED,
                PlayerToolbarButton.RESIZE,
                PlayerToolbarButton.SUBTITLES,
                PlayerToolbarButton.CHAPTERS,
                PlayerToolbarButton.SUBMIT_INTRO,
            ).map { PlayerToolbarEntry(it, visible = true) } +
                listOf(
                    PlayerToolbarButton.NIGHT_MODE,
                    PlayerToolbarButton.AUDIO,
                    PlayerToolbarButton.QUALITY,
                    PlayerToolbarButton.SOURCES,
                    PlayerToolbarButton.EPISODES,
                    PlayerToolbarButton.RATE,
                    PlayerToolbarButton.EXTERNAL_PLAYER,
                    PlayerToolbarButton.PLAYBACK_INFO,
                    PlayerToolbarButton.SWITCH_ENGINE,
                    PlayerToolbarButton.VIDEO_SETTINGS,
                ).map { PlayerToolbarEntry(it, visible = false) },
        )

        fun decode(value: String?): PlayerToolbarLayout {
            if (value.isNullOrBlank()) return Default
            val tokens = value.split(',')
            val parsed = tokens.mapNotNull { token ->
                val button = PlayerToolbarButton.fromStorageId(token.substringBefore(':').trim())
                    ?: return@mapNotNull null
                PlayerToolbarEntry(button, visible = token.substringAfter(':', "v").trim() != "h")
            }.distinctBy { it.button }
            val missing = Default.entries.filter { default -> parsed.none { it.button == default.button } }
            val merged = PlayerToolbarLayout(parsed + missing)
            // A saved layout that never names Submit intro kept the Flag before this row existed.
            // Only an explicit hidden token hides it.
            val mentionedSubmitIntro = parsed.any { it.button == PlayerToolbarButton.SUBMIT_INTRO }
            return if (mentionedSubmitIntro) merged else merged.withVisibility(PlayerToolbarButton.SUBMIT_INTRO, true)
        }
    }
}
