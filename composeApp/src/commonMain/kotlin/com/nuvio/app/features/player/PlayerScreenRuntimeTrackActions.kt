package com.nuvio.app.features.player

internal val PlayerScreenRuntime.subtitleStyle: SubtitleStyleState
    get() = playerSettingsUiState.subtitleStyle

internal val PlayerScreenRuntime.activeAddonSubtitleType: String
    get() = contentType ?: parentMetaType

internal val PlayerScreenRuntime.addonSubtitleFetchKey: String?
    get() = buildAddonSubtitleFetchKey(
        addons = addonsUiState.addons,
        type = activeAddonSubtitleType,
        videoId = activeVideoId,
    )

internal val PlayerScreenRuntime.visibleAddonSubtitles: List<AddonSubtitle>
    get() {
        val filtered = filterAddonSubtitlesForSettings(
            subtitles = addonSubtitles,
            settings = playerSettingsUiState,
        )
        val selectedId = selectedAddonSubtitleId ?: return filtered
        if (filtered.any { it.matchesSelection(selectedId) }) return filtered
        val selectedSub = addonSubtitles.findSelectedAddon(selectedId) ?: return filtered
        return listOf(selectedSub) + filtered
    }

internal val PlayerScreenRuntime.selectedAddonSubtitle: AddonSubtitle?
    get() {
        val selectedId = selectedAddonSubtitleId ?: return null
        return addonSubtitles.findSelectedAddon(selectedId)
            ?: visibleAddonSubtitles.findSelectedAddon(selectedId)
    }

internal fun PlayerScreenRuntime.updateTrackPreference(
    update: (PersistedPlayerTrackPreference) -> PersistedPlayerTrackPreference,
) {
    if (parentMetaId.isBlank()) return
    val current = PlayerTrackPreferenceStorage.load(parentMetaId) ?: PersistedPlayerTrackPreference()
    PlayerTrackPreferenceStorage.save(parentMetaId, update(current))
}

internal fun PlayerScreenRuntime.persistAudioPreference(track: AudioTrack?) {
    isUserExplicitAudioSelection = true
    preferredAudioSelectionApplied = true
    updateTrackPreference { current ->
        current.copy(
            audioLanguage = track?.language,
            audioName = track?.label,
            audioTrackId = track?.id,
        )
    }
}

internal fun PlayerScreenRuntime.persistInternalSubtitlePreference(track: SubtitleTrack?) {
    updateTrackPreference { current ->
        current.copy(
            subtitleType = if (track == null) {
                PersistedSubtitleSelectionType.DISABLED
            } else {
                PersistedSubtitleSelectionType.INTERNAL
            },
            subtitleLanguage = track?.language,
            subtitleName = track?.label,
            subtitleTrackId = track?.id,
            subtitleIsForced = track?.isForced,
            addonSubtitleId = null,
            addonSubtitleUrl = null,
            addonSubtitleAddonName = null,
        )
    }
}

internal fun PlayerScreenRuntime.persistAddonSubtitlePreference(subtitle: AddonSubtitle) {
    updateTrackPreference { current ->
        current.copy(
            subtitleType = PersistedSubtitleSelectionType.ADDON,
            subtitleLanguage = subtitle.language,
            subtitleName = subtitle.display,
            subtitleTrackId = null,
            addonSubtitleId = subtitle.id,
            addonSubtitleUrl = subtitle.url,
            addonSubtitleAddonName = subtitle.addonName,
            subtitleIsForced = null,
        )
    }
}

