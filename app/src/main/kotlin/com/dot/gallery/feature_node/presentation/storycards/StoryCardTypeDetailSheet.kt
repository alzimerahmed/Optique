/*
 * SPDX-FileCopyrightText: 2023-2026 alzimerahmed
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.feature_node.presentation.storycards

import android.content.res.Resources
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DisabledVisible
import androidx.compose.material.icons.outlined.ImageSearch
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.PhotoAlbum
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.dot.gallery.R
import com.dot.gallery.core.Position
import com.dot.gallery.core.SettingsEntity
import com.dot.gallery.core.presentation.components.DragHandle
import com.dot.gallery.core.presentation.components.NavigationBackButton
import com.dot.gallery.core.presentation.components.SetupButton
import com.dot.gallery.feature_node.data.data_source.CategoryWithMediaCount
import com.dot.gallery.feature_node.domain.model.Album
import com.dot.gallery.feature_node.domain.model.AlbumState
import com.dot.gallery.feature_node.domain.model.StoryCardType
import com.dot.gallery.feature_node.domain.model.StoryCardsConfig
import com.dot.gallery.feature_node.presentation.settings.components.SettingsItem
import com.dot.gallery.feature_node.presentation.util.AppBottomSheetState
import kotlinx.coroutines.launch

/**
 * Which source kind a card type's exclusion set draws from (R4/KTD2). Only
 * the three source-backed types expose an exclusion section; the effect of
 * every exclusion is global across all card types. [titleRes] is the
 * picker top-bar title; [emptyIcon]/[emptyTextRes] drive the picker's
 * empty state.
 */
internal enum class ExclusionSourceKind(
    @param:StringRes val titleRes: Int,
    val emptyIcon: ImageVector,
    @param:StringRes val emptyTextRes: Int,
) {
    ALBUM(
        R.string.story_cards_exclude_albums,
        Icons.Outlined.PhotoAlbum,
        R.string.story_cards_no_albums,
    ),
    CATEGORY(
        R.string.story_cards_exclude_categories,
        Icons.Outlined.ImageSearch,
        R.string.story_cards_no_categories,
    ),
    LOCATION(
        R.string.story_cards_exclude_locations,
        Icons.Outlined.LocationOn,
        R.string.story_cards_no_locations,
    ),
}

internal fun StoryCardType.exclusionSourceKind(): ExclusionSourceKind? = when (this) {
    StoryCardType.ALBUMS -> ExclusionSourceKind.ALBUM
    StoryCardType.CATEGORIES -> ExclusionSourceKind.CATEGORY
    StoryCardType.LOCATIONS -> ExclusionSourceKind.LOCATION
    else -> null
}

/**
 * One picker option — normalized across the three source kinds so a single
 * picker pattern serves all of them (mirrors the ignored-albums flow).
 * [isHidden] marks sources already hidden by the ignored-albums blacklist:
 * they are annotated, never offered as dead selectable entries (KTD2).
 */
internal data class ExclusionSourceItem(
    val key: String,
    val label: String,
    val supportingText: String? = null,
    val thumbnailModel: Any? = null,
    val isHidden: Boolean = false,
    val isExcluded: Boolean = false,
    val onToggle: () -> Unit = {},
)

