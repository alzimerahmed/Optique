/*
 * SPDX-FileCopyrightText: 2023-2026 alzimerahmed
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.core.decoder.format

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * JPEG quality estimation from DQT quantization tables (IJG method).
 * The standard quality-50 luminance table sums to 1024 (approx), so:
 * - exact standard table -> quality 50
 * - halved coefficients -> quality 75
 * - doubled coefficients -> quality ~25
 */
class SourceQualityProbeTest {

    private val stdLuminanceQt = intArrayOf(
        16, 11, 10, 16, 24, 40, 51, 61,
        12, 12, 14, 19, 26, 58, 60, 55,
        14, 13, 16, 24, 40, 57, 69, 56,
        14, 17, 22, 29, 51, 87, 80, 62,
        18, 22, 37, 56, 68, 109, 103, 77,
        24, 35, 55, 64, 81, 104, 113, 92,
        49, 64, 78, 87, 103, 121, 120, 101,
        72, 92, 95, 98, 112, 100, 103, 99
    )

    private fun jpegWithDqt(table: IntArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte())) // SOI
        out.write(byteArrayOf(0xFF.toByte(), 0xDB.toByte())) // DQT marker
        val len = 2 + 1 + 64
        out.write(byteArrayOf((len shr 8).toByte(), (len and 0xFF).toByte()))
        out.write(0x00) // precision 0 (8-bit), id 0 (luminance)
        table.forEach { out.write(it and 0xFF) }
        out.write(byteArrayOf(0xFF.toByte(), 0xD9.toByte())) // EOI
        return out.toByteArray()
    }

    @Test
    fun `standard quality-50 table yields quality 50`() {
        val q = SourceQualityProbe.detect(jpegWithDqt(stdLuminanceQt), "image/jpeg")
        assertEquals(50, q)
    }

    @Test
    fun `lower quantization values yield higher quality`() {
        val halved = stdLuminanceQt.map { (it / 2).coerceAtLeast(1) }.toIntArray()
        val q = SourceQualityProbe.detect(jpegWithDqt(halved), "image/jpeg")
        assertEquals(75, q)
    }

    @Test
    fun `higher quantization values yield lower quality`() {
        val doubled = stdLuminanceQt.map { it * 2 }.toIntArray()
        val q = SourceQualityProbe.detect(jpegWithDqt(doubled), "image/jpg")
        assertEquals(25, q)
    }

    @Test
    fun `non-jpeg mime returns null`() {
        assertNull(SourceQualityProbe.detect(jpegWithDqt(stdLuminanceQt), "image/png"))
        assertNull(SourceQualityProbe.detect(jpegWithDqt(stdLuminanceQt), null))
    }

    @Test
    fun `corrupt or empty jpeg returns null`() {
        assertNull(SourceQualityProbe.detect(ByteArray(0), "image/jpeg"))
        assertNull(SourceQualityProbe.detect(ByteArray(64), "image/jpeg"))
    }
}
