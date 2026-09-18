/*
 * SPDX-FileCopyrightText: 2023-2026 alzimerahmed
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.ui.theme

import android.content.ContentResolver
import android.database.ContentObserver
import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext

/**
 * Shared motion spec — Material motion durations, easings, and named spring intents.
 * Prefer these over ad-hoc durationMillis/spring() literals so motion stays coherent.
 */
object MotionSpec {
    /** Toggles, icon state changes, control show/hide. */
    const val MicroMs = 150

    /** Incoming content (matches DEFAULT_NAVIGATION_ANIMATION_DURATION). */
    const val StandardInMs = 300

    /** Outgoing content. */
    const val StandardOutMs = 200

    /** Shared elements, hero transitions, large content moves. */
    const val EmphasizedMs = 450

    /** Determinate progress indicators. */
    const val ProgressMs = 600

    /** Slow decorative drift loops (gradient washes, ambient movement). */
    const val DecorativeDriftMs = 8_000

    /** M3 emphasized easing — non-linear path feel for large moves. */
    val EmphasizedEasing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val StandardAccelerateEasing = CubicBezierEasing(0.3f, 0f, 1f, 1f)
    val StandardDecelerateEasing = CubicBezierEasing(0f, 0f, 0.2f, 1f)

    /** Pager snap, release-to-rest — standard stiffness, default bounce. */
    fun <T> snapSpring(): SpringSpec<T> =
        spring(stiffness = Spring.StiffnessMedium)

    /** Indicators, expansion — settles without overshoot. */
    fun <T> settleSpring(): SpringSpec<T> =
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)

    /** Drag-settle, zoom compensation — gentle bounce. */
    fun <T> playfulSpring(): SpringSpec<T> =
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium)
}

private fun isReduceMotionEnabled(resolver: ContentResolver): Boolean =
    Settings.Global.getFloat(
        resolver,
        Settings.Global.ANIMATOR_DURATION_SCALE,
        1f
    ) == 0f

/**
 * True while the user has animations disabled system-wide (animator duration scale = 0).
 * Observes the setting, so changes apply without restarting the app.
 *
 * Compose physics/spring animations are NOT auto-scaled by the system — call sites for
 * decorative, infinite, or auto-advancing motion must consult this and render a static
 * or instant alternative.
 */
@Composable
fun rememberReduceMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return produceState(
        initialValue = isReduceMotionEnabled(resolver),
        key1 = resolver
    ) {
        val observer = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) {
                value = isReduceMotionEnabled(resolver)
            }
        }
        resolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            false,
            observer
        )
        awaitDispose { resolver.unregisterContentObserver(observer) }
    }.value
}

/**
 * Fraction drifting 0f → 1f in reverse-repeat for ambient gradient washes.
 * Returns a static 0.5f when reduced motion is requested.
 */
@Composable
fun rememberDriftingFraction(
    durationMillis: Int = MotionSpec.DecorativeDriftMs,
    label: String = "DriftingFraction"
): Float {
    if (rememberReduceMotion()) return 0.5f
    val transition = rememberInfiniteTransition(label = label)
    val fraction by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = durationMillis),
            repeatMode = RepeatMode.Reverse
        ),
        label = label
    )
    return fraction
}
