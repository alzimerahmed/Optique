package com.dot.baselineprofile

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Measures frame timing while scrolling the timeline grid and while opening the
 * media viewer from the grid. Run on a physical device:
 * ```
 * ./gradlew :baselineprofile:connectedAndroidTest -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=Macrobenchmark
 * ```
 * Compare [CompilationMode.None] vs [CompilationMode.Partial] to verify the
 * Baseline Profile's effect on scroll jank (FrameTimingMetric percentiles).
 **/
@RunWith(AndroidJUnit4::class)
@LargeTest
class GridScrollBenchmarks {

    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun scrollCompilationNone() = benchmark(CompilationMode.None())

    @Test
    fun scrollCompilationBaselineProfiles() = benchmark(CompilationMode.Partial())

    private fun benchmark(compilationMode: CompilationMode) {
        rule.measureRepeated(
            packageName = "com.dot.gallery",
            metrics = listOf(FrameTimingMetric()),
            compilationMode = compilationMode,
            iterations = 5,
            setupBlock = { setupToTimelineGrid() },
            measureBlock = { scrollGridAndOpenViewer() }
        )
    }
}

/** Navigates to the app and waits for the timeline grid to be interactive. */
internal fun MacrobenchmarkScope.setupToTimelineGrid() {
    pressHome()
    startActivityAndWait()
    val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    device.waitForIdle()
}

/** Scrolls the grid several fling-lengths, then opens and closes the media viewer. */
internal fun MacrobenchmarkScope.scrollGridAndOpenViewer() {
    val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    val centerX = device.displayWidth / 2
    repeat(3) {
        device.swipe(centerX, device.displayHeight * 3 / 4, centerX, device.displayHeight / 4, 30)
    }
    device.waitForIdle()

    // Open the media viewer from the grid center, then return.
    device.click(centerX, device.displayHeight / 2)
    device.waitForIdle()
    device.pressBack()
    device.waitForIdle()
}
