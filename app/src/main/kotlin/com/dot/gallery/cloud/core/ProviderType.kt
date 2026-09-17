/*
 * SPDX-FileCopyrightText: 2023-2026 alzimerahmed
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.cloud.core

import com.dot.gallery.BuildConfig
import kotlinx.serialization.Serializable

@Serializable
enum class ProviderType(val displayName: String, val isRemote: Boolean) {
    IMMICH("Immich", isRemote = true),
    OWNCLOUD("ownCloud", isRemote = true),
    NEXTCLOUD("Nextcloud", isRemote = true),
    WEBDAV("WebDAV", isRemote = true),
    SMB("SMB", isRemote = true),
    NFS("NFS", isRemote = true),
    LOCAL_PEOPLE("On-Device Faces", isRemote = false),
    LOCAL_OCR("On-Device OCR", isRemote = false),
    LOCAL_CLIP("On-Device Search", isRemote = false);

    val isIncludedInBuild: Boolean
        get() = when (this) {
            IMMICH -> BuildConfig.IMMICH_ENABLED
            OWNCLOUD -> BuildConfig.OWNCLOUD_ENABLED
            NEXTCLOUD -> BuildConfig.NEXTCLOUD_ENABLED
            WEBDAV -> BuildConfig.WEBDAV_ENABLED
            SMB -> BuildConfig.SMB_ENABLED
            NFS -> BuildConfig.NFS_ENABLED
            else -> true
        }

    /**
     * Capabilities pre-selected for a provider during setup, per dialect:
     * Immich is a full-featured server; WebDAV-family (ownCloud/Nextcloud/WebDAV)
     * supports share links; SMB/NFS are plain network file systems.
     */
    fun defaultCapabilities(): Set<ProviderCapability> = when (this) {
        IMMICH -> setOf(
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
        OWNCLOUD, NEXTCLOUD, WEBDAV -> setOf(
            ProviderCapability.REMOTE_ASSETS,
            ProviderCapability.REMOTE_ALBUMS,
            ProviderCapability.SYNC,
            ProviderCapability.SHARE_CREATE
        )
        SMB, NFS -> setOf(
            ProviderCapability.REMOTE_ASSETS,
            ProviderCapability.REMOTE_ALBUMS,
            ProviderCapability.SYNC
        )
        else -> setOf(ProviderCapability.REMOTE_ASSETS)
    }

    companion object {
        fun remoteTypes(): List<ProviderType> = entries.filter { it.isRemote }
        fun availableRemoteTypes(): List<ProviderType> = entries.filter { it.isRemote && it.isIncludedInBuild }
        fun hasAnyRemoteProvider(): Boolean = entries.any { it.isRemote && it.isIncludedInBuild }
    }
}
