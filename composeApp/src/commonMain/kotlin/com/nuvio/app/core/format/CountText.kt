package com.nuvio.app.core.format

/** "1 episode", "3 episodes" for the English-only texts that are not string resources. */
internal fun countText(count: Int, singular: String, plural: String = singular + "s"): String =
    "$count ${if (count == 1) singular else plural}"
