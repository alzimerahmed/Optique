/*
 * SPDX-FileCopyrightText: 2023-2026 alzimerahmed
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.feature_node.presentation.common.components

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dot.gallery.core.Constants.mosaicColumnsList
import kotlinx.coroutines.launch
import kotlin.math.abs

@Stable
class MosaicPinchZoomState(
    initialColumnsIndex: Int,
    val gridState: LazyGridState,
) {
    var currentColumnsIndex by mutableIntStateOf(initialColumnsIndex.coerceIn(0, mosaicColumnsList.lastIndex))
        private set

    val currentColumns: Int
        get() = mosaicColumnsList[currentColumnsIndex]

    var isZooming by mutableStateOf(false)
        internal set

    internal val scaleAnimatable = Animatable(1f)
    internal var accumulatedScale by mutableFloatStateOf(1f)

    /**
     * Progress towards a column change during a gesture.
     * Range: -1f..1f where:
     *   negative = progressing towards zoom-out (more columns)
     *   positive = progressing towards zoom-in (fewer columns)
     *   0 = no gesture / within dead zone
     */
    var zoomProgress by mutableFloatStateOf(0f)
        internal set

    fun updateColumnsIndex(index: Int) {
        currentColumnsIndex = index.coerceIn(0, mosaicColumnsList.lastIndex)
    }
}

@Composable
fun rememberMosaicPinchZoomState(
    initialColumnsIndex: Int = mosaicColumnsList.indexOf(4),
    gridState: LazyGridState = rememberLazyGridState(),
): MosaicPinchZoomState {
    return remember(gridState) {
        MosaicPinchZoomState(initialColumnsIndex, gridState)
    }
}

@Composable
fun MosaicPinchZoomLayout(
    state: MosaicPinchZoomState,
    modifier: Modifier = Modifier,
    indicatorTopPadding: Dp = 32.dp,
    content: @Composable (columns: Int) -> Unit
) {
    val scope = rememberCoroutineScope()
    val view = LocalView.current

    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(state) {
                    val touchSlop = viewConfiguration.touchSlop
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var zoom = 1f
                        var pastTouchSlop = false
                        var lastSnapZone = 0

                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val canceled = event.changes.any { it.isConsumed }
                            if (!canceled && event.changes.size >= 2) {
                                val zoomChange = event.calculateZoom()
                                if (!pastTouchSlop) {
                                    zoom *= zoomChange
                                    val centroidSize =
                                        event.calculateCentroidSize(useCurrent = false)
                                    val zoomMotion = abs(1 - zoom) * centroidSize
                                    if (zoomMotion > touchSlop) {
                                        pastTouchSlop = true
                                        state.isZooming = true
                                        state.accumulatedScale = 1f
                                        state.zoomProgress = 0f
                                        lastSnapZone = 0
                                    }
                                }

                                if (pastTouchSlop && zoomChange != 1f) {
                                    state.accumulatedScale *= zoomChange
                                    scope.launch {
                                        state.scaleAnimatable.snapTo(state.accumulatedScale)
                                    }
                                    // Update progress: map scale to -1..1
                                    val s = state.accumulatedScale
                                    state.zoomProgress = when {
                                        s > 1f -> ((s - 1f) / PinchZoomSpec.ProgressSpan).coerceIn(0f, 1f)
                                        s < 1f -> -((1f - s) / PinchZoomSpec.ProgressSpan).coerceIn(0f, 1f)
                                        else -> 0f
                                    }

                                    val snapZone = when {
                                        s > PinchZoomSpec.ZoomInThreshold && state.currentColumnsIndex < mosaicColumnsList.lastIndex -> 1
                                        s < PinchZoomSpec.ZoomOutThreshold && state.currentColumnsIndex > 0 -> -1
                                        else -> 0
                                    }
                                    if (snapZone != lastSnapZone) {
                                        view.performHapticFeedback(
                                            HapticFeedbackConstants.CLOCK_TICK
                                        )
                                        lastSnapZone = snapZone
                                    }

                                    event.changes.forEach { it.consume() }
                                }
                            }
                        } while (!canceled && event.changes.any { it.pressed })

                        if (pastTouchSlop) {
                            // Determine target column index based on accumulated scale
                            val scale = state.accumulatedScale
                            val targetIndex = if (scale > PinchZoomSpec.ZoomInThreshold) {
                                (state.currentColumnsIndex + 1).coerceAtMost(mosaicColumnsList.lastIndex)
                            } else if (scale < PinchZoomSpec.ZoomOutThreshold) {
                                (state.currentColumnsIndex - 1).coerceAtLeast(0)
                            } else {
                                state.currentColumnsIndex
                            }

                            val columnsChanged = targetIndex != state.currentColumnsIndex
                            state.zoomProgress = 0f
                            if (columnsChanged) {
                                view.performHapticFeedback(
                                    HapticFeedbackConstants.CONFIRM
                                )
                                val oldCols = state.currentColumns
                                state.updateColumnsIndex(targetIndex)
                                val newCols = state.currentColumns
                                val compensationScale =
                                    oldCols.toFloat() / newCols.toFloat()
                                scope.launch {
                                    state.scaleAnimatable.snapTo(compensationScale)
                                    state.scaleAnimatable.animateTo(
                                        1f,
                                        PinchZoomSpec.compensationSpring()
                                    )
                                    state.isZooming = false
                                }
                            } else {
                                scope.launch {
                                    state.scaleAnimatable.animateTo(
                                        1f,
                                        PinchZoomSpec.releaseSpring()
                                    )
                                    state.isZooming = false
                                }
                            }
                        }
                    }
                }
                .graphicsLayer {
                    val scale = state.scaleAnimatable.value
                    scaleX = scale
                    scaleY = scale
                }
        ) {
            content(state.currentColumns)
        }

        val idx = state.currentColumnsIndex
        PinchZoomIndicator(
            isZooming = state.isZooming,
            zoomProgress = state.zoomProgress,
            // Left = zoom-in target (fewer cols), Right = zoom-out target (more cols)
            leftValue = if (idx < mosaicColumnsList.lastIndex) mosaicColumnsList[idx + 1] else null,
            centerValue = state.currentColumns,
            rightValue = if (idx > 0) mosaicColumnsList[idx - 1] else null,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = indicatorTopPadding)
        )
    }
}
