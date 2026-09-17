/*
 * SPDX-FileCopyrightText: 2023-2026 alzimerahmed
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.feature_node.presentation.search.tokenizer

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * BPE tokenizer tests against a minimal vocab/merges fixture.
 * BOS/EOS insertion and 77-token truncation live in SearchVisionHelper.getTextEmbedding,
 * not ClipTokenizer.encode — that behavior is covered at the helper level.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class ClipTokenizerTest {

    private lateinit var vocabFile: File
    private lateinit var mergesFile: File

    @Before
    fun setUp() {
        val dir = java.nio.file.Files.createTempDirectory("clip-tokenizer").toFile()
        // Keys use the raw CLIP vocab convention; "</w>" is rewritten to a trailing space on load.
        vocabFile = File(dir, "vocab.json").apply {
            writeText("""{"a</w>": 1, "b</w>": 2, "ab</w>": 3}""")
        }
        mergesFile = File(dir, "merges.txt").apply {
            // The loader drops the first line (version header).
            // The loader drops the first line and rewrites "</w>" to a trailing space,
            // so a merge line must end with </w> to match bpe()'s end-of-word marker.
            writeText("#version: 0.2\na b</w>\n")
        }
    }

    @Test
    fun `single character encodes to its vocab id`() {
        val tokenizer = ClipTokenizer(vocabFile, mergesFile)
        assertEquals(listOf(1), tokenizer.encode("a"))
    }

    @Test
    fun `mergeable pair collapses to merged token id`() {
        val tokenizer = ClipTokenizer(vocabFile, mergesFile)
        assertEquals(listOf(3), tokenizer.encode("ab"))
    }

    @Test
    fun `empty input yields empty token list`() {
        val tokenizer = ClipTokenizer(vocabFile, mergesFile)
        assertEquals(emptyList<Int>(), tokenizer.encode(""))
    }

    @Test
    fun `unknown subword fails lookup`() {
        val tokenizer = ClipTokenizer(vocabFile, mergesFile)
        assertThrows(NullPointerException::class.java) {
            tokenizer.encode("zzz")
        }
    }
}
