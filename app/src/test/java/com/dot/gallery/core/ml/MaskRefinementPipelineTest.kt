/*
 * SPDX-FileCopyrightText: 2023-2026 alzimerahmed
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.core.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the pure mask refinement pipeline (Cutout Engine / Mask Refinement).
 *
 * Grid convention: 100x100 (size = 10000) gives hole-fill threshold
 * = (10000*0.005).coerceIn(100, 2000) = 100 px and island-removal threshold
 * = (10000*0.002).coerceIn(50, 500) = 50 px.
 */
class MaskRefinementPipelineTest {

    private val w = 100
    private val h = 100

    private fun fullMask(): IntArray = IntArray(w * h) { 0xFFFFFFFF.toInt() }

    private fun alphaAt(mask: IntArray, x: Int, y: Int): Int = (mask[y * w + x] ushr 24) and 0xFF

    private fun fillRect(mask: IntArray, x0: Int, y0: Int, rw: Int, rh: Int, value: Int) {
        for (y in y0 until y0 + rh) {
            for (x in x0 until x0 + rw) {
                mask[y * w + x] = value
            }
        }
    }

    @Test
    fun `small interior hole is filled`() {
        val mask = fullMask()
        fillRect(mask, 40, 40, 4, 4, 0x00FFFFFF) // 16 px hole < 100 px threshold
        MaskRefinementPipeline.refineMask(mask, w, h, emptyList())
        assertEquals(255, alphaAt(mask, 41, 41))
        assertEquals(255, alphaAt(mask, 43, 43))
    }

    @Test
    fun `large interior hole is preserved`() {
        val mask = fullMask()
        fillRect(mask, 30, 30, 30, 30, 0x00FFFFFF) // 900 px hole >= 100 px threshold
        MaskRefinementPipeline.refineMask(mask, w, h, emptyList())
        assertEquals(0, alphaAt(mask, 45, 45))
        assertEquals(255, alphaAt(mask, 10, 10))
    }

    @Test
    fun `background connected to border is untouched`() {
        val mask = IntArray(w * h) { 0x00FFFFFF } // all background
        fillRect(mask, 10, 10, 10, 10, 0xFFFFFFFF.toInt()) // one foreground blob
        MaskRefinementPipeline.refineMask(mask, w, h, emptyList())
        // 100 px island >= 50 px threshold: kept
        assertEquals(255, alphaAt(mask, 12, 12))
        assertEquals(0, alphaAt(mask, 50, 50))
    }

    @Test
    fun `small isolated island is removed`() {
        val mask = fullMask()
        fillRect(mask, 5, 5, 3, 3, 0xFFFFFFFF.toInt()) // 9 px island < 50 px threshold
        // Separate it from the main component with a background ring
        fillRect(mask, 0, 0, 10, 10, 0x00FFFFFF)
        fillRect(mask, 5, 5, 3, 3, 0xFFFFFFFF.toInt())
        MaskRefinementPipeline.refineMask(mask, w, h, emptyList())
        assertEquals(0, alphaAt(mask, 6, 6))
    }

    @Test
    fun `small island containing a prompt point is kept`() {
        val mask = fullMask()
        fillRect(mask, 0, 0, 10, 10, 0x00FFFFFF)
        fillRect(mask, 5, 5, 3, 3, 0xFFFFFFFF.toInt())
        MaskRefinementPipeline.refineMask(mask, w, h, listOf(6 to 6))
        assertEquals(255, alphaAt(mask, 6, 6))
    }

    @Test
    fun `empty mask is a no-op`() {
        val mask = IntArray(0)
        MaskRefinementPipeline.refineMask(mask, 0, 0, emptyList())
        assertTrue(mask.isEmpty())
    }

    @Test
    fun `box blur preserves uniform alpha`() {
        val mask = IntArray(w * h) { 0xFF000000.toInt() }
        MaskRefinementPipeline.boxBlur(mask, w, h, 2)
        assertEquals(255, (mask[50 * w + 50] ushr 24) and 0xFF)
        assertEquals(255, (mask[0] ushr 24) and 0xFF)
    }

    @Test
    fun `box blur softens hard edges`() {
        // 5x5: left column opaque, rest transparent, radius 1
        val bw = 5
        val bh = 5
        val mask = IntArray(bw * bh) { 0x00FFFFFF }
        for (y in 0 until bh) mask[y * bw] = 0xFFFFFFFF.toInt()
        MaskRefinementPipeline.boxBlur(mask, bw, bh, 1)
        fun alphaAt(x: Int, y: Int) = (mask[y * bw + x] ushr 24) and 0xFF
        // Edge pixel is softened: strictly between the opaque column (255) and empty interior (0)
        val edge = alphaAt(0, 2)
        assertTrue("edge alpha $edge should be softened", edge in 1..254)
        // Far from the edge stays 0
        assertEquals(0, alphaAt(4, 4))
    }
}
