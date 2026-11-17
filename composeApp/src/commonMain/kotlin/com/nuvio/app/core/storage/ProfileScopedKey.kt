package com.nuvio.app.core.storage

import com.nuvio.app.core.automation.isolateStorageKey
import com.nuvio.app.features.profiles.ProfileRepository


object ProfileScopedKey {
    fun of(baseKey: String): String = of(baseKey, ProfileRepository.activeProfileId)
    fun of(baseKey: String, profileId: Int): String = isolateStorageKey("${baseKey}_$profileId")
}
