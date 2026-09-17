/*
 * SPDX-FileCopyrightText: 2023-2026 alzimerahmed
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.feature_node.presentation.memories

import android.content.Context
import android.widget.Toast
import com.dot.gallery.R
import com.dot.gallery.feature_node.domain.memories.YearRecap
import com.dot.gallery.feature_node.domain.model.Media
import com.dot.gallery.feature_node.presentation.util.shareMedia
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * U7/R8 share pipeline.
 *
 * The share set is always the recap's *full* [YearRecap.featured] list — never
 * the slide the pager happens to be on — so sharing works identically before,
 * during, and after playback (F3).
 */
internal fun recapShareSet(recap: YearRecap): List<Media.UriMedia> = recap.featured

/**
 * Share [media] through the standard multi-media share pipeline
 * ([Context.shareMedia], which wraps the send intent in `Intent.createChooser`
 * via `ShareCompat`). Any failure — no handler (`ActivityNotFoundException`),
 * `SecurityException`, URI resolution errors — shows the
 * [R.string.memories_share_no_app] toast instead of crashing or silently doing
 * nothing.
 */
internal suspend fun Context.shareRecapMedia(media: List<Media.UriMedia>) {
    try {
        shareMedia(media)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        withContext(Dispatchers.Main) {
            val text = getString(R.string.memories_share_no_app)
            Toast.makeText(this@shareRecapMedia, text, Toast.LENGTH_SHORT).show()
        }
    }
}
