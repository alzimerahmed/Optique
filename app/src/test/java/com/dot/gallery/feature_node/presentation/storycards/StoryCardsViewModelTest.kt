/*
 * SPDX-FileCopyrightText: 2023-2026 alzimerahmed
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.feature_node.presentation.storycards

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import com.dot.gallery.cloud.core.ProviderRegistry
import com.dot.gallery.core.activeDataStore
import com.dot.gallery.core.encryption.EncryptedDataStoreProvider
import com.dot.gallery.feature_node.data.data_source.CategoryWithMediaCount
import com.dot.gallery.feature_node.domain.model.Album
import com.dot.gallery.feature_node.domain.model.AlbumState
import com.dot.gallery.feature_node.domain.model.Media
import com.dot.gallery.feature_node.domain.model.MediaMetadata
import com.dot.gallery.feature_node.domain.model.MediaState
import com.dot.gallery.feature_node.domain.model.StoryCard
import com.dot.gallery.feature_node.domain.model.StoryCardType
import com.dot.gallery.feature_node.domain.model.StoryCardsConfig
import com.dot.gallery.feature_node.domain.repository.MediaRepository
import com.dot.gallery.feature_node.presentation.util.MockedMediaDistributor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * U4 — source scoping: exclusion filtering (R4, KTD2, AE1, AE2).
 *
 * Config is driven by writing the raw `story_cards_config` JSON to
 * [Context.activeDataStore] before the ViewModel is constructed — the same
 * store `Settings.Misc.getStoryCardsConfig` reads.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class StoryCardsViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var context: Context

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        context = ApplicationProvider.getApplicationContext()
        // Robolectric has no AndroidKeyStore provider, so the real encrypted
        // DataStore fails at first read/write. Install a plain per-test store
        // into the provider's cached singleton — [Context.activeDataStore]
        // then returns it to both this test and the ViewModel, and a fresh
        // file per test means no cross-test bleed.
        installPlainTestDataStore()
    }

    @After
    fun tearDown() {
        runBlocking { context.activeDataStore.edit { it.clear() } }
        Dispatchers.resetMain()
    }

    private fun installPlainTestDataStore() {
        val file = File(
            context.filesDir,
            "datastore/test_settings_${System.nanoTime()}.preferences_pb"
        )
        file.parentFile?.mkdirs()
        val store = PreferenceDataStoreFactory.create(produceFile = { file })
        val field = EncryptedDataStoreProvider::class.java.getDeclaredField("instance")
        field.isAccessible = true
        field.set(EncryptedDataStoreProvider, store)
    }

    private suspend fun writeConfig(config: StoryCardsConfig = StoryCardsConfig()) {
        context.activeDataStore.edit {
            it[stringPreferencesKey(STORY_CARDS_CONFIG_KEY)] = Json.encodeToString(config)
        }
    }

    // ---------- Fakes ----------

    private class TestDistributor : MockedMediaDistributor() {
        val timeline = MutableStateFlow(MediaState<Media.UriMedia>())
        val favorites = MutableStateFlow(MediaState<Media.UriMedia>())
        val albums = MutableStateFlow(AlbumState())

        override val timelineMediaFlow: StateFlow<MediaState<Media.UriMedia>> get() = timeline
        override val favoritesMediaFlow: StateFlow<MediaState<Media.UriMedia>> get() = favorites
        override val albumsFlow: StateFlow<AlbumState> get() = albums
    }

    /**
     * MediaRepository is a ~100-method interface with no existing fake. This
     * narrow stub answers only the members StoryCardsViewModel touches and
     * throws on everything else, so a future dependency surfaces loudly.
     * Suspend members return their result directly — the caller's state
     * machine treats a non-COROUTINE_SUSPENDED return as synchronous
     * completion, which is all these tests need.
     */
    private class FakeMediaRepository(
        var metadata: List<MediaMetadata> = emptyList(),
        var topCategories: List<CategoryWithMediaCount> = emptyList(),
        var mediaIdsByCategory: Map<Long, List<Long>> = emptyMap(),
        var allClassifiedIds: List<Long> = emptyList(),
    ) : InvocationHandler {

        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
            val arguments = args ?: emptyArray()
            return when {
                method.name == "getMetadata" && arguments.isEmpty() ->
                    MutableStateFlow(metadata)
                method.name == "getTopCategories" ->
                    MutableStateFlow(topCategories)
                method.name == "getMediaIdsInCategoryAsync" ->
                    mediaIdsByCategory[arguments[0] as Long] ?: emptyList<Long>()
                method.name == "getAllClassifiedMediaIds" -> allClassifiedIds
                method.name == "collectMetadataFor" -> Unit
                method.name == "toString" -> "FakeMediaRepository"
                method.name == "hashCode" -> System.identityHashCode(proxy)
                method.name == "equals" -> proxy === arguments[0]
                else -> throw UnsupportedOperationException(
                    "MediaRepository.${method.name} is not stubbed in StoryCardsViewModelTest"
                )
            }
        }

        fun asRepository(): MediaRepository = Proxy.newProxyInstance(
            MediaRepository::class.java.classLoader,
            arrayOf(MediaRepository::class.java),
            this
        ) as MediaRepository
    }

    // ---------- Fixtures ----------

    private fun media(
        id: Long,
        albumID: Long = 1L,
        albumLabel: String = "Camera",
        year: Int = 2020,
        month: Int = 1,
        day: Int = 15,
    ): Media.UriMedia {
        val epochSeconds = LocalDate.of(year, month, day)
            .atTime(12, 0)
            .toInstant(ZoneOffset.UTC)
            .epochSecond
        return Media.UriMedia(
            id = id,
            label = "Photo_$id.jpg",
            uri = Uri.parse("content://media/$id"),
            path = "/storage/DCIM/Photo_$id.jpg",
            relativePath = "DCIM/Camera",
            albumID = albumID,
            albumLabel = albumLabel,
            timestamp = epochSeconds,
            takenTimestamp = epochSeconds * 1000L,
            fullDate = "",
            mimeType = "image/jpeg",
            favorite = 0,
            trashed = 0,
            size = 1L,
        )
    }

    private fun album(id: Long, label: String, count: Long = 3, locked: Boolean = false) = Album(
        id = id,
        label = label,
        uri = Uri.EMPTY,
        pathToThumbnail = "",
        relativePath = "",
        timestamp = 1_000L,
        count = count,
        isLocked = locked,
    )

    private fun category(id: Long, name: String, count: Int = 2) = CategoryWithMediaCount(
        id = id,
        name = name,
        searchTerms = "",
        embedding = null,
        referenceImageIds = emptyList(),
        threshold = 0f,
        isUserCreated = false,
        isPinned = false,
        createdAt = 0L,
        updatedAt = 0L,
        mediaCount = count,
        thumbnailMediaId = null,
    )

    private fun locationMetadata(mediaId: Long, city: String, country: String) = MediaMetadata(
        mediaId = mediaId,
        imageDescription = null,
        dateTimeOriginal = null,
        manufacturerName = null,
        modelName = null,
        aperture = null,
        exposureTime = null,
        iso = null,
        gpsLatitude = null,
        gpsLongitude = null,
        gpsLocationName = null,
        gpsLocationNameCountry = country,
        gpsLocationNameCity = city,
        imageWidth = 0,
        imageHeight = 0,
        imageResolutionX = null,
        imageResolutionY = null,
        resolutionUnit = null,
        durationMs = null,
        videoWidth = null,
        videoHeight = null,
        frameRate = null,
        bitRate = null,
        isNightMode = false,
        isPanorama = false,
        isPhotosphere = false,
        isLongExposure = false,
        isMotionPhoto = false,
    )

    // ---------- Helpers ----------

    private fun viewModel(
        repository: FakeMediaRepository,
        distributor: TestDistributor,
    ) = StoryCardsViewModel(
        repository.asRepository(),
        distributor,
        ProviderRegistry(),
        context,
    )

    /**
     * [StoryCardsViewModel.allCards] emits transient merges while the upstream
     * `stateIn` combines still publish their `emptyList` seeds — a bare
     * "first non-null" can capture a pre-compute emission and make absence
     * assertions pass trivially. [expect] is the scenario's marker: the
     * emission must contain the cards we know should exist, which proves the
     * relevant combines have emitted their real values.
     */
    private suspend fun awaitCards(
        vm: StoryCardsViewModel,
        expect: (List<StoryCard>) -> Boolean,
    ): List<StoryCard> = withTimeout(30_000) {
        vm.allCards.filterNotNull().first { expect(it) }
    }

    private fun allMediaIds(cards: List<StoryCard>): Set<Long> =
        cards.flatMapTo(HashSet()) { card -> card.mediaList.map { it.id } }

    private fun cardsOfType(cards: List<StoryCard>, type: StoryCardType) =
        cards.filter { it.type == type }

    // ---------- Tests ----------

    /** AE1: excluded album → no ALBUMS card for it + its media absent everywhere. */
    @Test
    fun `excluded album produces no card and contributes no media anywhere`() = runBlocking {
        writeConfig(StoryCardsConfig(excludedAlbumIds = setOf(2L)))
        val distributor = TestDistributor()
        val m1 = media(1, albumID = 1)
        val m2 = media(2, albumID = 1)
        val m3 = media(3, albumID = 2, albumLabel = "Excluded")
        distributor.timeline.value = MediaState(listOf(m1, m2, m3))
        distributor.albums.value =
            AlbumState(albums = listOf(album(1, "Camera"), album(2, "Excluded")))
        // m3 is favorited too — the exclusion must strip it from FAVORITES as well.
        distributor.favorites.value = MediaState(listOf(m1, m3))

        val vm = viewModel(FakeMediaRepository(), distributor)
        val cards = awaitCards(vm) { cards ->
            cards.any { it.type == StoryCardType.ALBUMS }
        }

        assertTrue(cardsOfType(cards, StoryCardType.ALBUMS).none { it.albumId == 2L })
        assertTrue(cardsOfType(cards, StoryCardType.ALBUMS).any { it.albumId == 1L })
        assertFalse(3L in allMediaIds(cards))
        val favoritesCard = cards.single { it.type == StoryCardType.FAVORITES }
        assertEquals(listOf(1L), favoritesCard.mediaList.map { it.id })
    }

    /** AE2: locked album → no card and no media anywhere, exclusions empty. */
    @Test
    fun `locked album produces no card and leaks no media with empty exclusions`() = runBlocking {
        writeConfig(StoryCardsConfig())
        val distributor = TestDistributor()
        val m1 = media(1, albumID = 1)
        val lockedMedia = media(9, albumID = 7, albumLabel = "Locked")
        // The real distributor drops locked-album media from the main timeline
        // only — favoritesMediaFlow still carries it (the leak this fixes).
        distributor.timeline.value = MediaState(listOf(m1))
        distributor.albums.value =
            AlbumState(albums = listOf(album(1, "Camera"), album(7, "Locked", locked = true)))
        distributor.favorites.value = MediaState(listOf(m1, lockedMedia))

        val vm = viewModel(FakeMediaRepository(), distributor)
        val cards = awaitCards(vm) { cards ->
            cards.any { it.type == StoryCardType.FAVORITES }
        }

        assertTrue(cardsOfType(cards, StoryCardType.ALBUMS).none { it.albumId == 7L })
        assertFalse(9L in allMediaIds(cards))
    }

    /** FAVORITES card survives but contains no locked-album media after the fix. */
    @Test
    fun `favorites card drops locked album media but keeps visible favorites`() = runBlocking {
        writeConfig(StoryCardsConfig())
        val distributor = TestDistributor()
        val m1 = media(1, albumID = 1)
        val m2 = media(2, albumID = 1)
        val lockedFavorite = media(9, albumID = 7, albumLabel = "Locked")
        distributor.timeline.value = MediaState(listOf(m1, m2))
        distributor.albums.value =
            AlbumState(albums = listOf(album(1, "Camera"), album(7, "Locked", locked = true)))
        distributor.favorites.value = MediaState(listOf(m1, m2, lockedFavorite))

        val vm = viewModel(FakeMediaRepository(), distributor)
        val cards = awaitCards(vm) { cards ->
            cards.any { it.type == StoryCardType.FAVORITES }
        }

        val favoritesCard = cards.single { it.type == StoryCardType.FAVORITES }
        assertEquals(setOf(1L, 2L), favoritesCard.mediaList.mapTo(HashSet()) { it.id })
    }

    @Test
    fun `excluded location key removes its card other locations still build`() = runBlocking {
        writeConfig(StoryCardsConfig(excludedLocationKeys = setOf("Paris, France")))
        val distributor = TestDistributor()
        val m1 = media(1, albumID = 1)
        val m2 = media(2, albumID = 1)
        distributor.timeline.value = MediaState(listOf(m1, m2))
        distributor.albums.value = AlbumState(albums = listOf(album(1, "Camera")))
        val repository = FakeMediaRepository(
            metadata = listOf(
                locationMetadata(1, "Paris", "France"),
                locationMetadata(2, "Berlin", "Germany"),
            )
        )

        val vm = viewModel(repository, distributor)
        val cards = awaitCards(vm) { cards ->
            cards.any { it.type == StoryCardType.LOCATIONS }
        }

        val locationCards = cardsOfType(cards, StoryCardType.LOCATIONS)
        assertTrue(locationCards.none { it.title == "Paris, France" })
        assertTrue(locationCards.any { it.title == "Berlin, Germany" })
        // Excluded location's media is stripped globally (KTD2), including
        // from the ALBUMS card that still exists for the shared album.
        assertFalse(1L in allMediaIds(cards))
        val albumCard = cardsOfType(cards, StoryCardType.ALBUMS).single { it.albumId == 1L }
        assertEquals(listOf(2L), albumCard.mediaList.map { it.id })
    }

    @Test
    fun `excluded category removes its card and member media from other cards`() = runBlocking {
        writeConfig(StoryCardsConfig(excludedCategoryIds = setOf(9L)))
        val distributor = TestDistributor()
        val m1 = media(1, albumID = 1)
        val m2 = media(2, albumID = 1)
        val m3 = media(3, albumID = 1)
        distributor.timeline.value = MediaState(listOf(m1, m2, m3))
        distributor.albums.value = AlbumState(albums = listOf(album(1, "Camera")))
        distributor.favorites.value = MediaState(listOf(m1, m2))
        val repository = FakeMediaRepository(
            topCategories = listOf(category(9, "ExcludedCat"), category(10, "KeptCat")),
            mediaIdsByCategory = mapOf(9L to listOf(2L), 10L to listOf(3L)),
            allClassifiedIds = listOf(2L, 3L),
        )

        val vm = viewModel(repository, distributor)
        val cards = awaitCards(vm) { cards ->
            cards.any { it.type == StoryCardType.CATEGORIES } &&
                cards.any { it.type == StoryCardType.FAVORITES }
        }

        val categoryCards = cardsOfType(cards, StoryCardType.CATEGORIES)
        assertTrue(categoryCards.none { it.categoryId == 9L })
        val kept = categoryCards.single { it.categoryId == 10L }
        assertEquals(listOf(3L), kept.mediaList.map { it.id })
        // The excluded category's member media is gone everywhere (KTD2).
        assertFalse(2L in allMediaIds(cards))
        val favoritesCard = cards.single { it.type == StoryCardType.FAVORITES }
        assertEquals(listOf(1L), favoritesCard.mediaList.map { it.id })
    }

    @Test
    fun `empty exclusion sets leave card building unchanged`() = runBlocking {
        writeConfig(StoryCardsConfig())
        val distributor = TestDistributor()
        val m1 = media(1, albumID = 1)
        val m2 = media(2, albumID = 1)
        distributor.timeline.value = MediaState(listOf(m1, m2))
        distributor.albums.value = AlbumState(albums = listOf(album(1, "Camera", count = 2)))
        distributor.favorites.value = MediaState(listOf(m1))
        val repository = FakeMediaRepository(
            metadata = listOf(locationMetadata(1, "Paris", "France")),
            topCategories = listOf(category(5, "Cats")),
            mediaIdsByCategory = mapOf(5L to listOf(1L)),
            allClassifiedIds = listOf(1L),
        )

        val vm = viewModel(repository, distributor)
        val cards = awaitCards(vm) { cards ->
            cards.any { it.type == StoryCardType.ALBUMS } &&
                cards.any { it.type == StoryCardType.CATEGORIES } &&
                cards.any { it.type == StoryCardType.LOCATIONS } &&
                cards.any { it.type == StoryCardType.FAVORITES }
        }

        val albumCard = cardsOfType(cards, StoryCardType.ALBUMS).single { it.albumId == 1L }
        assertEquals(setOf(1L, 2L), albumCard.mediaList.mapTo(HashSet()) { it.id })
        val favoritesCard = cards.single { it.type == StoryCardType.FAVORITES }
        assertEquals(listOf(1L), favoritesCard.mediaList.map { it.id })
        val locationCard = cardsOfType(cards, StoryCardType.LOCATIONS)
            .single { it.title == "Paris, France" }
        assertEquals(listOf(1L), locationCard.mediaList.map { it.id })
        val categoryCard = cardsOfType(cards, StoryCardType.CATEGORIES).single { it.categoryId == 5L }
        assertEquals(listOf(1L), categoryCard.mediaList.map { it.id })
    }

    private companion object {
        /** Raw preferences key — mirrors Settings.Misc.STORY_CARDS_CONFIG (private there). */
        const val STORY_CARDS_CONFIG_KEY = "story_cards_config"
    }
}
