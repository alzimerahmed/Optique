/*
 * SPDX-FileCopyrightText: 2023-2026 alzimerahmed
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.feature_node.presentation.memories

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.dot.gallery.R
import com.dot.gallery.core.LocalEventHandler
import com.dot.gallery.core.setFollowTheme
import com.dot.gallery.feature_node.domain.model.Media
import com.dot.gallery.feature_node.domain.util.getUri
import com.dot.gallery.feature_node.presentation.mediaview.rememberedDerivedState
import com.dot.gallery.feature_node.presentation.memories.audio.RecapAudioPlayer
import com.dot.gallery.feature_node.presentation.memories.audio.rememberRecapAudioPlayer
import com.dot.gallery.feature_node.presentation.util.GlideInvalidation
import com.dot.gallery.feature_node.presentation.util.Screen
import com.dot.gallery.feature_node.presentation.util.rememberWindowInsetsController
import kotlinx.coroutines.launch

/** Playback state machine for the recap pager (U3, R5): Playing auto-advances,
 *  Paused waits for the user, Completed shows the end-of-recap overlay. */
internal enum class RecapPlaybackState {
    Playing,
    Paused,
    Completed,
}

private const val SLIDE_DURATION_MILLIS = 5_000
private const val TAP_ZONE_EDGE_FRACTION = 1f / 3f

/**
 * Resolves the playable set for the recap route. [kind] picks the primary list —
 * the on-this-day group or the year recap for [year] — with the other list as
 * fallback so a stale card or kind-less deep link never dead-ends. Absent or
 * unknown kinds keep the legacy recap-first order.
 */
internal fun resolvePlaybackMedia(
    content: MemoriesSectionState.Content,
    year: Int,
    kind: String?,
): List<Media.UriMedia> {
    val recap = content.recaps.firstOrNull { it.year == year }?.let(::recapShareSet)
    val onThisDay = content.onThisDayGroups.firstOrNull { it.year == year }?.media
    return (
        when (kind) {
            Screen.RecapPlaybackScreen.KIND_ON_THIS_DAY -> onThisDay ?: recap
            else -> recap ?: onThisDay
        }
    ).orEmpty()
}

/**
 * Story-style yearly recap player (R1/R5, modeled on `StoryViewerScreen`).
 *
 * [media] is `null` while the section state is still loading; an empty (resolved)
 * list means the requested year has nothing to play and the screen dismisses
 * itself via [onDismiss]. Swiping pauses playback, tapping the center toggles
 * play/pause and the left/right thirds step through photos. A bundled soundtrack
 * toggle sits in the bottom bar, silent by default.
 */
@Composable
fun RecapPlaybackScreen(
    year: Int,
    media: List<Media.UriMedia>?,
    onDismiss: () -> Unit,
) {
    // Force light status bar icons (white) on dark background, restore on exit
    val windowInsetsController = rememberWindowInsetsController()
    val eventHandler = LocalEventHandler.current
    DisposableEffect(Unit) {
        val previousLight = windowInsetsController.isAppearanceLightStatusBars
        windowInsetsController.isAppearanceLightStatusBars = false
        eventHandler.setFollowTheme(false)
        onDispose {
            windowInsetsController.isAppearanceLightStatusBars = previousLight
            eventHandler.setFollowTheme(true)
        }
    }

    // null = still loading, show spinner
    if (media == null) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(color = Color.White)
        }
        return
    }

    // Resolved but empty — dismiss via side effect (not during composition)
    if (media.isEmpty()) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }

    RecapPlaybackPager(
        year = year,
        media = media,
        onDismiss = onDismiss,
    )
}

@Composable
private fun RecapPlaybackPager(
    year: Int,
    media: List<Media.UriMedia>,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val player = rememberRecapAudioPlayer()
    val audioState by player.state.collectAsStateWithLifecycle()

    val swipeEnabled = media.size > 1
    val pagerState = rememberPagerState(pageCount = { media.size })
    var playbackState by rememberSaveable { mutableStateOf(RecapPlaybackState.Playing) }
    val progress = remember { Animatable(0f) }

    // Auto-advance timer — restarts whenever the settled page or state changes.
    LaunchedEffect(pagerState.currentPage, playbackState, media) {
        if (playbackState != RecapPlaybackState.Playing) return@LaunchedEffect
        progress.snapTo(0f)
        progress.animateTo(
            targetValue = 1f,
            animationSpec =
                tween(
                    durationMillis = SLIDE_DURATION_MILLIS,
                    easing = LinearEasing,
                ),
        )
        if (pagerState.currentPage < media.lastIndex) {
            pagerState.animateScrollToPage(pagerState.currentPage + 1)
        } else {
            playbackState = RecapPlaybackState.Completed
        }
    }

    // User drags pause playback and dismiss the completion overlay; programmatic
    // auto-advance scrolls do not surface as DragInteraction, so only real swipes
    // land here.
    LaunchedEffect(pagerState) {
        pagerState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start &&
                playbackState != RecapPlaybackState.Paused
            ) {
                playbackState = RecapPlaybackState.Paused
            }
        }
    }

    fun stepBack() {
        // Stepping back out of the Completed state dismisses the overlay.
        if (playbackState == RecapPlaybackState.Completed) {
            playbackState = RecapPlaybackState.Paused
        }
        val target = pagerState.currentPage - 1
        if (target >= 0) scope.launch { pagerState.animateScrollToPage(target) }
    }

    fun stepForward() {
        if (pagerState.currentPage < media.lastIndex) {
            scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
        } else {
            playbackState = RecapPlaybackState.Completed
        }
    }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color.Black),
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            userScrollEnabled = swipeEnabled,
            key = { index -> media[index].id },
            beyondViewportPageCount = 0,
        ) { page ->
            RecapPage(
                media = media[page],
                contentDescription =
                    if (year >= 0) {
                        stringResource(
                            R.string.memories_page_cd,
                            year,
                            page + 1,
                            media.size,
                        )
                    } else {
                        stringResource(
                            R.string.memories_page_cd_generic,
                            page + 1,
                            media.size,
                        )
                    },
            )
        }

        // Gesture overlay: left/right thirds step, center toggles play/pause.
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .pointerInput(swipeEnabled) {
                        detectTapGestures(
                            onTap = { offset ->
                                val width = size.width
                                when {
                                    offset.x < width * TAP_ZONE_EDGE_FRACTION -> stepBack()
                                    offset.x > width * (1f - TAP_ZONE_EDGE_FRACTION) -> stepForward()
                                    else -> {
                                        playbackState =
                                            when (playbackState) {
                                                RecapPlaybackState.Playing -> RecapPlaybackState.Paused
                                                RecapPlaybackState.Paused -> RecapPlaybackState.Playing
                                                RecapPlaybackState.Completed -> playbackState
                                            }
                                    }
                                }
                            },
                        )
                    },
        )

        // Top gradient + segmented progress + controls
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .align(Alignment.TopCenter)
                    .background(
                        Brush.verticalGradient(
                            colors =
                                listOf(
                                    Color.Black.copy(alpha = 0.6f),
                                    Color.Transparent,
                                ),
                        ),
                    ),
        )
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                media.forEachIndexed { index, _ ->
                    val segmentProgress by rememberedDerivedState(
                        pagerState.currentPage,
                        index,
                        progress.value,
                    ) {
                        when {
                            index < pagerState.currentPage -> 1f
                            index == pagerState.currentPage -> progress.value
                            else -> 0f
                        }
                    }
                    Box(
                        modifier =
                            Modifier
                                .weight(1f)
                                .height(3.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(Color.White.copy(alpha = 0.3f)),
                    ) {
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxWidth(segmentProgress)
                                    .height(3.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(Color.White),
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onDismiss,
                    modifier =
                        Modifier
                            .padding(end = 8.dp)
                            .background(Color.Black.copy(alpha = 0.4f), CircleShape),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.back_cd),
                        tint = Color.White,
                    )
                }

                Text(
                    // A kind-less/invalid deep link resolves year to -1 — never render it.
                    text = if (year >= 0) "$year" else stringResource(R.string.memories_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )

                // Share is available during playback too (R8/F3) — always the
                // full featured set, never just the slide the pager is on.
                IconButton(
                    onClick = {
                        if (playbackState == RecapPlaybackState.Playing) {
                            playbackState = RecapPlaybackState.Paused
                        }
                        scope.launch { context.shareRecapMedia(media) }
                    },
                    modifier =
                        Modifier
                            .padding(end = 8.dp)
                            .background(Color.Black.copy(alpha = 0.4f), CircleShape),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Share,
                        contentDescription = stringResource(R.string.memories_share),
                        tint = Color.White,
                        modifier = Modifier.size(22.dp),
                    )
                }

                IconButton(
                    onClick = {
                        playbackState =
                            when (playbackState) {
                                RecapPlaybackState.Playing -> RecapPlaybackState.Paused
                                RecapPlaybackState.Paused -> RecapPlaybackState.Playing
                                RecapPlaybackState.Completed -> playbackState
                            }
                    },
                    modifier =
                        Modifier.background(
                            Color.Black.copy(alpha = 0.4f),
                            CircleShape,
                        ),
                ) {
                    Icon(
                        imageVector =
                            if (playbackState == RecapPlaybackState.Playing) {
                                Icons.Outlined.Pause
                            } else {
                                Icons.Outlined.PlayArrow
                            },
                        contentDescription =
                            stringResource(
                                if (playbackState == RecapPlaybackState.Playing) {
                                    R.string.memories_pause
                                } else {
                                    R.string.memories_resume
                                },
                            ),
                        tint = Color.White,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }

        // Bottom: soundtrack toggle + page counter
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            colors =
                                listOf(
                                    Color.Transparent,
                                    Color.Black.copy(alpha = 0.5f),
                                ),
                        ),
                    ).navigationBarsPadding()
                    .padding(bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SoundtrackToggle(
                isPlaying = audioState.isPlaying,
                isAvailable = audioState.isSoundtrackAvailable,
                onToggle = player::setEnabled,
            )

            Text(
                text = "${pagerState.currentPage + 1} / ${media.size}",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                modifier =
                    Modifier
                        .background(
                            Color.Black.copy(alpha = 0.4f),
                            RoundedCornerShape(100),
                        ).padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        // End-of-recap overlay — action bar pinned to the bottom so it never
        // covers the photo; both buttons keep a 48dp touch target.
        if (playbackState == RecapPlaybackState.Completed) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                colors =
                                    listOf(
                                        Color.Transparent,
                                        Color.Black.copy(alpha = 0.7f),
                                    ),
                            ),
                        ).navigationBarsPadding()
                        .padding(horizontal = 24.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text =
                        if (year >= 0) {
                            stringResource(R.string.memories_recap_complete, year)
                        } else {
                            stringResource(R.string.memories_recap_complete_generic)
                        },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    IconButton(
                        onClick = {
                            playbackState = RecapPlaybackState.Playing
                            scope.launch { pagerState.scrollToPage(0) }
                        },
                        modifier =
                            Modifier
                                .size(48.dp)
                                .background(Color.White.copy(alpha = 0.2f), CircleShape),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Replay,
                            contentDescription = stringResource(R.string.memories_replay),
                            tint = Color.White,
                        )
                    }
                    IconButton(
                        onClick = { scope.launch { context.shareRecapMedia(media) } },
                        modifier =
                            Modifier
                                .size(48.dp)
                                .background(Color.White.copy(alpha = 0.2f), CircleShape),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Share,
                            contentDescription = stringResource(R.string.memories_share),
                            tint = Color.White,
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier =
                            Modifier
                                .size(48.dp)
                                .background(Color.White.copy(alpha = 0.2f), CircleShape),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = stringResource(R.string.memories_close),
                            tint = Color.White,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun RecapPage(
    media: Media.UriMedia,
    contentDescription: String,
) {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .semantics(mergeDescendants = true) {
                    this.contentDescription = contentDescription
                },
    ) {
        GlideImage(
            model = media.getUri(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
            requestBuilderTransform = {
                it.signature(GlideInvalidation.signature(media))
            },
        )
    }
}

/**
 * Soundtrack switch (R5/KTD5): silent by default, `Role.Switch` semantics with a
 * state-announcing content description. When the bundled asset is missing the
 * toggle renders disabled with the `memories_soundtrack_unavailable` explanation.
 */
@Composable
private fun SoundtrackToggle(
    isPlaying: Boolean,
    isAvailable: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val stateDescription =
        stringResource(
            if (isPlaying) R.string.memories_soundtrack_on_cd else R.string.memories_soundtrack_off_cd,
        )
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier =
                Modifier
                    .semantics(mergeDescendants = true) {
                        contentDescription = stateDescription
                    }.toggleable(
                        value = isPlaying,
                        enabled = isAvailable,
                        role = Role.Switch,
                        onValueChange = onToggle,
                    ).sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(100))
                    .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.MusicNote,
                contentDescription = null,
                tint = Color.White,
            )
            Text(
                text = stringResource(R.string.memories_soundtrack),
                style = MaterialTheme.typography.labelLarge,
                color = Color.White,
            )
            Switch(
                checked = isPlaying,
                onCheckedChange = null,
                enabled = isAvailable,
                modifier = Modifier.clearAndSetSemantics { },
            )
        }
        if (!isAvailable) {
            Text(
                text = stringResource(R.string.memories_soundtrack_unavailable),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp),
            )
        }
    }
}
