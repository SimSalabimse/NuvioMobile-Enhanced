package com.nuvio.app.features.player

import com.nuvio.app.features.addons.AddonResource
import com.nuvio.app.features.addons.ManagedAddon
import com.nuvio.app.features.addons.enabledAddons

internal fun buildAddonSubtitleFetchKey(
    addons: List<ManagedAddon>,
    type: String?,
    videoId: String?,
): String? {
    val normalizedType = type?.takeIf { it.isNotBlank() } ?: return null
    val normalizedVideoId = videoId?.takeIf { it.isNotBlank() } ?: return null
    val compatibleSubtitleAddons = addons.enabledAddons().mapNotNull { addon ->
        val manifest = addon.manifest ?: return@mapNotNull null
        val supportsSubtitles = manifest.resources.any { resource ->
            resource.isCompatibleSubtitleResource(
                type = normalizedType,
                videoId = normalizedVideoId,
            )
        }
        if (!supportsSubtitles) return@mapNotNull null
        "${manifest.id}:${manifest.transportUrl}"
    }

    if (compatibleSubtitleAddons.isEmpty()) return null
    return buildString {
        append(normalizedType)
        append('|')
        append(normalizedVideoId)
        append('|')
        append(compatibleSubtitleAddons.sorted().joinToString("|"))
    }
}

internal fun AddonResource.isCompatibleSubtitleResource(type: String, videoId: String): Boolean {
    val isSubtitleResource = name.equals("subtitles", ignoreCase = true) ||
        name.equals("subtitle", ignoreCase = true)
    if (!isSubtitleResource) return false

    val requestType = if (type.equals("tv", ignoreCase = true)) "series" else type
    val typeMatches = types.isEmpty() || types.any { it.equals(requestType, ignoreCase = true) }
    if (!typeMatches) return false

    return idPrefixes.isEmpty() || idPrefixes.any { prefix -> videoId.startsWith(prefix) }
}

internal enum class SubtitleAutoSelectionMode {
    FORCED_ONLY,
    NORMAL_ONLY,
}

internal data class SubtitleAutoSelectionPlan(
    val targets: List<String>,
    val mode: SubtitleAutoSelectionMode,
)

internal fun resolveAudioTrackLanguageTarget(track: AudioTrack?): String? {
    if (track == null) return null

    val directLanguage = normalizeLanguageCode(track.language)
        ?.takeUnless { it == "und" || it == "unknown" }
    if (directLanguage != null) return directLanguage

    val selectableLanguages = AvailableLanguageOptions
        .mapNotNull { option -> normalizeLanguageCode(option.code) }
        .toSet()
    return listOf(track.label, track.id).firstNotNullOfOrNull { value ->
        normalizeLanguageCode(value)?.takeIf(selectableLanguages::contains)
    }
}

internal fun resolveSubtitleAutoSelectionPlan(
    selectedAudioTrack: AudioTrack?,
    preferredAudioTargets: List<String>,
    preferredSubtitleTargets: List<String>,
    useForcedSubtitles: Boolean,
): SubtitleAutoSelectionPlan? {
    if (useForcedSubtitles && selectedAudioTrack == null) return null

    val subtitleTargets = preferredSubtitleTargets
        .map { target -> SubtitleLanguageMatching.normalizeLanguageCode(target) }
        .filter { target ->
            target.isNotBlank() &&
                target != SubtitleLanguageOption.NONE &&
                target != SubtitleLanguageOption.FORCED &&
                target != AudioLanguageOption.DEFAULT
        }
        .distinct()
    val primarySubtitleTarget = subtitleTargets.firstOrNull()
    val forcedTarget = when {
        !useForcedSubtitles -> null
        primarySubtitleTarget != null &&
            selectedAudioTrack != null &&
            audioMatchesSubtitleTargetForForced(selectedAudioTrack, primarySubtitleTarget) ->
            primarySubtitleTarget
        primarySubtitleTarget == null &&
            selectedAudioTrack != null &&
            preferredAudioTargets.any { target ->
                audioTrackMatchesLanguage(selectedAudioTrack, target)
            } -> selectedAudioLanguageTarget(selectedAudioTrack)
        else -> null
    }

    return SubtitleAutoSelectionPlan(
        targets = forcedTarget?.let(::listOf) ?: subtitleTargets,
        mode = if (forcedTarget != null) {
            SubtitleAutoSelectionMode.FORCED_ONLY
        } else {
            SubtitleAutoSelectionMode.NORMAL_ONLY
        },
    )
}

