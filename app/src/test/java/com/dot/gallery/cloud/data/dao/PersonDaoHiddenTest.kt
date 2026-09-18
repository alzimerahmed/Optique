/*
 * SPDX-FileCopyrightText: 2023-2026 alzimerahmed
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.cloud.data.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dot.gallery.cloud.core.ProviderType
import com.dot.gallery.cloud.data.entity.PersonEntity
import com.dot.gallery.feature_node.data.data_source.InternalDatabase
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
 * Room DAO integration test on the JVM via Robolectric, against an in-memory database.
 * Covers the hidden-people management surface (U3, R2/R6): getByProvider + hidden
 * filter feeds the manage-hidden list, setHidden(false) is the unhide path, and
 * getVisibleByProvider is what the People grid sees.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class PersonDaoHiddenTest {

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

    private fun person(
        id: String,
        hidden: Boolean,
        providerType: ProviderType = ProviderType.LOCAL_PEOPLE,
        faceCount: Int = 0
    ) = PersonEntity(
        id = id,
        name = "Person $id",
        providerType = providerType,
        faceCount = faceCount,
        hidden = hidden
    )

    @Test
    fun `getByProvider plus hidden filter returns only hidden persons`() = runTest {
        val dao = db.getPersonDao()
        dao.insertAll(
            listOf(
                person("local_hidden_1", hidden = true),
                person("local_visible", hidden = false),
                person("local_hidden_2", hidden = true)
            )
        )

        val hiddenPeople = dao.getByProvider(ProviderType.LOCAL_PEOPLE)
            .first()
            .filter(PersonEntity::hidden)

        assertEquals(
            setOf("local_hidden_1", "local_hidden_2"),
            hiddenPeople.map { it.id }.toSet()
        )
    }

    @Test
    fun `getByProvider LOCAL_PEOPLE excludes other providers`() = runTest {
        val dao = db.getPersonDao()
        dao.insertAll(
            listOf(
                person("local_1", hidden = true),
                person("immich_1", hidden = true, providerType = ProviderType.IMMICH)
            )
        )

        val localPeople = dao.getByProvider(ProviderType.LOCAL_PEOPLE).first()

        assertEquals(listOf("local_1"), localPeople.map { it.id })
    }

    @Test
    fun `setHidden false round-trips into getVisibleByProvider`() = runTest {
        val dao = db.getPersonDao()
        dao.insertAll(
            listOf(
                person("local_1", hidden = true, faceCount = 5),
                person("local_2", hidden = false, faceCount = 2)
            )
        )

        // Hidden person is invisible to the People grid feed.
        assertEquals(listOf("local_2"), dao.getVisibleByProvider(ProviderType.LOCAL_PEOPLE).first().map { it.id })

        // Unhide: the manage-hidden surface's only write path.
        dao.setHidden("local_1", false)

        val visible = dao.getVisibleByProvider(ProviderType.LOCAL_PEOPLE).first()
        assertEquals(setOf("local_1", "local_2"), visible.map { it.id }.toSet())
        // R6: hiding preserved name and faceCount — nothing was deleted.
        val unhidden = visible.single { it.id == "local_1" }
        assertEquals("Person local_1", unhidden.name)
        assertEquals(5, unhidden.faceCount)
        // And it no longer appears in the hidden list.
        assertTrue(
            dao.getByProvider(ProviderType.LOCAL_PEOPLE).first().none { it.hidden }
        )
    }
}
