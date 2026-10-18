package com.nuvio.app.core.ui

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction

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

/** [text] contains [query], ignoring case and accents; a blank query matches everything. */
internal fun matchesSearch(text: String?, query: String): Boolean {
    val needle = foldForSearch(query.trim())
    return needle.isEmpty() || (text != null && foldForSearch(text).contains(needle))
}

/** One-line search field with a search icon and a clear button. */
@Composable
internal fun NuvioSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier,
        singleLine = true,
        placeholder = { Text(placeholder) },
        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
        trailingIcon = if (query.isNotEmpty()) {
            {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Rounded.Close, contentDescription = "Clear")
                }
            }
        } else {
            null
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        textStyle = MaterialTheme.typography.bodyMedium,
        shape = MaterialTheme.shapes.medium,
    )
}
