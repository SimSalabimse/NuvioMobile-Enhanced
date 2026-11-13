package com.nuvio.app.features.analyzedsync

/** Constants from docs/sync/SYNC_FORMAT_v1.md. A reader that sees any other version stops. */
internal const val SYNC_FORMAT_VERSION = 1
internal const val SYNC_KDF_ITERATIONS = 600_000
internal const val SYNC_SALT_BYTES = 16
internal const val SYNC_NONCE_BYTES = 12
internal const val SYNC_KEY_BYTES = 32
internal const val SYNC_KEY_CHECK_BYTES = 16
internal const val SYNC_MAX_PLAINTEXT_BYTES = 8192
internal const val SYNC_MAX_HLC_COUNTER = 1_000_000
internal const val SYNC_MAX_HLC_SKEW_MS = 86_400_000L
internal const val SYNC_TOMBSTONE_KEEP_MS = 7_776_000_000L
internal const val SYNC_CUT_TOLERANCE_MS = 30_000L
internal const val SYNC_MAX_SAFE_INTEGER = 9_007_199_254_740_991L
internal const val SYNC_BATCH_LIMIT = 50
internal const val SYNC_PULL_LIMIT = 100

internal val SYNC_SEGMENT_TYPES = listOf("intro", "recap", "credits", "preview")
