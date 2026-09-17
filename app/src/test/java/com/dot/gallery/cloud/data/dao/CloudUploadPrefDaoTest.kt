/*
 * SPDX-FileCopyrightText: 2023-2026 alzimerahmed
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.cloud.data.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dot.gallery.cloud.core.ProviderType
import com.dot.gallery.cloud.data.entity.CloudUploadPrefEntity
import com.dot.gallery.feature_node.data.data_source.InternalDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Room DAO integration test on the JVM via Robolectric, against an in-memory database.
 * Uses a plain Application (not the Hilt GalleryApp) so no encrypted production DB is touched.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class CloudUploadPrefDaoTest {

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

    @Test
    fun `upsert inserts and replaces by primary key`() = runTest {
        val dao = db.getCloudUploadPrefDao()
        dao.upsert(CloudUploadPrefEntity(serverConfigId = 1, albumId = 10, providerType = ProviderType.IMMICH, uploadEnabled = true))
        dao.upsert(CloudUploadPrefEntity(serverConfigId = 1, albumId = 10, providerType = ProviderType.IMMICH, uploadEnabled = false))

        val rows = dao.getEnabledList()
        assertEquals(0, rows.size) // replaced row is disabled

        dao.upsert(CloudUploadPrefEntity(serverConfigId = 1, albumId = 10, providerType = ProviderType.WEBDAV, uploadEnabled = true))
        assertEquals(1, dao.getEnabledList().size)
    }

    @Test
    fun `per-account scoping filters rows by config`() = runTest {
        val dao = db.getCloudUploadPrefDao()
        dao.upsert(CloudUploadPrefEntity(serverConfigId = 1, albumId = 10, providerType = ProviderType.IMMICH, uploadEnabled = true))
        dao.upsert(CloudUploadPrefEntity(serverConfigId = 2, albumId = 20, providerType = ProviderType.SMB, uploadEnabled = true))

        assertEquals(2, dao.getEnabledList().size)
        assertEquals(1, dao.getEnabledByConfigList(1).size)
        assertEquals(ProviderType.IMMICH, dao.getEnabledByConfigList(1).first().providerType)
    }

    @Test
    fun `delete removes single row and deleteByConfig removes scope`() = runTest {
        val dao = db.getCloudUploadPrefDao()
        dao.upsert(CloudUploadPrefEntity(serverConfigId = 1, albumId = 10, providerType = ProviderType.NFS, uploadEnabled = true))
        dao.upsert(CloudUploadPrefEntity(serverConfigId = 1, albumId = 11, providerType = ProviderType.SMB, uploadEnabled = true))
        dao.upsert(CloudUploadPrefEntity(serverConfigId = 2, albumId = 10, providerType = ProviderType.NFS, uploadEnabled = true))

        dao.delete(configId = 1, albumId = 10)
        assertEquals(2, dao.getEnabledList().size)

        dao.deleteByConfig(1)
        assertEquals(1, dao.getEnabledList().size)
        assertEquals(2L, dao.getEnabledByConfigList(2).first().serverConfigId)
    }
}
