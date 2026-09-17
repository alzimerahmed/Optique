/*
 * SPDX-FileCopyrightText: 2023-2026 alzimerahmed
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.feature_node.presentation.search

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dot.gallery.core.ml.ModelManager
import com.dot.gallery.feature_node.presentation.search.helpers.SearchVisionHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class SearchHelperImplTest {

    // 512 * x^2 == 1 when x = 1/sqrt(512), so this is a unit vector.
    private val unitValue = 0.044194f

    @Test
    fun `higher similarity sorts first`() {
        val query = unitVector()
        val strong = unitVector()
        val weak = FloatArray(512) { if (it % 2 == 0) unitValue else -unitValue } // dot == 0.0

        val results = helper().sortByCosineDistance(query, listOf(weak, strong), listOf(1L, 2L))

        // The weak (orthogonal) vector scores 0.0, below the 0.2 threshold, so it is filtered.
        assertEquals(listOf(2L), results.map { it.first })
        assertTrue(results.first().second >= SearchVisionHelper.threshold)
    }

    @Test
    fun `results below threshold are filtered out`() {
        val query = unitVector()
        val orthogonal = FloatArray(512) { if (it % 2 == 0) unitValue else -unitValue }

        val results = helper().sortByCosineDistance(query, listOf(orthogonal, unitVector()), listOf(1L, 2L))

        assertTrue(results.none { it.second < SearchVisionHelper.threshold })
        assertEquals(1, results.size)
    }

    @Test
    fun `scores sort descending`() {
        val query = unitVector()

        val results = helper().sortByCosineDistance(query, listOf(unitVector(), unitVector(0.02f)), listOf(10L, 11L))

        assertTrue(results.first().second >= results.last().second)
        assertEquals(10L, results.first().first)
    }

    @Test
    fun `empty input returns empty results`() {
        val results = helper().sortByCosineDistance(unitVector(), emptyList(), emptyList())
        assertTrue(results.isEmpty())
    }

    private fun unitVector(value: Float = unitValue) = FloatArray(512) { value }

    private fun helper() = SearchHelperImpl(ModelManager(ApplicationProvider.getApplicationContext()))
}
