package com.dot.gallery.feature_node.presentation.settings.subsettings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * KTD4: the "Delete all face data" control must be gated on the *unfiltered*
 * active-run feed — a queued automatic run (invisible to the shouldShowRun-filtered
 * UI flow) still has to block the purge. The predicate itself only sees the flag;
 * these tests pin the contract that ANY active run disables the control.
 */
class FaceDataPurgePolicyTest {

    @Test
    fun purgeEnabledWhenNoActiveRun() {
        assertTrue(isFaceDataPurgeEnabled(hasActiveRun = false))
    }

    @Test
    fun purgeDisabledWhileAnyRunIsActive() {
        // Covers both running runs and queued automatic runs the user can't see —
        // the flag comes from the unfiltered observeActiveRun() feed.
        assertFalse(isFaceDataPurgeEnabled(hasActiveRun = true))
    }
}
