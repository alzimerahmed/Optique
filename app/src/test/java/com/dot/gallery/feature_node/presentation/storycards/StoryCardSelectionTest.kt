/*
 * SPDX-FileCopyrightText: 2023-2026 alzimerahmed
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.feature_node.presentation.storycards

import com.dot.gallery.feature_node.presentation.storycards.StoryCardSelection.Candidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the StoryCards cover pick — [StoryCardSelection] has no
 * Android dependencies, so no Robolectric is needed.
 */
class StoryCardSelectionTest {

    private fun candidate(
        id: Long,
        timestampSec: Long = id,
        isFavorite: Boolean = false,
        isCategorized: Boolean = false,
        isFlagged: Boolean = false,
        isScreenshot: Boolean = false,
        isPhoto: Boolean = true,
    ) = Candidate(
        id = id,
        timestampSec = timestampSec,
        isFavorite = isFavorite,
        isCategorized = isCategorized,
        isFlagged = isFlagged,
        isScreenshot = isScreenshot,
        isPhoto = isPhoto,
    )

    @Test
    fun `empty input yields -1 index and null cover`() {
        assertEquals(-1, StoryCardSelection.coverIndex(emptyList()))
        assertNull(StoryCardSelection.cover(emptyList<Long>()) { candidate(it) })
    }

    @Test
    fun `falls back to newest when no signals exist`() {
        val candidates = listOf(
            candidate(id = 1, timestampSec = 100),
            candidate(id = 2, timestampSec = 300),
            candidate(id = 3, timestampSec = 200),
        )

        assertEquals(1, StoryCardSelection.coverIndex(candidates))
    }

    @Test
    fun `favorite beats newer non-favorite`() {
        val candidates = listOf(
            candidate(id = 1, timestampSec = 300),
            candidate(id = 2, timestampSec = 100, isFavorite = true),
            candidate(id = 3, timestampSec = 200),
        )

        assertEquals(1, StoryCardSelection.coverIndex(candidates))
    }

    @Test
    fun `metadata-flagged beats newer unflagged`() {
        val candidates = listOf(
            candidate(id = 1, timestampSec = 300),
            candidate(id = 2, timestampSec = 100, isFlagged = true),
        )

        assertEquals(1, StoryCardSelection.coverIndex(candidates))
    }

    @Test
    fun `categorized beats newer uncategorized`() {
        val candidates = listOf(
            candidate(id = 1, timestampSec = 300),
            candidate(id = 2, timestampSec = 100, isCategorized = true),
        )

        assertEquals(1, StoryCardSelection.coverIndex(candidates))
    }

    @Test
    fun `favorite beats flagged and categorized`() {
        val candidates = listOf(
            candidate(id = 1, timestampSec = 300, isFlagged = true, isCategorized = true),
            candidate(id = 2, timestampSec = 100, isFavorite = true),
        )

        assertEquals(1, StoryCardSelection.coverIndex(candidates))
    }

    @Test
    fun `screenshot is deprioritized below a plain photo`() {
        val candidates = listOf(
            candidate(id = 1, timestampSec = 300, isScreenshot = true),
            candidate(id = 2, timestampSec = 100),
        )

        assertEquals(1, StoryCardSelection.coverIndex(candidates))
    }

    @Test
    fun `photo preferred over video when otherwise equal`() {
        val candidates = listOf(
            candidate(id = 1, timestampSec = 300, isPhoto = false),
            candidate(id = 2, timestampSec = 200),
        )

        assertEquals(1, StoryCardSelection.coverIndex(candidates))
    }

    @Test
    fun `equal scores resolve to newer then higher id`() {
        val byTimestamp = listOf(
            candidate(id = 1, timestampSec = 100, isFavorite = true),
            candidate(id = 2, timestampSec = 200, isFavorite = true),
        )
        assertEquals(1, StoryCardSelection.coverIndex(byTimestamp))

        val byId = listOf(
            candidate(id = 1, timestampSec = 100, isFavorite = true),
            candidate(id = 2, timestampSec = 100, isFavorite = true),
        )
        assertEquals(1, StoryCardSelection.coverIndex(byId))
    }

    @Test
    fun `pick is deterministic and independent of input order`() {
        val items = listOf(
            candidate(id = 1, timestampSec = 100),
            candidate(id = 2, timestampSec = 50, isFavorite = true),
            candidate(id = 3, timestampSec = 300),
            candidate(id = 4, timestampSec = 250, isFlagged = true),
        )

        val first = StoryCardSelection.cover(items) { it }
        val reversed = StoryCardSelection.cover(items.asReversed()) { it }
        val shuffled = StoryCardSelection.cover(items.shuffled()) { it }

        assertSame(first, reversed)
        assertSame(first, shuffled)
        assertEquals(2L, first?.id)
    }

    @Test
    fun `cover returns the item at the winning index`() {
        val items = listOf("a", "b", "c")
        val picked = StoryCardSelection.cover(items) { value ->
            candidate(
                id = value[0].code.toLong(),
                timestampSec = 0,
                isFavorite = value == "b",
            )
        }
        assertEquals("b", picked)
    }

    @Test
    fun `isScreenshotLike matches label path and relativePath case-insensitively`() {
        assertTrue(StoryCardSelection.isScreenshotLike("Screenshot_2026.png", null, null))
        assertTrue(StoryCardSelection.isScreenshotLike(null, "/DCIM/Screenshots/1.png", null))
        assertTrue(StoryCardSelection.isScreenshotLike(null, null, "Pictures/Screencaps"))
        assertFalse(StoryCardSelection.isScreenshotLike("IMG_1.jpg", "/DCIM/Camera", "DCIM"))
    }

    // ---------- U6: day-seeded rotation (KTD3) ----------

    @Test
    fun `rotatePick returns items unchanged when at or under count`() {
        val items = listOf(1, 2, 3)

        assertEquals(items, StoryCardSelection.rotatePick(items, seed = 1L, count = 3))
        assertEquals(items, StoryCardSelection.rotatePick(items, seed = 99L, count = 5))
        assertSame(items, StoryCardSelection.rotatePick(items, seed = 7L, count = 10))
    }

    @Test
    fun `rotatePick picks exactly count items from an over-cap pool`() {
        val items = (1L..10L).toList()

        val picked = StoryCardSelection.rotatePick(items, seed = 42L, count = 4)

        assertEquals(4, picked.size)
        assertEquals(4, picked.distinct().size)
        assertTrue(items.containsAll(picked))
    }

    @Test
    fun `rotatePick is deterministic for the same seed`() {
        val items = (1L..10L).toList()

        val first = StoryCardSelection.rotatePick(items, seed = 42L, count = 4)
        val second = StoryCardSelection.rotatePick(items, seed = 42L, count = 4)

        assertEquals(first, second)
    }

    @Test
    fun `rotatePick varies the pick across seeds for an over-cap pool`() {
        val items = (1L..10L).toList()

        val picks = (1L..20L).mapTo(HashSet()) { seed ->
            StoryCardSelection.rotatePick(items, seed = seed, count = 4).toSet()
        }

        assertTrue("expected seed variation in picks, got $picks", picks.size > 1)
        // Every pick stays a subset of the eligible pool — rotation re-picks
        // which cards fill the slots, never invents new ones.
        assertTrue(picks.all { items.containsAll(it) })
    }

    @Test
    fun `rotatePick with zero count picks nothing`() {
        assertTrue(StoryCardSelection.rotatePick(listOf(1, 2, 3), seed = 1L, count = 0).isEmpty())
    }
}
