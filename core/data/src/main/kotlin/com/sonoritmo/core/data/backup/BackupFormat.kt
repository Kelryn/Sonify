package com.sonoritmo.core.data.backup

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * Format constants, the JSON configuration and the checksum rules.
 *
 * ## Compatibility policy
 *
 * | Change | Bumps [CURRENT_VERSION]? | Behaviour |
 * |---|---|---|
 * | New optional field with a default | no | Old readers ignore it, new ones default it |
 * | New value in an enum | no | Old readers fall back and report a correction |
 * | Field renamed or removed | **yes** | Needs a JSON upgrade step |
 * | Meaning or unit of a field changes | **yes** | Same, without exception |
 * | Structure changes (nesting) | **yes** | Same |
 *
 * Two hard rules follow from it:
 *
 *  1. A file with a **higher** version is refused outright, never parsed hopefully. A
 *     reader that guesses at a format it does not know will eventually guess wrong and
 *     silently corrupt a configuration.
 *  2. A file with a **lower** version is always accepted, by transforming the raw
 *     `JsonObject` up to the current shape *before* decoding. Old DTO classes are never
 *     kept around; there is exactly one set of DTOs, and they describe the present.
 */
object BackupFormat {

    /** Monotonic, and independent of both the database version and the app version. */
    const val CURRENT_VERSION = 1

    /** The oldest file this build can read. */
    const val MINIMUM_SUPPORTED_VERSION = 1

    /**
     * Hard ceiling on the bytes read from a user-picked file.
     *
     * Applied *before* parsing, because the attack is the parse itself: a document picker
     * hands over a `Uri` from an arbitrary app, and `readBytes()` on it is an out-of-memory
     * crash waiting to be handed to us. A real configuration is a few kilobytes.
     */
    const val MAX_FILE_BYTES = 5L * 1024 * 1024

    /**
     * kotlinx.serialization's own default indent, and the only value it accepts when
     * pretty printing is disabled.
     */
    private const val DEFAULT_JSON_INDENT = "    "

    // prettyPrintIndent and explicitNulls are still marked experimental. Both are load
    // bearing here: RF-36 asks for a file a person can read, and a missing key means
    // something different from an explicit null to that reader.
    @OptIn(ExperimentalSerializationApi::class)
    val json: Json = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        // Every field is written, nulls included: RF-36 asks for a readable file, and a
        // missing key and an explicit null mean different things to a human reader.
        encodeDefaults = true
        explicitNulls = true
        // Forward compatibility for free: a field added in a later version does not stop
        // this build from reading the file.
        ignoreUnknownKeys = true
        // The opposites of everything above: a malformed file must fail loudly rather
        // than be quietly repaired into something the user did not write.
        isLenient = false
        coerceInputValues = false
        allowStructuredMapKeys = false
    }

    /** Compact, key-sorted output, used only to compute the checksum. */
    // The indent has to be put back to its default as well: this configuration inherits it
    // from [json], and kotlinx.serialization rejects a non-default indent when pretty
    // printing is off — from the object initialiser, so the whole object fails to load.
    @OptIn(ExperimentalSerializationApi::class)
    private val canonicalJson: Json = Json(json) {
        prettyPrint = false
        prettyPrintIndent = DEFAULT_JSON_INDENT
    }

    /**
     * SHA-256 over the root object with the `integrity` block removed and the keys
     * sorted, so that two exports of the same configuration hash identically regardless
     * of field order or formatting.
     */
    fun checksum(root: JsonObject): String {
        val withoutIntegrity = JsonObject(root.filterKeys { it != "integrity" })
        val canonical = canonicalJson.encodeToString(
            JsonElement.serializer(),
            canonicalize(withoutIntegrity),
        )
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray())
        return digest.joinToString("") { byte -> "%02x".format(Locale.ROOT, byte) }
    }

    /** Recursively sorts object keys; array order is data and is left alone. */
    private fun canonicalize(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(
            element.entries
                .sortedBy { it.key }
                .associate { it.key to canonicalize(it.value) },
        )
        is JsonArray -> JsonArray(element.map { canonicalize(it) })
        is JsonPrimitive -> element
    }

    // ── Wall-clock helpers ───────────────────────────────────────────────────

    /**
     * Minute of day to `"HH:mm"`.
     *
     * [Locale.ROOT] is not decoration: with the default locale this formats as
     * Arabic-Indic digits on an Arabic device, and the resulting file is unreadable by
     * every other installation — a corruption that only ever reproduces on one user's
     * phone.
     */
    fun formatStartTime(minuteOfDay: Int): String =
        String.format(Locale.ROOT, "%02d:%02d", minuteOfDay / 60, minuteOfDay % 60)

    /** `"HH:mm"` to minute of day, or null if it is not a valid wall-clock time. */
    fun parseStartTime(raw: String): Int? {
        val parts = raw.split(':')
        if (parts.size != 2) return null
        val hours = parts[0].toIntOrNull() ?: return null
        val minutes = parts[1].toIntOrNull() ?: return null
        if (hours !in 0..23 || minutes !in 0..59) return null
        return hours * 60 + minutes
    }

    fun formatInstant(instant: Instant): String = instant.toString()

    fun parseInstant(raw: String): Instant? = try {
        Instant.parse(raw)
    } catch (_: DateTimeParseException) {
        null
    }

    // ── Colour ───────────────────────────────────────────────────────────────

    /** Signed ARGB `Int` to the unsigned number written in the file. */
    fun colorToUnsigned(colorSeed: Int): Long = colorSeed.toLong() and 0xFFFFFFFFL

    /** Back again. Values outside 32 bits are truncated rather than rejected. */
    fun colorFromUnsigned(value: Long): Int = (value and 0xFFFFFFFFL).toInt()
}