internal fun audioMatchesSubtitleTargetForForced(
    audioTrack: AudioTrack,
    target: String,
): Boolean {
    if (audioTrackMatchesLanguage(audioTrack, target)) return true

    val normalizedTarget = SubtitleLanguageMatching.normalizeLanguageCode(target)
    val baseTarget = normalizedTarget.substringBefore('-')
    if (baseTarget == normalizedTarget) return false

    val audioVariant = SubtitleLanguageMatching.detectTrackLanguageVariant(
        language = audioTrack.language,
        name = audioTrack.label,
        trackId = audioTrack.id,
    )
    return audioVariant == baseTarget || audioVariant == normalizedTarget
}

internal fun findPreferredSubtitleTrackIndex(
    tracks: List<SubtitleTrack>,
    targets: List<String>,
    mode: SubtitleAutoSelectionMode,
    selectedAudioTrack: AudioTrack? = null,
): Int = findBestInternalSubtitleTrackIndex(
    tracks = tracks,
    targets = targets,
    forcedOnly = mode == SubtitleAutoSelectionMode.FORCED_ONLY,
    normalOnly = mode == SubtitleAutoSelectionMode.NORMAL_ONLY,
    selectedAudioTrack = selectedAudioTrack,
)

internal fun findBestInternalSubtitleTrackIndex(
    tracks: List<SubtitleTrack>,
    targets: List<String>,
    forcedOnly: Boolean = false,
    normalOnly: Boolean = false,
    selectedAudioTrack: AudioTrack? = null,
): Int {
    for ((targetPosition, target) in targets.withIndex()) {
        if (forcedOnly) {
            val forcedIndex = findBestForcedSubtitleTrackIndex(
                tracks = tracks,
                target = target,
                selectedAudioTrack = selectedAudioTrack,
            )
            if (forcedIndex >= 0) return forcedIndex
            if (targetPosition == 0) return -1
            continue
        }

        val normalizedTarget = SubtitleLanguageMatching.normalizeLanguageCode(target)
        val candidateIndexes = tracks.indices.filter { index ->
            val track = tracks[index]
            (!normalOnly || !track.isForced) && subtitleTrackMatchesLanguage(track, target)
        }
        if (candidateIndexes.isEmpty()) {
            if (normalizedTarget == "pt-br") {
                val brazilianFromGenericPt = findBrazilianPortugueseInGenericPtTracks(tracks, normalOnly)
                if (brazilianFromGenericPt >= 0) return brazilianFromGenericPt
                if (targetPosition == 0) return -1
            }
            if (normalizedTarget == "es-419") {
                val latinoFromGenericEs = findLatinoSpanishInGenericEsTracks(tracks, normalOnly)
                if (latinoFromGenericEs >= 0) return latinoFromGenericEs
                if (targetPosition == 0) return -1
            }
            continue
        }

        val preferredCandidateIndexes = candidateIndexes.filter { index -> !tracks[index].isForced }
            .takeIf { it.isNotEmpty() }
            ?: if (normalOnly) {
                continue
            } else {
                candidateIndexes
            }

        if (preferredCandidateIndexes.size == 1) {
            if (normalizedTarget == "pt" || normalizedTarget == "es") {
                val track = tracks[preferredCandidateIndexes.first()]
                val variant = SubtitleLanguageMatching.detectTrackLanguageVariant(
                    language = track.language,
                    name = track.label,
                    trackId = track.id,
                )
                if (variant != normalizedTarget && variant != track.language?.lowercase()) {
                    continue
                }
            }
            return preferredCandidateIndexes.first()
        }

        if (normalizedTarget == "pt" || normalizedTarget == "pt-br") {
            val tieBroken = breakPortugueseSubtitleTie(tracks, preferredCandidateIndexes, normalizedTarget)
            if (tieBroken >= 0) return tieBroken
        }
        if (normalizedTarget == "es" || normalizedTarget == "es-419") {
            val tieBroken = breakSpanishSubtitleTie(tracks, preferredCandidateIndexes, normalizedTarget)
            if (tieBroken >= 0) return tieBroken
        }
        return preferredCandidateIndexes.first()
    }
    return -1
}

