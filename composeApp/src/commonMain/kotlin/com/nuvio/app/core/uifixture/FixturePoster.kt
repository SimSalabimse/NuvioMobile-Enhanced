package com.nuvio.app.core.uifixture

import coil3.ImageLoader
import coil3.Uri
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import okio.Buffer
import okio.FileSystem

private val fixturePalette = listOf(
    "#1D4E89" to "#F2C14E",
    "#0F6E56" to "#E8F1F2",
    "#7A3E65" to "#F2D0A4",
    "#3D348B" to "#F7B267",
    "#1B3A4B" to "#8ECAE6",
    "#6B2D5B" to "#F4A261",
)

/** Synthetic artwork URL. Coil draws a gradient; nothing is fetched. */
fun fixtureArtUrl(slot: Int, wide: Boolean, label: String): String {
    val kind = if (wide) "wide" else "poster"
    val safe = label
        .take(42)
        .map { char -> if (char.isLetterOrDigit() || char == ' ' || char == '-') char else ' ' }
        .joinToString("")
        .trim()
        .replace(' ', '_')
        .ifBlank { "Title" }
    return "fixture://art/$kind/$slot/$safe"
}

/** Solid gradient SVG with the title painted on it. No external assets. */
fun fixturePosterSvg(slot: Int, wide: Boolean, label: String): String {
    val (start, end) = fixturePalette[slot.mod(fixturePalette.size).let { if (it < 0) it + fixturePalette.size else it }]
    val width = if (wide) 800 else 400
    val height = if (wide) 450 else 600
    val size = if (wide) 42 else 36
    val text = xmlText(label.ifBlank { "Title" }.take(48))
    return """
        <svg xmlns="http://www.w3.org/2000/svg" width="$width" height="$height" viewBox="0 0 $width $height">
          <defs>
            <linearGradient id="g" x1="0" y1="0" x2="1" y2="1">
              <stop offset="0%" stop-color="$start"/>
              <stop offset="100%" stop-color="$end"/>
            </linearGradient>
          </defs>
          <rect width="100%" height="100%" fill="url(#g)"/>
          <text x="28" y="${height - 48}" fill="#ffffff" font-size="$size" font-family="sans-serif">$text</text>
        </svg>
    """.trimIndent()
}

private fun xmlText(value: String): String = buildString(value.length) {
    value.forEach { char ->
        when (char) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            else -> append(char)
        }
    }
}

internal class FixturePosterFetcher(
    private val slot: Int,
    private val wide: Boolean,
    private val label: String,
) : Fetcher {
    override suspend fun fetch(): SourceFetchResult {
        val svg = fixturePosterSvg(slot, wide, label.replace('_', ' '))
        return SourceFetchResult(
            source = ImageSource(
                source = Buffer().write(svg.encodeToByteArray()),
                fileSystem = FileSystem.SYSTEM,
            ),
            mimeType = "image/svg+xml",
            dataSource = DataSource.MEMORY,
        )
    }

    class Factory : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            if (!data.scheme.equals("fixture", ignoreCase = true)) return null
            val parts = data.toString().substringAfter("://").split('/')
            if (parts.size < 4 || parts[0] != "art") return null
            val wide = parts[1] == "wide"
            val slot = parts[2].toIntOrNull() ?: 0
            val label = parts.drop(3).joinToString(" ")
            return FixturePosterFetcher(slot = slot, wide = wide, label = label)
        }
    }
}
