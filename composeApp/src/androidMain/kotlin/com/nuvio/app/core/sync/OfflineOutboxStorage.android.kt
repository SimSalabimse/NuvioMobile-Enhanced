package com.nuvio.app.core.sync

import android.content.Context
import android.content.SharedPreferences
import com.nuvio.app.core.automation.isolateStorageKey
import android.os.Looper
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

internal actual object PlatformOfflineOutboxDisk : OfflineOutboxDisk {
    private const val preferencesName = "nuvio_offline_outbox"
    private const val documentKey = "offline_account_outbox"
    private const val badDocumentKey = "offline_account_outbox.bad"

    private var preferences: SharedPreferences? = null

    fun initialize(context: Context) {
        preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    }

    actual override fun read(): String? =
        preferences?.getString(isolateStorageKey(documentKey), null)

    actual override fun write(payload: String) {
        val editor = preferences?.edit() ?: return
        editor.putString(isolateStorageKey(documentKey), payload)
        editor.commit()
    }

    override fun readBad(): String? =
        preferences?.getString(isolateStorageKey(badDocumentKey), null)

    override fun writeBad(payload: String) {
        val editor = preferences?.edit() ?: return
        editor.putString(isolateStorageKey(badDocumentKey), payload)
        editor.commit()
    }
}

internal actual fun runOfflineOutboxDiskWrite(block: () -> Unit) {
    if (!offlineOutboxOnMainThread()) {
        block()
        return
    }
    val done = CountDownLatch(1)
    var failure: Throwable? = null
    thread(name = "offline-outbox-write", isDaemon = true) {
        try {
            block()
        } catch (error: Throwable) {
            failure = error
        } finally {
            done.countDown()
        }
    }
    done.await()
    failure?.let { throw it }
}

private fun offlineOutboxOnMainThread(): Boolean = try {
    val main = Looper.getMainLooper()
    main != null && main == Looper.myLooper()
} catch (_: RuntimeException) {
    false
}