internal fun findBestForcedSubtitleTrackIndex(
    tracks: List<SubtitleTrack>,
    target: String,
    selectedAudioTrack: AudioTrack?,
): Int {
    val directMatch = tracks.indexOfFirst { track ->
        track.isForced &&
            subtitleTrackMatchesLanguage(track, target) &&
            selectedAudioTrack != null &&
            subtitleTrackMatchesSelectedAudioLanguage(track, selectedAudioTrack)
    }
    if (directMatch >= 0) return directMatch

    val normalizedTarget = SubtitleLanguageMatching.normalizeLanguageCode(target)
    if (normalizedTarget == "pt-br" || normalizedTarget == "es-419") {
        return tracks.indexOfFirst { track ->
            track.isForced &&
                selectedAudioTrack != null &&
                subtitleTrackMatchesSelectedAudioLanguage(track, selectedAudioTrack) &&
                SubtitleLanguageMatching.detectTrackLanguageVariant(
                    language = track.language,
                    name = track.label,
                    trackId = track.id,
                ) == normalizedTarget
        }
    }
    return -1
}

internal fun subtitleTrackMatchesLanguage(track: SubtitleTrack, target: String): Boolean {
    return SubtitleLanguageMatching.trackMatchesLanguage(
        name = track.label,
        language = track.language,
        trackId = track.id,
        target = target,
    )
}

internal fun audioTrackMatchesLanguage(track: AudioTrack, target: String): Boolean {
    return SubtitleLanguageMatching.trackMatchesLanguage(
        name = track.label,
        language = track.language,
        trackId = track.id,
        target = target,
    )
}

internal fun selectedAudioLanguageTarget(track: AudioTrack): String? {
    track.language
        ?.takeIf { it.isNotBlank() && !it.equals("und", ignoreCase = true) }
        ?.let { return it }

    val haystack = listOf(track.label, track.id).joinToString(" ").lowercase()
    return AvailableLanguageOptions.firstOrNull { language ->
        val code = language.code.lowercase()
        val name = SubtitleLanguageMatching.languageCodeToName(language.code)
        SubtitleLanguageMatching.languageCodeAppearsInHaystack(haystack, code) ||
            (name.isNotBlank() && haystack.contains(name))
    }?.code
}

internal fun subtitleTrackMatchesSelectedAudioLanguage(
    track: SubtitleTrack,
    selectedAudioTrack: AudioTrack,
): Boolean {
    selectedAudioLanguageTarget(selectedAudioTrack)?.let { audioLanguage ->
        if (subtitleTrackMatchesLanguage(track, audioLanguage)) return true
    }

    val subtitleLanguageName = track.language
        ?.takeIf { it.isNotBlank() && !it.equals("und", ignoreCase = true) }
        ?.let { SubtitleLanguageMatching.languageCodeToName(it) }
    val audioHaystack = listOfNotNull(
        selectedAudioTrack.label,
        selectedAudioTrack.language,
        selectedAudioTrack.id,
    ).joinToString(" ").lowercase()
    return !subtitleLanguageName.isNullOrBlank() && audioHaystack.contains(subtitleLanguageName)
}

internal fun addonSubtitleIsForced(subtitle: AddonSubtitle): Boolean {
    return listOfNotNull(subtitle.id, subtitle.url, subtitle.addonName)
        .any { value -> value.contains("forced", ignoreCase = true) }
}

internal fun addonSubtitleMatchesLanguage(subtitle: AddonSubtitle, target: String): Boolean {
    if (SubtitleLanguageMatching.matchesLanguageCode(subtitle.language, target)) return true
    val normalizedTarget = SubtitleLanguageMatching.normalizeLanguageCode(target)
    val targetName = SubtitleLanguageMatching.languageCodeToName(target)
    val haystack = listOfNotNull(subtitle.language, subtitle.id, subtitle.url, subtitle.addonName)
        .joinToString(" ")
        .lowercase()
    return SubtitleLanguageMatching.languageCodeAppearsInHaystack(haystack, normalizedTarget) ||
        (targetName.isNotBlank() && haystack.contains(targetName))
}

