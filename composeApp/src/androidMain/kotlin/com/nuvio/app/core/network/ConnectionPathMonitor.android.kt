package com.nuvio.app.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

internal actual object ConnectionPathMonitor {
    private val lock = SynchronizedObject()
    private var appContext: Context? = null
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var listener: ((Boolean) -> Unit)? = null
    private var lastSatisfied: Boolean? = null

    fun install(context: Context) {
        val applicationContext = context.applicationContext
        val pending = synchronized(lock) {
            appContext = applicationContext
            listener?.takeIf { callback == null }
        }
        if (pending != null) start(pending)
    }

    actual fun start(onSatisfied: (Boolean) -> Unit) {
        val registration = synchronized(lock) {
            listener = onSatisfied
            val installed = appContext ?: return@synchronized null
            if (callback != null) return@synchronized null
            val manager = installed.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return@synchronized null
            val created = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    publish(true)
                }

                override fun onLost(network: Network) {
                    publish(false)
                }

                override fun onUnavailable() {
                    publish(false)
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities,
                ) {
                    publish(networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
                }
            }
            callback = created
            manager to created
        } ?: return
        val (manager, created) = registration
        val registered = runCatching { manager.registerDefaultNetworkCallback(created) }.isSuccess
        if (!registered) {
            synchronized(lock) {
                if (callback === created) callback = null
            }
            return
        }
        val network = manager.activeNetwork
        val capabilities = network?.let { manager.getNetworkCapabilities(it) }
        publish(capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true)
    }

    actual fun stop() {
        val removal = synchronized(lock) {
            val installed = appContext ?: return@synchronized null
            val current = callback ?: return@synchronized null
            callback = null
            lastSatisfied = null
            installed to current
        } ?: return
        val (context, created) = removal
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        runCatching { manager?.unregisterNetworkCallback(created) }
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
