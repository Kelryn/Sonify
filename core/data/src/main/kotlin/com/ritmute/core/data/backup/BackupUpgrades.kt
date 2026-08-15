package com.ritmute.core.data.backup

import kotlinx.serialization.json.JsonObject

/**
 * Raises an older backup to the current shape **before** it is decoded.
 *
 * Working on the raw [JsonObject] rather than on versioned DTO classes is a deliberate
 * choice: there is exactly one set of DTOs and they always describe the present. The
 * alternative — keeping `ProfileDtoV1`, `ProfileDtoV2`, … around forever — is how a
 * serialisation layer becomes the largest and least testable part of a codebase.
 *
 * The chain is empty at version 1. That is not a placeholder: it is the mechanism, in
 * place and exercised by the version checks in [BackupImporter], so that the first real
 * upgrade is one entry in [chain] and a test, not a redesign.
 */
internal object BackupUpgrades {

    /** Ordered, contiguous, each step consuming the version the previous one produced. */
    private val chain: List<JsonUpgrade> = emptyList()

    /**
     * @return the upgraded root, or null when some version in the range has no step —
     *   which is a programming error, not bad input, and must fail loudly rather than
     *   decode a document of unknown shape.
     */
    fun upgrade(root: JsonObject, fromVersion: Int): JsonObject? {
        var current = root
        var version = fromVersion
        while (version < BackupFormat.CURRENT_VERSION) {
            val step = chain.firstOrNull { it.from == version } ?: return null
            current = step.apply(current)
            version = step.to
        }
        return current
    }

    /** True when every hop from the minimum supported version to the current one exists. */
    fun isChainComplete(): Boolean =
        (BackupFormat.MINIMUM_SUPPORTED_VERSION until BackupFormat.CURRENT_VERSION)
            .all { version -> chain.any { it.from == version && it.to == version + 1 } }
}

internal interface JsonUpgrade {
    val from: Int
    val to: Int

    fun apply(root: JsonObject): JsonObject
}
