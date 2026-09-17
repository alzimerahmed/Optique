/*
 * SPDX-FileCopyrightText: 2023-2026 alzimerahmed
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.cloud.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CloudUri dialect parsing: path-based providers (WebDAV/ownCloud/Nextcloud/SMB/NFS)
 * keep slashes inside remoteId, and grid-cell size downgrade behaves per dialect rules.
 */
class CloudUriDialectTest {

    @Test
    fun `webdav path with slashes survives parse`() {
        val uri = CloudUri.parse("cloud://WEBDAV/Photos/2024/Trip/IMG_0001.jpg?size=preview&cfg=3")!!
        assertEquals(ProviderType.WEBDAV, uri.providerType)
        assertEquals("Photos/2024/Trip/IMG_0001.jpg", uri.remoteId)
        assertEquals("preview", uri.size)
        assertEquals(3L, uri.configId)
    }

    @Test
    fun `smb and nfs paths parse with defaults`() {
        val smb = CloudUri.parse("cloud://SMB/gallery/videos/clip.mp4")!!
        assertEquals(ProviderType.SMB, smb.providerType)
        assertEquals("gallery/videos/clip.mp4", smb.remoteId)
        assertEquals("preview", smb.size)

        val nfs = CloudUri.parse("cloud://NFS/export/media/photo.png?size=thumbnail")!!
        assertEquals(ProviderType.NFS, nfs.providerType)
        assertEquals("export/media/photo.png", nfs.remoteId)
        assertEquals("thumbnail", nfs.size)
    }

    @Test
    fun `grid downgrade only applies to small non-original cells`() {
        val uri = CloudUri.parse("cloud://SMB/gallery/img.jpg?size=preview")!!
        assertEquals("thumbnail", uri.effectiveSize(requestedMaxPx = 256))
        assertEquals("preview", uri.effectiveSize(requestedMaxPx = 1200))
        assertEquals("original", CloudUri.parse("cloud://SMB/gallery/img.jpg?size=original")!!.effectiveSize(256))
        // Person thumbnails (type param present) are never downgraded
        val person = CloudUri.parse("cloud://SMB/faces/1.jpg?size=preview&type=person")!!
        assertEquals("preview", person.effectiveSize(256))
    }

    @Test
    fun `invalid uris are rejected`() {
        assertNull(CloudUri.parse("https://example.com/img.jpg"))
        assertNull(CloudUri.parse("cloud://NOT_A_PROVIDER/img.jpg"))
        assertNull(CloudUri.parse("cloud://WEBDAV"))
        assertNull(CloudUri.parse("cloud://WEBDAV/"))
        assertNull(CloudUri.parse("relative/path.jpg"))
    }

    @Test
    fun `defaults apply when params missing`() {
        val uri = CloudUri.parse("cloud://NEXTCLOUD/Photos/a.jpg")!!
        assertEquals("preview", uri.size)
        assertNull(uri.typeParam)
        assertNull(uri.fileId)
        assertEquals(-1L, uri.configId)
        assertNotNull(uri)
        assertTrue(uri.remoteId.isNotEmpty())
    }
}