internal fun addonSubtitleMatchesSelectedAudioLanguage(
    subtitle: AddonSubtitle,
    selectedAudioTrack: AudioTrack,
): Boolean {
    selectedAudioLanguageTarget(selectedAudioTrack)?.let { audioLanguage ->
        if (addonSubtitleMatchesLanguage(subtitle, audioLanguage)) return true
    }

    val subtitleLanguageName = subtitle.language
        .takeIf { it.isNotBlank() && !it.equals("und", ignoreCase = true) }
        ?.let { SubtitleLanguageMatching.languageCodeToName(it) }
    val audioHaystack = listOfNotNull(
        selectedAudioTrack.label,
        selectedAudioTrack.language,
        selectedAudioTrack.id,
    ).joinToString(" ").lowercase()
    return !subtitleLanguageName.isNullOrBlank() && audioHaystack.contains(subtitleLanguageName)
}

internal fun findBrazilianPortugueseInGenericPtTracks(
    tracks: List<SubtitleTrack>,
    normalOnly: Boolean = false,
): Int {
    val genericPtIndexes = tracks.indices.filter { index ->
        if (normalOnly && tracks[index].isForced) return@filter false
        val trackLanguage = tracks[index].language ?: return@filter false
        SubtitleLanguageMatching.normalizeLanguageCode(trackLanguage) == "pt"
    }
    if (genericPtIndexes.isEmpty()) return -1

    val brazilianNonForced = genericPtIndexes.filter { index ->
        !tracks[index].isForced &&
            subtitleHasAnyTag(tracks[index], SubtitleLanguageMatching.BRAZILIAN_TAGS) &&
            !subtitleHasAnyTag(tracks[index], SubtitleLanguageMatching.EUROPEAN_PT_TAGS)
    }
    if (brazilianNonForced.isNotEmpty()) return brazilianNonForced.first()

    return genericPtIndexes.firstOrNull { index ->
        subtitleHasAnyTag(tracks[index], SubtitleLanguageMatching.BRAZILIAN_TAGS) &&
            !subtitleHasAnyTag(tracks[index], SubtitleLanguageMatching.EUROPEAN_PT_TAGS)
    } ?: genericPtIndexes.firstOrNull { index ->
        subtitleHasAnyTag(tracks[index], SubtitleLanguageMatching.BRAZILIAN_TAGS)
    } ?: -1
}

internal fun findLatinoSpanishInGenericEsTracks(
    tracks: List<SubtitleTrack>,
    normalOnly: Boolean = false,
): Int {
    val genericEsIndexes = tracks.indices.filter { index ->
        if (normalOnly && tracks[index].isForced) return@filter false
        val trackLanguage = tracks[index].language ?: return@filter false
        SubtitleLanguageMatching.normalizeLanguageCode(trackLanguage) == "es"
    }
    if (genericEsIndexes.isEmpty()) return -1

    val latinoNonForced = genericEsIndexes.filter { index ->
        !tracks[index].isForced &&
            subtitleHasAnyTag(tracks[index], SubtitleLanguageMatching.LATINO_TAGS) &&
            !subtitleHasAnyTag(tracks[index], SubtitleLanguageMatching.CASTILIAN_TAGS)
    }
    if (latinoNonForced.isNotEmpty()) return latinoNonForced.first()

    return genericEsIndexes.firstOrNull { index ->
        subtitleHasAnyTag(tracks[index], SubtitleLanguageMatching.LATINO_TAGS) &&
            !subtitleHasAnyTag(tracks[index], SubtitleLanguageMatching.CASTILIAN_TAGS)
    } ?: genericEsIndexes.firstOrNull { index ->
        subtitleHasAnyTag(tracks[index], SubtitleLanguageMatching.LATINO_TAGS)
    } ?: -1
}

