/*
 * SPDX-FileCopyrightText: 2023-2026 alzimerahmed
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.feature_node.data.data_source

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dot.gallery.feature_node.domain.model.ImageEmbedding
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Room DAO integration test on the JVM via Robolectric, against an in-memory database.
 * Mirrors CloudUploadPrefDaoTest: plain Application, no Hilt, no encrypted production DB.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class ImageEmbeddingDaoTest {

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

    private fun embedding(id: Long, revision: String = "") = ImageEmbedding(
        id = id,
        date = id * 1000,
        embedding = FloatArray(512) { 0.044194f },
        resultRevision = revision,
    )

    @Test
    fun `upsert inserts and replaces by primary key`() = runTest {
        val dao = db.getImageEmbeddingDao()
        dao.addImageEmbedding(embedding(1, revision = "r1"))
        assertEquals(1, dao.count())
        assertEquals("r1", dao.getRecord(1)?.resultRevision)

        dao.addImageEmbedding(embedding(1, revision = "r2"))
        assertEquals(1, dao.count())
        assertEquals("r2", dao.getRecord(1)?.resultRevision)
    }

    @Test
    fun `getRecords by ids returns only matching rows`() = runTest {
        val dao = db.getImageEmbeddingDao()
        dao.addImageEmbedding(embedding(1))
        dao.addImageEmbedding(embedding(2))
        dao.addImageEmbedding(embedding(3))

        val records = dao.getRecords(listOf(1L, 3L))
        assertEquals(listOf(1L, 3L), records.map { it.id })
        assertNull(dao.getRecord(99))
    }

    @Test
    fun `getHeaders returns metadata with byte length`() = runTest {
        val dao = db.getImageEmbeddingDao()
        dao.addImageEmbedding(embedding(5, revision = "rev"))

        val header = dao.getHeaders().single()
        assertEquals(5L, header.id)
        assertEquals("rev", header.resultRevision)
        assertEquals(512 * 4, header.embeddingBytes)
    }

    @Test
    fun `updateResultRevisions stamps only targeted ids`() = runTest {
        val dao = db.getImageEmbeddingDao()
        dao.addImageEmbedding(embedding(1))
        dao.addImageEmbedding(embedding(2))

        val updated = dao.updateResultRevisions(listOf(1L, 2L), "rev-9")
        assertEquals(2, updated)
        assertEquals("rev-9", dao.getRecord(1)?.resultRevision)
    }

    @Test
    fun `count and getIds reflect stored rows`() = runTest {
        val dao = db.getImageEmbeddingDao()
        assertEquals(0, dao.count())
        dao.addImageEmbedding(embedding(1))
        dao.addImageEmbedding(embedding(2))
        assertEquals(2, dao.count())
        assertEquals(listOf(1L, 2L), dao.getIds().sorted())
        assertEquals(2, dao.getRecords().first().size)
    }

    @Test
    fun `deleteOrphans removes rows with no matching media and returns count`() = runTest {
        val dao = db.getImageEmbeddingDao()
        dao.addImageEmbedding(embedding(1))
        dao.addImageEmbedding(embedding(2))

        // No media/cloud_media rows exist, so every embedding is an orphan.
        assertEquals(2, dao.deleteOrphans())
        assertEquals(0, dao.count())
    }
}
