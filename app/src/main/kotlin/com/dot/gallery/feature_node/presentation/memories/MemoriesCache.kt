/*
 * SPDX-FileCopyrightText: 2023-2026 alzimerahmed
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.feature_node.presentation.memories

import com.dot.gallery.feature_node.domain.memories.OnThisDayGroup
import com.dot.gallery.feature_node.domain.memories.YearRecap
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

data class MemoriesCacheEntry(
    val date: LocalDate,
    val librarySignature: String,
    val onThisDayGroups: List<OnThisDayGroup>,
    val recaps: List<YearRecap>,
)

@Singleton
class MemoriesCache
    @Inject
    constructor() {
        private val entry = MutableStateFlow<MemoriesCacheEntry?>(null)

        fun get(
            date: LocalDate,
            librarySignature: String,
        ): MemoriesCacheEntry? = entry.value?.takeIf { it.date == date && it.librarySignature == librarySignature }

        fun getOrPut(
            date: LocalDate,
            librarySignature: String,
            compute: () -> MemoriesCacheEntry,
        ): MemoriesCacheEntry =
            synchronized(this) {
                get(date, librarySignature) ?: compute().also { entry.value = it }
            }

        fun invalidate() {
            entry.value = null
        }
    }
