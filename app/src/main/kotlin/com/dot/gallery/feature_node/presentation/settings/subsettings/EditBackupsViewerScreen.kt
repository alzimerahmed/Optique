/*
 * SPDX-FileCopyrightText: 2023-2026 alzimerahmed
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.feature_node.presentation.settings.subsettings

import android.os.Build
import android.text.format.Formatter
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.AutoDelete
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.compose.ui.layout.ContentScale
import java.io.File
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.github.panpf.zoomimage.compose.glide.ExperimentalGlideComposeApi as ZoomExperimentalGlideComposeApi
import com.dot.gallery.core.Settings
import com.dot.gallery.core.presentation.components.util.LocalBatteryStatus
import com.dot.gallery.core.presentation.components.util.ProvideBatteryStatus
import com.dot.gallery.core.presentation.components.util.swipe
import com.dot.gallery.feature_node.presentation.mediaview.components.media.BlurredMediaBackground
import com.github.panpf.zoomimage.GlideZoomAsyncImage
import com.github.panpf.zoomimage.rememberGlideZoomState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import com.dot.gallery.feature_node.presentation.util.LocalHazeState
import androidx.compose.animation.core.tween
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dot.gallery.R
import com.dot.gallery.core.Constants.Animation.enterAnimation
import com.dot.gallery.core.Constants.Animation.exitAnimation
import com.dot.gallery.core.LocalEventHandler
import com.dot.gallery.core.setFollowTheme
import com.dot.gallery.feature_node.domain.model.Media
import com.dot.gallery.feature_node.presentation.mediaview.LocalMediaViewerVisualPolicy
import com.dot.gallery.feature_node.presentation.mediaview.components.media.MediaPreviewComponent
import com.dot.gallery.feature_node.presentation.mediaview.rememberedDerivedState
import com.dot.gallery.feature_node.presentation.common.components.OptionItem
import com.dot.gallery.feature_node.presentation.common.components.OptionSheet
import com.dot.gallery.feature_node.presentation.util.rememberAppBottomSheetState
import com.dot.gallery.ui.theme.Alpha
import com.dot.gallery.ui.theme.BlackScrim
import com.dot.gallery.ui.theme.ComponentSize
import com.dot.gallery.ui.theme.MotionSpec
import com.dot.gallery.ui.theme.Spacing
import com.dot.gallery.ui.theme.isDarkTheme
import kotlinx.coroutines.flow.collectLatest

@OptIn(ExperimentalMaterial3Api::class, ExperimentalHazeMaterialsApi::class)
@Composable
fun EditBackupsViewerScreen(
    paddingValues: PaddingValues = PaddingValues(0.dp)
) {
    val viewModel = hiltViewModel<EditBackupsViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val eventHandler = LocalEventHandler.current
    val context = LocalContext.current
    val activity = LocalActivity.current

    val mediaList = state.mediaList
    val backups = state.backups

    // Pager state
    val pagerState = rememberPagerState(
        initialPage = 0,
        pageCount = { mediaList.size }
    )

    var currentPage by rememberSaveable { mutableIntStateOf(0) }
    val currentMedia by rememberedDerivedState(mediaList, currentPage) {
        mediaList.getOrNull(currentPage)
    }

    LaunchedEffect(mediaList) {
        snapshotFlow { pagerState.currentPage }.collectLatest { page ->
            currentPage = page
        }
    }

    // UI visibility
    var showUI by rememberSaveable { mutableStateOf(true) }

    // Header expansion: 0f = collapsed, 1f = expanded
    var headerExpansion by rememberSaveable { mutableFloatStateOf(0f) }
    val animatedExpansion by animateFloatAsState(
        targetValue = headerExpansion,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "headerExpansion"
    )

    // Selection state
    var selectionMode by rememberSaveable { mutableStateOf(false) }
    var selectedIds by rememberSaveable { mutableStateOf(emptySet<Long>()) }

    // Original vs Edited toggle state (keyed by mediaId)
    var showingOriginalIds by rememberSaveable { mutableStateOf(emptySet<Long>()) }
    val backupsByMediaId = remember(backups) {
        backups.associateBy { it.mediaId }
    }

    // Delete confirmation dialog
    var showDeleteDialog by rememberSaveable { mutableStateOf(false) }

    // Action menu
    var showActionMenu by rememberSaveable { mutableStateOf(false) }

    // Storage percentage
    val usable = state.totalSize + state.freeSpace
    val usedPercent = if (usable > 0) {
        "%.1f".format((state.totalSize.toFloat() / usable) * 100f)
    } else "0"
    val storageFraction = if (usable > 0) {
        (state.totalSize.toFloat() / usable).coerceIn(0f, 1f)
    } else 0f

    // Insets
    val statusBarPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val systemBarsPadding = WindowInsets.systemBars.asPaddingValues()

    val isDark = isDarkTheme()

    if (state.isLoading || mediaList.isEmpty()) {
        // Status bar follows theme in empty state
        DisposableEffect(isDark) {
            val window = (activity as? ComponentActivity)?.window ?: return@DisposableEffect onDispose {}
            val insetsController = WindowCompat.getInsetsController(window, window.decorView)
            val wasLight = insetsController.isAppearanceLightStatusBars
            insetsController.isAppearanceLightStatusBars = !isDark
            eventHandler.setFollowTheme(true)
            onDispose {
                insetsController.isAppearanceLightStatusBars = wasLight
            }
        }
        // Empty / loading state
        Box(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center
        ) {
            if (state.isLoading) {
                LinearProgressIndicator()
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Spacing.Small)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Image,
                        contentDescription = null,
                        modifier = Modifier.size(ComponentSize.MinimumTouchTarget),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    Text(
                        text = stringResource(R.string.edit_backups_no_backups),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = stringResource(R.string.edit_backups_no_backups_summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
            // Back button overlay
            IconButton(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = statusBarPadding + Spacing.Small, start = Spacing.MediumSmall)
                    .background(
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        shape = CircleShape
                    ),
                onClick = {
                    runCatching {
                        (activity as ComponentActivity).onBackPressedDispatcher.onBackPressed()
                    }.getOrElse { eventHandler.navigateUpAction() }
                }
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.back_cd),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        }
        return
    }

    // Viewer surface follows the same policy as MediaViewScreen: dark unless the
    // media-viewer visual policy permits a light background in light theme.
    val visualPolicy = LocalMediaViewerVisualPolicy.current
    val darkViewer = visualPolicy.usesDarkBackground(isDark)
    val backgroundColor = if (darkViewer) Color.Black else Color.White
    // Chrome colors derive from the surface so controls stay legible on either theme.
    val viewerContentColor = if (darkViewer) Color.White else Color.Black
    val viewerScrimColor = if (darkViewer) BlackScrim else Color.White.copy(alpha = 0.6f)
    val accentColor = MaterialTheme.colorScheme.primary

    // Status bar icons contrast with the viewer surface, not the system theme.
    DisposableEffect(darkViewer) {
        val window = (activity as? ComponentActivity)?.window ?: return@DisposableEffect onDispose {}
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        val wasLight = insetsController.isAppearanceLightStatusBars
        insetsController.isAppearanceLightStatusBars = !darkViewer
        eventHandler.setFollowTheme(false)
        onDispose {
            insetsController.isAppearanceLightStatusBars = wasLight
            eventHandler.setFollowTheme(true)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor)
    ) {
        // ── Media Pager ──
        // The pager keeps a fixed top inset — the expanding header card slides over
        // it via graphicsLayer.translationY instead. Animating a layout padding here
        // re-laid-out the whole GlideImage/haze/blur pager subtree on every frame.
        HorizontalPager(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = statusBarPadding + ComponentSize.ButtonHeight)
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)),
            state = pagerState,
            flingBehavior = PagerDefaults.flingBehavior(
                state = pagerState,
                snapAnimationSpec = spring(stiffness = Spring.StiffnessMedium),
                snapPositionalThreshold = 0.3f
            ),
            key = { index -> mediaList.getOrNull(index)?.id ?: "empty_$index" },
            pageSpacing = Spacing.Medium,
            beyondViewportPageCount = 0
        ) { index ->
            val media by rememberedDerivedState(mediaList, index) {
                mediaList.getOrNull(index)
            }
            val canPlay = rememberSaveable(media) { mutableStateOf(false) }
            val mediaId = media?.id ?: -1L
            val isShowingOriginal = mediaId in showingOriginalIds
            val backupInfo = backupsByMediaId[mediaId]

            AnimatedVisibility(
                visible = media != null,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    Crossfade(
                        targetState = isShowingOriginal,
                        label = "originalEditedCrossfade"
                    ) { showOriginal ->
                        if (showOriginal && backupInfo != null) {
                            Box(modifier = Modifier
                                .fillMaxSize()
                                .hazeSource(state = LocalHazeState.current)
                            ) {
                                // Blurred background for original (file-based)
                                @OptIn(ExperimentalGlideComposeApi::class)
                                ProvideBatteryStatus {
                                    val allowBlur by Settings.Misc.rememberAllowBlur()
                                    val isPowerSavingMode = LocalBatteryStatus.current.isPowerSavingMode
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && allowBlur && !isPowerSavingMode) {
                                        val blurAlpha by animateFloatAsState(
                                            animationSpec = tween(MotionSpec.EmphasizedMs),
                                            targetValue = if (showUI) 0.7f else 0f,
                                            label = "blurAlpha"
                                        )
                                        GlideImage(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .alpha(blurAlpha)
                                                .blur(100.dp),
                                            model = File(backupInfo.backupPath),
                                            contentDescription = null,
                                            contentScale = ContentScale.Crop
                                        )
                                    }
                                }
                                // Zoomable original image
                                @OptIn(ExperimentalGlideComposeApi::class, ZoomExperimentalGlideComposeApi::class)
                                GlideZoomAsyncImage(
                                    zoomState = rememberGlideZoomState(),
                                    model = File(backupInfo.backupPath),
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .swipe(
                                            onSwipeDown = {
                                                runCatching {
                                                    (activity as ComponentActivity).onBackPressedDispatcher.onBackPressed()
                                                }.getOrElse { eventHandler.navigateUpAction() }
                                            }
                                        ),
                                    onTap = { showUI = !showUI },
                                    alignment = Alignment.Center,
                                    contentDescription = stringResource(R.string.edit_backups_viewing_original),
                                    scrollBar = null
                                )
                            }
                        } else {
                            var offset by remember { mutableStateOf(IntOffset(0, 0)) }
                            MediaPreviewComponent<Media.UriMedia>(
                                modifier = Modifier,
                                media = media,
                                uiEnabled = showUI,
                                playWhenReady = canPlay,
                                onSwipeDown = {
                                    runCatching {
                                        (activity as ComponentActivity).onBackPressedDispatcher.onBackPressed()
                                    }.getOrElse { eventHandler.navigateUpAction() }
                                },
                                offset = offset,
                                isPanorama = false,
                                isPhotosphere = false,
                                isMotionPhoto = false,
                                motionPhotoState = null,
                                currentVault = null,
                                rotationDisabled = true,
                                onImageRotated = {},
                                onItemClick = {
                                    showUI = !showUI
                                }
                            ) { _, _, _, _, _, _, _ ->
                                // No video controller overlay for backups viewer
                            }
                        }
                    }
                }
            }
        }

        // ── Drag gesture area for header expansion ──
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(statusBarPadding + 120.dp + (140.dp * animatedExpansion))
                .align(Alignment.TopCenter)
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragEnd = {
                            headerExpansion = if (headerExpansion > 0.5f) 1f else 0f
                        },
                        onVerticalDrag = { _, dragAmount ->
                            val delta = dragAmount / 400f
                            headerExpansion = (headerExpansion + delta).coerceIn(0f, 1f)
                        }
                    )
                }
        )

        // ── Top Bar ──
        AnimatedVisibility(
            visible = showUI,
            enter = enterAnimation(MotionSpec.EmphasizedMs),
            exit = exitAnimation(MotionSpec.EmphasizedMs)
        ) {
            val gradientColor by animateColorAsState(viewerScrimColor)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(gradientColor, Color.Transparent)
                        )
                    )
                    .padding(top = statusBarPadding)
            ) {
                // Row: Back | Center text | Action menu
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.Small, vertical = Spacing.Small),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val buttonBackground = viewerContentColor.copy(alpha = Alpha.StateLayer)

                    // Back button
                    IconButton(
                        modifier = Modifier
                            .padding(horizontal = Spacing.Small)
                            .clip(CircleShape)
                            .background(color = buttonBackground, shape = CircleShape),
                        onClick = {
                            runCatching {
                                (activity as ComponentActivity).onBackPressedDispatcher.onBackPressed()
                            }.getOrElse { eventHandler.navigateUpAction() }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back_cd),
                            tint = viewerContentColor
                        )
                    }

                    // Center: Used space (collapsed only)
                    val centerAlpha by animateFloatAsState(
                        targetValue = 1f - animatedExpansion,
                        label = "centerAlpha"
                    )
                    Text(
                        text = stringResource(
                            R.string.edit_backups_used_percent,
                            usedPercent
                        ),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                        color = viewerContentColor,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .weight(1f)
                            .graphicsLayer { alpha = centerAlpha }
                    )

                    // Action menu button
                    IconButton(
                        modifier = Modifier
                            .padding(horizontal = Spacing.Small)
                            .clip(CircleShape)
                            .background(color = buttonBackground, shape = CircleShape),
                        onClick = { showActionMenu = true }
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.MoreVert,
                            contentDescription = stringResource(R.string.edit_backups_section_actions),
                            tint = viewerContentColor
                        )
                    }
                }

                // ── Expanded storage card ──
                val cardAlpha by animateFloatAsState(
                    targetValue = animatedExpansion,
                    label = "cardAlpha"
                )
                val cardColor = viewerContentColor.copy(alpha = 0.1f)
                // Matches the old pager top-padding travel so the card slides down over
                // the (now static) media with the same visual distance.
                val cardSlideDistance = 160.dp
                if (animatedExpansion > 0.05f) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .graphicsLayer {
                                alpha = cardAlpha
                                translationY = -(1f - animatedExpansion) * cardSlideDistance.toPx()
                            }
                            .padding(
                                start = Spacing.Medium,
                                end = Spacing.Medium,
                                top = Spacing.Medium,
                                bottom = Spacing.Medium
                            )
                            .clip(RoundedCornerShape(20.dp))
                            .background(color = cardColor)
                            .padding(Spacing.Medium)
                            .animateContentSize(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.edit_backups_storage),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = viewerContentColor
                        )

                        LinearProgressIndicator(
                            progress = { storageFraction },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = accentColor,
                            trackColor = viewerContentColor.copy(alpha = Alpha.StateLayer),
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(
                                    text = Formatter.formatShortFileSize(context, state.totalSize),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = accentColor
                                )
                                Text(
                                    text = stringResource(
                                        R.string.edit_backups_storage_summary,
                                        Formatter.formatShortFileSize(context, state.totalSize),
                                        backups.size
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = viewerContentColor.copy(alpha = 0.6f)
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = Formatter.formatShortFileSize(context, state.freeSpace),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = viewerContentColor
                                )
                                Text(
                                    text = stringResource(R.string.edit_backups_free_space),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = viewerContentColor.copy(alpha = 0.6f)
                                )
                            }
                        }
                    }
                }
            }
        }

        // ── Bottom bar: Selection (left) + Segmented toggle (right) ──
        AnimatedVisibility(
            visible = showUI,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            val isCurrentSelected = currentMedia?.let { it.id in selectedIds } ?: false
            val currentMediaId = currentMedia?.id ?: -1L
            val isShowingOriginal = currentMediaId in showingOriginalIds
            val hasBackup = backupsByMediaId.containsKey(currentMediaId)
            val pillShape = RoundedCornerShape(100)

            val hazeState = LocalHazeState.current
            val hazeStyle = HazeMaterials.ultraThin(
                containerColor = BlackScrim
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min)
                    .navigationBarsPadding()
                    .padding(start = Spacing.Medium, end = Spacing.Medium, bottom = Spacing.MediumSmall),
                horizontalArrangement = Arrangement.spacedBy(Spacing.Small),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // ── Selection pill (left) ──
                Row(
                    modifier = Modifier
                        .fillMaxHeight()
                        .clip(pillShape)
                        .hazeEffect(state = hazeState, style = hazeStyle)
                        .background(BlackScrim)
                        .padding(horizontal = Spacing.ExtraSmall, vertical = Spacing.ExtraSmall),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Select button — icon + text as single clickable
                    Row(
                        modifier = Modifier
                            .clip(pillShape)
                            .background(
                                if (isCurrentSelected) Color.White.copy(alpha = 0.15f)
                                else Color.Transparent
                            )
                            .clickable {
                                if (!selectionMode) {
                                    selectionMode = true
                                }
                                currentMedia?.let { media ->
                                    selectedIds = if (media.id in selectedIds) {
                                        val newSet = selectedIds - media.id
                                        if (newSet.isEmpty()) selectionMode = false
                                        newSet
                                    } else {
                                        selectedIds + media.id
                                    }
                                }
                            }
                            .padding(start = 10.dp, end = 14.dp, top = Spacing.Small, bottom = Spacing.Small),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.Small)
                    ) {
                        Icon(
                            imageVector = if (isCurrentSelected) Icons.Outlined.CheckCircle
                            else Icons.Outlined.RadioButtonUnchecked,
                            contentDescription = stringResource(R.string.edit_backups_select),
                            tint = Color.White,
                            modifier = Modifier.size(Spacing.MediumLarge)
                        )
                        Text(
                            text = if (selectionMode && selectedIds.isNotEmpty()) {
                                stringResource(
                                    R.string.edit_backups_selected_count,
                                    selectedIds.size
                                )
                            } else {
                                stringResource(R.string.edit_backups_select)
                            },
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Medium,
                            color = Color.White
                        )
                    }

                    // Delete button
                    IconButton(
                        enabled = selectionMode && selectedIds.isNotEmpty(),
                        onClick = {
                            viewModel.deleteSelected(selectedIds.toList()) {
                                selectedIds = emptySet()
                                selectionMode = false
                            }
                        },
                        modifier = Modifier.size(ComponentSize.IconLarge)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.DeleteOutline,
                            contentDescription = stringResource(R.string.edit_backups_delete_selected),
                            tint = if (selectionMode && selectedIds.isNotEmpty())
                                MaterialTheme.colorScheme.error
                            else Color.White.copy(alpha = Alpha.Disabled)
                        )
                    }
                }

                // ── Segmented toggle (right) — only if backup exists ──
                if (hasBackup) {
                    val selectedSegmentBg = Color.White.copy(alpha = 0.2f)

                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(pillShape)
                            .hazeEffect(state = hazeState, style = hazeStyle)
                            .background(BlackScrim)
                            .padding(Spacing.ExtraSmall),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // "Original" segment
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(pillShape)
                                .background(
                                    if (isShowingOriginal) selectedSegmentBg
                                    else Color.Transparent
                                )
                                .clickable {
                                    if (currentMediaId != -1L && !isShowingOriginal) {
                                        showingOriginalIds = showingOriginalIds + currentMediaId
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(R.string.edit_backups_viewing_original),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (isShowingOriginal) FontWeight.Bold else FontWeight.Medium,
                                color = if (isShowingOriginal) Color.White else Color.White.copy(alpha = 0.5f),
                                maxLines = 1
                            )
                        }

                        // "Edited" segment
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(pillShape)
                                .background(
                                    if (!isShowingOriginal) selectedSegmentBg
                                    else Color.Transparent
                                )
                                .clickable {
                                    if (currentMediaId != -1L && isShowingOriginal) {
                                        showingOriginalIds = showingOriginalIds - currentMediaId
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(R.string.edit_backups_viewing_edited),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (!isShowingOriginal) FontWeight.Bold else FontWeight.Medium,
                                color = if (!isShowingOriginal) Color.White else Color.White.copy(alpha = 0.5f),
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }

    // ── Actions Bottom Sheet ──
    val actionSheetState = rememberAppBottomSheetState()
    val scope = rememberCoroutineScope()

    val autoCleanupText = stringResource(R.string.edit_backups_auto_cleanup)
    val autoCleanupSummary = stringResource(R.string.edit_backups_auto_cleanup_summary)
    val deleteAllText = stringResource(R.string.edit_backups_delete_all)
    val deleteAllSummary = stringResource(R.string.edit_backups_delete_all_summary)
    val errorContainer = MaterialTheme.colorScheme.errorContainer
    val onErrorContainer = MaterialTheme.colorScheme.onErrorContainer

    val actionOptions = remember(
        autoCleanupText, autoCleanupSummary, deleteAllText, deleteAllSummary,
        errorContainer, onErrorContainer
    ) {
        mutableStateListOf(
            OptionItem(
                icon = Icons.Outlined.AutoDelete,
                text = autoCleanupText,
                summary = autoCleanupSummary,
                onClick = {
                    scope.launch { actionSheetState.hide() }
                    viewModel.autoCleanup { }
                }
            ),
            OptionItem(
                icon = Icons.Outlined.DeleteForever,
                text = deleteAllText,
                summary = deleteAllSummary,
                containerColor = errorContainer,
                contentColor = onErrorContainer,
                onClick = {
                    scope.launch { actionSheetState.hide() }
                    showDeleteDialog = true
                }
            )
        )
    }

    LaunchedEffect(showActionMenu) {
        if (showActionMenu) actionSheetState.show()
    }
    LaunchedEffect(actionSheetState.isVisible) {
        if (!actionSheetState.isVisible) showActionMenu = false
    }

    OptionSheet(
        state = actionSheetState,
        onDismiss = { showActionMenu = false },
        optionList = arrayOf(actionOptions)
    )

    // ── Delete All Dialog ──
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.edit_backups_delete_all)) },
            text = { Text(stringResource(R.string.edit_backups_delete_all_confirmation)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        viewModel.deleteAll {
                            runCatching {
                                (activity as ComponentActivity).onBackPressedDispatcher.onBackPressed()
                            }.getOrElse { eventHandler.navigateUpAction() }
                        }
                    }
                ) {
                    Text(
                        stringResource(R.string.action_confirm),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}
