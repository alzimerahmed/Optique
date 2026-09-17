/*
 * SPDX-FileCopyrightText: 2023-2026 alzimerahmed
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.core.ml

/**
 * Pure-Kotlin mask refinement pipeline used by the Cutout Engine (Subject Cutout).
 *
 * Operates on ARGB int arrays where only the alpha channel is meaningful:
 * alpha 0 = background, alpha 255 = foreground. No Android dependencies,
 * so the pipeline is unit-testable on the JVM.
 */
object MaskRefinementPipeline {

    /**
     * Refine a binary mask (alpha-only ARGB) in crop space:
     * 1. Fill small interior holes (BFS hole-fill, 4-connectivity).
     * 2. Remove small isolated foreground islands that contain no prompt point.
     *
     * @param mask ARGB pixels, mutated in place.
     * @param w crop width, @param h crop height.
     * @param cropPoints prompt points in crop coordinates (x, y).
     */
    fun refineMask(mask: IntArray, w: Int, h: Int, cropPoints: List<Pair<Int, Int>>) {
        val size = w * h
        if (size == 0) return

        // Calculate dynamic thresholds based on crop area
        val maxHoleSizeToFill = (size * 0.005f).coerceIn(100f, 2000f).toInt()
        val maxIslandSizeToRemove = (size * 0.002f).coerceIn(50f, 500f).toInt()

        val STATE_UNVISITED: Byte = 0
        val STATE_BG: Byte = 1
        val STATE_HOLE: Byte = 2
        val STATE_FG: Byte = 3

        val state = ByteArray(size) // Stores current state of each pixel
        val stack = IntArray(size)  // Reusable BFS queue/stack to avoid garbage collection
        var stackPtr = 0

        // Helper to push boundary-connected background to stack
        fun pushBg(x: Int, y: Int) {
            val idx = y * w + x
            if (idx in 0 until size && state[idx] == STATE_UNVISITED && (mask[idx] ushr 24) == 0) {
                state[idx] = STATE_BG
                stack[stackPtr++] = idx
            }
        }

        // Push all boundary pixels that are background
        for (x in 0 until w) {
            pushBg(x, 0)
            pushBg(x, h - 1)
        }
        for (y in 1 until h - 1) {
            pushBg(0, y)
            pushBg(w - 1, y)
        }

        // Run BFS to mark reachable background
        while (stackPtr > 0) {
            val idx = stack[--stackPtr]
            val x = idx % w
            val y = idx / w

            // 4-connectivity
            if (x > 0) pushBg(x - 1, y)
            if (x < w - 1) pushBg(x + 1, y)
            if (y > 0) pushBg(x, y - 1)
            if (y < h - 1) pushBg(x, y + 1)
        }

        // 1. Fill small inner holes (unvisited background regions)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val startIdx = y * w + x
                if ((mask[startIdx] ushr 24) == 0 && state[startIdx] == STATE_UNVISITED) {
                    var holeSize = 0
                    state[startIdx] = STATE_HOLE
                    stack[holeSize++] = startIdx

                    var holeReadPtr = 0
                    while (holeReadPtr < holeSize) {
                        val idx = stack[holeReadPtr++]
                        val cx = idx % w
                        val cy = idx / w

                        fun checkAndAddHole(nx: Int, ny: Int) {
                            val nidx = ny * w + nx
                            if (nidx in 0 until size && (mask[nidx] ushr 24) == 0 && state[nidx] == STATE_UNVISITED) {
                                state[nidx] = STATE_HOLE
                                stack[holeSize++] = nidx
                            }
                        }

                        if (cx > 0) checkAndAddHole(cx - 1, cy)
                        if (cx < w - 1) checkAndAddHole(cx + 1, cy)
                        if (cy > 0) checkAndAddHole(cx, cy - 1)
                        if (cy < h - 1) checkAndAddHole(cx, cy + 1)
                    }

                    // Fill the hole if it is smaller than the threshold
                    if (holeSize < maxHoleSizeToFill) {
                        for (i in 0 until holeSize) {
                            mask[stack[i]] = 0xFFFFFFFF.toInt()
                        }
                    }
                }
            }
        }

        // 2. Remove small isolated foreground islands
        for (y in 0 until h) {
            for (x in 0 until w) {
                val startIdx = y * w + x
                if ((mask[startIdx] ushr 24) != 0 && state[startIdx] != STATE_FG) {
                    var compSize = 0
                    state[startIdx] = STATE_FG
                    stack[compSize++] = startIdx

                    var compReadPtr = 0
                    while (compReadPtr < compSize) {
                        val idx = stack[compReadPtr++]
                        val cx = idx % w
                        val cy = idx / w

                        fun checkAndAddFg(nx: Int, ny: Int) {
                            val nidx = ny * w + nx
                            if (nidx in 0 until size && (mask[nidx] ushr 24) != 0 && state[nidx] != STATE_FG) {
                                state[nidx] = STATE_FG
                                stack[compSize++] = nidx
                            }
                        }

                        if (cx > 0) checkAndAddFg(cx - 1, cy)
                        if (cx < w - 1) checkAndAddFg(cx + 1, cy)
                        if (cy > 0) checkAndAddFg(cx, cy - 1)
                        if (cy < h - 1) checkAndAddFg(cx, cy + 1)
                    }

                    // Check if component contains any prompt point
                    var containsPrompt = false
                    for (i in 0 until compSize) {
                        val idx = stack[i]
                        val cx = idx % w
                        val cy = idx / w
                        if (cropPoints.any { (px, py) -> px == cx && py == cy }) {
                            containsPrompt = true
                            break
                        }
                    }

                    // Erase component if it is small and has no prompt points
                    if (compSize < maxIslandSizeToRemove && !containsPrompt) {
                        for (i in 0 until compSize) {
                            mask[stack[i]] = 0x00FFFFFF // Erase to transparent background
                        }
                    }
                }
            }
        }
    }

    /**
     * Separable box blur over the alpha channel (radius in pixels), in place.
     * RGB channels are forced to 0x00FFFFFF.
     */
    fun boxBlur(pixels: IntArray, w: Int, h: Int, radius: Int) {
        val temp = IntArray(pixels.size)
        // Horizontal pass
        for (y in 0 until h) {
            var sum = 0
            var count = 0
            for (x in 0..radius.coerceAtMost(w - 1)) {
                val color = pixels[y * w + x]
                sum += (color shr 24) and 0xFF
                count++
            }
            for (x in 0 until w) {
                temp[y * w + x] = sum / count
                val nextX = x + radius + 1
                val prevX = x - radius
                if (nextX < w) {
                    val nextColor = pixels[y * w + nextX]
                    sum += (nextColor shr 24) and 0xFF
                    count++
                }
                if (prevX >= 0) {
                    val prevColor = pixels[y * w + prevX]
                    sum -= (prevColor shr 24) and 0xFF
                    count--
                }
            }
        }
        // Vertical pass
        for (x in 0 until w) {
            var sum = 0
            var count = 0
            for (y in 0..radius.coerceAtMost(h - 1)) {
                sum += temp[y * w + x]
                count++
            }
            for (y in 0 until h) {
                pixels[y * w + x] = (sum / count) shl 24 or 0x00FFFFFF
                val nextY = y + radius + 1
                val prevY = y - radius
                if (nextY < h) {
                    sum += temp[nextY * w + x]
                    count++
                }
                if (prevY >= 0) {
                    sum -= temp[prevY * w + x]
                    count--
                }
            }
        }
    }
}
