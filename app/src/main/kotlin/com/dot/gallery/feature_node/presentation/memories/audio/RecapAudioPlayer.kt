/*
 * SPDX-FileCopyrightText: 2023-2026 alzimerahmed
 * SPDX-License-Identifier: Apache-2.0
 */

@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package com.dot.gallery.feature_node.presentation.memories.audio

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.annotation.RawRes
import androidx.core.net.toUri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.dot.gallery.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Audio-only soundtrack wrapper for the memories recap player (U6, R5/KTD5).
 *
 * Plays a single bundled `res/raw` asset in a loop. Silent by default: nothing is created
 * or played until [setEnabled] is called with `true`. The enabled state is a plain user
 * toggle — it intentionally survives pager slide changes and only ends on an explicit
 * toggle-off or [release] (e.g. recap pager exit / composition disposal).
 *
 * The underlying [ExoPlayer] is created lazily on first enable and is hidden behind
 * [SoundtrackBackend] so the toggle state machine is unit-testable without a real player.
 * When the bundled asset is missing (or fails to resolve) [RecapAudioState.isSoundtrackAvailable]
 * is `false` and the toggle is a no-op — callers render the switch disabled with the
 * `memories_soundtrack_unavailable` explanation.
 */
class RecapAudioPlayer(
    private val appContext: Context,
    @RawRes private val soundtrackResId: Int? = R.raw.memories_recap_soundtrack,
    private val backendFactory: (Context, Uri) -> SoundtrackBackend = ::ExoSoundtrackBackend,
) {
    /** Minimal player surface required by [RecapAudioPlayer]; faked in unit tests. */
    interface SoundtrackBackend {
        fun play()

        fun stop()

        fun release()
    }

    data class RecapAudioState(
        val isSoundtrackAvailable: Boolean,
        val isPlaying: Boolean,
    )

    private val _state =
        MutableStateFlow(
            RecapAudioState(
                isSoundtrackAvailable = resolveAvailability(),
                isPlaying = false,
            ),
        )

    /** Compose-friendly soundtrack state; collect with `collectAsStateWithLifecycle`. */
    val state: StateFlow<RecapAudioState> = _state.asStateFlow()

    /** Whether the bundled soundtrack asset resolved; when `false` the toggle must render disabled. */
    val isSoundtrackAvailable: Boolean get() = _state.value.isSoundtrackAvailable

    /** Whether the user toggle is currently on. */
    val isPlaying: Boolean get() = _state.value.isPlaying

    private var backend: SoundtrackBackend? = null
    private var released = false

    /**
     * Soundtrack toggle. `true` starts looping playback, `false` stops it immediately.
     * No-op when the asset is unavailable or after [release]. The state is sticky — it is
     * never reset by pager slide changes, only by this toggle or [release].
     */
    @Synchronized
    fun setEnabled(enabled: Boolean) {
        val current = _state.value
        if (released || !current.isSoundtrackAvailable || enabled == current.isPlaying) return
        if (enabled) {
            val created = backendOrNull() ?: return
            runCatching { created.play() }
                .onSuccess { _state.update { it.copy(isPlaying = true) } }
                .onFailure { _state.update { it.copy(isSoundtrackAvailable = false, isPlaying = false) } }
        } else {
            runCatching { backend?.stop() }
            _state.update { it.copy(isPlaying = false) }
        }
    }

    /** Stops playback and releases the underlying player. Idempotent; safe on pager exit. */
    @Synchronized
    fun release() {
        if (released) return
        released = true
        runCatching {
            backend?.stop()
            backend?.release()
        }
        backend = null
        _state.update { it.copy(isPlaying = false) }
    }

    private fun backendOrNull(): SoundtrackBackend? {
        val resolved = backend ?: createBackend()
        backend = resolved
        return resolved
    }

    private fun createBackend(): SoundtrackBackend? {
        val resId = soundtrackResId ?: return null
        return runCatching { backendFactory(appContext, rawResourceUri(resId)) }
            .onFailure { _state.update { it.copy(isSoundtrackAvailable = false) } }
            .getOrNull()
    }

    /** `android.resource://` URIs are routed to `RawResourceDataSource` by `DefaultDataSource`. */
    private fun rawResourceUri(
        @RawRes resId: Int,
    ): Uri = "${ContentResolver.SCHEME_ANDROID_RESOURCE}://${appContext.packageName}/$resId".toUri()

    private fun resolveAvailability(): Boolean {
        val resId = soundtrackResId ?: return false
        return runCatching { appContext.resources.getResourceName(resId) != null }
            .getOrDefault(false)
    }

    private class ExoSoundtrackBackend(
        context: Context,
        uri: Uri,
    ) : SoundtrackBackend {
        private val player: ExoPlayer =
            ExoPlayer
                .Builder(context)
                .setAudioAttributes(
                    AudioAttributes
                        .Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(),
                    true,
                ).setHandleAudioBecomingNoisy(true)
                .build()
                .apply {
                    setMediaItem(MediaItem.fromUri(uri))
                    repeatMode = Player.REPEAT_MODE_ONE
                    volume = SOUNDTRACK_VOLUME
                    prepare()
                }

        override fun play() {
            if (!player.isReleased) player.play()
        }

        override fun stop() {
            if (player.isReleased) return
            player.pause()
            player.seekTo(0L)
        }

        override fun release() {
            if (!player.isReleased) player.release()
        }
    }

    private companion object {
        /** Quieter than full volume so the soundtrack sits under narration/UX sounds. */
        const val SOUNDTRACK_VOLUME = 0.4f
    }
}

/**
 * Remembers a [RecapAudioPlayer] for the recap pager and releases it when the caller leaves
 * composition (pager exit). The enabled state survives pager slides because the player is
 * remembered — it only stops via [RecapAudioPlayer.setEnabled] or this disposal.
 *
 * A lifecycle observer silences playback on `ON_STOP` (app backgrounded): the toggle is
 * turned off while [RecapAudioPlayer.isSoundtrackAvailable] stays intact, so returning to
 * the foreground simply shows the switch off again instead of leaking audio.
 */
@Composable
fun rememberRecapAudioPlayer(): RecapAudioPlayer {
    val context = LocalContext.current
    val player = remember { RecapAudioPlayer(context.applicationContext) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(player, lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP) player.setEnabled(false)
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            player.release()
        }
    }
    return player
}
