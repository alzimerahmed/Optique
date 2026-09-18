/*
 * SPDX-FileCopyrightText: 2023-2026 alzimerahmed
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.feature_node.data.data_source

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dot.gallery.cloud.core.ProviderType
import com.dot.gallery.cloud.data.entity.DetectedFaceEntity
import com.dot.gallery.cloud.data.entity.FaceClusterEntity
import com.dot.gallery.cloud.data.entity.PersonEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Room DAO integration test pinning the delete-all-face-data composition (U4):
 * one transaction of detected_faces deleteAll → people deleteByProvider →
 * FACE_DETECTION feature-state delete. Verifies the face_clusters FK cascade
 * removes cluster rows and that unrelated feature state survives.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class FaceDataPurgeCompositionTest {

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

    private fun person(id: String, providerType: ProviderType = ProviderType.LOCAL_PEOPLE) =
        PersonEntity(id = id, name = "Person $id", providerType = providerType)

    private fun featureState(mediaId: Long, feature: MediaFeature) =
        MediaFeatureStateEntity(mediaId = mediaId, feature = feature, updatedAt = 1L)

    @Test
    fun `purge transaction removes faces people clusters and face feature state`() = runTest {
        val personDao = db.getPersonDao()
        val faceDao = db.getDetectedFaceDao()
        val scanDao = db.getSmartScanDao()
        personDao.insert(person("local_a"))
        personDao.insert(person("immich_b", ProviderType.IMMICH))
        faceDao.insert(DetectedFaceEntity(mediaId = 1L, personId = "local_a"))
        faceDao.upsertClusters(
            listOf(FaceClusterEntity("local_a", FloatArray(4), faceCount = 1))
        )
        scanDao.upsertFeatureStates(
            listOf(
                featureState(1L, MediaFeature.FACE_DETECTION),
                featureState(1L, MediaFeature.SEARCH_EMBEDDING)
            )
        )

        db.withTransaction {
            faceDao.deleteAll()
            personDao.deleteByProvider(ProviderType.LOCAL_PEOPLE)
            scanDao.deleteFeatureStates(MediaFeature.FACE_DETECTION)
        }

        assertTrue(faceDao.getAll().isEmpty())
        assertTrue(faceDao.getClusters().isEmpty())
        assertTrue(
            personDao.getByProviderOnce(ProviderType.LOCAL_PEOPLE).isEmpty()
        )
        assertTrue(scanDao.getFeatureStates(MediaFeature.FACE_DETECTION).isEmpty())
        // Remote people and unrelated feature state are not the purge's scope.
        assertEquals(
            listOf("immich_b"),
            personDao.getByProvider(ProviderType.IMMICH).first().map { it.id }
        )
        assertEquals(
            1,
            scanDao.getFeatureStates(MediaFeature.SEARCH_EMBEDDING).size
        )
    }

    @Test
    fun `purge on empty tables is a no-op`() = runTest {
        db.withTransaction {
            db.getDetectedFaceDao().deleteAll()
            db.getPersonDao().deleteByProvider(ProviderType.LOCAL_PEOPLE)
            db.getSmartScanDao().deleteFeatureStates(MediaFeature.FACE_DETECTION)
        }
        assertTrue(db.getDetectedFaceDao().getAll().isEmpty())
    }
}