internal fun breakPortugueseSubtitleTie(
    tracks: List<SubtitleTrack>,
    candidateIndexes: List<Int>,
    normalizedTarget: String,
): Int {
    fun hasBrazilianTags(index: Int) =
        subtitleHasAnyTag(tracks[index], SubtitleLanguageMatching.BRAZILIAN_TAGS)

    fun hasEuropeanTags(index: Int) =
        subtitleHasAnyTag(tracks[index], SubtitleLanguageMatching.EUROPEAN_PT_TAGS)

    return if (normalizedTarget == "pt-br") {
        candidateIndexes.firstOrNull { hasBrazilianTags(it) && !hasEuropeanTags(it) }
            ?: candidateIndexes.firstOrNull { hasBrazilianTags(it) }
            ?: candidateIndexes.first()
    } else {
        candidateIndexes.firstOrNull { hasEuropeanTags(it) && !hasBrazilianTags(it) }
            ?: candidateIndexes.firstOrNull { hasEuropeanTags(it) }
            ?: candidateIndexes.firstOrNull { !hasBrazilianTags(it) }
            ?: candidateIndexes.first()
    }
}

internal fun breakSpanishSubtitleTie(
    tracks: List<SubtitleTrack>,
    candidateIndexes: List<Int>,
    normalizedTarget: String,
): Int {
    fun hasLatinoTags(index: Int) =
        subtitleHasAnyTag(tracks[index], SubtitleLanguageMatching.LATINO_TAGS)

    fun hasCastilianTags(index: Int) =
        subtitleHasAnyTag(tracks[index], SubtitleLanguageMatching.CASTILIAN_TAGS)

    return if (normalizedTarget == "es-419") {
        candidateIndexes.firstOrNull { hasLatinoTags(it) && !hasCastilianTags(it) }
            ?: candidateIndexes.firstOrNull { hasLatinoTags(it) }
            ?: candidateIndexes.first()
    } else {
        candidateIndexes.firstOrNull { hasCastilianTags(it) && !hasLatinoTags(it) }
            ?: candidateIndexes.firstOrNull { hasCastilianTags(it) }
            ?: candidateIndexes.firstOrNull { !hasLatinoTags(it) }
            ?: candidateIndexes.first()
    }
}

private fun subtitleHasAnyTag(track: SubtitleTrack, tags: List<String>): Boolean {
    return SubtitleLanguageMatching.subtitleHasAnyTag(
        name = track.label,
        language = track.language,
        trackId = track.id,
        tags = tags,
    )
}

internal fun filterAddonSubtitlesForSettings(
    subtitles: List<AddonSubtitle>,
    settings: PlayerSettingsUiState,
): List<AddonSubtitle> {
    val shouldFilter = settings.subtitleStyle.showOnlyPreferredLanguages
    if (!shouldFilter) return subtitles

    val targets = preferredSubtitleTargetsForSettings(settings)
    if (targets.isEmpty()) return emptyList()

    return subtitles.filter { subtitle ->
        targets.any { target ->
            SubtitleLanguageMatching.matchesLanguageCode(subtitle.language, target)
        }
    }
}

internal fun preferredSubtitleTargetsForSettings(settings: PlayerSettingsUiState): List<String> {
    return resolvePreferredSubtitleLanguageTargets(
        preferredSubtitleLanguage = settings.preferredSubtitleLanguage,
        secondaryPreferredSubtitleLanguage = settings.secondaryPreferredSubtitleLanguage,
        deviceLanguages = DeviceLanguagePreferences.preferredLanguageCodes(),
    ).filterNot { it == SubtitleLanguageOption.FORCED }
}

internal fun findPersistedAudioTrackIndex(
    tracks: List<AudioTrack>,
    preference: PersistedPlayerTrackPreference,
): Int {
    val targetId = preference.audioTrackId?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
    val targetName = preference.audioName?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
    val targetLanguage = normalizeLanguageCode(preference.audioLanguage)
    val strictCandidates = tracks.filter {
        targetLanguage == null || normalizeLanguageCode(it.language) == targetLanguage
    }
    if (targetId != null) {
        strictCandidates.firstOrNull {
            it.id.trim().lowercase() == targetId &&
                (targetName == null || it.label.trim().lowercase().contains(targetName))
        }?.let { return it.index }
    }
    if (targetName != null) {
        strictCandidates.firstOrNull { it.label.trim().lowercase() == targetName }
            ?.let { return it.index }
        strictCandidates.firstOrNull { it.label.trim().lowercase().contains(targetName) }
            ?.let { return it.index }
    }
    if (targetLanguage == null) return -1
    val languageCandidates = tracks.filter { languageMatchesPreference(it.language, targetLanguage) }
    val targetVariant = SubtitleLanguageMatching.detectTrackLanguageVariant(
        language = preference.audioLanguage,
        name = preference.audioName,
        trackId = preference.audioTrackId,
    )
    return languageCandidates.firstOrNull {
        SubtitleLanguageMatching.detectTrackLanguageVariant(
            language = it.language,
            name = it.label,
            trackId = it.id,
        ) == targetVariant
    }?.index ?: languageCandidates.firstOrNull()?.index ?: -1
}

