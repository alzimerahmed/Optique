/*
 * SPDX-FileCopyrightText: 2023-2026 alzimerahmed
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.feature_node.data.data_source

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Room DAO integration test on the JVM via Robolectric, against an in-memory database.
 * Covers the media_feature_state delete-by-feature primitive used by the
 * delete-all-face-data purge (resetting FACE_DETECTION state lets indexing rebuild).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class SmartScanDaoFeatureStateTest {

    private lateinit var db: InternalDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            InternalDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun featureState(
        mediaId: Long,
        feature: MediaFeature,
        status: MediaFeatureStatus = MediaFeatureStatus.SUCCEEDED,
    ) = MediaFeatureStateEntity(
        mediaId = mediaId,
        feature = feature,
        status = status,
        updatedAt = mediaId * 1000,
    )

    @Test
    fun `deleteFeatureStates removes only rows for the targeted feature`() = runTest {
        val dao = db.getSmartScanDao()
        dao.upsertFeatureStates(
            listOf(
                featureState(1, MediaFeature.FACE_DETECTION),
                featureState(2, MediaFeature.FACE_DETECTION),
                featureState(1, MediaFeature.SEARCH_EMBEDDING),
                featureState(3, MediaFeature.METADATA),
            )
        )

        dao.deleteFeatureStates(MediaFeature.FACE_DETECTION)

        assertTrue(dao.getFeatureStates(MediaFeature.FACE_DETECTION).isEmpty())
        assertEquals(
            listOf(1L),
            dao.getFeatureStates(MediaFeature.SEARCH_EMBEDDING).map { it.mediaId }
        )
        assertEquals(
            listOf(3L),
            dao.getFeatureStates(MediaFeature.METADATA).map { it.mediaId }
        )
    }

    @Test
    fun `deleteFeatureStates on empty table is a no-op`() = runTest {
        val dao = db.getSmartScanDao()

        dao.deleteFeatureStates(MediaFeature.FACE_DETECTION)

        assertTrue(dao.getFeatureStates(MediaFeature.FACE_DETECTION).isEmpty())
        assertEquals(0L, dao.getFeatureGeneration(MediaFeature.FACE_DETECTION))
    }
}
