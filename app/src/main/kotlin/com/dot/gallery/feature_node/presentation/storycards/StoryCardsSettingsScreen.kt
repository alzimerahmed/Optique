/*
 * SPDX-FileCopyrightText: 2023-2026 alzimerahmed
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.feature_node.presentation.storycards

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dot.gallery.R
import com.dot.gallery.core.Position
import com.dot.gallery.core.SettingsEntity
import com.dot.gallery.core.Settings.Memories.rememberNotificationsEnabled
import com.dot.gallery.core.Settings.Misc.rememberStoryCardsConfig
import com.dot.gallery.core.Settings.Misc.rememberStoryViewerAutoAdvance
import com.dot.gallery.core.Settings.Misc.rememberStoryViewerDuration
import com.dot.gallery.core.presentation.components.NavigationBackButton
import com.dot.gallery.feature_node.domain.model.StoryCardType
import com.dot.gallery.feature_node.domain.model.StoryCardsConfig
import com.dot.gallery.feature_node.presentation.mediaview.rememberedDerivedState
import com.dot.gallery.feature_node.presentation.settings.components.SettingsItem
import com.dot.gallery.feature_node.presentation.storycards.components.icon
import com.dot.gallery.feature_node.presentation.util.rememberAppBottomSheetState
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberPermissionState
import kotlinx.coroutines.launch

@SuppressLint("InlinedApi") // POST_NOTIFICATIONS launch is SDK-gated below
@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun StoryCardsSettingsScreen(
    onNavigateBack: () -> Unit = {}
) {
    val context = LocalContext.current
    var configState by rememberStoryCardsConfig()
    // One decode per composition — the preference getter JSON-decodes on every read.
    val config = configState
    var autoAdvance by rememberStoryViewerAutoAdvance()
    var duration by rememberStoryViewerDuration()
    var memoriesNotifications by rememberNotificationsEnabled()

    // U5 detail surface: picker data seam + the per-type sheet state.
    val vm = hiltViewModel<StoryCardsSettingsViewModel>()
    val albumsState by vm.albumsState.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val locationKeys by vm.locationKeys.collectAsStateWithLifecycle()
    val detailSheetState = rememberAppBottomSheetState()
    var detailType by remember { mutableStateOf<StoryCardType?>(null) }
    val scope = rememberCoroutineScope()

    // API 33+: enabling the on-this-day notification toggle must also fire the
    // runtime POST_NOTIFICATIONS request. The toggle stays on when denied —
    // MemoriesNotifier re-checks the permission before every post.
    val notificationPermission = rememberPermissionState(
        permission = Manifest.permission.POST_NOTIFICATIONS,
        onPermissionResult = { }
    )

    val scrollBehavior =
        TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    // Drag-to-reorder state — swaps mutate a local order list during the
    // gesture; the single DataStore write happens on drag end.
    var draggingIndex by remember { mutableIntStateOf(-1) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    var dragOrder by remember { mutableStateOf<List<StoryCardType>?>(null) }
    val displayedOrder = dragOrder ?: config.normalizedOrder
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val itemHeightPx = remember(density) { with(density) { 72.dp.toPx() } }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.story_cards_settings_title)) },
                navigationIcon = { NavigationBackButton() },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    scrolledContainerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            userScrollEnabled = draggingIndex == -1,
            contentPadding = PaddingValues(
                start = padding.calculateStartPadding(LocalLayoutDirection.current),
                end = padding.calculateEndPadding(LocalLayoutDirection.current),
                top = 16.dp + padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding() + 16.dp
            ),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ── Master Toggle ──
            item(key = "master_toggle") {
                SettingsItem(
                    item = SettingsEntity.SwitchPreference(
                        title = stringResource(R.string.story_cards_enabled),
                        summary = stringResource(R.string.story_cards_enabled_summary),
                        isChecked = config.enabled,
                        onCheck = { configState = config.copy(enabled = it) },
                        screenPosition = Position.Alone
                    ),
                    modifier = Modifier
                        .widthInSheet()
                        .padding(bottom = 16.dp)
                )
            }

            // ── Card Types Header ──
            item(key = "card_types_header") {
                SettingsItem(
                    item = SettingsEntity.Header(
                        title = stringResource(R.string.story_cards_order_title)
                    )
                )
            }

            // ── Card Type Items (drag-to-reorder) ──
            itemsIndexed(
                items = displayedOrder,
                key = { _, type -> "card_${type.name}" }
            ) { index, type ->
                val isEnabled = type !in config.disabledTypes
                val position = cardItemPosition(index, displayedOrder.size)
                val isDragged = draggingIndex == index

                CardTypeListItem(
                    type = type,
                    isEnabled = isEnabled && config.enabled,
                    position = position,
                    isDragging = isDragged,
                    dragOffset = if (isDragged) dragOffsetY else 0f,
                    exclusionCount = config.exclusionCount(type),
                    onOpenDetail = {
                        detailType = type
                        scope.launch { detailSheetState.show() }
                    },
                    onDragStart = {
                        draggingIndex = index
                        dragOffsetY = 0f
                        dragOrder = config.normalizedOrder.toMutableList()
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    },
                    onDrag = { delta ->
                        dragOffsetY += delta
                        val swapThreshold = itemHeightPx * 0.5f
                        val currentOrder = dragOrder ?: config.normalizedOrder
                        if (dragOffsetY > swapThreshold && draggingIndex < currentOrder.lastIndex) {
                            val list = currentOrder.toMutableList()
                            val item = list.removeAt(draggingIndex)
                            list.add(draggingIndex + 1, item)
                            dragOrder = list
                            draggingIndex++
                            dragOffsetY -= itemHeightPx
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        } else if (dragOffsetY < -swapThreshold && draggingIndex > 0) {
                            val list = currentOrder.toMutableList()
                            val item = list.removeAt(draggingIndex)
                            list.add(draggingIndex - 1, item)
                            dragOrder = list
                            draggingIndex--
                            dragOffsetY += itemHeightPx
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                    },
                    onDragEnd = {
                        // Single DataStore write for the whole gesture — mid-drag
                        // swaps only touched the local order list.
                        dragOrder?.let { order ->
                            if (order != config.normalizedOrder) {
                                configState = config.copy(cardOrder = order)
                            }
                        }
                        dragOrder = null
                        draggingIndex = -1
                        dragOffsetY = 0f
                    },
                    onToggle = { checked ->
                        val newDisabled = if (checked) {
                            config.disabledTypes - type
                        } else {
                            config.disabledTypes + type
                        }
                        configState = config.copy(disabledTypes = newDisabled)
                    },
                    modifier = Modifier.widthInSheet()
                )
            }

            // ── Viewer Settings Header ──
            item(key = "viewer_header") {
                SettingsItem(
                    item = SettingsEntity.Header(
                        title = stringResource(R.string.story_viewer_settings_title)
                    ),
                    modifier = Modifier.padding(top = 16.dp)
                )
            }

            // ── Auto-Advance ──
            item(key = "auto_advance") {
                SettingsItem(
                    item = SettingsEntity.SwitchPreference(
                        title = stringResource(R.string.story_viewer_auto_advance),
                        summary = stringResource(R.string.story_viewer_auto_advance_summary),
                        isChecked = autoAdvance,
                        onCheck = { autoAdvance = it },
                        screenPosition = Position.Top
                    ),
                    modifier = Modifier.widthInSheet()
                )
            }

            // ── Duration ──
            item(key = "duration") {
                SettingsItem(
                    item = SettingsEntity.SeekPreference(
                        title = stringResource(R.string.story_viewer_duration),
                        summary = stringResource(R.string.story_viewer_duration_summary, duration),
                        minValue = 3f,
                        currentValue = duration.toFloatOrNull() ?: 5f,
                        maxValue = 10f,
                        step = 1,
                        seekSuffix = "s",
                        onSeek = { duration = it.toInt().toString() },
                        screenPosition = Position.Bottom
                    ),
                    modifier = Modifier.widthInSheet()
                )
            }

            // ── Memories ──
            item(key = "memories_header") {
                SettingsItem(
                    item = SettingsEntity.Header(
                        title = stringResource(R.string.memories_title)
                    ),
                    modifier = Modifier.padding(top = 16.dp)
                )
            }

            // ── On-this-day notification (default off) ──
            item(key = "memories_notification") {
                SettingsItem(
                    item = SettingsEntity.SwitchPreference(
                        title = stringResource(R.string.memories_notification_toggle),
                        summary = stringResource(R.string.memories_notification_toggle_summary),
                        isChecked = memoriesNotifications,
                        onCheck = { enabled ->
                            memoriesNotifications = enabled
                            if (enabled &&
                                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.POST_NOTIFICATIONS,
                                ) != PackageManager.PERMISSION_GRANTED
                            ) {
                                notificationPermission.launchPermissionRequest()
                            }
                        },
                        screenPosition = Position.Alone
                    ),
                    modifier = Modifier
                        .widthInSheet()
                        .padding(bottom = 16.dp)
                )
            }
        }
    }

    // Per-type detail surface (U5): caps + source exclusions. Disabled types
    // can still open it — configuration precedes enabling.
    StoryCardTypeDetailSheet(
        sheetState = detailSheetState,
        type = detailType,
        config = config,
        albumsState = albumsState,
        categories = categories,
        locationKeys = locationKeys,
        onConfigChange = { configState = it }
    )
}

/** Excluded-source count surfaced on the type row for discoverability (U5). */
private fun StoryCardsConfig.exclusionCount(type: StoryCardType): Int = when (type) {
    StoryCardType.ALBUMS -> excludedAlbumIds.size
    StoryCardType.CATEGORIES -> excludedCategoryIds.size
    StoryCardType.LOCATIONS -> excludedLocationKeys.size
    else -> 0
}