internal fun findPersistedAddonSubtitle(
    subtitles: List<AddonSubtitle>,
    preference: PersistedPlayerTrackPreference,
): AddonSubtitle? {
    preference.addonSubtitleUrl?.takeIf { it.isNotBlank() }?.let { url ->
        subtitles.firstOrNull { it.url == url }?.let { return it }
    }
    val language = preference.subtitleLanguage?.takeIf { it.isNotBlank() } ?: return null
    val candidates = subtitles.filter { addonSubtitleMatchesLanguage(it, language) }
    val providerCandidates = preference.addonSubtitleAddonName?.takeIf { it.isNotBlank() }?.let { name ->
        candidates.filter { it.addonName.equals(name, ignoreCase = true) }
    }.orEmpty()
    val preferredCandidates = providerCandidates.ifEmpty { candidates }
    return preferredCandidates.firstOrNull {
        it.display.equals(preference.subtitleName, ignoreCase = true)
    } ?: preferredCandidates.firstOrNull()
}

internal fun preferredSubtitleDisablesSelection(preferredSubtitleLanguage: String?): Boolean {
    val normalized = normalizeLanguageCode(preferredSubtitleLanguage) ?: return true
    return normalized == SubtitleLanguageOption.NONE || normalized == SubtitleLanguageOption.FORCED
}

/**
 * Which subtitle row to keep after the engine reports [engineSelectedIndex].
 * That index is whatever the player autoselected (container default, OS language, or
 * subs-fallback). It is not a user choice. Off, and a choice the user already made,
 * stay as they are.
 */
internal fun subtitleIndexAfterEngineReport(
    currentIndex: Int,
    engineSelectedIndex: Int?,
    explicitUserChoice: Boolean,
    preferredSubtitleLanguage: String,
    useCustomSubtitles: Boolean,
): Int {
    if (useCustomSubtitles || explicitUserChoice) return currentIndex
    if (preferredSubtitleDisablesSelection(preferredSubtitleLanguage)) return -1
    return engineSelectedIndex ?: currentIndex
}

