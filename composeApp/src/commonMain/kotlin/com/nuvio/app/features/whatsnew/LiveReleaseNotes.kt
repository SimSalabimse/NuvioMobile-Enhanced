package com.nuvio.app.features.whatsnew

import com.nuvio.app.features.addons.httpRequestRaw
import com.nuvio.app.features.downloads.DownloadsSettingsStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.time.Clock

/**
 * Release notes fetched live from this fork's GitHub Releases (tag sideload-<version>-<sha>).
 * Cached; the bundled changelog.json is the fallback when offline, rate-limited or slow.
 * Never throws and never waits longer than [TIMEOUT_MS].
 */
internal object LiveReleaseNotes {
    const val OWNER = "SimSalabimse"
    const val REPO = "NuvioMobile-Enhanced"
    private const val TAG_PREFIX = "sideload-"
    private const val TIMEOUT_MS = 4_000L
    private const val CACHE_KEY = "whats_new_live_cache_v1"
    private const val CACHE_TTL_MS = 6L * 60 * 60 * 1000
    private const val RETRY_AFTER_FAIL_MS = 30L * 60 * 1000

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Serializable
    private data class ReleaseDto(
        val tag_name: String = "",
        val body: String? = null,
        val published_at: String? = null,
        val draft: Boolean = false,
    )

    @Serializable
    private data class Cache(
        val fetchedAt: Long,
        val failedAt: Long = 0,
        val etag: String? = null,
        val releases: List<BundledRelease> = emptyList(),
    )

    private var memory: List<BundledRelease>? = null

    private fun now() = Clock.System.now().toEpochMilliseconds()

    private fun readCache(): Cache? = runCatching {
        DownloadsSettingsStorage.loadString(CACHE_KEY)?.let { json.decodeFromString(Cache.serializer(), it) }
    }.getOrNull()

    private fun writeCache(cache: Cache) = runCatching {
        DownloadsSettingsStorage.saveString(CACHE_KEY, json.encodeToString(Cache.serializer(), cache))
    }

    /** Cached releases straight away (may be empty). */
    fun cached(): List<BundledRelease> = memory ?: readCache()?.releases.orEmpty().also { memory = it }

