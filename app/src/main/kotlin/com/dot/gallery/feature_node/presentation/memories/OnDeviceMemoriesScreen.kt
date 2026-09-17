/*
 * SPDX-FileCopyrightText: 2023-2026 alzimerahmed
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.feature_node.presentation.memories

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.PhotoAlbum
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.dot.gallery.R
import com.dot.gallery.core.LocalEventHandler
import com.dot.gallery.core.navigate
import com.dot.gallery.core.presentation.components.LoadingMedia
import com.dot.gallery.core.presentation.components.NavigationBackButton
import com.dot.gallery.feature_node.domain.memories.OnThisDayGroup
import com.dot.gallery.feature_node.domain.memories.YearRecap
import com.dot.gallery.feature_node.domain.util.getUri
import com.dot.gallery.feature_node.presentation.util.GlideInvalidation
import com.dot.gallery.feature_node.presentation.util.LocalHazeState
import com.dot.gallery.feature_node.presentation.util.Screen
import dev.chrisbanes.haze.LocalHazeStyle
import dev.chrisbanes.haze.hazeEffect

/**
 * Dedicated on-device Memories section (U3, R1/R10): "On this day" groups plus
 * per-year recaps computed by [MemoriesViewModel]. Tapping any entry opens the
 * story-style [RecapPlaybackScreen] for that year.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnDeviceMemoriesScreen(initialYear: Int? = null) {
    val viewModel = hiltViewModel<MemoriesViewModel>()
    val state by viewModel.sectionState.collectAsStateWithLifecycle()
    val eventHandler = LocalEventHandler.current
    val scrollBehavior =
        TopAppBarDefaults.exitUntilCollapsedScrollBehavior(
            state = rememberTopAppBarState(),
        )

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                modifier =
                    Modifier.hazeEffect(
                        state = LocalHazeState.current,
                        style = LocalHazeStyle.current,
                    ),
                title = { Text(stringResource(R.string.memories_title)) },
                navigationIcon = { NavigationBackButton() },
                scrollBehavior = scrollBehavior,
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        scrolledContainerColor = MaterialTheme.colorScheme.surface,
                    ),
            )
        },
    ) { innerPadding ->
        OnDeviceMemoriesSection(
            state = state,
            initialYear = initialYear,
            contentPadding = innerPadding,
            onPlay = { year, kind ->
                eventHandler.navigate(Screen.RecapPlaybackScreen.year(year, kind))
            },
            onRetry = viewModel::retry,
            onEmptyAction = { eventHandler.navigate(Screen.TimelineScreen()) },
        )
    }
}

/**
 * Stateless content area for [OnDeviceMemoriesScreen]; kept separate from the
 * Scaffold/Hilt wiring so it can be exercised directly in Robolectric tests.
 */
@Composable
internal fun OnDeviceMemoriesSection(
    state: MemoriesSectionState,
    onPlay: (year: Int, kind: String) -> Unit,
    onRetry: () -> Unit,
    onEmptyAction: () -> Unit,
    initialYear: Int? = null,
    contentPadding: PaddingValues = PaddingValues(),
) {
    when (state) {
        is MemoriesSectionState.Loading -> {
            LoadingMedia(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(contentPadding),
            )
        }

        is MemoriesSectionState.Empty -> {
            MemoriesEmptyState(
                onAction = onEmptyAction,
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(contentPadding),
            )
        }

        is MemoriesSectionState.Error -> {
            MemoriesErrorState(
                message = state.message,
                onRetry = onRetry,
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(contentPadding),
            )
        }

        is MemoriesSectionState.Content -> {
            MemoriesContent(
                content = state,
                initialYear = initialYear,
                contentPadding = contentPadding,
                onPlay = onPlay,
            )
        }
    }
}