/**
 * Per-type detail surface for Story Cards settings (U5): a `ModalBottomSheet`
 * holding the per-type cap control and the source-exclusion list/picker.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoryCardTypeDetailSheet(
    sheetState: AppBottomSheetState,
    type: StoryCardType?,
    config: StoryCardsConfig,
    albumsState: AlbumState,
    categories: List<CategoryWithMediaCount>,
    locationKeys: List<String>,
    onConfigChange: (StoryCardsConfig) -> Unit,
) {
    val scope = rememberCoroutineScope()

    if (sheetState.isVisible && type != null) {
        val density = LocalDensity.current
        val dragHandleAlpha by remember(density) {
            derivedStateOf {
                val offset =
                    runCatching { sheetState.sheetState.requireOffset() }
                        .getOrElse { Float.MAX_VALUE }
                val fadeThreshold = with(density) { 200.dp.toPx() }
                (offset / fadeThreshold).coerceIn(0f, 1f)
            }
        }

        ModalBottomSheet(
            sheetState = sheetState.sheetState,
            onDismissRequest = {
                scope.launch { sheetState.hide() }
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
            tonalElevation = 0.dp,
            dragHandle = { DragHandle(alpha = dragHandleAlpha) },
            contentWindowInsets = { WindowInsets(0, 0, 0, 0) }
        ) {
            StoryCardTypeDetailContent(
                type = type,
                config = config,
                albumsState = albumsState,
                categories = categories,
                locationKeys = locationKeys,
                onConfigChange = onConfigChange,
                modifier = Modifier.navigationBarsPadding()
            )
        }
    }
}

/**
 * Sheet content: cap slider (multi-card types — every type except FAVORITES
 * has an entry in [StoryCardsConfig.DEFAULT_MAX_CARDS_PER_TYPE]) plus the
 * source-kind exclusion list and picker for ALBUMS/CATEGORIES/LOCATIONS.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoryCardTypeDetailContent(
    type: StoryCardType,
    config: StoryCardsConfig,
    albumsState: AlbumState,
    categories: List<CategoryWithMediaCount>,
    locationKeys: List<String>,
    onConfigChange: (StoryCardsConfig) -> Unit,
    modifier: Modifier = Modifier,
) {
    var pickerOpen by remember(type) { mutableStateOf(false) }
    BackHandler(enabled = pickerOpen) { pickerOpen = false }

    Scaffold(
        modifier = modifier,
        containerColor = Color.Transparent,
        topBar = {
            if (pickerOpen) {
                TopAppBar(
                    title = {
                        Text(
                            text = type.exclusionSourceKind()
                                ?.let { stringResource(it.titleRes) }
                                .orEmpty(),
                            style = MaterialTheme.typography.titleMedium
                        )
                    },
                    navigationIcon = {
                        NavigationBackButton(forcedAction = { pickerOpen = false })
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent
                    )
                )
            } else {
                CenterAlignedTopAppBar(
                    title = { Text(text = type.displayName) },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent
                    )
                )
            }
        }
    ) { paddingValues ->
        AnimatedContent(
            targetState = pickerOpen,
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxWidth(),
            transitionSpec = {
                if (targetState) {
                    slideInHorizontally { it } + fadeIn() togetherWith
                            slideOutHorizontally { -it } + fadeOut()
                } else {
                    slideInHorizontally { -it } + fadeIn() togetherWith
                            slideOutHorizontally { it } + fadeOut()
                }
            },
            label = "detail_picker_transition"
        ) { isPicker ->
            if (isPicker) {
                SourceExclusionPickerContent(
                    type = type,
                    config = config,
                    albumsState = albumsState,
                    categories = categories,
                    locationKeys = locationKeys,
                    onConfigChange = onConfigChange,
                    onDone = { pickerOpen = false }
                )
            } else {
                TypeDetailList(
                    type = type,
                    config = config,
                    albumsState = albumsState,
                    categories = categories,
                    onConfigChange = onConfigChange,
                    onOpenPicker = { pickerOpen = true }
                )
            }
        }
    }
}

@Composable
private fun TypeDetailList(
    type: StoryCardType,
    config: StoryCardsConfig,
    albumsState: AlbumState,
    categories: List<CategoryWithMediaCount>,
    onConfigChange: (StoryCardsConfig) -> Unit,
    onOpenPicker: () -> Unit,
) {
    val defaultCap = StoryCardsConfig.DEFAULT_MAX_CARDS_PER_TYPE[type]
    val sourceKind = type.exclusionSourceKind()

    // Resolved outside the LazyListScope lambda — composable calls are only
    // allowed in composable context, and the scope builder is not one.
    val unknownAlbumLabel = stringResource(R.string.story_cards_excluded_album_unknown)
    val unknownCategoryLabel = stringResource(R.string.story_cards_excluded_category_unknown)
    val entries = remember(
        sourceKind, config, albumsState, categories,
        unknownAlbumLabel, unknownCategoryLabel
    ) {
        if (sourceKind != null) {
            exclusionEntries(
                kind = sourceKind,
                config = config,
                albumsState = albumsState,
                categories = categories,
                unknownAlbumLabel = unknownAlbumLabel,
                unknownCategoryLabel = unknownCategoryLabel,
            )
        } else {
            emptyList()
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Single-card, non-source types (FAVORITES) have neither a cap nor
        // exclusions — the sheet still opens, so explain what it is.
        if (defaultCap == null && sourceKind == null) {
            item(key = "type_summary") {
                Text(
                    text = type.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .widthInSheet()
                        .padding(horizontal = 8.dp, vertical = 8.dp)
                )
            }
        }

        if (defaultCap != null) {
            val cap = config.maxCardsPerType[type] ?: defaultCap
            item(key = "cap") {
                SettingsItem(
                    item = SettingsEntity.SeekPreference(
                        title = stringResource(R.string.story_cards_max_cards_title),
                        summary = stringResource(R.string.story_cards_max_cards_summary, cap),
                        minValue = 1f,
                        currentValue = cap.toFloat(),
                        maxValue = 10f,
                        step = 8,
                        onSeek = { value ->
                            onConfigChange(
                                config.copy(
                                    maxCardsPerType =
                                        config.maxCardsPerType + (type to value.toInt())
                                )
                            )
                        },
                        screenPosition = Position.Alone
                    ),
                    modifier = Modifier
                        .widthInSheet()
                        .padding(bottom = 16.dp)
                )
            }
        }

        if (sourceKind != null) {
            item(key = "exclusions_header") {
                Column(
                    modifier = Modifier
                        .widthInSheet()
                        .padding(horizontal = 8.dp)
                        .padding(bottom = 8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.story_cards_exclusions_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.story_cards_exclusions_global_summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }

            itemsIndexed(
                items = entries,
                key = { _, entry -> "excluded_${entry.key}" }
            ) { index, entry ->
                ExclusionEntryRow(
                    label = entry.label,
                    position = if (index == 0) Position.Top else Position.Middle,
                    onRemove = {
                        onConfigChange(removeExclusion(sourceKind, config, entry))
                    },
                    modifier = Modifier.widthInSheet()
                )
            }

            item(key = "add_exclusion") {
                SettingsItem(
                    item = SettingsEntity.Preference(
                        icon = Icons.Outlined.Add,
                        title = stringResource(R.string.story_cards_add_exclusion),
                        onClick = onOpenPicker,
                        screenPosition = if (entries.isEmpty()) Position.Alone else Position.Bottom
                    ),
                    modifier = Modifier.widthInSheet()
                )
            }
        }
    }
}

/**
 * Resolved exclusion entries for display: label + list key + the typed
 * payload [removeExclusion] consumes (no string re-parsing). One row per
 * excluded source.
 */