internal fun findPersistedSubtitleTrackIndex(
    tracks: List<SubtitleTrack>,
    preference: PersistedPlayerTrackPreference,
): Int {
    if (
        preference.subtitleType == PersistedSubtitleSelectionType.DISABLED ||
        preference.subtitleType == PersistedSubtitleSelectionType.ADDON
    ) {
        return -1
    }

    val targetId = preference.subtitleTrackId?.trim()?.takeIf { it.isNotBlank() }
    val targetLanguage = preference.subtitleLanguage?.trim()?.takeIf { it.isNotBlank() }
    fun matchesSavedLanguage(track: SubtitleTrack): Boolean {
        if (targetLanguage == null) return false
        return SubtitleLanguageMatching.matchesLanguageCode(track.language, targetLanguage) ||
            subtitleTrackMatchesLanguage(track, targetLanguage)
    }

    val languageCandidates = if (targetLanguage == null) {
        emptyList()
    } else {
        tracks.indices.filter { index -> matchesSavedLanguage(tracks[index]) }
    }
    // A per-file track id is only meaningful together with the saved language.
    // The same id on the next file, or on a download of the same video, can be another language.
    val idCandidates = if (targetLanguage != null) languageCandidates else tracks.indices.toList()
    val forcedIdCandidates = if (preference.subtitleIsForced == true) {
        idCandidates.filter { index -> tracks[index].isForced }
    } else {
        idCandidates
    }
    if (targetId != null) {
        forcedIdCandidates.firstOrNull { index -> tracks[index].id == targetId }
            ?.let { return tracks[it].index }
    }

    val forcedFiltered = if (preference.subtitleIsForced == true) {
        languageCandidates.filter { index -> tracks[index].isForced }
    } else {
        languageCandidates
    }
    if (forcedFiltered.size == 1) return tracks[forcedFiltered.first()].index
    if (forcedFiltered.size > 1) {
        val targetVariant = SubtitleLanguageMatching.detectTrackLanguageVariant(
            language = preference.subtitleLanguage,
            name = preference.subtitleName,
            trackId = preference.subtitleTrackId,
        )
        val variantPool = forcedFiltered.filter { index ->
            SubtitleLanguageMatching.detectTrackLanguageVariant(
                language = tracks[index].language,
                name = tracks[index].label,
                trackId = tracks[index].id,
            ) == targetVariant
        }.ifEmpty { forcedFiltered }
        preference.subtitleName?.trim()?.takeIf { it.isNotBlank() }?.let { name ->
            bestNamedTrackIndex(
                tracks = variantPool.map { index ->
                    NamedTrack(
                        index = tracks[index].index,
                        label = tracks[index].label,
                        forced = tracks[index].isForced,
                    )
                },
                wantedName = name,
                wantedForced = preference.subtitleIsForced == true,
                requireUnique = true,
            )?.let { return it }
        }
        return tracks[variantPool.first()].index
    }

    preference.subtitleName?.trim()?.takeIf { it.isNotBlank() }?.let { name ->
        val namePool = if (targetLanguage != null) {
            languageCandidates.map { index -> tracks[index] }
        } else {
            tracks
        }
        val nameMatches = namePool.filter { it.label.equals(name, ignoreCase = true) }
        val forcedNameMatches = if (preference.subtitleIsForced == true) {
            nameMatches.filter { it.isForced }
        } else {
            nameMatches
        }
        forcedNameMatches.firstOrNull()?.let { return it.index }
    }
    return -1
}

internal fun audioTrackListChanged(previous: List<AudioTrack>, current: List<AudioTrack>): Boolean {
    if (previous.size != current.size) return true
    return previous.indices.any { index ->
        val before = previous[index]
        val after = current[index]
        before.index != after.index ||
            before.id != after.id ||
            before.language != after.language ||
            before.label != after.label
    }
}

internal fun subtitleTrackListChanged(previous: List<SubtitleTrack>, current: List<SubtitleTrack>): Boolean {
    if (previous.size != current.size) return true
    return previous.indices.any { index ->
        val before = previous[index]
        val after = current[index]
        before.index != after.index ||
            before.id != after.id ||
            before.language != after.language ||
            before.label != after.label ||
            before.isForced != after.isForced
    }
}

/**
 * Index of the explicit audio choice after the file's track list changed.
 * Language and name identify the track. SDH is part of the name, so "English"
 * still matches "English SDH" when that is the English track. -1 means the
 * choice is absent and the old index must not be selected.
 */
internal fun explicitAudioIndexAfterListChange(
    tracks: List<AudioTrack>,
    wantedLanguage: String?,
    wantedName: String?,
): Int {
    val pool = tracksInLanguage(
        tracks = tracks,
        wantedLanguage = wantedLanguage,
        matches = { track, language -> audioTrackMatchesLanguage(track, language) },
    )
    if (pool.isEmpty()) return -1
    if (!wantedName.isNullOrBlank()) {
        bestNamedTrackIndex(
            tracks = pool.map { NamedTrack(it.index, it.label, forced = false) },
            wantedName = wantedName,
            wantedForced = false,
            requireUnique = false,
        )?.let { return it }
    }
    return pool.first().index
}

/** Same rule as [explicitAudioIndexAfterListChange], including forced and SDH. */
internal fun explicitSubtitleIndexAfterListChange(
    tracks: List<SubtitleTrack>,
    wantedLanguage: String?,
    wantedName: String?,
    wantedForced: Boolean,
): Int {
    val pool = tracksInLanguage(
        tracks = tracks,
        wantedLanguage = wantedLanguage,
        matches = { track, language ->
            SubtitleLanguageMatching.matchesLanguageCode(track.language, language) ||
                subtitleTrackMatchesLanguage(track, language)
        },
    )
    if (pool.isEmpty()) return -1
    val forcedPool = if (wantedForced) pool.filter { it.isForced } else pool.filter { !it.isForced }
    val candidates = forcedPool.ifEmpty { pool }
    if (!wantedName.isNullOrBlank()) {
        bestNamedTrackIndex(
            tracks = candidates.map { NamedTrack(it.index, it.label, it.isForced) },
            wantedName = wantedName,
            wantedForced = wantedForced,
            requireUnique = false,
        )?.let { return it }
    }
    return candidates.first().index
}