@Composable
private fun MemoriesContent(
    content: MemoriesSectionState.Content,
    initialYear: Int?,
    contentPadding: PaddingValues,
    onPlay: (year: Int, kind: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val layoutDir = LocalLayoutDirection.current
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding =
            PaddingValues(
                start = contentPadding.calculateStartPadding(layoutDir) + 16.dp,
                end = contentPadding.calculateEndPadding(layoutDir) + 16.dp,
                top = contentPadding.calculateTopPadding() + 16.dp,
                bottom = contentPadding.calculateBottomPadding() + 32.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (content.onThisDayGroups.isNotEmpty()) {
            item(key = "on_this_day_header") {
                Text(
                    text = stringResource(R.string.memories_on_this_day),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
            items(
                items = content.onThisDayGroups,
                key = { "on_this_day_${it.year}" },
            ) { group ->
                OnThisDayCard(
                    group = group,
                    onClick = { onPlay(group.year, Screen.RecapPlaybackScreen.KIND_ON_THIS_DAY) },
                )
            }
        }
        if (content.recaps.isNotEmpty()) {
            item(key = "recaps_header") {
                Text(
                    text = stringResource(R.string.memories_recaps),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
            items(
                items = content.recaps,
                key = { "recap_${it.year}" },
            ) { recap ->
                YearRecapCard(
                    recap = recap,
                    onClick = { onPlay(recap.year, Screen.RecapPlaybackScreen.KIND_RECAP) },
                )
            }
        }
    }

    // Deep-link / card entry point: scroll straight to the requested year's entry.
    // Attempted once per composition — Content re-emissions must not yank the user
    // back to the target year after they scrolled away.
    var hasScrolledToInitial by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(initialYear, content) {
        if (initialYear == null || hasScrolledToInitial) return@LaunchedEffect
        hasScrolledToInitial = true
        val target = memoriesItemIndex(content, initialYear)
        if (target >= 0) listState.scrollToItem(target)
    }
}

/** Absolute LazyColumn index of the entry for [year], or -1 when absent. */
private fun memoriesItemIndex(
    content: MemoriesSectionState.Content,
    year: Int,
): Int {
    val groupIndex = content.onThisDayGroups.indexOfFirst { it.year == year }
    val recapIndex = content.recaps.indexOfFirst { it.year == year }
    val groupsBlock = if (content.onThisDayGroups.isEmpty()) 0 else 1 + content.onThisDayGroups.size
    return when {
        // +1 for each section's header item
        groupIndex >= 0 -> 1 + groupIndex
        recapIndex >= 0 -> groupsBlock + 1 + recapIndex
        else -> -1
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun OnThisDayCard(
    group: OnThisDayGroup,
    onClick: () -> Unit,
) {
    val cardDescription =
        pluralStringResource(
            R.plurals.memories_on_this_day_card_cd,
            group.media.size,
            group.year,
            group.media.size,
        )
    Card(
        onClick = onClick,
        modifier =
            Modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) {
                    contentDescription = cardDescription
                },
        shape = RoundedCornerShape(16.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "${group.year}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = stringResource(R.string.memories_on_this_day),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = pluralStringResource(R.plurals.memories_photo_count, group.media.size, group.media.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(12.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(
                    items = group.media,
                    key = { it.id },
                ) { media ->
                    GlideImage(
                        model = media.getUri(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier =
                            Modifier
                                .size(96.dp)
                                .clip(RoundedCornerShape(12.dp)),
                        requestBuilderTransform = {
                            it.signature(GlideInvalidation.signature(media))
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun YearRecapCard(
    recap: YearRecap,
    onClick: () -> Unit,
) {
    val cardDescription = stringResource(R.string.memories_recap_card_cd, recap.year)
    Card(
        onClick = onClick,
        modifier =
            Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
                contentDescription = cardDescription
            },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Box(modifier = Modifier.fillMaxWidth().height(200.dp)) {
            recap.featured.firstOrNull()?.let { cover ->
                GlideImage(
                    model = cover.getUri(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                    requestBuilderTransform = { it.signature(GlideInvalidation.signature(cover)) },
                )
            }
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(96.dp)
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f)),
                            ),
                        ),
            )
            Column(modifier = Modifier.align(Alignment.BottomStart).padding(16.dp)) {
                Text(
                    text = "${recap.year}",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                )
                Text(
                    text = pluralStringResource(R.plurals.memories_photo_count, recap.featured.size, recap.featured.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.8f),
                )
            }
        }
    }
}

@Composable
private fun MemoriesEmptyState(
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(top = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Icon(
            modifier = Modifier.size(128.dp),
            imageVector = Icons.Outlined.PhotoAlbum,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = stringResource(R.string.memories_empty_title),
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            text = stringResource(R.string.memories_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 32.dp),
        )
        Button(onClick = onAction) {
            Text(text = stringResource(R.string.memories_empty_action))
        }
    }
}

@Composable
private fun MemoriesErrorState(
    message: String?,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(top = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Icon(
            modifier = Modifier.size(128.dp),
            imageVector = Icons.Outlined.ErrorOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
        )
        Text(
            text = message ?: stringResource(R.string.memories_error_generic),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 32.dp),
        )
        Button(onClick = onRetry) {
            Text(text = stringResource(R.string.memories_error_retry))
        }
    }
}
