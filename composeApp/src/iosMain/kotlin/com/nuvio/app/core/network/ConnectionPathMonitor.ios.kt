package com.nuvio.app.core.network

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Network.nw_path_get_status
import platform.Network.nw_path_monitor_cancel
import platform.Network.nw_path_monitor_create
import platform.Network.nw_path_monitor_set_queue
import platform.Network.nw_path_monitor_set_update_handler
import platform.Network.nw_path_monitor_start
import platform.Network.nw_path_monitor_t
import platform.Network.nw_path_status_satisfied
import platform.Network.nw_release
import platform.darwin.dispatch_get_main_queue

@OptIn(ExperimentalForeignApi::class)
internal actual object ConnectionPathMonitor {
    private val lock = SynchronizedObject()
    private var monitor: nw_path_monitor_t? = null
    private var listener: ((Boolean) -> Unit)? = null
    private var lastSatisfied: Boolean? = null

    actual fun start(onSatisfied: (Boolean) -> Unit) {
        val created = synchronized(lock) {
            listener = onSatisfied
            if (monitor != null) return
            val pathMonitor = nw_path_monitor_create() ?: return
            monitor = pathMonitor
            pathMonitor
        }
        nw_path_monitor_set_update_handler(created) { path ->
            val satisfied = path != null && nw_path_get_status(path) == nw_path_status_satisfied
            publish(satisfied)
        }
        nw_path_monitor_set_queue(created, dispatch_get_main_queue())
        val stillOwned = synchronized(lock) { monitor == created }
        if (!stillOwned) return
        nw_path_monitor_start(created)
    }

    actual fun stop() {
        val current = synchronized(lock) {
            val existing = monitor ?: return
            monitor = null
            lastSatisfied = null
            existing
        }
        nw_path_monitor_cancel(current)
        nw_release(current)
    }

    private fun publish(satisfied: Boolean) {
        val current = synchronized(lock) {
            if (lastSatisfied == satisfied) return
            lastSatisfied = satisfied
            listener
        }
        current?.invoke(satisfied)
    }
}
