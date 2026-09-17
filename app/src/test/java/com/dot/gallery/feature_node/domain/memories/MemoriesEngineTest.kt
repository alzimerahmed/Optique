/*
 * SPDX-FileCopyrightText: 2023-2026 alzimerahmed
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.feature_node.domain.memories

import android.net.Uri
import com.dot.gallery.feature_node.domain.model.Media
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class MemoriesEngineTest {

    private val today = LocalDate.of(2026, 6, 15)

    private fun engine(zone: ZoneId = ZoneOffset.UTC, favorites: Set<Long> = emptySet()) =
        MemoriesEngine(
            clock = java.time.Clock.fixed(today.atStartOfDay(zone).toInstant(), zone),
            favoriteIds = favorites
        )

    @Suppress("LongParameterList")
    private fun media(
        id: Long,
        year: Int,
        month: Int,
        day: Int,
        hour: Int = 12,
        isVideo: Boolean = false,
        isScreenshot: Boolean = false,
        favorite: Boolean = false
    ): Media.UriMedia {
        val epochSeconds = LocalDate.of(year, month, day).atTime(hour, 0)
            .toInstant(ZoneOffset.UTC).epochSecond
        val name = if (isScreenshot) "Screenshot_$id.png" else "Photo_$id.${if (isVideo) "mp4" else "jpg"}"
        return Media.UriMedia(
            id = id,
            label = name,
            uri = Uri.parse("content://media/$id"),
            path = if (isScreenshot) "/storage/Pictures/Screenshots/$name" else "/storage/DCIM/$name",
            relativePath = if (isScreenshot) "Pictures/Screenshots" else "DCIM/Camera",
            albumID = 1L,
            albumLabel = "Camera",
            timestamp = epochSeconds,
            takenTimestamp = epochSeconds * 1000L,
            fullDate = "",
            mimeType = if (isVideo) "video/mp4" else "image/jpeg",
            favorite = if (favorite) 1 else 0,
            trashed = 0,
            size = 1L
        )
    }

    @Test
    fun `exact month-day matches across years group newest first`() {
        val result = engine().onThisDay(
            listOf(
                media(1, 2020, 6, 15),
                media(2, 2023, 6, 15),
                media(3, 2018, 6, 15),
                media(4, 2026, 6, 15),
                media(5, 2026, 6, 16)
            )
        )
        assertEquals(listOf(2023, 2020, 2018), result.map { it.year })
    }

    @Test
    fun `fewer than three exact matches triggers proximity fallback`() {
        val result = engine().onThisDay(
            listOf(
                media(1, 2020, 6, 14),
                media(2, 2021, 6, 18)
            )
        )
        assertEquals(listOf(2021, 2020), result.map { it.year })
    }

    @Test
    fun `proximity fallback not used when three or more exact matches exist`() {
        val result = engine().onThisDay(
            listOf(
                media(1, 2020, 6, 15),
                media(2, 2021, 6, 15),
                media(3, 2022, 6, 15),
                media(4, 2019, 6, 18)
            )
        )
        assertEquals(listOf(2022, 2021, 2020), result.map { it.year })
    }

    @Test
    fun `current year same month-day is excluded and future year never matches`() {
        val result = engine().onThisDay(
            listOf(
                media(1, 2026, 6, 15),
                media(2, 2027, 6, 15)
            )
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `timezone shifted today changes match set`() {
        val boundary = media(1, 2025, 6, 16, hour = 0).let {
            it.copy(timestamp = LocalDate.of(2025, 6, 16).atTime(0, 30).toInstant(ZoneOffset.UTC).epochSecond)
        }
        val exact = listOf(
            media(2, 2024, 6, 15),
            media(3, 2023, 6, 15),
            media(4, 2021, 6, 15)
        )
        val utcEngine = engine(ZoneOffset.UTC)
        val plus14 = ZoneId.of("UTC+14")
        val shiftedEngine = engine(plus14)
        val utcResult = utcEngine.onThisDay(exact + boundary)
        assertTrue(utcResult.all { group -> group.media.none { it.id == 1L } })
        val shiftedResult = shiftedEngine.onThisDay(exact + boundary)
        assertTrue(shiftedResult.any { group -> group.year == 2025 && group.media.any { it.id == 1L } })
    }

    @Test
    fun `recap with twelve populated months picks from every month`() {
        val media = (1..12).flatMap { month ->
            (0 until 5).map { i -> media((month * 100 + i).toLong(), 2024, month, 10 + i) }
        }
        val recap = engine().yearRecap(media, 2024, maxPerMonth = 2, target = 24)
        assertEquals(24, recap.featured.size)
        assertEquals((1..12).toSet(), recap.featured.map { epochMonthOf(it) }.toSet())
    }

    @Test
    fun `month with only screenshots falls back to screenshots`() {
        val media = listOf(
            media(1, 2024, 3, 5, isScreenshot = true),
            media(2, 2024, 3, 6, isScreenshot = true),
            media(3, 2024, 4, 5)
        )
        val recap = engine().yearRecap(media, 2024, maxPerMonth = 2, target = 6)
        val march = recap.featured.filter { epochMonthOf(it) == 3 }
        assertEquals(2, march.size)
        assertTrue(march.all { it.id == 1L || it.id == 2L })
        assertTrue(recap.featured.any { epochMonthOf(it) == 4 })
    }

    @Test
    fun `screenshots excluded when month has alternatives`() {
        val media = listOf(
            media(1, 2024, 5, 5, isScreenshot = true),
            media(2, 2024, 5, 6),
            media(3, 2024, 5, 7)
        )
        val recap = engine().yearRecap(media, 2024, maxPerMonth = 2, target = 6)
        assertTrue(recap.featured.none { it.id == 1L })
    }

    @Test
    fun `favorites boosted candidates outrank equal-spread non-favorites`() {
        val media = listOf(
            media(10, 2024, 7, 1),
            media(11, 2024, 7, 2),
            media(12, 2024, 7, 3)
        )
        val recap = engine(favorites = setOf(10L)).yearRecap(media, 2024, maxPerMonth = 1, target = 6)
        assertEquals(10L, recap.featured.first().id)
    }

    @Test
    fun `same input and today produce identical selection`() {
        val media = (1..12).flatMap { month ->
            (0 until 4).map { i -> media((month * 100 + i).toLong(), 2023, month, 3 + i) }
        }
        val a = engine().yearRecap(media, 2023, maxPerMonth = 2, target = 12)
        val b = engine().yearRecap(media, 2023, maxPerMonth = 2, target = 12)
        assertEquals(a.featured.map { it.id }, b.featured.map { it.id })
    }

    @Test
    fun `empty library produces empty results without exceptions`() {
        val e = engine()
        assertTrue(e.onThisDay(emptyList()).isEmpty())
        assertTrue(e.recaps(emptyList()).isEmpty())
    }

    @Test
    fun `single photo year produces one item recap`() {
        val recap = engine().yearRecap(listOf(media(1, 2022, 2, 2)), 2022, maxPerMonth = 2, target = 6)
        assertEquals(1, recap.featured.size)
        assertEquals(1L, recap.featured.first().id)
    }

    @Test
    fun `photos preferred over videos in recap`() {
        val media = listOf(
            media(1, 2024, 9, 1, isVideo = true),
            media(2, 2024, 9, 2)
        )
        val recap = engine().yearRecap(media, 2024, maxPerMonth = 1, target = 6)
        assertEquals(2L, recap.featured.first().id)
    }

    @Test
    fun `recaps returns one recap per eligible prior year`() {
        val media = listOf(
            media(1, 2024, 1, 1),
            media(2, 2023, 2, 2),
            media(3, 2026, 3, 3)
        )
        val recaps = engine().recaps(media)
        assertEquals(listOf(2024, 2023), recaps.map { it.year })
        assertFalse(recaps.any { it.year == 2026 })
    }

    private fun epochMonthOf(m: Media.UriMedia): Int =
        Instant.ofEpochSecond(m.definedTimestamp).atZone(ZoneOffset.UTC).monthValue
}