private data class ExclusionEntry(
    val key: String,
    val label: String,
    val albumIds: Set<Long> = emptySet(),
    val categoryId: Long? = null,
    val locationKey: String? = null,
)

private fun exclusionEntries(
    kind: ExclusionSourceKind,
    config: StoryCardsConfig,
    albumsState: AlbumState,
    categories: List<CategoryWithMediaCount>,
    unknownAlbumLabel: String,
    unknownCategoryLabel: String,
): List<ExclusionEntry> = when (kind) {
    ExclusionSourceKind.ALBUM -> {
        val allAlbums = albumsState.albums + albumsState.albumsWithBlacklisted
        // Group source ids that resolve to the same (merged) album so it
        // appears once; unresolved ids fall back to a neutral label.
        val grouped = LinkedHashMap<String, MutableSet<Long>>()
        val labels = HashMap<String, String>()
        for (id in config.excludedAlbumIds) {
            val album = allAlbums.firstOrNull { it.id == id || id in it.sourceAlbumIds }
            val groupKey = album?.let { "album_${it.id}" } ?: "raw_$id"
            labels[groupKey] = album?.label ?: unknownAlbumLabel
            grouped.getOrPut(groupKey) { mutableSetOf() } += id
        }
        grouped.map { (groupKey, ids) ->
            ExclusionEntry(
                key = groupKey,
                label = labels.getValue(groupKey),
                albumIds = ids,
            )
        }
    }

    ExclusionSourceKind.CATEGORY ->
        config.excludedCategoryIds.map { id ->
            ExclusionEntry(
                key = "category_$id",
                label = categories.firstOrNull { it.id == id }?.name ?: unknownCategoryLabel,
                categoryId = id,
            )
        }

    ExclusionSourceKind.LOCATION ->
        config.excludedLocationKeys.map {
            ExclusionEntry(key = "location_$it", label = it, locationKey = it)
        }
}