// ────────────────────────────────────────────────────────────────────────────────
// Card Type List Item — drag-to-reorder + toggle, matching SettingsItem style
// ────────────────────────────────────────────────────────────────────────────────

@Composable
internal fun CardTypeListItem(
    type: StoryCardType,
    isEnabled: Boolean,
    position: Position,
    isDragging: Boolean = false,
    dragOffset: Float = 0f,
    exclusionCount: Int = 0,
    onOpenDetail: () -> Unit = {},
    onDragStart: () -> Unit = {},
    onDrag: (Float) -> Unit = {},
    onDragEnd: () -> Unit = {},
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val backgroundColor = MaterialTheme.colorScheme.surfaceContainer

    // Fixed targets — plain dp values, no animation state needed.
    val fullCornerRadius = 24.dp
    val normalCornerRadius = 8.dp

    val shape by rememberedDerivedState(position, fullCornerRadius, normalCornerRadius) {
        when (position) {
            Position.Alone -> RoundedCornerShape(fullCornerRadius)
            Position.Top -> RoundedCornerShape(
                topStart = fullCornerRadius, topEnd = fullCornerRadius,
                bottomStart = normalCornerRadius, bottomEnd = normalCornerRadius
            )
            Position.Middle -> RoundedCornerShape(normalCornerRadius)
            Position.Bottom -> RoundedCornerShape(
                topStart = normalCornerRadius, topEnd = normalCornerRadius,
                bottomStart = fullCornerRadius, bottomEnd = fullCornerRadius
            )
        }
    }

    val paddingModifier = when (position) {
        Position.Alone -> Modifier.padding(bottom = 16.dp)
        Position.Bottom -> Modifier.padding(top = 1.dp, bottom = 16.dp)
        Position.Middle -> Modifier.padding(vertical = 1.dp)
        Position.Top -> Modifier.padding(bottom = 1.dp)
    }

    val elevation by animateFloatAsState(
        targetValue = if (isDragging) 8f else 0f,
        label = "dragElevation"
    )

    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val currentOnDrag by rememberUpdatedState(onDrag)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)

    Box(
        modifier = Modifier
            .then(paddingModifier)
            .graphicsLayer {
                translationY = dragOffset
                shadowElevation = elevation
                scaleX = if (isDragging) 1.02f else 1f
                scaleY = if (isDragging) 1.02f else 1f
            }
            .pointerInput(Unit) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { currentOnDragStart() },
                    onDrag = { change, offset ->
                        change.consume()
                        currentOnDrag(offset.y)
                    },
                    onDragEnd = { currentOnDragEnd() },
                    onDragCancel = { currentOnDragEnd() }
                )
            }
    ) {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Row(
                modifier = modifier
                    .padding(horizontal = 16.dp)
                    .clip(shape)
                    .background(color = backgroundColor)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier
                        .padding(horizontal = 8.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Drag handle — excluded from the detail-sheet tap target.
                    Icon(
                        Icons.Outlined.DragHandle, null,
                        modifier = Modifier
                            .padding(end = 12.dp)
                            .size(22.dp),
                        tint = if (isDragging) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )

                    // Tap target for the per-type detail sheet (U5): the row
                    // content between the drag handle and the Switch. The
                    // outer row keeps unmerged semantics so the Switch stays
                    // independently focusable; this sub-row merges into one
                    // announced action.
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(onClick = onOpenDetail)
                            .semantics(mergeDescendants = true) { }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Type icon
                        Image(
                            imageVector = type.icon,
                            contentDescription = null,
                            modifier = Modifier
                                .padding(end = 12.dp)
                                .size(22.dp),
                            colorFilter = ColorFilter.tint(
                                if (isEnabled) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            )
                        )

                        // Label + description + exclusion count
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = type.displayName,
                                style = MaterialTheme.typography.titleMedium,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isEnabled) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            )
                            Text(
                                text = type.description,
                                style = MaterialTheme.typography.bodySmall,
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                    alpha = if (isEnabled) 1f else 0.38f
                                ),
                                modifier = Modifier.padding(top = 2.dp)
                            )
                            if (exclusionCount > 0) {
                                Text(
                                    text = pluralStringResource(
                                        R.plurals.story_cards_excluded_count,
                                        exclusionCount,
                                        exclusionCount
                                    ),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary.copy(
                                        alpha = if (isEnabled) 1f else 0.5f
                                    ),
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }
                        }

                        // Trailing affordance announcing the row is clickable.
                        Icon(
                            imageVector = Icons.Outlined.ChevronRight,
                            contentDescription = stringResource(
                                R.string.story_cards_open_type_settings_cd,
                                type.displayName
                            ),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                alpha = if (isEnabled) 1f else 0.38f
                            ),
                            modifier = Modifier
                                .padding(horizontal = 4.dp)
                                .size(20.dp)
                        )
                    }

                    // Toggle — outside the detail tap target.
                    Switch(
                        checked = isEnabled,
                        onCheckedChange = onToggle
                    )
                }
            }
        }
    }
}