    /** Live releases, refreshed when the cache is old. Falls back to the cache on any failure. */
    suspend fun load(force: Boolean = false): List<BundledRelease> {
        val cache = readCache()
        val t = now()
        if (!force && cache != null) {
            val fresh = t - cache.fetchedAt < CACHE_TTL_MS
            val backingOff = cache.failedAt > 0 && t - cache.failedAt < RETRY_AFTER_FAIL_MS
            if (fresh || backingOff) return cache.releases.also { memory = it }
        }
        val fetched = try {
            withTimeoutOrNull(TIMEOUT_MS) { fetch(cache?.etag.takeIf { cache?.releases?.isNotEmpty() == true }) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }
        return when {
            fetched == null -> {
                writeCache((cache ?: Cache(fetchedAt = 0)).copy(failedAt = t))
                cache?.releases.orEmpty()
            }
            fetched.notModified -> {
                writeCache(cache!!.copy(fetchedAt = t, failedAt = 0))
                cache.releases
            }
            else -> {
                writeCache(Cache(fetchedAt = t, etag = fetched.etag, releases = fetched.releases))
                fetched.releases
            }
        }.also { memory = it }
    }

    private class Fetched(val releases: List<BundledRelease>, val etag: String?, val notModified: Boolean)

    private suspend fun fetch(etag: String?): Fetched? {
        val response = httpRequestRaw(
            method = "GET",
            url = "https://api.github.com/repos/$OWNER/$REPO/releases?per_page=30",
            headers = buildMap {
                put("Accept", "application/vnd.github+json")
                put("User-Agent", "NuvioMobile")
                etag?.let { put("If-None-Match", it) }
            },
            body = "",
            maxResponseBodyBytes = 2 * 1024 * 1024,
        )
        if (response.status == 304) return Fetched(emptyList(), etag, notModified = true)
        if (response.status !in 200..299) return null // 403/429 rate limit etc.
        val dtos = json.decodeFromString(ListSerializer(ReleaseDto.serializer()), response.body)
        val newEtag = response.headers.entries.firstOrNull { it.key.equals("etag", ignoreCase = true) }?.value
        val releases = dtos.asSequence()
            .filter { !it.draft && it.tag_name.startsWith(TAG_PREFIX) }
            .mapNotNull { dto -> parseRelease(versionFromTag(dto.tag_name) ?: return@mapNotNull null, dto.body.orEmpty(), dto.published_at) }
            .distinctBy { it.version }
            .toList()
        return Fetched(releases, newEtag, notModified = false)
    }

    /** sideload-0.5.10.3-7e3c0e04 -> 0.5.10.3 */
    internal fun versionFromTag(tag: String): String? =
        tag.removePrefix(TAG_PREFIX).substringBeforeLast('-').takeIf { it.isNotBlank() && it.first().isDigit() }

    /**
     * Parses a release body made by scripts/release-notes.py ("### New" / "New:" headings with "- " bullets).
     * Returns null when the body has no recognisable sections (the bundled notes are used instead).
     */
    internal fun parseRelease(version: String, body: String, publishedAt: String?): BundledRelease? {
        val sections = mapOf("new" to mutableListOf<String>(), "improved" to mutableListOf(), "fixed" to mutableListOf())
        var current: MutableList<String>? = null
        var afterBlank = false
        body.replace("\r\n", "\n").lineSequence().forEach { raw ->
            val line = raw.trim()
            val heading = line.trimStart('#').trim().trimEnd(':').trim().lowercase()
            val isHeading = line.startsWith("#") || (line.endsWith(":") && !line.startsWith("-") && !line.startsWith("*"))
            when {
                isHeading && sectionFor(heading) != null -> current = sections[sectionFor(heading)]
                line.startsWith("- ") || line.startsWith("* ") -> current?.add(cleanText(line.drop(2)))
                line.isBlank() -> Unit
                isHeading -> current = null
                // Plain text after a blank line (a footer or intro) ends the section.
                afterBlank -> current = null
                else -> {
                    // Wrapped continuation of the previous bullet.
                    val list = current
                    if (list != null && list.isNotEmpty()) list[list.lastIndex] = list.last() + " " + cleanText(line)
                }
            }
            afterBlank = line.isBlank()
        }
        if (sections.values.all { it.isEmpty() }) return null
        return BundledRelease(
            version = version,
            date = publishedAt?.take(10),
            new = sections.getValue("new").filter { it.isNotBlank() },
            improved = sections.getValue("improved").filter { it.isNotBlank() },
            fixed = sections.getValue("fixed").filter { it.isNotBlank() },
        )
    }

    private fun sectionFor(heading: String): String? = when {
        heading in setOf("new", "features", "new features", "added", "what's new") || heading.startsWith("new since") -> "new"
        heading in setOf("improved", "improvements", "changes", "changed", "performance", "polish") -> "improved"
        heading in setOf("fixed", "fixes", "bug fixes", "bugfixes", "bugs") -> "fixed"
        else -> null
    }

    private val markdownEscape = Regex("""\\([\\`*_{}\[\]()#+\-.!>|~"'])""")
    private val link = Regex("""\[([^\]]+)]\((https?://[^)]+)\)""")

    /** Plain text for the UI: no markdown escapes, links, bold markers or stray backslashes. */
    internal fun cleanText(text: String): String = text
        .replace(link, "$1")
        .replace(markdownEscape, "$1")
        .replace("**", "")
        .replace("`", "")
        .replace("\\n", " ")
        .replace("\\", "")
        .replace(Regex("\\s+"), " ")
        .trim()
}
