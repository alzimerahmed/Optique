/*
 * SPDX-FileCopyrightText: 2023-2026 alzimerahmed
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.feature_node.presentation.memories

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dot.gallery.core.MediaDistributor
import com.dot.gallery.core.notifications.MemoriesNotifier
import com.dot.gallery.feature_node.domain.memories.MemoriesEngine
import com.dot.gallery.feature_node.domain.memories.OnThisDayGroup
import com.dot.gallery.feature_node.domain.memories.YearRecap
import com.dot.gallery.feature_node.domain.model.Media
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject

sealed interface MemoriesSectionState {
    data object Loading : MemoriesSectionState

    data object Empty : MemoriesSectionState

    data class Content(
        val onThisDayGroups: List<OnThisDayGroup>,
        val recaps: List<YearRecap>,
    ) : MemoriesSectionState

    data class Error(
        val message: String?,
    ) : MemoriesSectionState
}

@HiltViewModel
class MemoriesViewModel
    @Inject
    constructor(
        distributor: MediaDistributor,
        private val cache: MemoriesCache,
        private val clock: Clock,
        private val notifier: MemoriesNotifier,
    ) : ViewModel() {
        private val refreshTrigger = MutableStateFlow(0)

        @OptIn(ExperimentalCoroutinesApi::class)
        val sectionState: StateFlow<MemoriesSectionState> =
            refreshTrigger
                .flatMapLatest {
                    distributor.timelineMediaFlow
                        .map { it.media }
                        .map { media -> computeSectionState(media) }
                        .catch { throwable ->
                            emit(MemoriesSectionState.Error(throwable.message))
                        }
                }.flowOn(Dispatchers.Default)
                .stateIn(
                    viewModelScope,
                    SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                    MemoriesSectionState.Loading,
                )

        /** Drops the cached selection and re-subscribes upstream so an [MemoriesSectionState.Error] can recover. */
        fun retry() {
            cache.invalidate()
            refreshTrigger.update { it + 1 }
        }

        /**
         * Forwards an on-this-day match to [MemoriesNotifier] (U5). All gating — toggle,
         * permission, once-per-day — lives inside the notifier, so this is safe to call
         * whenever content is emitted.
         */
        fun maybePostNotification(onThisDayGroups: List<OnThisDayGroup>) {
            viewModelScope.launch { notifier.maybePostToday(onThisDayGroups) }
        }

        /** Local "today" from the injected [clock] — single source for date-sensitive UI. */
        fun today(): LocalDate = LocalDate.now(clock)

        private fun computeSectionState(media: List<Media.UriMedia>): MemoriesSectionState {
            val today = LocalDate.now(clock)
            val signature = librarySignature(media)
            val entry =
                cache.getOrPut(today, signature) {
                    val engine = MemoriesEngine(clock)
                    MemoriesCacheEntry(
                        date = today,
                        librarySignature = signature,
                        onThisDayGroups = engine.onThisDay(media),
                        recaps = engine.recaps(media),
                    )
                }
            return if (entry.onThisDayGroups.isEmpty() && entry.recaps.isEmpty()) {
                MemoriesSectionState.Empty
            } else {
                MemoriesSectionState.Content(entry.onThisDayGroups, entry.recaps)
            }
        }

        // size + id sum catch membership changes; max timestamp catches edits to an
        // identical membership (re-dated or replaced media would otherwise hit cache).
        private fun librarySignature(media: List<Media.UriMedia>): String =
            "${media.size}:${media.sumOf { it.id }}:${media.maxOfOrNull { it.definedTimestamp } ?: 0L}"

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }
