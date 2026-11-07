package com.nuvio.app.features.player

/**
 * Computes the OpenSubtitles-style file hash for a remote video stream.
 *
 * Returns a pair of `(hash, fileSize)` on success, or `null` when the hash
 * cannot be computed (e.g. server does not support range requests, file is
 * too small, or the platform has no implementation).
 */
internal expect suspend fun computeVideoHash(
    url: String,
    headers: Map<String, String>,
): Pair<String, Long>?

/**
 * OpenSubtitles hash of a file already on disk. Null when the path is not a local
 * file or the file is smaller than 128 KB. Playback of a remote stream uses
 * [computeVideoHash] instead.
 */
internal expect suspend fun computeLocalVideoHash(uri: String): Pair<String, Long>?
