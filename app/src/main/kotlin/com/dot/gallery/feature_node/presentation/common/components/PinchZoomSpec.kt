/*
 * SPDX-FileCopyrightText: 2023-2026 alzimerahmed
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.feature_node.presentation.common.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dot.gallery.ui.theme.MotionSpec

/**
 * Shared pinch-zoom choreography for [GridPinchZoomLayout] and [MosaicPinchZoomLayout]:
 * accumulated-scale thresholds, indicator segments and gesture-release springs.
 */
internal object PinchZoomSpec {
    /** Accumulated-scale delta that maps to full indicator progress. */
    const val ProgressSpan = 0.15f

    /** Accumulated scale past which releasing commits a zoom-in (fewer columns). */
    const val ZoomInThreshold = 1.15f

    /** Accumulated scale below which releasing commits a zoom-out (more columns). */
    const val ZoomOutThreshold = 0.85f

    /** Fill segments on each side of the zoom indicator. */
    const val IndicatorBars = 5

    /** Gesture released inside the dead zone — snap back to rest. */
    fun <T> releaseSpring(): SpringSpec<T> = MotionSpec.snapSpring()

    /** Post-snap scale compensation into the new cell size — gentle bounce. */
    fun <T> compensationSpring(): SpringSpec<T> = MotionSpec.playfulSpring()
}

/**
 * Segmented zoom indicator shared by the grid and mosaic pinch-zoom layouts.
 * [leftValue] is the column count a zoom-in would land on (null at the list edge),
 * [rightValue] the zoom-out target, [centerValue] the current count.
 */
@Composable
internal fun PinchZoomIndicator(
    isZooming: Boolean,
    zoomProgress: Float,
    leftValue: Int?,
    centerValue: Int,
    rightValue: Int?,
    modifier: Modifier = Modifier,
) {
    val isActive = isZooming && zoomProgress != 0f

    AnimatedVisibility(
        visible = isActive,
        modifier = modifier,
        enter = fadeIn(spring(stiffness = Spring.StiffnessHigh)) + scaleIn(
            initialScale = 0.8f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessHigh
            )
        ),
        exit = fadeOut(MotionSpec.snapSpring()) + scaleOut(
            targetScale = 0.8f,
            animationSpec = MotionSpec.snapSpring()
        )
    ) {
        val animatedProgress by animateFloatAsState(
            targetValue = zoomProgress,
            animationSpec = MotionSpec.settleSpring(),
            label = "pinchZoomBarProgress"
        )

        val leftFill = if (animatedProgress > 0f) animatedProgress else 0f
        val rightFill = if (animatedProgress < 0f) -animatedProgress else 0f

        val activeColor = MaterialTheme.colorScheme.primary
        val inactiveColor = MaterialTheme.colorScheme.outlineVariant
        val numberColor = MaterialTheme.colorScheme.onSurface
        val dimColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)

        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Left number (zoom-in target)
            Text(
                text = leftValue?.toString() ?: "",
                color = if (leftValue != null) {
                    if (leftFill >= 1f) activeColor else numberColor
                } else dimColor,
                fontSize = 13.sp,
                fontWeight = if (leftFill >= 1f) FontWeight.Bold else FontWeight.Medium,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(14.dp)
            )

            // Left bars: fill from center (right) outward (left)
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                for (i in 0 until PinchZoomSpec.IndicatorBars) {
                    val barFill = if (leftValue != null) {
                        (leftFill * PinchZoomSpec.IndicatorBars -
                            (PinchZoomSpec.IndicatorBars - 1 - i)).coerceIn(0f, 1f)
                    } else 0f
                    PinchZoomBar(
                        fill = barFill,
                        activeColor = activeColor,
                        inactiveColor = inactiveColor
                    )
                }
            }

            // Center number (current)
            Text(
                text = "$centerValue",
                color = numberColor,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(14.dp)
            )

            // Right bars: fill from center (left) outward (right)
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                for (i in 0 until PinchZoomSpec.IndicatorBars) {
                    val barFill = if (rightValue != null) {
                        (rightFill * PinchZoomSpec.IndicatorBars - i).coerceIn(0f, 1f)
                    } else 0f
                    PinchZoomBar(
                        fill = barFill,
                        activeColor = activeColor,
                        inactiveColor = inactiveColor
                    )
                }
            }

            // Right number (zoom-out target)
            Text(
                text = rightValue?.toString() ?: "",
                color = if (rightValue != null) {
                    if (rightFill >= 1f) activeColor else numberColor
                } else dimColor,
                fontSize = 13.sp,
                fontWeight = if (rightFill >= 1f) FontWeight.Bold else FontWeight.Medium,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(14.dp)
            )
        }
    }
}

@Composable
private fun PinchZoomBar(
    fill: Float,
    activeColor: Color,
    inactiveColor: Color,
) {
    Box(
        modifier = Modifier
            .width(3.dp)
            .height(14.dp)
            .clip(RoundedCornerShape(1.5.dp))
            .background(lerp(inactiveColor, activeColor, fill))
    )
}