internal fun PlayerScreenRuntime.restorePersistedTrackPreferenceIfNeeded(): Boolean {
    if (trackPreferenceRestoreApplied) return true
    val preference = PlayerTrackPreferenceStorage.load(parentMetaId)
    if (preference == null) {
        trackPreferenceRestoreApplied = true
        return true
    }

    restorePersistedAudioPreference(preference)

    when (preference.subtitleType) {
        PersistedSubtitleSelectionType.DISABLED -> {
            playerController?.selectSubtitleTrack(-1)
            selectedSubtitleIndex = -1
            selectedAddonSubtitleId = null
            useCustomSubtitles = false
            preferredSubtitleSelectionApplied = true
            isUserExplicitSubtitleSelection = true
        }
        PersistedSubtitleSelectionType.INTERNAL -> {
            if (subtitleTracks.isNotEmpty()) {
                val restoredSubtitleIndex = findPersistedSubtitleTrackIndex(subtitleTracks, preference)
                if (restoredSubtitleIndex >= 0) {
                    if (useCustomSubtitles) {
                        playerController?.clearExternalSubtitleAndSelect(restoredSubtitleIndex)
                    } else {
                        playerController?.selectSubtitleTrack(restoredSubtitleIndex)
                    }
                    selectedSubtitleIndex = restoredSubtitleIndex
                    selectedAddonSubtitleId = null
                    useCustomSubtitles = false
                    preferredSubtitleSelectionApplied = true
                    isUserExplicitSubtitleSelection = true
                }
            }
        }
        PersistedSubtitleSelectionType.ADDON -> {
            if (!isUserExplicitSubtitleSelection) {
                val fetchKey = addonSubtitleFetchKey.takeUnless {
                    activeSourceUrl.startsWith("file:") && externalSubtitles.isNotEmpty()
                }
                if (fetchKey != null && autoFetchedAddonSubtitlesForKey != fetchKey) return false

                val subtitle = findPersistedAddonSubtitle(addonSubtitles, preference)
                val canRestoreWhileLoading = subtitle != null && (
                    subtitle.url == preference.addonSubtitleUrl ||
                        preference.addonSubtitleAddonName.isNullOrBlank() ||
                        subtitle.addonName.equals(preference.addonSubtitleAddonName, ignoreCase = true)
                    )
                if (isLoadingAddonSubtitles && !canRestoreWhileLoading) return false
                if (subtitle != null) {
                    val alreadyAdded = useCustomSubtitles && subtitle.matchesSelection(selectedAddonSubtitleId)
                    selectedAddonSubtitleId = subtitle.selectionKey
                    selectedSubtitleIndex = -1
                    useCustomSubtitles = true
                    if (!alreadyAdded && !maybeAutoSyncRestoredSubtitleAtStart(subtitle.url)) { // AutoSync hook
                        playerController?.setSubtitleUri(subtitle.url)
                    }
                    preferredSubtitleSelectionApplied = true
                    isUserExplicitSubtitleSelection = true
                }
            }
        }
    }

    val subtitleStillWaiting = preference.subtitleType == PersistedSubtitleSelectionType.INTERNAL &&
        subtitleTracks.isEmpty() &&
        (!preference.subtitleLanguage.isNullOrBlank() ||
            !preference.subtitleTrackId.isNullOrBlank() ||
            !preference.subtitleName.isNullOrBlank())
    val audioStillWaiting = audioTracks.isEmpty() &&
        (!preference.audioTrackId.isNullOrBlank() ||
            !preference.audioLanguage.isNullOrBlank() ||
            !preference.audioName.isNullOrBlank())
    trackPreferenceRestoreApplied = !subtitleStillWaiting && !audioStillWaiting
    // Subtitle auto-select waits until a saved built-in track can be restored. Audio can
    // arrive later without blocking that, which matches the previous retry.
    return !subtitleStillWaiting
}

