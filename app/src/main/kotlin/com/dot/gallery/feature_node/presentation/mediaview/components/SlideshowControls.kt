/*
 * SPDX-FileCopyrightText: 2023-2026 alzimerahmed
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.feature_node.presentation.mediaview.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.dot.gallery.R
import com.dot.gallery.ui.theme.BlackScrim
import com.dot.gallery.ui.theme.Spacing

@Composable
fun SlideshowControls(
    isPaused: Boolean,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onExit: () -> Unit,
) {
    Row(
        modifier = Modifier
            .background(
                color = BlackScrim,
                shape = RoundedCornerShape(100)
            )
            .padding(horizontal = Spacing.Small, vertical = Spacing.ExtraSmall),
        horizontalArrangement = Arrangement.spacedBy(Spacing.ExtraSmall)
    ) {
        IconButton(onClick = onPrevious) {
            Icon(
                imageVector = Icons.Outlined.SkipPrevious,
                contentDescription = stringResource(R.string.slideshow_previous),
                tint = Color.White
            )
        }
        IconButton(onClick = onPlayPause) {
            Icon(
                imageVector = if (isPaused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause,
                contentDescription = stringResource(
                    if (isPaused) R.string.slideshow_play else R.string.slideshow_pause
                ),
                tint = Color.White
            )
        }
        IconButton(onClick = onNext) {
            Icon(
                imageVector = Icons.Outlined.SkipNext,
                contentDescription = stringResource(R.string.slideshow_next),
                tint = Color.White
            )
        }
        IconButton(onClick = onExit) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = stringResource(R.string.slideshow_exit),
                tint = Color.White
            )
        }
    }
}
