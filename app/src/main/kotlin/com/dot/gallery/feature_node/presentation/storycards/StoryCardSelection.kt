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

    private fun isBetter(a: Candidate, b: Candidate): Boolean =
        HIGHLIGHT_ORDER.compare(a, b) < 0

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

    /**
     * U7/R8 highlights selection: the top-scored candidates inside a
     * trailing window of [windowDays] days ending at [nowEpochSec]
     * (epoch seconds — `Media.definedTimestamp` semantics).
     *
     * Returns an empty list when fewer than [minItems] candidates qualify —
     * a "2 photos from Tuesday" card is noise (AE4). Otherwise up to
     * [maxItems] candidates ordered by the same total order as the cover
     * pick (score desc, timestampSec desc, id desc), so the result is
     * deterministic and independent of input order.
     *
     * Membership spreads across days (KTD6): each epoch-day contributes its
     * best-scored candidate before any day takes a second slot, then the
     * remaining slots fill in global score order.
     */
    fun selectHighlights(
        candidates: List<Candidate>,
        nowEpochSec: Long,
        windowDays: Int,
        minItems: Int = MIN_HIGHLIGHT_ITEMS,
        maxItems: Int = MAX_HIGHLIGHT_ITEMS,
    ): List<Candidate> {
        val windowStartSec = nowEpochSec - windowDays * SECONDS_PER_DAY
        val sorted = candidates
            .filter { it.timestampSec > windowStartSec }
            .sortedWith(HIGHLIGHT_ORDER)
        if (sorted.size < minItems) return emptyList()

        val picked = ArrayList<Candidate>(minOf(maxItems, sorted.size))
        val pickedIds = HashSet<Long>()
        val seenDays = HashSet<Long>()
        // First pass: one candidate per epoch-day for day diversity.
        for (candidate in sorted) {
            if (picked.size >= maxItems) break
            if (seenDays.add(Math.floorDiv(candidate.timestampSec, SECONDS_PER_DAY))) {
                picked += candidate
                pickedIds += candidate.id
            }
        }
        // Second pass: fill remaining slots in global score order.
        for (candidate in sorted) {
            if (picked.size >= maxItems) break
            if (pickedIds.add(candidate.id)) picked += candidate
        }
        return picked.sortedWith(HIGHLIGHT_ORDER)
    }

    /** Total candidate order shared by the cover pick and highlights. */
    private val HIGHLIGHT_ORDER: Comparator<Candidate> =
        compareByDescending<Candidate> { score(it) }
            .thenByDescending { it.timestampSec }
            .thenByDescending { it.id }

    /** Minimum scored candidates a highlights window needs to emit a card (AE4). */
    internal const val MIN_HIGHLIGHT_ITEMS = 3

    /** Per-window mediaList bound — matches the other builders' `take(20)`. */
    internal const val MAX_HIGHLIGHT_ITEMS = 20

    private const val SECONDS_PER_DAY = 86_400L

    private const val FAVORITE_BONUS = 4
    private const val FLAGGED_BONUS = 2
    private const val CATEGORIZED_BONUS = 1
    private const val PHOTO_BONUS = 1
    private const val SCREENSHOT_PENALTY = 3
}
