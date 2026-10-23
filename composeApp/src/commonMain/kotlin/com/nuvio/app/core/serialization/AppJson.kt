package com.nuvio.app.core.serialization

import kotlinx.serialization.json.Json

/** Shared Json configs; Json is immutable and thread-safe, so one instance per config is enough. */
internal val LenientJson: Json = Json { ignoreUnknownKeys = true }

/** [LenientJson] that also writes default values. */
internal val LenientDefaultsJson: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
