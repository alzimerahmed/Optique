/*
 * SPDX-FileCopyrightText: 2023-2026 alzimerahmed
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.feature_node.presentation.memories

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import com.dot.gallery.R
import com.dot.gallery.feature_node.domain.memories.YearRecap
import com.dot.gallery.feature_node.domain.model.Media
import com.dot.gallery.feature_node.presentation.util.shareExtraMimeTypes
import com.dot.gallery.feature_node.presentation.util.shareMimeType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import java.time.LocalDate
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class RecapShareTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun media(
        id: Long,
        mimeType: String = "image/jpeg",
        duration: String? = null,
    ): Media.UriMedia {
        val epochSeconds =
            LocalDate
                .of(2020, 6, 15)
                .atTime(12, 0)
                .toInstant(ZoneOffset.UTC)
                .epochSecond
        return Media.UriMedia(
            id = id,
            label = "Photo_$id.jpg",
            uri = Uri.parse("content://media/$id"),
            path = "/storage/DCIM/Photo_$id.jpg",
            relativePath = "DCIM/Camera",
            albumID = 1L,
            albumLabel = "Camera",
            timestamp = epochSeconds,
            takenTimestamp = epochSeconds * 1000L,
            fullDate = "",
            mimeType = mimeType,
            favorite = 0,
            trashed = 0,
            size = 1L,
            duration = duration,
        )
    }

    @Test
    fun `share set is the full featured list regardless of pager position`() {
        // F3: the share set comes straight from YearRecap.featured — the pager
        // index is never consulted, so sharing is identical before, during,
        // and after playback.
        val featured = listOf(media(1), media(2), media(3))
        val recap = YearRecap(year = 2020, featured = featured)

        assertEquals(featured, recapShareSet(recap))
        assertSame(featured, recapShareSet(recap))
    }

    @Test
    fun `photo-only share set resolves to image mime`() {
        val list = listOf(media(1), media(2))

        assertEquals("image/*", list.shareMimeType())
        assertNull(list.shareExtraMimeTypes())
    }

    @Test
    fun `video-only share set resolves to video mime`() {
        val list = listOf(media(1, mimeType = "video/mp4", duration = "12000"))

        assertEquals("video/*", list.shareMimeType())
        assertNull(list.shareExtraMimeTypes())
    }

    @Test
    fun `mixed photo and video set uses wildcard type with extra mime types`() {
        val list = listOf(media(1), media(2, mimeType = "video/mp4", duration = "12000"))

        assertEquals("*/*", list.shareMimeType())
        assertArrayEquals(arrayOf("image/*", "video/*"), list.shareExtraMimeTypes())
    }

    @Test
    fun `share launches a chooser wrapping a send-multiple intent`() =
        runBlocking {
            // ShareCompat.startChooser needs an Activity context (matching the
            // Compose LocalContext used in production).
            val activity =
                Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
            val list =
                listOf(
                    media(1),
                    media(2, mimeType = "video/mp4", duration = "12000"),
                    media(3),
                )

            activity.shareRecapMedia(list)

            val chooser = shadowOf(activity).nextStartedActivity
            assertNotNull(chooser)
            assertEquals(Intent.ACTION_CHOOSER, chooser.action)

            val send = chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            assertNotNull(send)
            assertEquals(Intent.ACTION_SEND_MULTIPLE, send!!.action)
            assertEquals("*/*", send.type)
            assertArrayEquals(
                arrayOf("image/*", "video/*"),
                send.getStringArrayExtra(Intent.EXTRA_MIME_TYPES),
            )
            assertEquals(
                list.map { it.uri },
                send.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java),
            )
        }

    @Test
    fun `missing share target shows toast instead of crashing`() =
        runBlocking {
            val app = ApplicationProvider.getApplicationContext<Context>()
            val noHandlerContext =
                object : ContextWrapper(app) {
                    override fun startActivity(intent: Intent): Unit =
                        throw ActivityNotFoundException("test: no share handler")
                }

            noHandlerContext.shareRecapMedia(listOf(media(1)))

            assertNotNull(ShadowToast.getLatestToast())
            assertEquals(
                app.getString(R.string.memories_share_no_app),
                ShadowToast.getTextOfLatestToast(),
            )
        }

    @Test
    fun `non-activity share failure also shows toast instead of crashing`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val failingContext =
            object : ContextWrapper(app) {
                override fun startActivity(intent: Intent): Unit =
                    throw SecurityException("test: share denied")
            }

        failingContext.shareRecapMedia(listOf(media(1)))

        assertNotNull(ShadowToast.getLatestToast())
        assertEquals(
            app.getString(R.string.memories_share_no_app),
            ShadowToast.getTextOfLatestToast(),
        )
    }
}
