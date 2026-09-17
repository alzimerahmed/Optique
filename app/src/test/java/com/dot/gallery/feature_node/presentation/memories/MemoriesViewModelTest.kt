/*
 * SPDX-FileCopyrightText: 2023-2026 alzimerahmed
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.feature_node.presentation.memories

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.dot.gallery.core.dataStore
import com.dot.gallery.core.notifications.MemoriesNotifier
import com.dot.gallery.feature_node.domain.model.Media
import com.dot.gallery.feature_node.domain.model.MediaState
import com.dot.gallery.feature_node.presentation.util.MockedMediaDistributor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class MemoriesViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class MutableClock(
        var today: LocalDate,
    ) : Clock() {
        override fun getZone(): ZoneOffset = ZoneOffset.UTC

        override fun withZone(zone: java.time.ZoneId): Clock = this

        override fun instant(): Instant = today.atStartOfDay(ZoneOffset.UTC).toInstant()
    }

    private class TestDistributor : MockedMediaDistributor() {
        val flow = MutableStateFlow(MediaState<Media.UriMedia>())
        override val timelineMediaFlow: StateFlow<MediaState<Media.UriMedia>> get() = flow
    }

    private class FailingDistributor : MockedMediaDistributor() {
        val flow = MutableStateFlow(MediaState<Media.UriMedia>())
        var failing = true

        private class TimelineSourceException : Exception("timeline source failed")

        @OptIn(ExperimentalForInheritanceCoroutinesApi::class)
        private val failingFlow =
            object : StateFlow<MediaState<Media.UriMedia>> {
                override val replayCache: List<MediaState<Media.UriMedia>> get() = emptyList()
                override val value: MediaState<Media.UriMedia> get() = MediaState()

                override suspend fun collect(collector: FlowCollector<MediaState<Media.UriMedia>>): Nothing =
                    throw TimelineSourceException()
            }

        override val timelineMediaFlow: StateFlow<MediaState<Media.UriMedia>>
            get() = if (failing) failingFlow else flow
    }

    private fun media(
        id: Long,
        year: Int,
        month: Int,
        day: Int,
    ): Media.UriMedia {
        val epochSeconds =
            java.time.LocalDate
                .of(year, month, day)
                .atTime(12, 0)
                .toInstant(ZoneOffset.UTC)
                .epochSecond
        return Media.UriMedia(
            id = id,
            label = "Photo_$id.jpg",
            uri = Uri.parse("content://media/$id"),
            path = "/storage/DCIM/Photo_$id.jpg",
            relativePath = "DCIM/Camera",
            albumID = 1L,
            albumLabel = "Camera",
            timestamp = epochSeconds,
            takenTimestamp = epochSeconds * 1000L,
            fullDate = "",
            mimeType = "image/jpeg",
            favorite = 0,
            trashed = 0,
            size = 1L,
        )
    }

    private suspend fun awaitContents(
        states: MutableList<MemoriesSectionState>,
        count: Int,
    ): List<MemoriesSectionState.Content> =
        withTimeout(30_000) {
            while (states.count { it is MemoriesSectionState.Content } < count) delay(50)
            states.filterIsInstance<MemoriesSectionState.Content>().take(count)
        }

    private fun CoroutineScope.collectStates(vm: MemoriesViewModel): Pair<MutableList<MemoriesSectionState>, Job> {
        val states = mutableListOf<MemoriesSectionState>()
        val job = launch { vm.sectionState.collect { states += it } }
        return states to job
    }

    // The notifier is never exercised by these tests; the plaintext store is enough.
    private fun notifier(clock: Clock): MemoriesNotifier {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        return MemoriesNotifier(context, clock, context.dataStore)
    }

    @Test
    fun `same day repeated computation returns cached selection instance`() =
        runBlocking {
            val clock = MutableClock(java.time.LocalDate.of(2026, 6, 15))
            val distributor = TestDistributor()
            val cache = MemoriesCache()
            distributor.flow.value =
                MediaState(
                    listOf(
                        media(1, 2020, 6, 15),
                        media(2, 2023, 6, 15),
                        media(3, 2018, 6, 15),
                    ),
                )

            val vm1 = MemoriesViewModel(distributor, cache, clock, notifier(clock))
            val vm2 = MemoriesViewModel(distributor, cache, clock, notifier(clock))
            val (states1, job1) = collectStates(vm1)
            val (states2, job2) = collectStates(vm2)
            try {
                val contents1 = awaitContents(states1, 1)
                val contents2 = awaitContents(states2, 1)

                assertSame(contents1.single().onThisDayGroups, contents2.single().onThisDayGroups)
                assertSame(contents1.single().recaps, contents2.single().recaps)
            } finally {
                job1.cancel()
                job2.cancel()
            }
        }

    @Test
    fun `date rollover recomputes for the new date`() =
        runBlocking {
            val clock = MutableClock(java.time.LocalDate.of(2026, 6, 15))
            val distributor = TestDistributor()
            val cache = MemoriesCache()
            // Two+ items so the reversed re-emission differs from the first MediaState while
            // keeping the same library signature (size + id sum) — only the date changes.
            // Five 2021 items keep a year recap alive (engine needs >= MIN_RECAP_SIZE picks)
            // while staying outside the June 15/18 +/-3-day on-this-day window.
            val mediaList =
                listOf(
                    media(1, 2020, 6, 14),
                    media(2, 2021, 1, 10),
                    media(3, 2021, 2, 10),
                    media(4, 2021, 3, 10),
                    media(5, 2021, 4, 10),
                    media(6, 2021, 5, 10),
                )
            distributor.flow.value = MediaState(mediaList)

            val vm = MemoriesViewModel(distributor, cache, clock, notifier(clock))
            val (states, job) = collectStates(vm)
            try {
                val contents = awaitContents(states, 1)
                assertEquals(listOf(2020), contents.single().onThisDayGroups.map { it.year })

                clock.today = java.time.LocalDate.of(2026, 6, 18)
                distributor.flow.value = MediaState(mediaList.asReversed())
                val after = awaitContents(states, 2)

                assertTrue(after.last().onThisDayGroups.isEmpty())
                assertNotSame(contents.single().onThisDayGroups, after.last().onThisDayGroups)
            } finally {
                job.cancel()
            }
        }

    @Test
    fun `library change invalidates cache and recomputes`() =
        runBlocking {
            val clock = MutableClock(java.time.LocalDate.of(2026, 6, 15))
            val distributor = TestDistributor()
            val cache = MemoriesCache()
            distributor.flow.value = MediaState(listOf(media(1, 2020, 6, 15)))

            val vm = MemoriesViewModel(distributor, cache, clock, notifier(clock))
            val (states, job) = collectStates(vm)
            try {
                val first = awaitContents(states, 1).single()
                assertEquals(listOf(2020), first.onThisDayGroups.map { it.year })

                distributor.flow.value =
                    MediaState(listOf(media(1, 2020, 6, 15), media(2, 2019, 6, 15)))
                val contents = awaitContents(states, 2)

                assertEquals(listOf(2020, 2019), contents.last().onThisDayGroups.map { it.year })
                assertNotSame(first.onThisDayGroups, contents.last().onThisDayGroups)
            } finally {
                job.cancel()
            }
        }

    @Test
    fun `metadata change on identical membership recomputes via timestamp in signature`() =
        runBlocking {
            val clock = MutableClock(java.time.LocalDate.of(2026, 6, 15))
            val distributor = TestDistributor()
            val cache = MemoriesCache()
            distributor.flow.value = MediaState(listOf(media(1, 2020, 6, 15)))

            val vm = MemoriesViewModel(distributor, cache, clock, notifier(clock))
            val (states, job) = collectStates(vm)
            try {
                val first = awaitContents(states, 1).single()
                assertEquals(listOf(2020), first.onThisDayGroups.map { it.year })

                // Same size + same id sum as before — only the taken-date changed.
                // A size:id-sum-only signature would wrongly hit the cache.
                distributor.flow.value = MediaState(listOf(media(1, 2019, 6, 15)))
                val contents = awaitContents(states, 2)

                assertEquals(listOf(2019), contents.last().onThisDayGroups.map { it.year })
                assertNotSame(first.onThisDayGroups, contents.last().onThisDayGroups)
            } finally {
                job.cancel()
            }
        }

    @Test
    fun `distributor error yields error state and retry recovers to content`() =
        runBlocking {
            val clock = MutableClock(java.time.LocalDate.of(2026, 6, 15))
            val distributor = FailingDistributor()
            distributor.flow.value = MediaState(listOf(media(1, 2020, 6, 15)))

            val vm = MemoriesViewModel(distributor, MemoriesCache(), clock, notifier(clock))
            val error =
                withTimeout(30_000) {
                    vm.sectionState.first { it !is MemoriesSectionState.Loading }
                }
            assertTrue(error is MemoriesSectionState.Error)

            distributor.failing = false
            vm.retry()
            val recovered =
                withTimeout(30_000) {
                    vm.sectionState.first { it is MemoriesSectionState.Content }
                } as MemoriesSectionState.Content

            assertEquals(listOf(2020), recovered.onThisDayGroups.map { it.year })
        }

    @Test
    fun `empty media list yields empty state without crash`() =
        runBlocking {
            val clock = MutableClock(java.time.LocalDate.of(2026, 6, 15))
            val distributor = TestDistributor()
            distributor.flow.value = MediaState()

            val vm = MemoriesViewModel(distributor, MemoriesCache(), clock, notifier(clock))
            val state =
                withTimeout(30_000) {
                    vm.sectionState.first { it !is MemoriesSectionState.Loading }
                }

            assertTrue(state is MemoriesSectionState.Empty)
        }
}