/**
 * True when [current] is [previous] plus the external subtitle at [addedUrl].
 * `sub-add` grows the track list by that one subtitle. A new embedded track does not.
 */
internal fun subtitleListChangeIsOwnExternalAdd(
    previous: List<SubtitleTrack>,
    current: List<SubtitleTrack>,
    addedUrl: String?,
): Boolean {
    if (addedUrl.isNullOrBlank() || current.size <= previous.size) return false
    val previousIds = previous.map { it.id }.toSet()
    val added = current.filter { it.id !in previousIds }
    if (added.isEmpty() || previous.any { prev -> current.none { it.id == prev.id } }) return false
    val keptChanged = previous.any { prev ->
        val now = current.first { it.id == prev.id }
        now.language != prev.language || now.label != prev.label || now.isForced != prev.isForced
    }
    if (keptChanged) return false
    return added.all { trackBelongsToAddedSubtitle(it, addedUrl) }
}

private fun trackBelongsToAddedSubtitle(track: SubtitleTrack, addedUrl: String): Boolean {
    val label = track.label.trim()
    val id = track.id.trim()
    if (label.equals(addedUrl, ignoreCase = true) || id.equals(addedUrl, ignoreCase = true)) return true
    if (label.contains(addedUrl, ignoreCase = true) || id.contains(addedUrl, ignoreCase = true)) return true
    val fileName = addedUrl.substringAfterLast('/').substringBefore('?').trim()
    return fileName.length >= 3 &&
        (label.contains(fileName, ignoreCase = true) || id.contains(fileName, ignoreCase = true))
}

private fun <T> tracksInLanguage(
    tracks: List<T>,
    wantedLanguage: String?,
    matches: (T, String) -> Boolean,
): List<T> {
    if (wantedLanguage.isNullOrBlank()) return tracks
    return tracks.filter { matches(it, wantedLanguage) }
}

internal data class NamedTrack(
    val index: Int,
    val label: String,
    val forced: Boolean,
)

internal fun bestNamedTrackIndex(
    tracks: List<NamedTrack>,
    wantedName: String,
    wantedForced: Boolean,
    requireUnique: Boolean,
): Int? {
    if (tracks.isEmpty() || wantedName.isBlank()) return null
    val wantedBase = baseTrackName(wantedName)
    val wantedSdh = nameHasSdh(wantedName)
    val wantedForcedName = wantedForced || nameHasForced(wantedName)
    val scored = tracks.map { track ->
        val base = baseTrackName(track.label)
        val sameSdh = nameHasSdh(track.label) == wantedSdh
        val sameForced = (track.forced || nameHasForced(track.label)) == wantedForcedName
        var score = 0
        if (track.label.equals(wantedName, ignoreCase = true)) score += 100
        if (wantedBase.isNotBlank() && base == wantedBase) score += 40
        if (sameSdh) score += 10
        if (sameForced) score += 10
        track.index to score
    }
    val best = scored.maxOf { it.second }
    if (best < 40) return null
    val winners = scored.filter { it.second == best }
    if (requireUnique && winners.size != 1) return null
    return winners.first().first
}

private val trackQualifierPattern = Regex(
    """\b(sdh|forced|cc|hearing[- ]impaired|for the deaf)\b""",
    RegexOption.IGNORE_CASE,
)

private fun baseTrackName(name: String?): String {
    return name.orEmpty()
        .replace(Regex("""[\[\(].*?[\]\)]"""), " ")
        .replace(trackQualifierPattern, " ")
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()
}

private fun nameHasSdh(name: String?): Boolean {
    val value = name?.lowercase().orEmpty()
    return value.contains("sdh") ||
        value.contains("hearing") ||
        Regex("""\bcc\b""").containsMatchIn(value)
}

private fun nameHasForced(name: String?): Boolean =
    name?.contains("forced", ignoreCase = true) == true