internal fun PlayerScreenRuntime.refreshTracks() {
    val ctrl = playerController ?: return
    val previousAudioIndex = selectedAudioIndex
    val previousAudioTracks = audioTracks
    val previousSubtitleTracks = subtitleTracks
    audioTracks = ctrl.getAudioTracks()
    subtitleTracks = ctrl.getSubtitleTracks()
    val selectedAudio = audioTracks.firstOrNull { it.isSelected }
    val audioListChanged = audioTrackListChanged(previousAudioTracks, audioTracks)
    if (isUserExplicitAudioSelection) {
        if (!audioListChanged) {
            if (
                selectedAudioIndex >= 0 &&
                selectedAudio?.index != selectedAudioIndex &&
                audioTracks.any { it.index == selectedAudioIndex }
            ) {
                ctrl.selectAudioTrack(selectedAudioIndex)
            }
        } else {
            val wanted = previousAudioTracks.firstOrNull { it.index == selectedAudioIndex }
            val match = wanted?.let {
                explicitAudioIndexAfterListChange(audioTracks, it.language, it.label)
            } ?: -1
            if (match >= 0) {
                selectedAudioIndex = match
                if (selectedAudio?.index != match) ctrl.selectAudioTrack(match)
            } else {
                selectedAudioIndex = selectedAudio?.index ?: -1
                isUserExplicitAudioSelection = false
            }
        }
    } else if (selectedAudio != null) {
        selectedAudioIndex = selectedAudio.index
    }
    val engineSelectedSubtitle = subtitleTracks.firstOrNull { it.isSelected }
    selectedSubtitleIndex = subtitleIndexAfterEngineReport(
        currentIndex = selectedSubtitleIndex,
        engineSelectedIndex = engineSelectedSubtitle?.index,
        explicitUserChoice = isUserExplicitSubtitleSelection,
        preferredSubtitleLanguage = playerSettingsUiState.preferredSubtitleLanguage,
        useCustomSubtitles = useCustomSubtitles,
    )
    val subtitleListChanged = subtitleTrackListChanged(previousSubtitleTracks, subtitleTracks)
    if (!useCustomSubtitles && selectedAddonSubtitleId == null && isUserExplicitSubtitleSelection) {
        when {
            selectedSubtitleIndex < 0 && subtitleTracks.any { it.isSelected } ->
                ctrl.selectSubtitleTrack(-1)
            !subtitleListChanged &&
                selectedSubtitleIndex >= 0 &&
                engineSelectedSubtitle?.index != selectedSubtitleIndex &&
                subtitleTracks.any { it.index == selectedSubtitleIndex } ->
                ctrl.selectSubtitleTrack(selectedSubtitleIndex)
            subtitleListChanged && selectedSubtitleIndex >= 0 -> {
                val wanted = previousSubtitleTracks.firstOrNull { it.index == selectedSubtitleIndex }
                val match = wanted?.let {
                    explicitSubtitleIndexAfterListChange(
                        tracks = subtitleTracks,
                        wantedLanguage = it.language,
                        wantedName = it.label,
                        wantedForced = it.isForced,
                    )
                } ?: -1
                if (match >= 0) {
                    selectedSubtitleIndex = match
                    if (engineSelectedSubtitle?.index != match) ctrl.selectSubtitleTrack(match)
                } else {
                    selectedSubtitleIndex = engineSelectedSubtitle?.index ?: -1
                    isUserExplicitSubtitleSelection = false
                }
            }
        }
    }
    if (!playbackSnapshot.isLoading) {
        hasScannedTextTracksOnce = true
    }

    val subtitleStructure = subtitleTracksStructureKey(subtitleTracks)
    if (subtitleStructure != observedSubtitleTrackStructure) {
        val ownExternalAdd = subtitleListChangeIsOwnExternalAdd(
            previous = previousSubtitleTracks,
            current = subtitleTracks,
            addedUrl = selectedAddonSubtitleId?.takeIf { useCustomSubtitles },
        )
        observedSubtitleTrackStructure = subtitleStructure
        if (!isUserExplicitSubtitleSelection && !ownExternalAdd) preferredSubtitleSelectionApplied = false
    }

    if (selectedAudioIndex != previousAudioIndex && !isUserExplicitSubtitleSelection) {
        preferredSubtitleSelectionApplied = false
    }

    val subtitlePreferenceReady = restorePersistedTrackPreferenceIfNeeded()

    val preferredAudioTargets = preferredAudioLanguageTargets

    applyPreferredAudioTrack(preferredAudioTargets)

    if (subtitlePreferenceReady && (preferredAudioSelectionApplied || audioTracks.isEmpty())) {
        tryAutoSelectPreferredSubtitleFromAvailableTracks(preferredAudioTargets)
    }
}

