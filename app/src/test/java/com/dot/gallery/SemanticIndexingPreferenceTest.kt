/*
 * SPDX-FileCopyrightText: 2023-2026 alzimerahmed
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery

import com.dot.gallery.core.Settings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure resolver tests for the semantic-indexing preference. The DataStore write path
 * needs AndroidKeyStore, which Robolectric cannot provide, so persistence round-trips
 * are covered by instrumented tests instead.
 */
class SemanticIndexingPreferenceTest {

    @Test
    fun `defaults to enabled when the key was never written`() {
        assertTrue(Settings.SmartFeatures.resolveSemanticIndexing(null))
    }

    @Test
    fun `stored values pass through unchanged`() {
        assertFalse(Settings.SmartFeatures.resolveSemanticIndexing(false))
        assertTrue(Settings.SmartFeatures.resolveSemanticIndexing(true))
    }
}
