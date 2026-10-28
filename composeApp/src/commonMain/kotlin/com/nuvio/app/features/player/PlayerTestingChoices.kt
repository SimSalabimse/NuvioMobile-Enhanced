package com.nuvio.app.features.player

/** Temporary comparison choices. Stored on this device only, not in profile sync. */
enum class TestingControlsStyle(val storageId: String) {
    ForkButtons("fork_buttons"),
    PopupMenu("popup_menu"),
    ;

    companion object {
        fun decode(value: String?): TestingControlsStyle =
            entries.firstOrNull { it.storageId == value } ?: ForkButtons
    }
}

/** Temporary comparison choices. Stored on this device only, not in profile sync. */
enum class TestingSyncUi(val storageId: String) {
    BottomStrip("bottom_strip"),
    TopCard("top_card"),
    ;

    companion object {
        fun decode(value: String?): TestingSyncUi =
            entries.firstOrNull { it.storageId == value } ?: BottomStrip
    }
}

/** Temporary comparison choices. Stored on this device only, not in profile sync. */
enum class TestingTitleWhileSpeed(val storageId: String) {
    Stays("stays"),
    Fades("fades"),
    ;

    companion object {
        fun decode(value: String?): TestingTitleWhileSpeed =
            entries.firstOrNull { it.storageId == value } ?: Stays
    }
}

/**
 * Title block alpha while Sleep/Speed or the popup menu is open.
 * The block stays in the layout either way, so the seek bar does not move.
 */
internal fun testingTitleHidden(choice: TestingTitleWhileSpeed, speedOpen: Boolean, menuOpen: Boolean): Boolean =
    choice == TestingTitleWhileSpeed.Fades && (speedOpen || menuOpen)
