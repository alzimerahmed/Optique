/*
 * SPDX-FileCopyrightText: 2023-2026 alzimerahmed
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.cloud.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Per-dialect default capability sets surfaced during cloud provider setup.
 */
class ProviderTypeDefaultCapabilitiesTest {

    @Test
    fun `immich gets the full capability set`() {
        val caps = ProviderType.IMMICH.defaultCapabilities()
        assertTrue(caps.containsAll(
            setOf(
                ProviderCapability.REMOTE_ASSETS,
                ProviderCapability.REMOTE_ALBUMS,
                ProviderCapability.SYNC,
                ProviderCapability.PEOPLE,
                ProviderCapability.MAP,
                ProviderCapability.SMART_SEARCH,
                ProviderCapability.SHARE_CREATE,
                ProviderCapability.SHARE_MANAGE,
                ProviderCapability.ARCHIVE,
                ProviderCapability.MEMORIES
            )
        ))
    }

    @Test
    fun `webdav family supports sync and share links but not ml features`() {
        for (type in listOf(ProviderType.OWNCLOUD, ProviderType.NEXTCLOUD, ProviderType.WEBDAV)) {
            val caps = type.defaultCapabilities()
            assertEquals(setOf(
                ProviderCapability.REMOTE_ASSETS,
                ProviderCapability.REMOTE_ALBUMS,
                ProviderCapability.SYNC,
                ProviderCapability.SHARE_CREATE
            ), caps)
        }
    }

    @Test
    fun `smb and nfs are plain network file systems`() {
        for (type in listOf(ProviderType.SMB, ProviderType.NFS)) {
            val caps = type.defaultCapabilities()
            assertEquals(setOf(
                ProviderCapability.REMOTE_ASSETS,
                ProviderCapability.REMOTE_ALBUMS,
                ProviderCapability.SYNC
            ), caps)
        }
    }

    @Test
    fun `local providers fall back to remote assets only`() {
        for (type in listOf(ProviderType.LOCAL_PEOPLE, ProviderType.LOCAL_OCR, ProviderType.LOCAL_CLIP)) {
            assertEquals(setOf(ProviderCapability.REMOTE_ASSETS), type.defaultCapabilities())
        }
    }
}
