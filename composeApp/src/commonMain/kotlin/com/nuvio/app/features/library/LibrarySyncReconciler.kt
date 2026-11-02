package com.nuvio.app.features.library

import com.nuvio.app.core.sync.OfflineConflictWinner
import com.nuvio.app.core.sync.resolveOfflineOutboxConflict
import com.nuvio.app.features.library.sync.LibraryDeltaEvent
import com.nuvio.app.features.library.sync.LibrarySyncKey

internal data class LibrarySnapshotReconciliation(
    val itemsByKey: MutableMap<String, LibraryItem>,
    val pendingUpsertKeysByKey: MutableMap<String, LibrarySyncKey>,
    val pendingDeleteKeysByKey: MutableMap<String, LibrarySyncKey>,
    val preservedLocalItems: Boolean,
)

internal data class LibraryDeltaReconciliation(
    val itemsByKey: MutableMap<String, LibraryItem>,
    val pendingUpsertKeysByKey: Map<String, LibrarySyncKey>,
    val pendingDeleteKeysByKey: Map<String, LibrarySyncKey>,
    val changed: Boolean,
    val cursorEventId: Long,
)

internal fun reconcileLibrarySnapshot(
    serverItems: Collection<LibraryItem>,
    localItemsByKey: Map<String, LibraryItem>,
    pendingUpsertKeysByKey: Map<String, LibrarySyncKey>,
    pendingDeleteKeysByKey: Map<String, LibrarySyncKey>,
): LibrarySnapshotReconciliation {
    val serverItemsByKey = serverItems.associateByTo(mutableMapOf()) {
        libraryItemKey(it.id, it.type)
    }
    val pendingUpserts = pendingUpsertKeysByKey.toMutableMap()
    val pendingDeletes = pendingDeleteKeysByKey.toMutableMap()
    pendingDeletes.keys.forEach(serverItemsByKey::remove)
    pendingUpserts.keys.toList().forEach { key ->
        val local = localItemsByKey[key]
        val remote = serverItemsByKey[key]
        if (
            local != null &&
            remote != null &&
            resolveOfflineOutboxConflict(local.savedAtEpochMs, remote.savedAtEpochMs) ==
            OfflineConflictWinner.Remote
        ) {
            pendingUpserts.remove(key)
        } else {
            local?.let { item -> serverItemsByKey[key] = item }
        }
    }

    return LibrarySnapshotReconciliation(
        itemsByKey = serverItemsByKey,
        pendingUpsertKeysByKey = pendingUpserts,
        pendingDeleteKeysByKey = pendingDeletes,
        preservedLocalItems = pendingUpserts.isNotEmpty() || pendingDeletes.isNotEmpty(),
    )
}

internal fun reconcileLibraryDelta(
    events: Collection<LibraryDeltaEvent>,
    currentItemsByKey: Map<String, LibraryItem>,
    pendingUpsertKeysByKey: Map<String, LibrarySyncKey>,
    pendingDeleteKeysByKey: Map<String, LibrarySyncKey>,
    currentCursorEventId: Long,
): LibraryDeltaReconciliation {
    val items = currentItemsByKey.toMutableMap()
    val pendingUpserts = pendingUpsertKeysByKey.toMutableMap()
    val pendingDeletes = pendingDeleteKeysByKey.toMutableMap()

    events.sortedBy(LibraryDeltaEvent::eventId).forEach { event ->
        val key = libraryItemKey(event.item.id, event.item.type)
        if (key in pendingUpserts || key in pendingDeletes) {
            val localTimestamp = items[key]?.savedAtEpochMs
            if (
                localTimestamp == null ||
                resolveOfflineOutboxConflict(localTimestamp, event.item.savedAtEpochMs) ==
                OfflineConflictWinner.Local
            ) {
                return@forEach
            }
            pendingUpserts.remove(key)
            pendingDeletes.remove(key)
        }

        when (event.operation.trim().lowercase()) {
            "upsert" -> items[key] = event.item
            "delete" -> items.remove(key)
        }
    }

    return LibraryDeltaReconciliation(
        itemsByKey = items,
        pendingUpsertKeysByKey = pendingUpserts,
        pendingDeleteKeysByKey = pendingDeletes,
        changed = items != currentItemsByKey ||
            pendingUpserts != pendingUpsertKeysByKey ||
            pendingDeletes != pendingDeleteKeysByKey,
        cursorEventId = maxOf(
            currentCursorEventId,
            events.maxOfOrNull(LibraryDeltaEvent::eventId) ?: currentCursorEventId,
        ),
    )
}
