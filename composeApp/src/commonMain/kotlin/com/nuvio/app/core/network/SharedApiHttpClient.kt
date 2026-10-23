package com.nuvio.app.core.network

import io.ktor.client.HttpClient

/**
 * One API client for the whole app. Every caller used an identically configured client of its
 * own; sharing one keeps a single connection pool. Never close it.
 */
internal val sharedApiHttpClient: HttpClient by lazy { createApiHttpClient() }
