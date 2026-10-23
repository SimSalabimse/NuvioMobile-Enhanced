package com.nuvio.app.features.details.components

import com.nuvio.app.core.ui.matchesSearch
import com.nuvio.app.features.details.MetaPerson

/** Cast whose actor name or character/role contains [query] (case and accents ignored). */
internal fun filterCast(cast: List<IndexedValue<MetaPerson>>, query: String): List<IndexedValue<MetaPerson>> {
    if (query.isBlank()) return cast
    return cast.filter { (_, person) -> matchesSearch(person.name, query) || matchesSearch(person.role, query) }
}
