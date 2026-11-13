package com.nuvio.app.features.analyzedsync.json

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Strict conversion. A non-integer number rejects the whole value. */
internal fun JsonElement.toSyncJson(): JsonValue? = when (this) {
    is JsonNull -> JsonValue.Null
    is JsonPrimitive -> primitiveToSyncJson()
    is JsonArray -> {
        val items = ArrayList<JsonValue>(size)
        for (item in this) items += item.toSyncJson() ?: return null
        JsonValue.Arr(items)
    }
    is JsonObject -> {
        val fields = linkedMapOf<String, JsonValue>()
        for ((key, value) in this) fields[key] = value.toSyncJson() ?: return null
        JsonValue.Obj(fields)
    }
    else -> null
}

private fun JsonPrimitive.primitiveToSyncJson(): JsonValue? {
    if (isString) return JsonValue.Str(content)
    return when (content) {
        "true" -> JsonValue.Bool(true)
        "false" -> JsonValue.Bool(false)
        else -> JsonValue.Num(parseSafeInteger(content) ?: return null)
    }
}
