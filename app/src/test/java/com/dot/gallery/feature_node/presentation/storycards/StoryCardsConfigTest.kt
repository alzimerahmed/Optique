/*
 * SPDX-FileCopyrightText: 2023-2026 alzimerahmed
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.feature_node.presentation.storycards

import com.dot.gallery.feature_node.domain.model.StoryCardType
import com.dot.gallery.feature_node.domain.model.StoryCardsConfig
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class StoryCardsConfigTest {

    private fun guardedDecode(json: String): StoryCardsConfig =
        runCatching { Json.decodeFromString<StoryCardsConfig>(json) }.getOrNull()
            ?: StoryCardsConfig()

    @Test
    fun `stored json missing new fields decodes to defaults`() {
        // Written by an app version that predates caps/exclusions.
        val stored =
            """{"enabled":true,"cardOrder":["ALBUMS","MEMORIES"],"disabledTypes":["FAVORITES"]}"""

        val config = Json.decodeFromString<StoryCardsConfig>(stored)

        assertTrue(config.enabled)
        assertEquals(listOf(StoryCardType.ALBUMS, StoryCardType.MEMORIES), config.cardOrder)
        assertEquals(setOf(StoryCardType.FAVORITES), config.disabledTypes)
        assertTrue(config.maxCardsPerType.isEmpty())
        assertTrue(config.excludedAlbumIds.isEmpty())
        assertTrue(config.excludedCategoryIds.isEmpty())
        assertTrue(config.excludedLocationKeys.isEmpty())
    }

    @Test
    fun `stored json with unknown keys falls back to default via guarded decode`() {
        // Written by a newer app version; Json.Default does not ignore unknown keys,
        // so the runCatching guard (getStoryCardsConfig / rememberPreferenceSerializable)
        // is what keeps this from crashing.
        val stored = """{"enabled":true,"futureField":{"nested":42}}"""

        val config = guardedDecode(stored)

        assertEquals(StoryCardsConfig(), config)
    }

    @Test
    fun `normalizedOrder appends new types to a stored order that predates them`() {
        val stored =
            """{"cardOrder":["MEMORIES","ALBUMS","CATEGORIES","LOCATIONS","FAVORITES","CLOUD_MEMORIES"]}"""

        val config = Json.decodeFromString<StoryCardsConfig>(stored)

        assertEquals(
            StoryCardType.entries.toList(),
            config.normalizedOrder,
        )
        assertEquals(
            listOf(StoryCardType.HIGHLIGHTS, StoryCardType.PEOPLE),
            config.normalizedOrder.takeLast(2),
        )
    }

    @Test
    fun `serialization round trip preserves caps exclusions order and disabledTypes`() {
        val config = StoryCardsConfig(
            enabled = true,
            cardOrder = listOf(
                StoryCardType.PEOPLE,
                StoryCardType.HIGHLIGHTS,
                StoryCardType.ALBUMS,
            ),
            disabledTypes = setOf(StoryCardType.CLOUD_MEMORIES),
            maxCardsPerType = mapOf(
                StoryCardType.ALBUMS to 3,
                StoryCardType.PEOPLE to 7,
            ),
            excludedAlbumIds = setOf(11L, 22L),
            excludedCategoryIds = setOf(5L),
            excludedLocationKeys = setOf("Paris, France", "Berlin, Germany"),
        )

        val decoded = Json.decodeFromString<StoryCardsConfig>(Json.encodeToString(config))

        assertEquals(config, decoded)
    }

    @Test
    fun `default config encodes without persisted keys`() {
        // encodeDefaults=false on Json.Default keeps stored JSON clean — a
        // default config persists nothing and still decodes back to defaults.
        val encoded = Json.encodeToString(StoryCardsConfig())

        assertFalse(encoded.contains("maxCardsPerType"))
        assertFalse(encoded.contains("excludedAlbumIds"))
        assertFalse(encoded.contains("excludedCategoryIds"))
        assertFalse(encoded.contains("excludedLocationKeys"))
        assertEquals(StoryCardsConfig(), Json.decodeFromString<StoryCardsConfig>(encoded))
    }
}
