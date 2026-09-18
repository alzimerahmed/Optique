/*
 * SPDX-FileCopyrightText: 2023-2026 alzimerahmed
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.core.smart

import com.dot.gallery.cloud.core.ProviderType
import com.dot.gallery.cloud.data.entity.DetectedFaceEntity
import com.dot.gallery.cloud.data.entity.PersonEntity
import com.dot.gallery.core.ml.DetectedFaceBox
import com.dot.gallery.core.ml.FaceHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HiddenPersonFaceIndexTest {

    private fun unitVector(index: Int): FloatArray = FloatArray(512).also { it[index] = 1f }

    private fun cluster(personId: String, centroid: FloatArray, count: Int = 1) =
        FaceIndexPhaseProcessor.Cluster(
            personId = personId,
            centroid = centroid.copyOf(),
            normalizedCentroid = FaceHelper.l2Normalize(centroid),
            count = count
        )

    @Test
    fun `hidden snapshot keeps only hidden person ids`() {
        val people = listOf(
            PersonEntity(id = "local_a", name = "A", providerType = ProviderType.LOCAL_PEOPLE, hidden = true),
            PersonEntity(id = "local_b", name = "B", providerType = ProviderType.LOCAL_PEOPLE),
            PersonEntity(id = "local_c", name = "", providerType = ProviderType.LOCAL_PEOPLE, hidden = true)
        )

        assertEquals(setOf("local_a", "local_c"), hiddenPersonIds(people))
        assertEquals(emptySet<String>(), hiddenPersonIds(emptyList()))
        assertEquals(emptySet<String>(), hiddenPersonIds(people.filterNot { it.hidden }))
    }

    @Test
    fun `face scoring above threshold only against hidden cluster finds no match`() {
        val embedding = unitVector(0)
        val hidden = cluster("local_hidden", unitVector(0)) // cosine 1.0
        val visible = cluster("local_visible", unitVector(1)) // cosine 0.0

        val match = bestFaceClusterMatch(
            embedding,
            listOf(hidden, visible),
            hiddenIds = setOf("local_hidden"),
            threshold = 0.45f
        )

        assertNull(match)
    }

    @Test
    fun `face scoring reaches visible cluster when hidden cluster is skipped`() {
        val embedding = unitVector(0)
        val closeToEmbedding = FloatArray(512).also { it[0] = 1f; it[1] = 1f } // cosine ~0.707
        val hidden = cluster("local_hidden", unitVector(0))
        val visible = cluster("local_visible", closeToEmbedding)

        val match = bestFaceClusterMatch(
            embedding,
            listOf(hidden, visible),
            hiddenIds = setOf("local_hidden"),
            threshold = 0.45f
        )

        assertEquals("local_visible", match?.personId)
    }

    @Test
    fun `empty hidden set picks the highest scoring cluster as before`() {
        val embedding = unitVector(0)
        val best = cluster("local_best", unitVector(0))
        val weaker = cluster("local_weaker", unitVector(1))

        val match = bestFaceClusterMatch(
            embedding,
            listOf(weaker, best),
            hiddenIds = emptySet(),
            threshold = 0.45f
        )

        assertEquals("local_best", match?.personId)
    }

    @Test
    fun `previously hidden cluster matches again once absent from the snapshot`() {
        val embedding = unitVector(0)
        val person = cluster("local_person", unitVector(0))

        assertNull(
            bestFaceClusterMatch(
                embedding,
                listOf(person),
                hiddenIds = setOf("local_person"),
                threshold = 0.45f
            )
        )
        assertEquals(
            "local_person",
            bestFaceClusterMatch(
                embedding,
                listOf(person),
                hiddenIds = emptySet(),
                threshold = 0.45f
            )?.personId
        )
    }

    @Test
    fun `hidden cluster staying in the cluster list is never an empty cluster id`() {
        val hidden = cluster("local_hidden", unitVector(0), count = 3)
        val drained = cluster("local_drained", unitVector(1), count = 0)
        val clusters = listOf(hidden, drained)
        val touched = setOf("local_hidden", "local_drained")

        assertEquals(setOf("local_drained"), emptyClusterIdsFor(clusters, touched))
    }

    @Test
    fun `hidden owned faces are carried while others are drainable`() {
        val faces = listOf(
            DetectedFaceEntity(id = 1, mediaId = 7, personId = "local_hidden"),
            DetectedFaceEntity(id = 2, mediaId = 7, personId = "local_visible"),
            DetectedFaceEntity(id = 3, mediaId = 7, personId = null)
        )

        val (carried, drainable) = splitCarriedFaces(faces, setOf("local_hidden"))

        assertEquals(listOf(1L), carried.map { it.id })
        assertEquals(listOf(2L, 3L), drainable.map { it.id })
        assertEquals(
            Pair(emptyList<DetectedFaceEntity>(), faces),
            splitCarriedFaces(faces, emptySet())
        )
    }

    @Test
    fun `carried rows keep person embedding and geometry with refreshed revision`() {
        val prior = DetectedFaceEntity(
            id = 9,
            mediaId = 7,
            personId = "local_hidden",
            embedding = byteArrayOf(1, 2, 3),
            left = 0.1f,
            top = 0.2f,
            right = 0.5f,
            bottom = 0.6f,
            confidence = 0.9f,
            timestamp = 100L,
            resultRevision = "face-v1"
        )

        val carried = carriedFaceRow(prior, timestamp = 200L, revision = "face-v2")

        assertEquals(0L, carried.id)
        assertEquals("local_hidden", carried.personId)
        assertTrue(carried.embedding.contentEquals(byteArrayOf(1, 2, 3)))
        assertEquals(0.1f, carried.left, 0.0001f)
        assertEquals(0.2f, carried.top, 0.0001f)
        assertEquals(0.5f, carried.right, 0.0001f)
        assertEquals(0.6f, carried.bottom, 0.0001f)
        assertEquals(0.9f, carried.confidence, 0.0001f)
        assertEquals(200L, carried.timestamp)
        assertEquals("face-v2", carried.resultRevision)
    }

    @Test
    fun `redetected face overlapping a carried face is not rescored`() {
        val prior = DetectedFaceEntity(
            mediaId = 7,
            personId = "local_hidden",
            left = 0.1f,
            top = 0.1f,
            right = 0.4f,
            bottom = 0.5f
        )
        val sameFace = DetectedFaceBox(0.11f, 0.12f, 0.42f, 0.52f, 0.9f)
        val otherFace = DetectedFaceBox(0.6f, 0.1f, 0.9f, 0.5f, 0.9f)

        assertTrue(isCarriedHiddenFace(sameFace, listOf(prior)))
        assertFalse(isCarriedHiddenFace(otherFace, listOf(prior)))
        assertFalse(isCarriedHiddenFace(sameFace, emptyList()))
    }

    @Test
    fun `face box iou handles identical disjoint and partial boxes`() {
        assertEquals(1f, faceBoxIoU(0f, 0f, 1f, 1f, 0f, 0f, 1f, 1f), 0.0001f)
        assertEquals(0f, faceBoxIoU(0f, 0f, 0.4f, 0.4f, 0.6f, 0.6f, 1f, 1f), 0.0001f)
        assertEquals(
            0.25f / 1.75f,
            faceBoxIoU(0f, 0f, 1f, 1f, 0.5f, 0.5f, 1.5f, 1.5f),
            0.0001f
        )
    }
}
