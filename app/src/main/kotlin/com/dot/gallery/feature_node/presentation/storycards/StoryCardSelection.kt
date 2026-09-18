/*
 * SPDX-FileCopyrightText: 2023-2026 alzimerahmed
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.feature_node.presentation.storycards

import kotlin.random.Random

/**
 * Pure-Kotlin selection helpers shared by the StoryCards builders.
 *
 * Intentionally Android-free (same pattern as `MemoriesEngine`, KTD6): the
 * ViewModel maps media/metadata into [Candidate] and these helpers stay
 * JVM-testable. Consumed by cover picking (U3), day-seeded rotation (U6) and
 * the highlights selector (U7).
 */
object StoryCardSelection {

    /**
     * One selection candidate: identity + recency + the on-device curation
     * signals available without network or engagement tracking (KTD6).
     *
     * Flags are captured ViewModel-side:
     * - [isFavorite] — `Media.favorite == 1` or membership in the favorites flow
     * - [isCategorized] — membership in the `media_category` table
     * - [isFlagged] — `MediaMetadata.isRelevant` (night / panorama /
     *   photosphere / long-exposure / motion photo)
     * - [isScreenshot] — [isScreenshotLike] label/path heuristic
     * - [isPhoto] — `!mimeType.startsWith("video/")`
     */
    data class Candidate(
        val id: Long,
        /** Epoch seconds — `Media.definedTimestamp` semantics. */
        val timestampSec: Long,
        val isFavorite: Boolean = false,
        val isCategorized: Boolean = false,
        val isFlagged: Boolean = false,
        val isScreenshot: Boolean = false,
        val isPhoto: Boolean = true,
    )

    /**
     * Curation score: favorites boosted hardest, metadata-flagged and
     * categorized items next, photos over videos, screenshots deprioritized.
     * A heuristic — there is no on-device aesthetic score (KTD6).
     */
    internal fun score(candidate: Candidate): Int =
        (if (candidate.isFavorite) FAVORITE_BONUS else 0) +
            (if (candidate.isFlagged) FLAGGED_BONUS else 0) +
            (if (candidate.isCategorized) CATEGORIZED_BONUS else 0) +
            (if (candidate.isPhoto) PHOTO_BONUS else 0) -
            (if (candidate.isScreenshot) SCREENSHOT_PENALTY else 0)

    /**
     * Index of the strongest cover candidate, or -1 when [candidates] is empty.
     *
     * The order is total — (score desc, timestampSec desc, id desc) — so the
     * pick is deterministic and, given unique ids, independent of input order.
     * When no item carries any signal the newest item wins, preserving the
     * previous newest/first pick behavior.
     */
    fun coverIndex(candidates: List<Candidate>): Int {
        var best = -1
        for (i in candidates.indices) {
            if (best == -1 || isBetter(candidates[i], candidates[best])) best = i
        }
        return best
    }

    /**
     * Strongest cover item from [items], or null when empty. [toCandidate]
     * maps an item to its signals — see [Candidate] for flag sources.
     */
    fun <T> cover(items: List<T>, toCandidate: (T) -> Candidate): T? =
        items.getOrNull(coverIndex(items.map(toCandidate)))

    private fun isBetter(a: Candidate, b: Candidate): Boolean {
        val scoreDiff = score(a) - score(b)
        if (scoreDiff != 0) return scoreDiff > 0
        if (a.timestampSec != b.timestampSec) return a.timestampSec > b.timestampSec
        return a.id > b.id
    }

    /**
     * KTD3/R6 freshness rotation: deterministic, calendar-seeded within-type
     * selection. [seed] mixes day-of-epoch with the card-type identity
     * (computed ViewModel-side), so each day re-picks which eligible cards
     * fill a type's slots while type order and count stay fixed.
     *
     * Returns [items] unchanged when it fits within [count] — pools at or
     * under the cap render in builder order regardless of seed. Otherwise a
     * seeded shuffle re-picks which [count] items surface; identical
     * (items, seed, count) always yields the identical pick.
     */
    fun <T> rotatePick(items: List<T>, seed: Long, count: Int): List<T> =
        if (items.size <= count) items else items.shuffled(Random(seed)).take(count)

    /**
     * Screenshot heuristic identical to `MemoriesEngine.isScreenshot` —
     * label/path string match, no I/O. Lives here so the cover pick and the
     * U7 highlights selector deprioritize screenshots consistently.
     */
    fun isScreenshotLike(label: String?, path: String?, relativePath: String?): Boolean {
        val haystack = buildString {
            label?.let { append(it).append(' ') }
            path?.let { append(it).append(' ') }
            relativePath?.let { append(it) }
        }
        return haystack.contains("screenshot", ignoreCase = true) ||
            haystack.contains("screencap", ignoreCase = true)
    }

    private const val FAVORITE_BONUS = 4
    private const val FLAGGED_BONUS = 2
    private const val CATEGORIZED_BONUS = 1
    private const val PHOTO_BONUS = 1
    private const val SCREENSHOT_PENALTY = 3
}
