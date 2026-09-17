/*
 * SPDX-FileCopyrightText: 2023-2026 alzimerahmed
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.feature_node.domain.memories

import com.dot.gallery.feature_node.domain.model.Media
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.random.Random

data class OnThisDayGroup(
    val year: Int,
    val media: List<Media.UriMedia>,
)

data class YearRecap(
    val year: Int,
    val featured: List<Media.UriMedia>,
)

class MemoriesEngine(
    private val clock: Clock,
    private val favoriteIds: Set<Long> = emptySet(),
) {
    fun onThisDay(media: List<Media.UriMedia>): List<OnThisDayGroup> {
        val today = LocalDate.now(clock)
        val zone = clock.zone
        val byDate =
            media.associateWith {
                Instant.ofEpochSecond(it.definedTimestamp).atZone(zone).toLocalDate()
            }

        val exact =
            media.filter { m ->
                val d = byDate.getValue(m)
                d.year < today.year && d.monthValue == today.monthValue && d.dayOfMonth == today.dayOfMonth
            }

        val matches =
            if (exact.size >= MIN_EXACT_MATCHES) {
                exact
            } else {
                media.filter { m ->
                    val d = byDate.getValue(m)
                    if (d.year >= today.year) return@filter false
                    val shifted = d.withYear(today.year)
                    kotlin.math.abs(shifted.toEpochDay() - today.toEpochDay()) <= PROXIMITY_DAYS
                }
            }

        return matches
            .groupBy { byDate.getValue(it).year }
            .entries
            .sortedByDescending { it.key }
            .map { (year, items) ->
                OnThisDayGroup(year, items.sortedByDescending { it.definedTimestamp })
            }
    }

    fun recaps(media: List<Media.UriMedia>): List<YearRecap> {
        val today = LocalDate.now(clock)
        val zone = clock.zone
        return media
            .map { it to Instant.ofEpochSecond(it.definedTimestamp).atZone(zone).toLocalDate() }
            .filter { it.second.year < today.year }
            .groupBy { it.second.year }
            .entries
            .sortedByDescending { it.key }
            .map { (year, items) -> yearRecap(items.map { it.first }, year) }
            .filter { it.featured.isNotEmpty() }
    }

    fun yearRecap(
        media: List<Media.UriMedia>,
        year: Int,
        maxPerMonth: Int = DEFAULT_MAX_PER_MONTH,
        target: Int = MAX_RECAP_SIZE,
        minFeatured: Int = MIN_RECAP_SIZE,
    ): YearRecap {
        val zone = clock.zone
        val inYear =
            media.filter {
                Instant.ofEpochSecond(it.definedTimestamp).atZone(zone).year == year
            }
        if (inYear.isEmpty()) return YearRecap(year, emptyList())

        val byMonth =
            inYear.groupBy {
                Instant.ofEpochSecond(it.definedTimestamp).atZone(zone).monthValue
            }

        val pools =
            (1..MONTHS_IN_YEAR)
                .mapNotNull { month ->
                    val items = byMonth[month] ?: return@mapNotNull null
                    month to monthPool(year, items)
                }.toMap()

        val picked = mutableListOf<Media.UriMedia>()
        var round = 0
        while (picked.size < target && round < maxPerMonth) {
            for (month in 1..MONTHS_IN_YEAR) {
                pools[month]?.getOrNull(round)?.let { picked += it }
                if (picked.size >= target) break
            }
            round++
        }

        val featured = if (picked.size < minFeatured) emptyList() else picked
        return YearRecap(year, featured)
    }

    private fun monthPool(
        year: Int,
        items: List<Media.UriMedia>,
    ): List<Media.UriMedia> {
        val nonScreenshots = items.filterNot { isScreenshot(it) }
        val candidates = nonScreenshots.ifEmpty { items }
        return candidates
            .map { m ->
                val isPhoto = !m.mimeType.startsWith("video/")
                val score = (if (isFavorite(m)) FAVORITE_BOOST else 0) + (if (isPhoto) PHOTO_BOOST else 0)
                Triple(m, score, shuffleKey(year, m))
            }.sortedWith(compareByDescending<Triple<Media.UriMedia, Int, Long>> { it.second }.thenBy { it.third })
            .map { it.first }
    }

    private fun isFavorite(m: Media.UriMedia) = m.favorite == 1 || m.id in favoriteIds

    private fun isScreenshot(m: Media.UriMedia): Boolean {
        val haystack = "${m.label} ${m.path} ${m.relativePath}"
        return haystack.contains("screenshot", ignoreCase = true) ||
            haystack.contains("screencap", ignoreCase = true)
    }

    private fun shuffleKey(
        year: Int,
        m: Media.UriMedia,
    ): Long = Random(seed = year * 1_000_003L + m.id * 31L + m.label.hashCode()).nextLong()

    companion object {
        private const val PROXIMITY_DAYS = 3L
        private const val MIN_EXACT_MATCHES = 3
        private const val MONTHS_IN_YEAR = 12
        private const val FAVORITE_BOOST = 2
        private const val PHOTO_BOOST = 1
        const val DEFAULT_MAX_PER_MONTH = 2
        const val MIN_RECAP_SIZE = 5
        const val MAX_RECAP_SIZE = 24
    }
}
