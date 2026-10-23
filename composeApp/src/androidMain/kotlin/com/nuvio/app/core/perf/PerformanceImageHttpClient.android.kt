package com.nuvio.app.core.perf

import com.nuvio.app.core.network.SharedOkHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp

/** Coil's image fetcher on the app's shared OkHttp pool and dispatcher instead of a private one. */
internal fun performanceImageHttpClient(): HttpClient =
    if (PerformanceSettings.isEnabled(PerformanceOptimization.RequestDedupe)) {
        HttpClient(OkHttp) { engine { preconfigured = SharedOkHttpClient.base } }
    } else {
        HttpClient()
    }
