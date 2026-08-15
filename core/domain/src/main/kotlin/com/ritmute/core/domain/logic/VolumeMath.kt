package com.ritmute.core.domain.logic

import kotlin.math.roundToInt

/**
 * Conversion between the portable percentage the user configures and the native step
 * index a device actually accepts.
 *
 * The naive formula `steps = round(pct / 100 * max)` is wrong in two ways. It ignores
 * `getStreamMinVolume()` (API 28+), which is **not always 0** — it is typically 1 for
 * `STREAM_VOICE_CALL` and several OEMs set non-zero minimums elsewhere. And it is lossy:
 * on a 7-step device each step is ~14.3 %, so 30 % is stored, applied as step 2, and read
 * back as 28.6 %.
 *
 * That second point matters more than it looks. If the watchdog compared **percentages**
 * to decide whether the state is correct, it would see 28.6 ≠ 30 on every pass, re-apply,
 * read 28.6 again, and loop forever — burning battery and filling the very activity log
 * that is supposed to be readable.
 *
 * Hence the rule, enforced by contract: **the watchdog compares native indices, never
 * percentages.**
 */
object VolumeMath {

    /**
     * @param percent 0..100
     * @param minSteps device minimum for the stream (`getStreamMinVolume`, 0 below API 28)
     * @param maxSteps device maximum for the stream (`getStreamMaxVolume`)
     */
    fun percentToIndex(percent: Int, minSteps: Int, maxSteps: Int): Int {
        require(percent in 0..100) { "percent must be 0..100, was $percent" }
        require(maxSteps >= minSteps) { "maxSteps ($maxSteps) < minSteps ($minSteps)" }
        val span = maxSteps - minSteps
        if (span == 0) return minSteps
        val index = minSteps + (percent / 100.0 * span).roundToInt()
        return index.coerceIn(minSteps, maxSteps)
    }

    /** Inverse of [percentToIndex], for showing the device's real state in the UI. */
    fun indexToPercent(index: Int, minSteps: Int, maxSteps: Int): Int {
        require(maxSteps >= minSteps) { "maxSteps ($maxSteps) < minSteps ($minSteps)" }
        val span = maxSteps - minSteps
        if (span == 0) return 0
        val clamped = index.coerceIn(minSteps, maxSteps)
        return ((clamped - minSteps) * 100.0 / span).roundToInt().coerceIn(0, 100)
    }

    /**
     * Intermediate indices for a linear ramp, excluding the starting point and always
     * ending exactly on [toIndex].
     *
     * Steps are computed over the device's real step count rather than over seconds: a
     * 15-second ramp on a 7-step device is 7 changes, not 15.
     */
    fun rampIndices(fromIndex: Int, toIndex: Int): List<Int> {
        if (fromIndex == toIndex) return emptyList()
        val step = if (toIndex > fromIndex) 1 else -1
        return generateSequence(fromIndex + step) { previous ->
            if (previous == toIndex) null else previous + step
        }.toList()
    }
}