private fun PlayerScreenRuntime.tryAutoSelectPreferredSubtitleFromAvailableTracks(
    preferredAudioTargets: List<String>,
) {
    if (isUserExplicitSubtitleSelection) return

    if (
        preferredSubtitleSelectionApplied &&
        preferredSubtitleDisablesSelection(playerSettingsUiState.preferredSubtitleLanguage)
    ) {
        if (
            selectedSubtitleIndex >= 0 ||
            subtitleTracks.any { it.isSelected } ||
            selectedAddonSubtitleId != null
        ) {
            disableAutomaticSubtitleSelection()
        }
        return
    }

    val preferredSubtitleLanguage = normalizeLanguageCode(
        playerSettingsUiState.preferredSubtitleLanguage,
    )
    val preferredSubtitleTargets = if (
        preferredSubtitleLanguage == SubtitleLanguageOption.NONE ||
        preferredSubtitleLanguage == SubtitleLanguageOption.FORCED
    ) {
        emptyList()
    } else {
        resolvePreferredSubtitleLanguageTargets(
            preferredSubtitleLanguage = playerSettingsUiState.preferredSubtitleLanguage,
            secondaryPreferredSubtitleLanguage = playerSettingsUiState.secondaryPreferredSubtitleLanguage,
            deviceLanguages = DeviceLanguagePreferences.preferredLanguageCodes(),
        )
    }
    val primaryTarget = preferredSubtitleTargets.firstOrNull()

    if (preferredSubtitleSelectionApplied) {
        val currentSelectedLang = when {
            selectedAddonSubtitle != null -> selectedAddonSubtitle?.language
            selectedSubtitleIndex >= 0 -> subtitleTracks.firstOrNull { it.index == selectedSubtitleIndex }?.language
            else -> null
        }
        val isPrimarySatisfied = primaryTarget != null && currentSelectedLang != null &&
            SubtitleLanguageMatching.matchesLanguageCode(currentSelectedLang, primaryTarget)
        val hasBetterAddonMatch = !isPrimarySatisfied && primaryTarget != null &&
            addonSubtitles.any { SubtitleLanguageMatching.matchesLanguageCode(it.language, primaryTarget) }
        val currentMatchesPreferred = preferredSubtitleTargets.any { target ->
            currentSelectedLang != null &&
                SubtitleLanguageMatching.matchesLanguageCode(currentSelectedLang, target)
        }
        val nothingSelected = selectedSubtitleIndex < 0 &&
            selectedAddonSubtitleId == null &&
            subtitleTracks.none { it.isSelected }
        if (!hasBetterAddonMatch && (currentMatchesPreferred || nothingSelected)) return
    }

    val selectedAudioTrack = audioTracks.firstOrNull { track -> track.index == selectedAudioIndex }
        ?: audioTracks.firstOrNull { it.isSelected }
    val selectionPlan = resolveSubtitleAutoSelectionPlan(
        selectedAudioTrack = selectedAudioTrack,
        preferredAudioTargets = preferredAudioTargets,
        preferredSubtitleTargets = preferredSubtitleTargets,
        useForcedSubtitles = subtitleStyle.useForcedSubtitles,
    )
    if (selectionPlan == null) {
        disableAutomaticSubtitleSelection()
        return
    }
    if (selectionPlan.targets.isEmpty()) {
        disableAutomaticSubtitleSelection()
        preferredSubtitleSelectionApplied = true
        return
    }

    val internalIndex = findPreferredSubtitleTrackIndex(
        tracks = subtitleTracks,
        targets = selectionPlan.targets,
        mode = selectionPlan.mode,
        selectedAudioTrack = selectedAudioTrack,
    )
    if (internalIndex >= 0 && hasScannedTextTracksOnce) {
        val matchedTrack = subtitleTracks[internalIndex]
        val trackVariant = SubtitleLanguageMatching.detectTrackLanguageVariant(
            language = matchedTrack.language,
            name = matchedTrack.label,
            trackId = matchedTrack.id,
        )
        val matchedTargetPosition = selectionPlan.targets.indexOfFirst { target ->
            val normalizedTarget = SubtitleLanguageMatching.normalizeLanguageCode(target)
            trackVariant == normalizedTarget ||
                SubtitleLanguageMatching.matchesLanguageCode(trackVariant, target)
        }
        if (matchedTargetPosition > 0 && isLoadingAddonSubtitles) {
            return
        }
        if (matchedTargetPosition > 0 && !isLoadingAddonSubtitles) {
            val primaryAddonMatch = addonSubtitles.firstOrNull { subtitle ->
                SubtitleLanguageMatching.matchesLanguageCode(subtitle.language, selectionPlan.targets.first())
            }
            if (primaryAddonMatch != null) {
                applyAddonSubtitle(primaryAddonMatch, autoSync = true)
                return
            }
        }
        preferredSubtitleSelectionApplied = true
        if (selectedSubtitleIndex != internalIndex || selectedAddonSubtitleId != null) {
            if (useCustomSubtitles) {
                playerController?.clearExternalSubtitleAndSelect(internalIndex)
            } else {
                playerController?.selectSubtitleTrack(internalIndex)
            }
            selectedSubtitleIndex = internalIndex
            selectedAddonSubtitleId = null
            useCustomSubtitles = false
        }
        return
    }

    if (selectionPlan.mode == SubtitleAutoSelectionMode.FORCED_ONLY) {
        val requiredForcedTarget = selectionPlan.targets.firstOrNull() ?: return
        if (!hasScannedTextTracksOnce) return
        if (isLoadingAddonSubtitles) {
            val currentTrack = subtitleTracks.firstOrNull { it.index == selectedSubtitleIndex }
            if (currentTrack != null && !currentTrack.isForced) {
                disableAutomaticSubtitleSelection()
            }
            return
        }
        val forcedAddonMatch = if (selectedAudioTrack != null) {
            addonSubtitles.firstOrNull { subtitle ->
                addonSubtitleIsForced(subtitle) &&
                    addonSubtitleMatchesLanguage(subtitle, requiredForcedTarget) &&
                    addonSubtitleMatchesSelectedAudioLanguage(subtitle, selectedAudioTrack)
            }
        } else {
            null
        }
        preferredSubtitleSelectionApplied = true
        if (forcedAddonMatch != null) {
            applyAddonSubtitle(forcedAddonMatch, autoSync = false)
        } else {
            disableAutomaticSubtitleSelection()
        }
        return
    }

    val selectedAddon = selectedAddonSubtitle
    val selectedAddonMatchesTarget = selectedAddon != null &&
        (!subtitleStyle.useForcedSubtitles || !addonSubtitleIsForced(selectedAddon)) &&
        selectionPlan.targets.any { target ->
            SubtitleLanguageMatching.matchesLanguageCode(selectedAddon.language, target)
        }
    if (selectedAddonMatchesTarget && selectedAddon != null) {
        val selectedMatchesPrimary = SubtitleLanguageMatching.matchesLanguageCode(
            selectedAddon.language,
            selectionPlan.targets.first(),
        )
        if (selectedMatchesPrimary) {
            preferredSubtitleSelectionApplied = true
            maybeAutoSyncPreferredSubtitleAtStart(selectedAddon) // AutoSync hook
            return
        }
    }

    if (!hasScannedTextTracksOnce) return
    if (playbackSnapshot.isLoading) return

    val addonMatch = selectionPlan.targets.firstNotNullOfOrNull { target ->
        addonSubtitles.firstOrNull { subtitle ->
            (!subtitleStyle.useForcedSubtitles || !addonSubtitleIsForced(subtitle)) &&
                SubtitleLanguageMatching.matchesLanguageCode(subtitle.language, target)
        }
    }
    if (addonMatch != null) {
        applyAddonSubtitle(addonMatch, autoSync = true)
    } else if (!preferredSubtitleSelectionApplied) {
        disableAutomaticSubtitleSelection()
        preferredSubtitleSelectionApplied = true
    }
}

private fun PlayerScreenRuntime.applyAddonSubtitle(subtitle: AddonSubtitle, autoSync: Boolean) {
    val alreadyAdded = useCustomSubtitles && subtitle.matchesSelection(selectedAddonSubtitleId)
    selectedAddonSubtitleId = subtitle.selectionKey
    selectedSubtitleIndex = -1
    useCustomSubtitles = true
    preferredSubtitleSelectionApplied = true
    if (alreadyAdded) return
    if (autoSync && maybeAutoSyncPreferredSubtitleAtStart(subtitle)) return // AutoSync hook
    playerController?.setSubtitleUri(subtitle.url)
}

private fun PlayerScreenRuntime.disableAutomaticSubtitleSelection() {
    val engineHasSubtitle = subtitleTracks.any { it.isSelected }
    if (selectedSubtitleIndex != -1 || engineHasSubtitle || !subtitleOffRequested) {
        playerController?.selectSubtitleTrack(-1)
        subtitleOffRequested = true
    }
    selectedSubtitleIndex = -1
    selectedAddonSubtitleId = null
    useCustomSubtitles = false
}
