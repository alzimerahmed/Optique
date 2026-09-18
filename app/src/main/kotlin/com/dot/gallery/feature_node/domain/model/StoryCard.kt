/*
 * SPDX-FileCopyrightText: 2023-2026 alzimerahmed
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.feature_node.domain.model

import android.net.Uri
import androidx.compose.runtime.Stable
import kotlinx.serialization.Serializable

/**
 * Represents the type of a Story Card displayed above the timeline.
 */
@Serializable
enum class StoryCardType {
    MEMORIES,
    ALBUMS,
    CATEGORIES,
    LOCATIONS,
    FAVORITES,
    CLOUD_MEMORIES,
    HIGHLIGHTS,
    PEOPLE
}

/**
 * Configuration for which Story Card types are enabled and their display order.
 */
@Serializable
data class StoryCardsConfig(
    val enabled: Boolean = true,
    val cardOrder: List<StoryCardType> = StoryCardType.entries.toList(),
    val disabledTypes: Set<StoryCardType> = emptySet(),
    /** Per-type card-count caps. An empty map (or missing entry) falls back to [DEFAULT_MAX_CARDS_PER_TYPE]. */
    val maxCardsPerType: Map<StoryCardType, Int> = emptyMap(),
    /** Albums excluded from card generation entirely — no card, no media in other cards' lists. */
    val excludedAlbumIds: Set<Long> = emptySet(),
    /** Categories excluded from card generation and media contribution. */
    val excludedCategoryIds: Set<Long> = emptySet(),
    /** "city, country" location keys excluded from card generation and media contribution. */
    val excludedLocationKeys: Set<String> = emptySet()
) {
    /** cardOrder with any newly-added types appended (handles config persisted before the type existed). */
    val normalizedOrder: List<StoryCardType>
        get() {
            val missing = StoryCardType.entries - cardOrder.toSet()
            return if (missing.isEmpty()) cardOrder else cardOrder + missing
        }

    val activeTypes: List<StoryCardType>
        get() = if (enabled) normalizedOrder.filter { it !in disabledTypes } else emptyList()

    companion object {
        /**
         * Default per-type card caps replicating the limits that were previously
         * hardcoded in the card builders. Single-card types (FAVORITES) have no
         * cap and are intentionally absent.
         */
        val DEFAULT_MAX_CARDS_PER_TYPE: Map<StoryCardType, Int> = mapOf(
            StoryCardType.MEMORIES to 10,
            StoryCardType.ALBUMS to 5,
            StoryCardType.CATEGORIES to 5,
            StoryCardType.LOCATIONS to 5,
            StoryCardType.CLOUD_MEMORIES to 10,
            StoryCardType.HIGHLIGHTS to 4,
            StoryCardType.PEOPLE to 5
        )
    }
}

/**
 * Represents a single Story Card to be displayed in the timeline carousel.
 * Each card has a type, title, subtitle, thumbnail, and the backing media list
 * that will be shown in the story viewer.
 */
@Stable
data class StoryCard(
    val id: Long,
    val type: StoryCardType,
    val title: String,
    val subtitle: String? = null,
    val thumbnailUri: Uri? = null,
    val thumbnailMedia: Media.UriMedia? = null,
    val mediaList: List<Media.UriMedia> = emptyList(),
    val albumId: Long? = null,
    val categoryId: Long? = null,
    val locationCity: String? = null,
    val locationCountry: String? = null,
    val year: Int? = null,
    /** Face-cluster/person identifier for PEOPLE cards; used viewer-side. */
    val personId: String? = null
)
