package com.nuvio.app.features.analyzedsync.json

import com.nuvio.app.features.analyzedsync.SYNC_MAX_SAFE_INTEGER

/** JSON value used for strict records and for canonical plaintext. */
internal sealed interface JsonValue {
    data object Null : JsonValue
    data class Bool(val value: Boolean) : JsonValue
    data class Num(val value: Long) : JsonValue
    data class Str(val value: String) : JsonValue
    data class Arr(val items: List<JsonValue>) : JsonValue
    data class Obj(val fields: Map<String, JsonValue>) : JsonValue
}

internal fun jNull(): JsonValue = JsonValue.Null
internal fun jBool(value: Boolean): JsonValue = JsonValue.Bool(value)
internal fun jNum(value: Long): JsonValue = JsonValue.Num(value)
internal fun jStr(value: String): JsonValue = JsonValue.Str(value)
internal fun jObj(vararg pairs: Pair<String, JsonValue>): JsonValue.Obj = JsonValue.Obj(linkedMapOf(*pairs))

internal fun JsonValue.objFields(): Map<String, JsonValue>? = (this as? JsonValue.Obj)?.fields

/** Canonical JSON. Object keys are sorted by UTF-16 code unit, the same order as Kotlin [String.compareTo]. */
internal fun canonicalJson(value: JsonValue): String = when (value) {
    JsonValue.Null -> "null"
    is JsonValue.Bool -> if (value.value) "true" else "false"
    is JsonValue.Num -> {
        require(value.value in -SYNC_MAX_SAFE_INTEGER..SYNC_MAX_SAFE_INTEGER) { "canonical JSON allows only safe integers" }
        value.value.toString()
    }
    is JsonValue.Str -> jsonString(value.value)
    is JsonValue.Arr -> value.items.joinToString(separator = ",", prefix = "[", postfix = "]") { canonicalJson(it) }
    is JsonValue.Obj -> {
        val keys = value.fields.keys.sorted()
        keys.joinToString(separator = ",", prefix = "{", postfix = "}") { key ->
            jsonString(key) + ":" + canonicalJson(value.fields.getValue(key))
        }
    }
}

internal fun jsonString(value: String): String {
    val out = StringBuilder(value.length + 2)
    out.append('"')
    for (ch in value) {
        when (ch) {
            '"' -> out.append("\\\"")
            '\\' -> out.append("\\\\")
            '\b' -> out.append("\\b")
            '\u000C' -> out.append("\\f")
            '\n' -> out.append("\\n")
            '\r' -> out.append("\\r")
            '\t' -> out.append("\\t")
            else -> if (ch.code < 0x20) {
                out.append("\\u")
                out.append(ch.code.toString(16).padStart(4, '0'))
            } else {
                out.append(ch)
            }
        }
    }
    out.append('"')
    return out.toString()
}

/** A JSON number that is not a safe integer becomes null so the record is rejected. */
internal fun parseSafeInteger(text: String): Long? {
    if (text.isEmpty()) return null
    val negative = text.startsWith("-")
    val digits = if (negative) text.substring(1) else text
    if (digits.isEmpty() || digits.any { it !in '0'..'9' }) return null
    if (digits.length > 1 && digits[0] == '0') return null
    if (digits.length > 16) return null
    val magnitude = digits.toLongOrNull() ?: return null
    val signed = if (negative) -magnitude else magnitude
    if (signed !in -SYNC_MAX_SAFE_INTEGER..SYNC_MAX_SAFE_INTEGER) return null
    return signed
}
