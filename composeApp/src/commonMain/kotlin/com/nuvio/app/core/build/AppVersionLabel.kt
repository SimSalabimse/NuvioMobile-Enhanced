package com.nuvio.app.core.build

internal fun appVersionRevisionSuffix(gitRevision: String, gitDirty: Boolean): String {
    val revision = normalizedGitRevision(gitRevision) ?: return ""
    val label = if (gitDirty) "$revision*" else revision
    return " · $label"
}

internal fun appVersionDetail(
    versionName: String,
    versionCode: Int,
    gitRevision: String = "",
    gitDirty: Boolean = false,
): String = "$versionName ($versionCode)${appVersionRevisionSuffix(gitRevision, gitDirty)}"

private fun normalizedGitRevision(gitRevision: String): String? =
    gitRevision.trim().lowercase().takeIf { it.matches(Regex("[0-9a-f]{4,40}")) }
