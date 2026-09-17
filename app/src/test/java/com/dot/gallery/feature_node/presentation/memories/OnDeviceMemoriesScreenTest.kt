/*
 * SPDX-FileCopyrightText: 2023-2026 alzimerahmed
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.feature_node.presentation.memories

import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.dot.gallery.core.DefaultEventHandler
import com.dot.gallery.core.LocalEventHandler
import com.dot.gallery.feature_node.domain.memories.OnThisDayGroup
import com.dot.gallery.feature_node.domain.memories.YearRecap
import com.dot.gallery.feature_node.domain.model.Media
import com.dot.gallery.feature_node.presentation.util.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
class OnDeviceMemoriesScreenTest {
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
    fun emptyStateRendersWithPrimaryAction() {
        var actionInvoked = false
        composeRule.setContent {
            MaterialTheme {
                OnDeviceMemoriesSection(
                    state = MemoriesSectionState.Empty,
                    onPlay = { _, _ -> },
                    onRetry = {},
                    onEmptyAction = { actionInvoked = true },
                )
            }
        }

        composeRule.onNodeWithText("No memories yet").assertIsDisplayed()
        composeRule
            .onNodeWithText("Explore your timeline")
            .assertHasClickAction()
            .performClick()
        assertTrue(actionInvoked)
    }

    @Test
    fun errorStateRendersAndRetries() {
        var retried = false
        composeRule.setContent {
            MaterialTheme {
                OnDeviceMemoriesSection(
                    state = MemoriesSectionState.Error("boom"),
                    onPlay = { _, _ -> },
                    onRetry = { retried = true },
                    onEmptyAction = {},
                )
            }
        }

        composeRule.onNodeWithText("boom").assertIsDisplayed()
        composeRule
            .onNodeWithText("Retry")
            .assertHasClickAction()
            .performClick()
        assertTrue(retried)
    }

    @Test
    fun singlePhotoPagerRendersWithoutCrash() {
        composeRule.setContent {
            CompositionLocalProvider(LocalEventHandler provides DefaultEventHandler()) {
                MaterialTheme {
                    RecapPlaybackScreen(
                        year = 2020,
                        media = listOf(media(1)),
                        onDismiss = {},
                    )
                }
            }
        }

        composeRule
            .onNodeWithContentDescription("Year 2020, item 1 of 1")
            .assertIsDisplayed()
    }

    @Test
    fun onThisDayCardPlaysWithOnThisDayKind() {
        var played: Pair<Int, String>? = null
        composeRule.setContent {
            MaterialTheme {
                OnDeviceMemoriesSection(
                    state =
                        MemoriesSectionState.Content(
                            onThisDayGroups =
                                listOf(OnThisDayGroup(year = 2020, media = listOf(media(1)))),
                            // A recap for the same year must not shadow the
                            // on-this-day media list (route kind disambiguation).
                            recaps =
                                listOf(YearRecap(year = 2020, featured = listOf(media(2)))),
                        ),
                    onPlay = { year, kind -> played = year to kind },
                    onRetry = {},
                    onEmptyAction = {},
                )
            }
        }

        composeRule
            .onNodeWithContentDescription("On this day in 2020, 1 item")
            .assertHasClickAction()
            .performClick()

        assertEquals(2020 to Screen.RecapPlaybackScreen.KIND_ON_THIS_DAY, played)
    }

    @Test
    fun yearRecapCardPlaysWithRecapKind() {
        var played: Pair<Int, String>? = null
        composeRule.setContent {
            MaterialTheme {
                OnDeviceMemoriesSection(
                    state =
                        MemoriesSectionState.Content(
                            onThisDayGroups = emptyList(),
                            recaps =
                                listOf(YearRecap(year = 2019, featured = listOf(media(1)))),
                        ),
                    onPlay = { year, kind -> played = year to kind },
                    onRetry = {},
                    onEmptyAction = {},
                )
            }
        }

        composeRule
            .onNodeWithContentDescription("Play recap for 2019")
            .assertHasClickAction()
            .performClick()

        assertEquals(2019 to Screen.RecapPlaybackScreen.KIND_RECAP, played)
    }

    @Test
    fun soundtrackToggleIsOffByDefault() {
        composeRule.setContent {
            CompositionLocalProvider(LocalEventHandler provides DefaultEventHandler()) {
                MaterialTheme {
                    RecapPlaybackScreen(
                        year = 2020,
                        media = listOf(media(1), media(2)),
                        onDismiss = {},
                    )
                }
            }
        }

        composeRule
            .onNodeWithContentDescription("Soundtrack off")
            .assertIsOff()
    }

    @Test
    fun endOfRecapShowsShareAndClose() {
        composeRule.setContent {
            CompositionLocalProvider(LocalEventHandler provides DefaultEventHandler()) {
                MaterialTheme {
                    RecapPlaybackScreen(
                        year = 2020,
                        media = listOf(media(1)),
                        onDismiss = {},
                    )
                }
            }
        }

        // Tap the right-third "next" zone past the last photo to reach Completed.
        composeRule
            .onNodeWithContentDescription("Year 2020, item 1 of 1")
            .performTouchInput {
                click(Offset(width * 0.9f, height * 0.5f))
            }

        // Top-bar share + completed-overlay share
        composeRule.onAllNodesWithContentDescription("Share recap").assertCountEquals(2)
        composeRule.onNodeWithContentDescription("Close recap").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Replay recap").assertIsDisplayed()
    }

    @Test
    fun completedRecapBackTapDismissesOverlay() {
        composeRule.setContent {
            CompositionLocalProvider(LocalEventHandler provides DefaultEventHandler()) {
                MaterialTheme {
                    RecapPlaybackScreen(
                        year = 2020,
                        media = listOf(media(1)),
                        onDismiss = {},
                    )
                }
            }
        }

        // Reach Completed, then tap the left-third "previous" zone — the overlay
        // must dismiss and playback drop to Paused instead of dead-ending.
        composeRule
            .onNodeWithContentDescription("Year 2020, item 1 of 1")
            .performTouchInput {
                click(Offset(width * 0.9f, height * 0.5f))
            }
        composeRule
            .onNodeWithText("Your recap from 2020 is complete")
            .assertIsDisplayed()

        composeRule
            .onNodeWithContentDescription("Year 2020, item 1 of 1")
            .performTouchInput {
                click(Offset(width * 0.1f, height * 0.5f))
            }

        composeRule
            .onNodeWithText("Your recap from 2020 is complete")
            .assertDoesNotExist()
        composeRule
            .onNodeWithContentDescription("Resume recap")
            .assertIsDisplayed()
    }

    @Test
    fun completedRecapReplayRestartsFromFirstPage() {
        composeRule.setContent {
            CompositionLocalProvider(LocalEventHandler provides DefaultEventHandler()) {
                MaterialTheme {
                    RecapPlaybackScreen(
                        year = 2020,
                        media = listOf(media(1), media(2)),
                        onDismiss = {},
                    )
                }
            }
        }

        // Tap the right-third "next" zone: lands on page 2, then the auto-advance
        // timer (auto-advancing test clock) completes the recap.
        composeRule
            .onNodeWithContentDescription("Year 2020, item 1 of 2")
            .performTouchInput {
                click(Offset(width * 0.9f, height * 0.5f))
            }
        composeRule
            .onNodeWithText("Your recap from 2020 is complete")
            .assertIsDisplayed()

        // Freeze the clock so the restarted auto-advance cannot re-complete the
        // recap before assertions run; one frame runs the queued scroll coroutine.
        composeRule.mainClock.autoAdvance = false
        composeRule
            .onNodeWithContentDescription("Replay recap")
            .performClick()
        composeRule.mainClock.advanceTimeByFrame()

        composeRule
            .onNodeWithText("Your recap from 2020 is complete")
            .assertDoesNotExist()
        composeRule.onNodeWithText("1 / 2").assertIsDisplayed()
        composeRule
            .onNodeWithContentDescription("Pause recap")
            .assertIsDisplayed()
    }

    @Test
    fun shareAvailableDuringPlayback() {
        // Freeze the clock so the auto-advance animation never finishes —
        // playback stays in Playing and only the top-bar share affordance
        // exists (U7/R8: share works before the recap completes).
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            CompositionLocalProvider(LocalEventHandler provides DefaultEventHandler()) {
                MaterialTheme {
                    RecapPlaybackScreen(
                        year = 2020,
                        media = listOf(media(1), media(2)),
                        onDismiss = {},
                    )
                }
            }
        }
        composeRule.mainClock.advanceTimeByFrame()

        composeRule
            .onAllNodesWithContentDescription("Share recap")
            .assertCountEquals(1)
        composeRule
            .onNodeWithContentDescription("Share recap")
            .assertIsDisplayed()
            .assertHasClickAction()
    }
}
