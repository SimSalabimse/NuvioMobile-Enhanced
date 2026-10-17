package com.nuvio.app.features.details.components

import com.nuvio.app.features.details.MetaPerson

private const val ACCENTED =
    "àáâãäåāăąÀÁÂÃÄÅĀĂĄçćĉċčÇĆĈĊČďđĎĐèéêëēĕėęěÈÉÊËĒĔĖĘĚĝğġģĜĞĠĢĥħĤĦìíîïĩīĭįıÌÍÎÏĨĪĬĮİĵĴķĶĺļľŀłĹĻĽĿŁñńņňŉÑŃŅŇòóôõöøōŏőÒÓÔÕÖØŌŎŐŕŗřŔŖŘśŝşšșŚŜŞŠȘţťŧțŢŤŦȚùúûüũūŭůűųÙÚÛÜŨŪŬŮŰŲŵŴýÿŷÝŸŶźżžŹŻŽ"
private const val PLAIN =
    "aaaaaaaaaAAAAAAAAAcccccCCCCCddDDeeeeeeeeeEEEEEEEEEggggGGGGhhHHiiiiiiiiiIIIIIIIIIjJkKlllllLLLLLnnnnnNNNNoooooooooOOOOOOOOOrrrRRRsssssSSSSSttttTTTTuuuuuuuuuuUUUUUUUUUUwWyyyYYYzzzZZZ"

private val foldMap: Map<Char, Char> = ACCENTED.zip(PLAIN).toMap()

/**
 * Lower case without accents or other marks, so "Zoe" finds "Zoë" and "jose" finds "José".
 * Combining marks (decomposed text) are dropped; a few letters become two (ß → ss, æ → ae).
 */
internal fun foldForSearch(value: String): String = buildString(value.length) {
    for (char in value) {
        if (char in '\u0300'..'\u036F') continue
        when (char) {
            'ß' -> append("ss")
            'æ', 'Æ' -> append("ae")
            'œ', 'Œ' -> append("oe")
            'þ', 'Þ' -> append("th")
            else -> append((foldMap[char] ?: char).lowercaseChar())
        }
    }
}

/** Cast whose actor name or character/role contains [query] (case and accents ignored). */
internal fun filterCast(cast: List<IndexedValue<MetaPerson>>, query: String): List<IndexedValue<MetaPerson>> {
    val needle = foldForSearch(query.trim())
    if (needle.isEmpty()) return cast
    return cast.filter { (_, person) ->
        foldForSearch(person.name).contains(needle) || foldForSearch(person.role.orEmpty()).contains(needle)
    }
}
