/*
 * SPDX-FileCopyrightText: 2023-2026 alzimerahmed
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.feature_node.presentation.memories

import android.net.Uri
import com.dot.gallery.feature_node.domain.memories.OnThisDayGroup
import com.dot.gallery.feature_node.domain.memories.YearRecap
import com.dot.gallery.feature_node.domain.model.Media
import com.dot.gallery.feature_node.presentation.util.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Route `kind` disambiguation (M1): an on-this-day card for a year that also has a
 * year recap must play the on-this-day media, not the recap's featured set.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class RecapPlaybackSelectionTest {
    private fun media(id: Long): Media.UriMedia {
        val epochSeconds =
            LocalDate
                .of(2020, 6, 15)
                .atTime(12, 0)
                .toInstant(ZoneOffset.UTC)
                .epochSecond
        return Media.UriMedia(
            id = id,
            label = "Photo_$id.jpg",
            uri = Uri.parse("content://media/$id"),
            path = "/storage/DCIM/Photo_$id.jpg",
            relativePath = "DCIM/Camera",
            albumID = 1L,
            albumLabel = "Camera",
            timestamp = epochSeconds,
            takenTimestamp = epochSeconds * 1000L,
            fullDate = "",
            mimeType = "image/jpeg",
            favorite = 0,
            trashed = 0,
            size = 1L,
        )
    }

    private val onThisDayMedia = listOf(media(10), media(11))
    private val recapFeatured = listOf(media(20), media(21), media(22))
    private val content =
        MemoriesSectionState.Content(
            onThisDayGroups = listOf(OnThisDayGroup(year = 2020, media = onThisDayMedia)),
            recaps = listOf(YearRecap(year = 2020, featured = recapFeatured)),
        )

    @Test
    fun `onthisday kind selects the group media not the recap`() {
        val resolved =
            resolvePlaybackMedia(content, 2020, Screen.RecapPlaybackScreen.KIND_ON_THIS_DAY)

        assertSame(onThisDayMedia, resolved)
    }

    @Test
    fun `recap kind selects the recap featured set`() {
        val resolved = resolvePlaybackMedia(content, 2020, Screen.RecapPlaybackScreen.KIND_RECAP)

        assertSame(recapFeatured, resolved)
    }

    @Test
    fun `absent kind keeps the legacy recap-first order`() {
        val resolved = resolvePlaybackMedia(content, 2020, kind = null)

        assertSame(recapFeatured, resolved)
    }

    @Test
    fun `onthisday kind falls back to recap when the group is missing`() {
        val resolved = resolvePlaybackMedia(content, 2019, Screen.RecapPlaybackScreen.KIND_ON_THIS_DAY)

        assertTrue(resolved.isEmpty())

        val recapOnly =
            MemoriesSectionState.Content(
                onThisDayGroups = emptyList(),
                recaps = listOf(YearRecap(year = 2019, featured = recapFeatured)),
            )
        assertSame(
            recapFeatured,
            resolvePlaybackMedia(recapOnly, 2019, Screen.RecapPlaybackScreen.KIND_ON_THIS_DAY),
        )
    }

    @Test
    fun `recap kind falls back to on-this-day group when the recap is missing`() {
        val groupOnly =
            MemoriesSectionState.Content(
                onThisDayGroups = listOf(OnThisDayGroup(year = 2020, media = onThisDayMedia)),
                recaps = emptyList(),
            )

        assertSame(
            onThisDayMedia,
            resolvePlaybackMedia(groupOnly, 2020, Screen.RecapPlaybackScreen.KIND_RECAP),
        )
    }
}
