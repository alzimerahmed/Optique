/*
 * SPDX-FileCopyrightText: 2023-2026 alzimerahmed
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.feature_node.presentation.storycards

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dot.gallery.core.MediaDistributor
import com.dot.gallery.feature_node.data.data_source.CategoryWithMediaCount
import com.dot.gallery.feature_node.domain.model.AlbumState
import com.dot.gallery.feature_node.domain.model.locationKey
import com.dot.gallery.feature_node.domain.repository.MediaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Data seam for the Story Cards settings detail surface (U5/R4/R5/R7).
 *
 * The settings screen itself stays a pure composable on
 * `rememberStoryCardsConfig()`; this ViewModel only feeds the source-exclusion
 * pickers with the full option space (a `getTopCategories(5)`-style fetch is
 * too small — the picker needs everything the user could exclude).
 */
@HiltViewModel
class StoryCardsSettingsViewModel @Inject constructor(
    repository: MediaRepository,
    distributor: MediaDistributor,
) : ViewModel() {

    /**
     * All albums the app knows about. [AlbumState.albums] is the
     * card-eligible (blacklist-filtered, merged) list; the difference against
     * [AlbumState.albumsWithBlacklisted] is what the picker annotates as
     * already hidden by the ignored-albums blacklist instead of offering it
     * as a dead entry (KTD2).
     */
    val albumsState: StateFlow<AlbumState> = distributor.albumsFlow

    /** Every category with media — the full picker option space. */
    val categories: StateFlow<List<CategoryWithMediaCount>> =
        repository.getCategoriesWithMediaCount()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Distinct `"city, country"` keys — the same key the location-card
     * builder groups by, so picker entries map 1:1 onto
     * `StoryCardsConfig.excludedLocationKeys`.
     */
    val locationKeys: StateFlow<List<String>> = repository.getMetadata()
        .map { metadata ->
            metadata.mapNotNull { it.locationKey }.distinct().sorted()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
