/*
 * SPDX-FileCopyrightText: 2023-2026 alzimerahmed
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.core.notifications

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import com.dot.gallery.core.Settings
import com.dot.gallery.feature_node.domain.memories.OnThisDayGroup
import com.dot.gallery.feature_node.presentation.main.MainActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class MemoriesNotifierTest {
    private val clock: Clock =
        Clock.fixed(Instant.parse("2026-09-17T10:00:00Z"), ZoneOffset.UTC)

    private lateinit var context: Context
    private lateinit var store: DataStore<Preferences>
    private lateinit var notifier: MemoriesNotifier

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // A real DataStore over a scratch file — the production store is Keystore-backed
        // and its write path is unavailable under Robolectric.
        store =
            PreferenceDataStoreFactory.create(
                produceFile = { File(context.cacheDir, "memories_notifier_test.preferences_pb") },
            )
        notifier = MemoriesNotifier(context, clock, store)
    }

    private fun groups(vararg years: Int) = years.map { OnThisDayGroup(it, emptyList()) }

    private fun grantPostPermission() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun postedNotifications(): List<android.app.Notification> =
        shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications

    private suspend fun setEnabled(enabled: Boolean) {
        store.edit { it[Settings.Memories.NOTIFICATION_ENABLED] = enabled }
    }

    @Test
    fun `toggle off posts nothing even with permission`() =
        runBlocking {
            grantPostPermission()
            setEnabled(false)

            assertFalse(notifier.maybePostToday(groups(2020)))
            assertTrue(postedNotifications().isEmpty())
        }

    @Test
    fun `permission denied posts nothing and does not crash`() =
        runBlocking {
            setEnabled(true)
            // POST_NOTIFICATIONS stays revoked on SDK 34 by default.

            assertFalse(notifier.maybePostToday(groups(2020)))
            assertTrue(postedNotifications().isEmpty())
        }

    @Test
    fun `no on-this-day match posts nothing`() =
        runBlocking {
            grantPostPermission()
            setEnabled(true)

            assertFalse(notifier.maybePostToday(emptyList()))
            assertTrue(postedNotifications().isEmpty())
        }

    @Test
    fun `already posted today does not post again`() =
        runBlocking {
            grantPostPermission()
            setEnabled(true)

            assertTrue(notifier.maybePostToday(groups(2020)))
            assertEquals(1, postedNotifications().size)

            // Second call same day is gated by memories_notification_last_posted_date.
            assertFalse(notifier.maybePostToday(groups(2020)))
            assertEquals(1, postedNotifications().size)
        }

    @Test
    fun `pre-seeded last-posted date for today blocks posting`() =
        runBlocking {
            grantPostPermission()
            setEnabled(true)
            store.edit {
                it[Settings.Memories.NOTIFICATION_LAST_POSTED_DATE] = "2026-09-17"
            }

            assertFalse(notifier.maybePostToday(groups(2020)))
            assertTrue(postedNotifications().isEmpty())
        }

    @Test
    fun `pending intent targets on-device memories route with newest year`() =
        runBlocking {
            grantPostPermission()
            setEnabled(true)

            assertTrue(notifier.maybePostToday(groups(2019, 2021)))

            val notification = postedNotifications().single()
            val pendingIntent = notification.contentIntent
            assertNotNull(pendingIntent)
            val intent = shadowOf(pendingIntent).savedIntent
            assertNotNull(intent)
            assertEquals(Intent.ACTION_VIEW, intent!!.action)
            assertEquals("app://optique/memories?year=2021", intent.data.toString())
            assertEquals(MainActivity::class.java.name, intent.component?.className)
            // The cloud memories route must not be targeted.
            assertFalse(intent.data.toString().contains("memories_screen"))
        }
}
