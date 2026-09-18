/*
 * SPDX-FileCopyrightText: 2023-2026 alzimerahmed
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.cloud.ui.people

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.dot.gallery.cloud.core.PersonInfo
import com.dot.gallery.cloud.core.ProviderRegistry
import com.dot.gallery.cloud.core.ProviderType
import com.dot.gallery.cloud.data.dao.DetectedFaceDao
import com.dot.gallery.cloud.data.repository.CloudRepository
import com.dot.gallery.cloud.local.LocalPeopleBlurrer
import com.dot.gallery.core.Resource
import com.dot.gallery.feature_node.domain.model.Media
import com.dot.gallery.feature_node.domain.repository.MediaRepository
import com.dot.gallery.feature_node.presentation.util.MockedMediaHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * ViewModel test for the person-gone exit (R13) and the birthday-chip gate (R9/KTD6).
 * Robolectric supplies the Context that [LocalPeopleBlurrer]'s constructor needs; the
 * blurrer itself is never exercised. CloudRepository follows the proxy-fake convention
 * from StoryCardsViewModelTest — only the members the VM touches are stubbed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PersonDetailViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * CloudRepository is a ~40-method interface with no existing fake. This narrow stub
     * answers only `getAllPeople`/`getPersonMedia` and throws on everything else, so a
     * future dependency surfaces loudly.
     */
    private class FakeCloudRepository(
        val people: MutableStateFlow<Resource<List<PersonInfo>>>,
    ) : InvocationHandler {

        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
            val arguments = args ?: emptyArray()
            return when {
                method.name == "getAllPeople" -> people
                method.name == "getPersonMedia" ->
                    MutableStateFlow<Resource<List<Media>>>(Resource.Success(emptyList()))
                method.name == "toString" -> "FakeCloudRepository"
                method.name == "hashCode" -> System.identityHashCode(proxy)
                method.name == "equals" -> proxy === arguments[0]
                else -> throw UnsupportedOperationException(
                    "CloudRepository.${method.name} is not stubbed in PersonDetailViewModelTest"
                )
            }
        }

        fun asRepository(): CloudRepository = Proxy.newProxyInstance(
            CloudRepository::class.java.classLoader,
            arrayOf(CloudRepository::class.java),
            this
        ) as CloudRepository
    }

    /** Interface stub for constructor deps the tests never invoke (DetectedFaceDao, MediaRepository). */
    @Suppress("UNCHECKED_CAST")
    private fun <T> unstubbed(clazz: Class<T>): T = Proxy.newProxyInstance(
        clazz.classLoader,
        arrayOf(clazz),
        InvocationHandler { proxy, method, args ->
            when (method.name) {
                "toString" -> "Stub${clazz.simpleName}"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.get(0)
                else -> throw UnsupportedOperationException(
                    "${clazz.simpleName}.${method.name} is not stubbed in PersonDetailViewModelTest"
                )
            }
        }
    ) as T

    private fun viewModel(
        repository: CloudRepository,
        personId: String = "p1",
        configId: Long = 7L,
    ): PersonDetailViewModel {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return PersonDetailViewModel(
            savedStateHandle = SavedStateHandle(
                mapOf("personId" to personId, "configId" to configId)
            ),
            repository = repository,
            registry = ProviderRegistry(),
            blurrer = LocalPeopleBlurrer(
                context = context,
                faceDao = unstubbed(DetectedFaceDao::class.java),
                mediaRepository = unstubbed(MediaRepository::class.java),
                mediaHandler = MockedMediaHandler()
            )
        )
    }

    private fun person(
        id: String = "p1",
        name: String = "Ann",
        providerType: ProviderType = ProviderType.LOCAL_PEOPLE,
        configId: Long = 7L,
    ) = PersonInfo(id = id, name = name, providerType = providerType, serverConfigId = configId)

    // Covers AE6/R13: the loaded→gone transition signals the single exit path.
    @Test
    fun `person emitted then removed emits exit event`() = runBlocking {
        val repository = FakeCloudRepository(
            MutableStateFlow(Resource.Success(listOf(person())))
        )
        val vm = viewModel(repository.asRepository())
        val events = mutableListOf<PersonDetailViewModel.PersonDetailEvent>()
        val job = launch { vm.uiEvents.collect { events += it } }
        try {
            // Let the event collector subscribe before the transition fires.
            yield()
            repository.people.value = Resource.Success(emptyList())
            withTimeout(30_000) { while (events.isEmpty()) delay(10) }
            assertEquals(listOf(PersonDetailViewModel.PersonDetailEvent.Exit), events)
        } finally {
            job.cancel()
        }
    }

    // R13: a stale back-stack entry resolves missing on the first snapshot — unavailable
    // state (error), not an exit.
    @Test
    fun `missing person on first resolve sets error without exit`() = runBlocking {
        val repository = FakeCloudRepository(
            MutableStateFlow<Resource<List<PersonInfo>>>(Resource.Success(emptyList()))
        )
        val vm = viewModel(repository.asRepository())
        val events = mutableListOf<PersonDetailViewModel.PersonDetailEvent>()
        val job = launch { vm.uiEvents.collect { events += it } }
        try {
            yield()
            assertNull(vm.uiState.value.person)
            assertNotNull(vm.uiState.value.error)
            assertFalse(vm.uiState.value.isLoading)
            // Give any stray emission a chance to arrive.
            delay(100)
            assertTrue(events.isEmpty())
        } finally {
            job.cancel()
        }
    }

    // A re-emission that still contains the person is not a gone-signal.
    @Test
    fun `re-emission keeping the person does not emit exit`() = runBlocking {
        val repository = FakeCloudRepository(
            MutableStateFlow(Resource.Success(listOf(person())))
        )
        val vm = viewModel(repository.asRepository())
        val events = mutableListOf<PersonDetailViewModel.PersonDetailEvent>()
        val job = launch { vm.uiEvents.collect { events += it } }
        try {
            yield()
            repository.people.value = Resource.Success(listOf(person(name = "Anna")))
            delay(100)
            assertTrue(events.isEmpty())
            assertEquals("Anna", vm.uiState.value.person?.name)
        } finally {
            job.cancel()
        }
    }

    @Test
    fun `presence transition maps prior and resolved person`() {
        val p = person()
        assertEquals(PersonPresence.Loaded, personPresence(null, p))
        assertEquals(PersonPresence.Loaded, personPresence(p, p))
        assertEquals(PersonPresence.Gone, personPresence(p, null))
        assertEquals(PersonPresence.Missing, personPresence(null, null))
    }

    // Covers AE4/R9/KTD6: the birthday chip is gated on a loaded remote person — no flash
    // while person == null, hidden for local persons.
    @Test
    fun `birthday chip visible only for loaded remote person`() {
        assertFalse(birthdayChipVisible(null))
        assertFalse(birthdayChipVisible(person(providerType = ProviderType.LOCAL_PEOPLE)))
        assertTrue(birthdayChipVisible(person(providerType = ProviderType.IMMICH)))
    }
}
