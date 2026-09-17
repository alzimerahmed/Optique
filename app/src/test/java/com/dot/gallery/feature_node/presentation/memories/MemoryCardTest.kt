/*
 * SPDX-FileCopyrightText: 2023-2026 alzimerahmed
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.feature_node.presentation.memories

import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import com.dot.gallery.feature_node.domain.memories.OnThisDayGroup
import com.dot.gallery.feature_node.domain.model.Media
import com.dot.gallery.feature_node.presentation.memories.components.MemoryCard
import com.dot.gallery.feature_node.presentation.util.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MemoryCardTest {
    @get:Rule
    val composeRule = createComposeRule()

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

    @Test
    fun cardRendersWhenOnThisDayGroupExists() {
        composeRule.setContent {
            MaterialTheme {
                MemoryCard(
                    group = OnThisDayGroup(year = 2020, media = listOf(media(1), media(2))),
                    onOpenYear = {},
                )
            }
        }

        composeRule
            .onNodeWithContentDescription("On this day in 2020, 2 items")
            .assertIsDisplayed()
    }

    @Test
    fun cardHiddenWithoutOnThisDayGroup() {
        composeRule.setContent {
            MaterialTheme {
                MemoryCard(
                    group = null,
                    onOpenYear = {},
                )
            }
        }

        composeRule
            .onNodeWithContentDescription("On this day in 2020, 2 items")
            .assertDoesNotExist()
    }

    @Test
    fun tapNavigatesToNewestGroupYearRoute() {
        var navigatedRoute: String? = null
        composeRule.setContent {
            MaterialTheme {
                MemoryCard(
                    group = OnThisDayGroup(year = 2020, media = listOf(media(1))),
                    onOpenYear = { year ->
                        navigatedRoute = Screen.OnDeviceMemoriesScreen.year(year)
                    },
                )
            }
        }

        composeRule
            .onNodeWithContentDescription("On this day in 2020, 1 item")
            .assertHasClickAction()
            .performClick()

        assertEquals("on_device_memories_screen?year=2020", navigatedRoute)
    }

    @Test
    fun hiddenCardNeverNavigates() {
        var navigatedRoute: String? = null
        composeRule.setContent {
            MaterialTheme {
                MemoryCard(
                    group = null,
                    onOpenYear = { year ->
                        navigatedRoute = Screen.OnDeviceMemoriesScreen.year(year)
                    },
                )
            }
        }

        composeRule
            .onNodeWithContentDescription("On this day in 2020, 1 item")
            .assertDoesNotExist()
        assertNull(navigatedRoute)
    }
}
