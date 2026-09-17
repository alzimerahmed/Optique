package com.dot.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Generates the app's baseline profile covering the critical user journeys:
 * cold startup, timeline grid scroll, and opening the media viewer.
 *
 * Run with:
 * ```
 * ./gradlew :app:generateReleaseBaselineProfile -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=BaselineProfile
 * ```
 * Verify improvements with [StartupBenchmarks] and [GridScrollBenchmarks].
 **/
@RunWith(AndroidJUnit4::class)
@LargeTest
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() {
        rule.collect(
            packageName = "com.dot.gallery",
            includeInStartupProfile = true
        ) {
            pressHome()
            startActivityAndWait()

            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

            // 1. Wait for the timeline grid to populate, then scroll it so grid-cell
            //    composables (MediaImage, headers, Glide thumbnail paths) land in the profile.
            device.waitForIdle()
            device.swipe(
                device.displayWidth / 2,
                device.displayHeight * 3 / 4,
                device.displayWidth / 2,
                device.displayHeight / 4,
                20
            )
            device.waitForIdle()

            // 2. Open the media viewer from the grid center, so pager/Zoomable/Sketch
            //    viewer classes are profiled, then return to the grid.
            device.click(device.displayWidth / 2, device.displayHeight / 2)
            device.waitForIdle()
            device.pressBack()

            // 3. Return home; startup + scroll + viewer are the profiled journeys.
            device.pressHome()
        }
    }
}
