package com.nuvio.app.features.player.skip

/** Where the movie catalogue status is actually drawn. */
internal enum class MovieSegmentSurface {
    Details,
    PlayerSubmit,
}

/**
 * Movies show public counts and this account's confirmed or pending rows.
 * Episodes do not use this block. The unused submit dialog is not one of these surfaces.
 */
internal fun movieSegmentStatusAttached(surface: MovieSegmentSurface, isMovie: Boolean): Boolean = isMovie

/** The taps that open the block. Tests lock these paths. */
internal fun movieSegmentTapPath(surface: MovieSegmentSurface): String = when (surface) {
    MovieSegmentSurface.Details -> "DetailRoute > movie page > Overview"
    MovieSegmentSurface.PlayerSubmit -> "PlayerRoute > controls > Flag > submit strip"
}
