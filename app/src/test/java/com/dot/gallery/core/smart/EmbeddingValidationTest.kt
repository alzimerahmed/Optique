/*
 * SPDX-FileCopyrightText: 2023-2026 alzimerahmed
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.core.smart

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddingValidationTest {

    private val valid = FloatArray(512) { 0.044194f } // 512 * x^2 == 1 when x = 1/sqrt(512)

    @Test
    fun `valid unit-norm vector passes`() {
        assertTrue(isValidEmbeddingVector(valid, 512))
    }

    @Test
    fun `wrong dimension fails`() {
        assertFalse(isValidEmbeddingVector(FloatArray(511) { 0f }, 512))
        assertFalse(isValidEmbeddingVector(FloatArray(513) { 0.044194f }, 512))
    }

    @Test
    fun `non-finite values fail`() {
        val withNaN = valid.copyOf().also { it[7] = Float.NaN }
        assertFalse(isValidEmbeddingVector(withNaN, 512))

        val withInf = valid.copyOf().also { it[7] = Float.POSITIVE_INFINITY }
        assertFalse(isValidEmbeddingVector(withInf, 512))
    }

    @Test
    fun `zero vector fails norm check`() {
        assertFalse(isValidEmbeddingVector(FloatArray(512), 512))
    }

    @Test
    fun `norm outside tolerance fails`() {
        assertFalse(isValidEmbeddingVector(FloatArray(512) { 0.05f }, 512))
        assertFalse(isValidEmbeddingVector(FloatArray(512) { 0.01f }, 512))
    }
}
