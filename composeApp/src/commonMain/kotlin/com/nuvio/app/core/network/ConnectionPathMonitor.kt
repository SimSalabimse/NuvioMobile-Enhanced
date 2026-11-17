package com.nuvio.app.core.network

internal expect object ConnectionPathMonitor {
    fun start(onSatisfied: (Boolean) -> Unit)
    fun stop()
}
