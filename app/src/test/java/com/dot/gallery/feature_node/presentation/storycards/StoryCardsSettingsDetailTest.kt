/*
 * SPDX-FileCopyrightText: 2023-2026 alzimerahmed
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.feature_node.presentation.storycards

import android.net.Uri
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.dot.gallery.core.DefaultEventHandler
import com.dot.gallery.core.LocalEventHandler
import com.dot.gallery.core.Position
import com.dot.gallery.feature_node.domain.model.Album
import com.dot.gallery.feature_node.domain.model.AlbumState
import com.dot.gallery.feature_node.domain.model.StoryCardType
import com.dot.gallery.feature_node.domain.model.StoryCardsConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * U5 settings detail surface: per-type cap control, exclusion list/picker,
 * and the row affordance semantics — Robolectric Compose tests in the same
 * style as [TimelineFilterChipAccessibilityTest]/[OnDeviceMemoriesScreenTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StoryCardsSettingsDetailTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** `BackHandler` inside the detail content requires an owner. */
    private class TestBackDispatcherOwner : OnBackPressedDispatcherOwner, LifecycleOwner {
        private val registry = LifecycleRegistry(this)

        init {
            registry.currentState = Lifecycle.State.RESUMED
        }

        override val lifecycle: Lifecycle get() = registry
        override val onBackPressedDispatcher = OnBackPressedDispatcher()
    }

    private fun testContent(content: @Composable () -> Unit) {
        composeRule.setContent {
            CompositionLocalProvider(
                LocalEventHandler provides DefaultEventHandler(),
                LocalOnBackPressedDispatcherOwner provides TestBackDispatcherOwner(),
            ) {
                MaterialTheme { content() }
            }
        }
    }

    private fun album(id: Long, label: String, count: Long = 3) = Album(
        id = id,
        label = label,
        uri = Uri.EMPTY,
        pathToThumbnail = "",
        relativePath = "",
        timestamp = 0,
        count = count,
    )

    @Test
    fun rowTapOpensDetailWithoutTogglingSwitch() {
        var opened = false
        var toggled = false
        testContent {
            CardTypeListItem(
                type = StoryCardType.ALBUMS,
                isEnabled = true,
                position = Position.Alone,
                onOpenDetail = { opened = true },
                onToggle = { toggled = true },
            )
        }

        // The merged content area is one announced action; the Switch stays a
        // separate toggleable node.
        composeRule.onNodeWithText("Albums")
            .assertHasClickAction()
            .performClick()
        composeRule.onNode(isToggleable()).assertIsOn()

        assertTrue(opened)
        assertFalse(toggled)
    }

    @Test
    fun rowShowsExclusionCountWhenNonZero() {
        testContent {
            CardTypeListItem(
                type = StoryCardType.ALBUMS,
                isEnabled = true,
                position = Position.Alone,
                exclusionCount = 2,
                onOpenDetail = {},
                onToggle = {},
            )
        }

        composeRule.onNodeWithText("2 excluded").assertIsDisplayed()
    }

    @Test
    fun favoritesDetailShowsNoCapControl() {
        testContent {
            StoryCardTypeDetailContent(
                type = StoryCardType.FAVORITES,
                config = StoryCardsConfig(),
                albumsState = AlbumState(),
                categories = emptyList(),
                locationKeys = emptyList(),
                onConfigChange = {},
            )
        }

        // Single-card type: no cap slider and no exclusion section.
        composeRule.onNodeWithText("Maximum cards").assertDoesNotExist()
        composeRule.onNodeWithText("Excluded sources").assertDoesNotExist()
        composeRule.onNodeWithText("Your favorite photos").assertIsDisplayed()
    }

    @Test
    fun albumsDetailShowsCapAndExclusionSection() {
        testContent {
            StoryCardTypeDetailContent(
                type = StoryCardType.ALBUMS,
                config = StoryCardsConfig(),
                albumsState = AlbumState(),
                categories = emptyList(),
                locationKeys = emptyList(),
                onConfigChange = {},
            )
        }

        composeRule.onNodeWithText("Maximum cards").assertIsDisplayed()
        // Default cap (5) stands in when the user never set one.
        composeRule.onNodeWithText("Show up to 5 cards of this type").assertIsDisplayed()
        composeRule.onNodeWithText("Excluded sources").assertIsDisplayed()
        composeRule.onNodeWithText("Add excluded sources").assertHasClickAction()
    }

    @Test
    fun albumPickerToggleWritesExclusionAndAnnotatesHidden() {
        var written: StoryCardsConfig? = null
        val camera = album(id = 1, label = "Camera")
        val hidden = album(id = 2, label = "Secret")
        testContent {
            var config by remember { mutableStateOf(StoryCardsConfig()) }
            StoryCardTypeDetailContent(
                type = StoryCardType.ALBUMS,
                config = config,
                albumsState = AlbumState(
                    albums = listOf(camera),
                    albumsWithBlacklisted = listOf(camera, hidden),
                ),
                categories = emptyList(),
                locationKeys = emptyList(),
                onConfigChange = { config = it; written = it },
            )
        }

        composeRule.onNodeWithText("Add excluded sources").performClick()
        composeRule.onNodeWithText("Exclude albums").assertIsDisplayed()

        // Blacklist-hidden source: annotated, not offered as a dead entry.
        composeRule.onNodeWithText("Secret").assertIsDisplayed()
        composeRule.onNodeWithText("Already hidden by ignored albums").assertIsDisplayed()

        // Unchecked rows expose their check state for TalkBack.
        composeRule.onAllNodes(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Not excluded")
        ).assertCountEquals(2)

        composeRule.onNodeWithText("Camera").performClick()

        composeRule.onAllNodes(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Excluded")
        ).assertCountEquals(1)
        assertEquals(setOf(1L), written?.excludedAlbumIds)
    }

    @Test
    fun locationPickerToggleWritesExclusion() {
        var written: StoryCardsConfig? = null
        testContent {
            StoryCardTypeDetailContent(
                type = StoryCardType.LOCATIONS,
                config = StoryCardsConfig(),
                albumsState = AlbumState(),
                categories = emptyList(),
                locationKeys = listOf("Berlin, Germany", "Paris, France"),
                onConfigChange = { written = it },
            )
        }

        composeRule.onNodeWithText("Add excluded sources").performClick()
        composeRule.onNodeWithText("Exclude locations").assertIsDisplayed()
        composeRule.onNodeWithText("Paris, France").performClick()

        assertEquals(setOf("Paris, France"), written?.excludedLocationKeys)
    }

    @Test
    fun exclusionEntryIsRemovableViaTrailingAffordance() {
        var written: StoryCardsConfig? = null
        val camera = album(id = 7, label = "Camera")
        testContent {
            StoryCardTypeDetailContent(
                type = StoryCardType.ALBUMS,
                config = StoryCardsConfig(excludedAlbumIds = setOf(7L)),
                albumsState = AlbumState(
                    albums = listOf(camera),
                    albumsWithBlacklisted = listOf(camera),
                ),
                categories = emptyList(),
                locationKeys = emptyList(),
                onConfigChange = { written = it },
            )
        }

        composeRule.onNodeWithText("Camera").assertIsDisplayed()
        composeRule
            .onNodeWithContentDescription("Remove Camera from excluded sources")
            .assertHasClickAction()
            .performClick()

        assertEquals(emptySet<Long>(), written?.excludedAlbumIds)
    }
}
