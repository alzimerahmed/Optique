/*
 * SPDX-FileCopyrightText: 2023-2026 alzimerahmed
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.feature_node.presentation.memories.audio

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.dot.gallery.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class RecapAudioPlayerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private class FakeBackend : RecapAudioPlayer.SoundtrackBackend {
        var playCalls = 0
        var stopCalls = 0
        var releaseCalls = 0

        override fun play() {
            playCalls++
        }

        override fun stop() {
            stopCalls++
        }

        override fun release() {
            releaseCalls++
        }
    }

    /** Records backend factory invocations so tests can assert lazy player creation. */
    private class BackendProbe {
        var factoryCalls = 0
        var lastUri: Uri? = null
        val backend = FakeBackend()
        val factory: (Context, Uri) -> RecapAudioPlayer.SoundtrackBackend = { _, uri ->
            factoryCalls++
            lastUri = uri
            backend
        }
    }

    @Test
    fun `bundled soundtrack asset resolves and starts silent`() {
        val player = RecapAudioPlayer(context)

        assertTrue(player.isSoundtrackAvailable)
        assertFalse(player.isPlaying)
        assertFalse(player.state.value.isPlaying)
    }

    @Test
    fun `player backend is created lazily on first enable`() {
        val probe = BackendProbe()
        val player =
            RecapAudioPlayer(
                context,
                soundtrackResId = R.raw.memories_recap_soundtrack,
                backendFactory = probe.factory,
            )

        assertEquals(0, probe.factoryCalls)

        player.setEnabled(true)

        assertEquals(1, probe.factoryCalls)
        assertNotNull(probe.lastUri)
        assertEquals("android.resource", probe.lastUri?.scheme)
    }

    @Test
    fun `toggle on plays the bundled asset`() {
        val probe = BackendProbe()
        val player =
            RecapAudioPlayer(
                context,
                soundtrackResId = R.raw.memories_recap_soundtrack,
                backendFactory = probe.factory,
            )

        player.setEnabled(true)

        assertTrue(player.isPlaying)
        assertTrue(player.state.value.isPlaying)
        assertEquals(1, probe.backend.playCalls)
    }

    @Test
    fun `toggle off stops playback immediately`() {
        val probe = BackendProbe()
        val player =
            RecapAudioPlayer(
                context,
                soundtrackResId = R.raw.memories_recap_soundtrack,
                backendFactory = probe.factory,
            )
        player.setEnabled(true)

        player.setEnabled(false)

        assertFalse(player.isPlaying)
        assertFalse(player.state.value.isPlaying)
        assertEquals(1, probe.backend.stopCalls)
    }

    @Test
    fun `enabled state persists across pager slide changes`() {
        val probe = BackendProbe()
        val player =
            RecapAudioPlayer(
                context,
                soundtrackResId = R.raw.memories_recap_soundtrack,
                backendFactory = probe.factory,
            )
        player.setEnabled(true)

        // Slide changes are a no-op for the soundtrack: the enabled state is sticky and is
        // only ended by an explicit toggle-off or release(). Re-reading state and re-applying
        // the same toggle must not stop or restart playback.
        repeat(3) {
            assertTrue(player.isPlaying)
            player.setEnabled(true)
        }

        assertTrue(player.isPlaying)
        assertEquals(1, probe.backend.playCalls)
        assertEquals(0, probe.backend.stopCalls)
    }

    @Test
    fun `re-enabling after toggle off restarts playback`() {
        val probe = BackendProbe()
        val player =
            RecapAudioPlayer(
                context,
                soundtrackResId = R.raw.memories_recap_soundtrack,
                backendFactory = probe.factory,
            )
        player.setEnabled(true)
        player.setEnabled(false)

        player.setEnabled(true)

        assertTrue(player.isPlaying)
        assertEquals(2, probe.backend.playCalls)
        assertEquals(1, probe.backend.stopCalls)
        // Backend is reused, not recreated.
        assertEquals(1, probe.factoryCalls)
    }

    @Test
    fun `missing asset disables the toggle without crash`() {
        val probe = BackendProbe()
        val player =
            RecapAudioPlayer(
                context,
                soundtrackResId = null,
                backendFactory = probe.factory,
            )

        assertFalse(player.isSoundtrackAvailable)
        assertFalse(player.isPlaying)

        // Toggle attempts are no-ops; no backend is ever created and nothing throws.
        player.setEnabled(true)
        player.setEnabled(false)

        assertFalse(player.isPlaying)
        assertEquals(0, probe.factoryCalls)

        player.release()
        assertEquals(0, probe.backend.releaseCalls)
    }

    @Test
    fun `unresolvable resource id disables the toggle without crash`() {
        val probe = BackendProbe()
        val player =
            RecapAudioPlayer(
                context,
                soundtrackResId = INVALID_RES_ID,
                backendFactory = probe.factory,
            )

        assertFalse(player.isSoundtrackAvailable)

        player.setEnabled(true)

        assertFalse(player.isPlaying)
        assertEquals(0, probe.factoryCalls)
    }

    @Test
    fun `release stops and disposes the backend and ignores further toggles`() {
        val probe = BackendProbe()
        val player =
            RecapAudioPlayer(
                context,
                soundtrackResId = R.raw.memories_recap_soundtrack,
                backendFactory = probe.factory,
            )
        player.setEnabled(true)

        player.release()

        assertFalse(player.isPlaying)
        assertEquals(1, probe.backend.stopCalls)
        assertEquals(1, probe.backend.releaseCalls)

        player.setEnabled(true)

        assertFalse(player.isPlaying)
        assertEquals(1, probe.backend.playCalls)
        assertEquals(1, probe.factoryCalls)
    }

    @Test
    fun `release before any toggle is a safe no-op`() {
        val probe = BackendProbe()
        val player =
            RecapAudioPlayer(
                context,
                soundtrackResId = R.raw.memories_recap_soundtrack,
                backendFactory = probe.factory,
            )

        player.release()
        player.release()

        assertFalse(player.isPlaying)
        assertEquals(0, probe.factoryCalls)
        assertEquals(0, probe.backend.releaseCalls)
    }

    @Test
    fun `backend creation failure marks the soundtrack unavailable`() {
        val player =
            RecapAudioPlayer(
                context,
                soundtrackResId = R.raw.memories_recap_soundtrack,
                backendFactory = { _, _ -> error("no codec") },
            )

        player.setEnabled(true)

        assertFalse(player.isPlaying)
        assertFalse(player.isSoundtrackAvailable)
    }

    private companion object {
        const val INVALID_RES_ID = -1
    }
}
