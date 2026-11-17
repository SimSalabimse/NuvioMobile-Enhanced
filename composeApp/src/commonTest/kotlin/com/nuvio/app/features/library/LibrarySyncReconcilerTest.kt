package com.nuvio.app.features.library

import com.nuvio.app.features.library.sync.LibraryDeltaEvent
import com.nuvio.app.features.library.sync.LibrarySyncKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LibrarySyncReconcilerTest {

    @Test
    fun `empty snapshot removes cached items without queuing an upload`() {
        val localItem = libraryItem(id = "local", savedAtEpochMs = 1L)

        val result = reconcileLibrarySnapshot(
            serverItems = emptyList(),
            localItemsByKey = mapOf(libraryItemKey(localItem.id, localItem.type) to localItem),
            pendingUpsertKeysByKey = emptyMap(),
            pendingDeleteKeysByKey = emptyMap(),
        )

        assertTrue(result.itemsByKey.isEmpty())
        assertTrue(result.pendingUpsertKeysByKey.isEmpty())
        assertTrue(result.pendingDeleteKeysByKey.isEmpty())
        assertFalse(result.preservedLocalItems)
    }

    @Test
    fun `snapshot merges pending upserts and deletes over remote state`() {
        val localUpsert = libraryItem(id = "changed", savedAtEpochMs = 30L)
        val remoteChanged = libraryItem(id = "changed", savedAtEpochMs = 10L)
        val remoteKept = libraryItem(id = "kept", savedAtEpochMs = 20L)
        val remoteDeleted = libraryItem(id = "deleted", savedAtEpochMs = 15L)

        val result = reconcileLibrarySnapshot(
            serverItems = listOf(remoteChanged, remoteKept, remoteDeleted),
            localItemsByKey = mapOf(libraryItemKey(localUpsert.id, localUpsert.type) to localUpsert),
            pendingUpsertKeysByKey = mapOf(
                libraryItemKey(localUpsert.id, localUpsert.type) to
                    LibrarySyncKey(localUpsert.id, localUpsert.type),
            ),
            pendingDeleteKeysByKey = mapOf(
                libraryItemKey(remoteDeleted.id, remoteDeleted.type) to
                    LibrarySyncKey(remoteDeleted.id, remoteDeleted.type),
            ),
        )

        assertEquals(
            setOf("changed", "kept"),
            result.itemsByKey.values.mapTo(mutableSetOf(), LibraryItem::id),
        )
        assertEquals(30L, result.itemsByKey[libraryItemKey("changed", "movie")]?.savedAtEpochMs)
        assertTrue(result.preservedLocalItems)
    }

    @Test
    fun `delta applies ordered upserts and deletes and advances cursor`() {
        val existing = libraryItem(id = "existing", savedAtEpochMs = 1L)
        val added = libraryItem(id = "added", savedAtEpochMs = 2L)

        val result = reconcileLibraryDelta(
            events = listOf(
                LibraryDeltaEvent(eventId = 6L, operation = "delete", item = existing),
                LibraryDeltaEvent(eventId = 7L, operation = "upsert", item = added),
            ),
            currentItemsByKey = mapOf(libraryItemKey(existing.id, existing.type) to existing),
            pendingUpsertKeysByKey = emptyMap(),
            pendingDeleteKeysByKey = emptyMap(),
            currentCursorEventId = 5L,
        )

        assertEquals(listOf("added"), result.itemsByKey.values.map(LibraryItem::id))
        assertEquals(7L, result.cursorEventId)
        assertTrue(result.changed)
    }

    @Test
    fun `delta preserves pending local mutations while advancing cursor`() {
        val localUpsert = libraryItem(id = "local", savedAtEpochMs = 20L)
        val remoteUpsert = libraryItem(id = "deleted-locally", savedAtEpochMs = 30L)

        val result = reconcileLibraryDelta(
            events = listOf(
                LibraryDeltaEvent(eventId = 11L, operation = "delete", item = localUpsert),
                LibraryDeltaEvent(eventId = 12L, operation = "upsert", item = remoteUpsert),
            ),
            currentItemsByKey = mapOf(
                libraryItemKey(localUpsert.id, localUpsert.type) to localUpsert,
            ),
            pendingUpsertKeysByKey = mapOf(
                libraryItemKey(localUpsert.id, localUpsert.type) to
                    LibrarySyncKey(localUpsert.id, localUpsert.type),
            ),
            pendingDeleteKeysByKey = mapOf(
                libraryItemKey(remoteUpsert.id, remoteUpsert.type) to
                    LibrarySyncKey(remoteUpsert.id, remoteUpsert.type),
            ),
            currentCursorEventId = 10L,
        )

        assertEquals(listOf("local"), result.itemsByKey.values.map(LibraryItem::id))
        assertEquals(12L, result.cursorEventId)
        assertFalse(result.changed)
    }

    @Test
    fun `newer remote removal drops an older local add`() {
        val localUpsert = libraryItem(id = "local", savedAtEpochMs = 10L)
        val remoteRemoval = libraryItem(id = "local", savedAtEpochMs = 30L)

        val result = reconcileLibraryDelta(
            events = listOf(
                LibraryDeltaEvent(eventId = 4L, operation = "delete", item = remoteRemoval),
            ),
            currentItemsByKey = mapOf(libraryItemKey(localUpsert.id, localUpsert.type) to localUpsert),
            pendingUpsertKeysByKey = mapOf(
                libraryItemKey(localUpsert.id, localUpsert.type) to
                    LibrarySyncKey(localUpsert.id, localUpsert.type),
            ),
            pendingDeleteKeysByKey = emptyMap(),
            currentCursorEventId = 3L,
        )

        assertTrue(result.itemsByKey.isEmpty())
        assertTrue(result.pendingUpsertKeysByKey.isEmpty())
        assertTrue(result.changed)
    }

    @Test
    fun `newer remote library item replaces an older pending add`() {
        val localUpsert = libraryItem(id = "changed", savedAtEpochMs = 10L)
        val remote = libraryItem(id = "changed", savedAtEpochMs = 40L)

        val result = reconcileLibrarySnapshot(
            serverItems = listOf(remote),
            localItemsByKey = mapOf(libraryItemKey(localUpsert.id, localUpsert.type) to localUpsert),
            pendingUpsertKeysByKey = mapOf(
                libraryItemKey(localUpsert.id, localUpsert.type) to
                    LibrarySyncKey(localUpsert.id, localUpsert.type),
            ),
            pendingDeleteKeysByKey = emptyMap(),
        )

        assertEquals(40L, result.itemsByKey[libraryItemKey("changed", "movie")]?.savedAtEpochMs)
        assertTrue(result.pendingUpsertKeysByKey.isEmpty())
        assertFalse(result.preservedLocalItems)
    }

    private fun libraryItem(
        id: String,
        savedAtEpochMs: Long,
    ): LibraryItem =
        LibraryItem(
            id = id,
            type = "movie",
            name = id,
            savedAtEpochMs = savedAtEpochMs,
        )
}
