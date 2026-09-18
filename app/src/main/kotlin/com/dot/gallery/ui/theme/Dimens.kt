/*
 * SPDX-FileCopyrightText: 2023-2026 alzimerahmed
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Immutable
sealed class Dimens(val size: Dp) {
    data object Photo : Dimens(size = 100.dp)
    data object Album : Dimens(size = 178.dp)
    data object Person : Dimens(size = 96.dp)

    operator fun invoke(): Dp = size
}

/** Shared spacing roles for reusable UI rather than one-off numeric values. */
object Spacing {
    val Hairline = 1.dp
    val Tiny = 2.dp
    val ExtraSmall = 4.dp
    val Micro = 6.dp
    val Small = 8.dp
    val MediumSmall = 12.dp
    val Medium = 16.dp
    val MediumLarge = 20.dp
    val Large = 24.dp
    val ExtraLarge = 32.dp
    val ScreenHorizontal = 16.dp
    val ContentHorizontal = 24.dp
}

/** Shared component sizes with accessibility-sensitive minimums. */
object ComponentSize {
    val MinimumTouchTarget = 48.dp
    val IconLarge = 40.dp
    val ThumbnailMedium = 56.dp
    val ButtonHeight = 64.dp
    val NavigationBarHeight = 64.dp
    val NavigationRailWidth = 80.dp
    val StateIcon = 96.dp
}

/** Shared alpha levels — prefer these over raw floats so overlays stay consistent. */
object Alpha {
    /** M3 disabled content alpha. */
    val Disabled = 0.38f
    /** Selected/track fills and other state layers. */
    val StateLayer = 0.12f
    /** Secondary text/icons rendered over media. */
    val SecondaryOnMedia = 0.7f
    /** Standard overlay scrim over media. */
    val ScrimMedium = 0.4f
    /** Heavier overlay scrim, e.g. viewer chrome containers. */
    val ScrimHeavy = 0.5f
}