private fun removeExclusion(
    kind: ExclusionSourceKind,
    config: StoryCardsConfig,
    entry: ExclusionEntry,
): StoryCardsConfig = when (kind) {
    ExclusionSourceKind.ALBUM ->
        config.copy(excludedAlbumIds = config.excludedAlbumIds - entry.albumIds)

    ExclusionSourceKind.CATEGORY ->
        entry.categoryId?.let { id ->
            config.copy(excludedCategoryIds = config.excludedCategoryIds - id)
        } ?: config

    ExclusionSourceKind.LOCATION ->
        entry.locationKey?.let { key ->
            config.copy(excludedLocationKeys = config.excludedLocationKeys - key)
        } ?: config
}

/**
 * The single picker pattern for all three source kinds: check-state rows +
 * confirm action. Writes are live per toggle (same as every other settings
 * toggle); the confirm button returns to the detail view.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SourceExclusionPickerContent(
    type: StoryCardType,
    config: StoryCardsConfig,
    albumsState: AlbumState,
    categories: List<CategoryWithMediaCount>,
    locationKeys: List<String>,
    onConfigChange: (StoryCardsConfig) -> Unit,
    onDone: () -> Unit,
) {
    val kind = type.exclusionSourceKind() ?: return
    val hiddenAnnotation = stringResource(R.string.story_cards_source_already_hidden)
    val resources = LocalResources.current

    val items = remember(
        kind, config, albumsState, categories, locationKeys,
        hiddenAnnotation, resources, onConfigChange
    ) {
        pickerItems(
            kind = kind,
            config = config,
            albumsState = albumsState,
            categories = categories,
            locationKeys = locationKeys,
            hiddenAnnotation = hiddenAnnotation,
            resources = resources,
            onConfigChange = onConfigChange
        )
    }

    Scaffold(
        containerColor = Color.Transparent,
        bottomBar = {
            SetupButton(
                onClick = onDone,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                applyHorizontalPadding = false,
                applyBottomPadding = false,
                applyInsets = false,
                text = stringResource(R.string.story_cards_picker_done)
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp)
        ) {
            item(key = "picker_summary") {
                Text(
                    text = stringResource(R.string.story_cards_exclusions_global_summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)
                )
            }

            if (items.isEmpty()) {
                item(key = "picker_empty") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Icon(
                            imageVector = kind.emptyIcon,
                            contentDescription = null,
                            modifier = Modifier.size(72.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = stringResource(kind.emptyTextRes),
                            style = MaterialTheme.typography.titleMedium,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                items(items = items, key = { it.key }) { item ->
                    ExclusionPickerRow(item = item)
                }
            }
        }
    }
}

private fun pickerItems(
    kind: ExclusionSourceKind,
    config: StoryCardsConfig,
    albumsState: AlbumState,
    categories: List<CategoryWithMediaCount>,
    locationKeys: List<String>,
    hiddenAnnotation: String,
    resources: Resources,
    onConfigChange: (StoryCardsConfig) -> Unit,
): List<ExclusionSourceItem> {
    val itemCount = { count: Long ->
        resources.getQuantityString(
            R.plurals.item_count, count.toInt(), count.toInt()
        )
    }
    return when (kind) {
        ExclusionSourceKind.ALBUM -> {
            // Card-eligible albums first; blacklist-hidden ones trail the
            // list annotated, never selectable (KTD2 — already absent upstream).
            val coveredIds = albumsState.albums
                .flatMap { it.sourceAlbumIds + it.id }
                .toSet()
            val hidden = albumsState.albumsWithBlacklisted.filter { album ->
                album.id !in coveredIds && album.sourceAlbumIds.none { it in coveredIds }
            }
            val toItem = { album: Album, isHidden: Boolean ->
                val exclusionIds = album.sourceAlbumIds + album.id
                ExclusionSourceItem(
                    key = "album_${album.id}",
                    label = album.label,
                    supportingText = if (isHidden) hiddenAnnotation
                    else itemCount(album.count),
                    thumbnailModel = album.uri,
                    isHidden = isHidden,
                    isExcluded = exclusionIds.any { it in config.excludedAlbumIds },
                    onToggle = {
                        // Multi-id toggle: removing only applies when at least
                        // one of the album's ids is currently excluded.
                        val newSet = if (exclusionIds.any { it in config.excludedAlbumIds }) {
                            config.excludedAlbumIds - exclusionIds.toSet()
                        } else {
                            config.excludedAlbumIds + exclusionIds
                        }
                        onConfigChange(config.copy(excludedAlbumIds = newSet))
                    }
                )
            }
            albumsState.albums.map { toItem(it, false) } +
                    hidden.map { toItem(it, true) }
        }

        ExclusionSourceKind.CATEGORY ->
            categories.map { cat ->
                ExclusionSourceItem(
                    key = "category_${cat.id}",
                    label = cat.name,
                    supportingText = itemCount(cat.mediaCount.toLong()),
                    isExcluded = cat.id in config.excludedCategoryIds,
                    onToggle = {
                        onConfigChange(
                            config.copy(
                                excludedCategoryIds =
                                    config.excludedCategoryIds.toggled(cat.id)
                            )
                        )
                    }
                )
            }

        ExclusionSourceKind.LOCATION ->
            locationKeys.map { key ->
                ExclusionSourceItem(
                    key = "location_$key",
                    label = key,
                    isExcluded = key in config.excludedLocationKeys,
                    onToggle = {
                        onConfigChange(
                            config.copy(
                                excludedLocationKeys =
                                    config.excludedLocationKeys.toggled(key)
                            )
                        )
                    }
                )
            }
    }
}

/** Add [item] when absent, remove when present — the exclusion toggle pattern. */
private fun <T> Set<T>.toggled(item: T): Set<T> =
    if (item in this) this - item else this + item

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun ExclusionPickerRow(
    item: ExclusionSourceItem,
    modifier: Modifier = Modifier,
) {
    val excludedState = stringResource(R.string.story_cards_state_excluded)
    val includedState = stringResource(R.string.story_cards_state_included)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(16.dp))
            .toggleable(
                value = item.isExcluded,
                enabled = !item.isHidden,
                role = Role.Checkbox,
                onValueChange = { item.onToggle() }
            )
            .semantics {
                stateDescription = if (item.isExcluded) excludedState else includedState
            }
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (item.thumbnailModel != null) {
            GlideImage(
                model = item.thumbnailModel,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .padding(end = 12.dp)
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .alpha(if (item.isHidden) 0.4f else 1f)
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.label,
                style = MaterialTheme.typography.titleMedium,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (item.isHidden) {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )
            if (item.supportingText != null) {
                Text(
                    text = item.supportingText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                        alpha = if (item.isHidden) 0.45f else 1f
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }

        if (item.isHidden) {
            Icon(
                imageVector = Icons.Outlined.DisabledVisible,
                contentDescription = item.supportingText,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier
                    .padding(start = 12.dp)
                    .size(20.dp)
            )
        } else {
            Checkbox(
                checked = item.isExcluded,
                onCheckedChange = null,
                modifier = Modifier.padding(start = 12.dp)
            )
        }
    }
}

/**
 * A currently-excluded source styled like the other settings rows: both the
 * row tap and the trailing affordance remove it (the icon carries its own
 * contentDescription for TalkBack).
 */
@Composable
private fun ExclusionEntryRow(
    label: String,
    position: Position,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsItem(
        item = SettingsEntity.Preference(
            title = label,
            screenPosition = position,
            onClick = onRemove
        ),
        modifier = modifier,
        customTrailingContent = {
            IconButton(onClick = onRemove) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = stringResource(
                        R.string.story_cards_remove_exclusion_cd, label
                    ),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    )
}

internal fun Modifier.widthInSheet(): Modifier =
    this.widthIn(max = 600.dp).fillMaxWidth()
