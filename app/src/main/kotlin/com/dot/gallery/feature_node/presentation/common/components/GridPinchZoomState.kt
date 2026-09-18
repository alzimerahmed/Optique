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
import androidx.compose.foundation.lazy.grid.GridCells
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
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Scope provided by [GridPinchZoomLayout] to its content.
 * Drop-in replacement for the library's PinchZoomGridScope.
 */
interface GridPinchZoomScope {
    val gridState: LazyGridState
    val gridCells: GridCells

    /**
     * No-op modifier kept for source compatibility with items
     * that previously used the library's pinchItem transitions.
     */
    fun Modifier.pinchItem(key: Any): Modifier = this
}

@Stable
class GridPinchZoomState(
    initialCellsIndex: Int,
    val cellsList: List<GridCells>,
    val gridState: LazyGridState,
) {
    /** Precomputed column counts for each entry in [cellsList]. */
    internal val columnCounts: List<Int> = cellsList.map { cells ->
        if (cells is GridCells.Fixed) -cells.hashCode() else 4
    }

    var currentCellsIndex by mutableIntStateOf(initialCellsIndex.coerceIn(0, cellsList.lastIndex))
        private set

    val currentCells: GridCells
        get() = cellsList[currentCellsIndex]

    var isZooming by mutableStateOf(false)
        internal set

    internal val scaleAnimatable = Animatable(1f)
    internal var accumulatedScale by mutableFloatStateOf(1f)

    var zoomProgress by mutableFloatStateOf(0f)
        internal set

    fun updateCellsIndex(index: Int) {
        currentCellsIndex = index.coerceIn(0, cellsList.lastIndex)
    }
}

@Composable
fun rememberGridPinchZoomState(
    cellsList: List<GridCells>,
    initialCellsIndex: Int = 0,
    gridState: LazyGridState = rememberLazyGridState(),
): GridPinchZoomState {
    return remember(gridState, cellsList) {
        GridPinchZoomState(initialCellsIndex, cellsList, gridState)
    }
}

@Composable
fun GridPinchZoomLayout(
    state: GridPinchZoomState,
    modifier: Modifier = Modifier,
    indicatorTopPadding: Dp = 32.dp,
    content: @Composable GridPinchZoomScope.() -> Unit
) {
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val pinchScope = remember(state) {
        GridPinchZoomScopeImpl(state)
    }

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
                        // Track snap zone: -1 = zooming out (more cols), 0 = neutral, 1 = zooming in (fewer cols)
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
                                    val s = state.accumulatedScale
                                    state.zoomProgress = when {
                                        s > 1f -> ((s - 1f) / PinchZoomSpec.ProgressSpan).coerceIn(0f, 1f)
                                        s < 1f -> -((1f - s) / PinchZoomSpec.ProgressSpan).coerceIn(0f, 1f)
                                        else -> 0f
                                    }

                                    // Determine current snap zone and vibrate on transitions
                                    val snapZone = when {
                                        s > PinchZoomSpec.ZoomInThreshold && state.currentCellsIndex < state.cellsList.lastIndex -> 1
                                        s < PinchZoomSpec.ZoomOutThreshold && state.currentCellsIndex > 0 -> -1
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
                            val scale = state.accumulatedScale
                            val targetIndex = if (scale > PinchZoomSpec.ZoomInThreshold) {
                                (state.currentCellsIndex + 1).coerceAtMost(state.cellsList.lastIndex)
                            } else if (scale < PinchZoomSpec.ZoomOutThreshold) {
                                (state.currentCellsIndex - 1).coerceAtLeast(0)
                            } else {
                                state.currentCellsIndex
                            }

                            val changed = targetIndex != state.currentCellsIndex
                            state.zoomProgress = 0f
                            if (changed) {
                                view.performHapticFeedback(
                                    HapticFeedbackConstants.CONFIRM
                                )
                                val oldCount = state.columnCounts[state.currentCellsIndex]
                                state.updateCellsIndex(targetIndex)
                                val newCount = state.columnCounts[state.currentCellsIndex]
                                val compensationScale =
                                    oldCount.toFloat() / newCount.toFloat()
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
            pinchScope.content()
        }

        val idx = state.currentCellsIndex
        val counts = state.columnCounts
        PinchZoomIndicator(
            isZooming = state.isZooming,
            zoomProgress = state.zoomProgress,
            leftValue = if (idx < counts.lastIndex) counts[idx + 1] else null,
            centerValue = counts[idx],
            rightValue = if (idx > 0) counts[idx - 1] else null,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = indicatorTopPadding)
        )
    }
}

private class GridPinchZoomScopeImpl(
    private val state: GridPinchZoomState
) : GridPinchZoomScope {
    override val gridState: LazyGridState
        get() = state.gridState

    override val gridCells: GridCells
        get() = state.currentCells
}