private fun cardItemPosition(index: Int, size: Int): Position {
    return when {
        size == 1 -> Position.Alone
        index == 0 -> Position.Top
        index == size - 1 -> Position.Bottom
        else -> Position.Middle
    }
}

internal val StoryCardType.displayName: String
    @Composable get() = when (this) {
        StoryCardType.MEMORIES -> stringResource(R.string.story_cards_type_memories)
        StoryCardType.ALBUMS -> stringResource(R.string.story_cards_type_albums)
        StoryCardType.CATEGORIES -> stringResource(R.string.story_cards_type_categories)
        StoryCardType.LOCATIONS -> stringResource(R.string.story_cards_type_locations)
        StoryCardType.FAVORITES -> stringResource(R.string.story_cards_type_favorites)
        StoryCardType.CLOUD_MEMORIES -> stringResource(R.string.story_cards_type_cloud_memories)
        StoryCardType.HIGHLIGHTS -> stringResource(R.string.story_cards_type_highlights)
        StoryCardType.PEOPLE -> stringResource(R.string.story_cards_type_people)
    }

internal val StoryCardType.description: String
    @Composable get() = when (this) {
        StoryCardType.MEMORIES -> stringResource(R.string.story_cards_type_memories_desc)
        StoryCardType.ALBUMS -> stringResource(R.string.story_cards_type_albums_desc)
        StoryCardType.CATEGORIES -> stringResource(R.string.story_cards_type_categories_desc)
        StoryCardType.LOCATIONS -> stringResource(R.string.story_cards_type_locations_desc)
        StoryCardType.FAVORITES -> stringResource(R.string.story_cards_type_favorites_desc)
        StoryCardType.CLOUD_MEMORIES -> stringResource(R.string.story_cards_type_cloud_memories_desc)
        StoryCardType.HIGHLIGHTS -> stringResource(R.string.story_cards_type_highlights_desc)
        StoryCardType.PEOPLE -> stringResource(R.string.story_cards_type_people_desc)
    }
