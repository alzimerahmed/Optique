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
import com.dot.gallery.cloud.core.LOCAL_PEOPLE_CONFIG_ID
import com.dot.gallery.cloud.core.PersonInfo
import com.dot.gallery.cloud.core.ProviderCapability
import com.dot.gallery.cloud.core.ProviderRegistry
import com.dot.gallery.cloud.core.ProviderType
import com.dot.gallery.cloud.core.capabilities.PeopleCapableProvider
import com.dot.gallery.cloud.data.dao.PersonDao
import com.dot.gallery.cloud.data.entity.PersonEntity
import com.dot.gallery.core.Resource
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
import java.time.Clock
import java.time.Instant
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

    /**
     * Same proxy trick as [FakeMediaRepository]: StoryCardsViewModel only
     * calls `getById` (the KTD5 crop-binding lookup), everything else throws.
     */
    private class FakePersonDao(
        var byId: Map<String, PersonEntity> = emptyMap(),
    ) : InvocationHandler {

        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
            val arguments = args ?: emptyArray()
            return when {
                method.name == "getById" -> byId[arguments[0] as String]
                method.name == "toString" -> "FakePersonDao"
                method.name == "hashCode" -> System.identityHashCode(proxy)
                method.name == "equals" -> proxy === arguments[0]
                else -> throw UnsupportedOperationException(
                    "PersonDao.${method.name} is not stubbed in StoryCardsViewModelTest"
                )
            }
        }

        fun asDao(): PersonDao = Proxy.newProxyInstance(
            PersonDao::class.java.classLoader,
            arrayOf(PersonDao::class.java),
            this
        ) as PersonDao
    }

    /**
     * A [PeopleCapableProvider] for U8: [providerType] decides whether the
     * ViewModel treats it as the local face-cluster source — only
     * [ProviderType.LOCAL_PEOPLE] providers may produce cards (KTD4's
     * local-only boundary), so an IMMICH fake exercises the exclusion.
     */
    private class FakePeopleProvider(
        override val providerType: ProviderType,
        private val people: List<PersonInfo> = emptyList(),
        private val personMedia: Map<String, List<Media>> = emptyMap(),
        override val isAvailable: Boolean = true,
    ) : PeopleCapableProvider {
        override val displayName: String = providerType.displayName
        override val capabilities: Set<ProviderCapability> =
            setOf(ProviderCapability.PEOPLE)

        /** personIds [getPersonMedia] was invoked for — proves the size gate runs first. */
        val personMediaCalls = mutableListOf<String>()

        override fun getPeople(): Flow<Resource<List<PersonInfo>>> =
            flowOf(Resource.Success(people))

        override fun getPersonMedia(personId: String): Flow<Resource<List<Media>>> {
            personMediaCalls += personId
            return flowOf(Resource.Success(personMedia[personId].orEmpty()))
        }

        override fun getPersonThumbnailUrl(personId: String): String? = null
        override suspend fun updatePersonName(personId: String, name: String) =
            Result.success(Unit)
        override suspend fun updatePersonBirthDate(personId: String, birthDate: String) =
            Result.success(Unit)
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
        clock: Clock = Clock.fixed(TEST_INSTANT, ZoneOffset.UTC),
        registry: ProviderRegistry = ProviderRegistry(),
        personDao: PersonDao = FakePersonDao().asDao(),
    ) = StoryCardsViewModel(
        repository.asRepository(),
        distributor,
        registry,
        personDao,
        context,
        clock,
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

    // ---------- U6: cap application + day-seeded rotation (R5, R6, KTD3) ----------

    /**
     * Determinism: two ViewModels pinned to the same day emit the identical
     * pick from an over-cap pool.
     */
    @Test
    fun `same day produces identical capped selection`() = runBlocking {
        writeConfig(StoryCardsConfig())
        val clock = Clock.fixed(TEST_INSTANT, ZoneOffset.UTC)
        val distributor = TestDistributor()
        seedAlbumPool(distributor, albumCount = 7)

        val first = awaitCards(viewModel(FakeMediaRepository(), distributor, clock)) { cards ->
            cards.count { it.type == StoryCardType.ALBUMS } == 5
        }
        val second = awaitCards(viewModel(FakeMediaRepository(), distributor, clock)) { cards ->
            cards.count { it.type == StoryCardType.ALBUMS } == 5
        }

        assertEquals(first.map { it.id }, second.map { it.id })
    }

    /**
     * Rotation: with 7 eligible albums and cap 5, different days re-pick
     * which cards fill the slots — the union of picked sets exceeds the cap.
     */
    @Test
    fun `different days re-pick which eligible albums fill the cap`() = runBlocking {
        writeConfig(StoryCardsConfig())
        val distributor = TestDistributor()
        seedAlbumPool(distributor, albumCount = 7)

        val picksByDay = mutableSetOf<Set<Long>>()
        for (dayOffset in 0L..6L) {
            val clock = Clock.fixed(
                TEST_INSTANT.plus(dayOffset, java.time.temporal.ChronoUnit.DAYS),
                ZoneOffset.UTC
            )
            val cards = awaitCards(viewModel(FakeMediaRepository(), distributor, clock)) { c ->
                c.count { it.type == StoryCardType.ALBUMS } == 5
            }
            val albumIds = cardsOfType(cards, StoryCardType.ALBUMS)
                .mapTo(HashSet()) { it.id }
            assertEquals(5, albumIds.size)
            picksByDay += albumIds
        }

        assertTrue(
            "expected day-seeded picks to vary across the week, got $picksByDay",
            picksByDay.size > 1
        )
    }

    /** Eligible pool at/under the cap renders identically regardless of day. */
    @Test
    fun `pool at or under cap is identical across days`() = runBlocking {
        writeConfig(StoryCardsConfig())
        val distributor = TestDistributor()
        seedAlbumPool(distributor, albumCount = 3)

        val picksByDay = mutableSetOf<List<Long>>()
        for (dayOffset in 0L..3L) {
            val clock = Clock.fixed(
                TEST_INSTANT.plus(dayOffset, java.time.temporal.ChronoUnit.DAYS),
                ZoneOffset.UTC
            )
            val cards = awaitCards(viewModel(FakeMediaRepository(), distributor, clock)) { c ->
                c.count { it.type == StoryCardType.ALBUMS } == 3
            }
            picksByDay += cardsOfType(cards, StoryCardType.ALBUMS).map { it.id }
        }

        assertEquals(1, picksByDay.size)
    }

    /** The merged strip's type ordering follows config.activeTypes on any seed. */
    @Test
    fun `merged type order follows activeTypes on different days`() = runBlocking {
        // Favorites first, then categories, then albums — non-default order.
        val config = StoryCardsConfig(
            cardOrder = listOf(
                StoryCardType.FAVORITES,
                StoryCardType.CATEGORIES,
                StoryCardType.ALBUMS,
                StoryCardType.MEMORIES,
                StoryCardType.LOCATIONS,
                StoryCardType.CLOUD_MEMORIES,
                StoryCardType.HIGHLIGHTS,
                StoryCardType.PEOPLE,
            )
        )
        writeConfig(config)
        val distributor = TestDistributor()
        seedAlbumPool(distributor, albumCount = 3)
        // Both the favorite and the category member resolve through the
        // filtered timeline set, so they must share a timeline media id.
        distributor.favorites.value = MediaState(listOf(media(1, albumID = 1)))
        val repository = FakeMediaRepository(
            topCategories = listOf(category(5, "Cats")),
            mediaIdsByCategory = mapOf(5L to listOf(1L)),
        )

        for (dayOffset in 0L..2L) {
            val clock = Clock.fixed(
                TEST_INSTANT.plus(dayOffset, java.time.temporal.ChronoUnit.DAYS),
                ZoneOffset.UTC
            )
            val cards = awaitCards(viewModel(repository, distributor, clock)) { c ->
                c.any { it.type == StoryCardType.FAVORITES } &&
                    c.any { it.type == StoryCardType.CATEGORIES } &&
                    c.any { it.type == StoryCardType.ALBUMS }
            }
            val presentOrder = cards.map { it.type }.distinct()
            val expectedOrder = config.activeTypes.filter { t -> cards.any { it.type == t } }
            assertEquals(expectedOrder, presentOrder)
        }
    }

    /** R5: a configured cap of 3 yields exactly 3 cards from a 7-album pool. */
    @Test
    fun `configured cap is honored over an over-cap pool`() = runBlocking {
        writeConfig(
            StoryCardsConfig(maxCardsPerType = mapOf(StoryCardType.ALBUMS to 3))
        )
        val distributor = TestDistributor()
        seedAlbumPool(distributor, albumCount = 7)

        val cards = awaitCards(viewModel(FakeMediaRepository(), distributor)) { c ->
            c.count { it.type == StoryCardType.ALBUMS } == 3
        }

        assertEquals(3, cardsOfType(cards, StoryCardType.ALBUMS).size)
    }

    /** R5: raising the cap above the old hardcoded default surfaces more cards. */
    @Test
    fun `raised cap surfaces more than the old default of five`() = runBlocking {
        writeConfig(
            StoryCardsConfig(maxCardsPerType = mapOf(StoryCardType.ALBUMS to 8))
        )
        val distributor = TestDistributor()
        seedAlbumPool(distributor, albumCount = 7)

        val cards = awaitCards(viewModel(FakeMediaRepository(), distributor)) { c ->
            c.count { it.type == StoryCardType.ALBUMS } == 7
        }

        assertEquals(7, cardsOfType(cards, StoryCardType.ALBUMS).size)
    }

    /**
     * R5/R6: a CATEGORIES cap above the old fetch limit of 5 works — the
     * fetch was raised to 20 so the eligible pool exceeds the cap.
     */
    @Test
    fun `category cap above five is honored after fetch raise`() = runBlocking {
        writeConfig(
            StoryCardsConfig(maxCardsPerType = mapOf(StoryCardType.CATEGORIES to 8))
        )
        val distributor = TestDistributor()
        val items = (1L..9L).map { media(it, albumID = 1) }
        distributor.timeline.value = MediaState(items)
        distributor.albums.value = AlbumState(albums = listOf(album(1, "Camera", count = 9)))
        val repository = FakeMediaRepository(
            topCategories = (1L..9L).map { category(it, "Cat$it") },
            mediaIdsByCategory = (1L..9L).associateWith { listOf(it) },
        )

        val cards = awaitCards(viewModel(repository, distributor)) { c ->
            c.count { it.type == StoryCardType.CATEGORIES } == 8
        }

        assertEquals(8, cardsOfType(cards, StoryCardType.CATEGORIES).size)
    }

    /** First-ever launch: default config, pinned day — normal seeded strip. */
    @Test
    fun `first launch with default config produces a seeded strip`() = runBlocking {
        writeConfig(StoryCardsConfig())
        val distributor = TestDistributor()
        seedAlbumPool(distributor, albumCount = 3)
        distributor.favorites.value = MediaState(listOf(media(1, albumID = 1)))

        val cards = awaitCards(viewModel(FakeMediaRepository(), distributor)) { it.isNotEmpty() }

        assertTrue(cards.any { it.type == StoryCardType.ALBUMS })
        assertTrue(cards.any { it.type == StoryCardType.FAVORITES })
    }

    // ---------- U7: HIGHLIGHTS cards (R8, AE4, KTD4/KTD6) ----------

    /**
     * Pinned day is 2026-09-18T12:00Z: the 7-day window starts
     * 2026-09-11T12:00Z, the 30-day window 2026-08-19T12:00Z.
     */
    @Test
    fun `recent media produces this week and this month highlight cards`() = runBlocking {
        writeConfig(StoryCardsConfig())
        val distributor = TestDistributor()
        val m1 = media(1, year = 2026, month = 9, day = 12) // 6 days — week + month
        val m2 = media(2, year = 2026, month = 9, day = 16) // 2 days — week + month
        val m3 = media(3, year = 2026, month = 9, day = 17) // 1 day — week + month
        val m4 = media(4, year = 2026, month = 9, day = 10) // 8 days — month only
        val m5 = media(5, year = 2026, month = 8, day = 25) // 24 days — month only
        val mOld = media(6, year = 2020, month = 9, day = 15) // years old — neither
        distributor.timeline.value = MediaState(listOf(m1, m2, m3, m4, m5, mOld))
        distributor.albums.value = AlbumState(albums = listOf(album(1, "Camera", count = 6)))
        // m3 favorited: the scoring must surface it as the week card's cover.
        distributor.favorites.value = MediaState(listOf(m3))

        val vm = viewModel(FakeMediaRepository(), distributor)
        val cards = awaitCards(vm) { c ->
            c.count { it.type == StoryCardType.HIGHLIGHTS } == 2
        }

        val highlights = cardsOfType(cards, StoryCardType.HIGHLIGHTS)
        assertEquals(setOf("This week", "This month"), highlights.mapTo(HashSet()) { it.title })
        val week = highlights.single { it.title == "This week" }
        assertEquals(setOf(1L, 2L, 3L), week.mediaList.mapTo(HashSet()) { it.id })
        assertEquals(3L, week.thumbnailMedia?.id)
        val month = highlights.single { it.title == "This month" }
        assertEquals(setOf(1L, 2L, 3L, 4L, 5L), month.mediaList.mapTo(HashSet()) { it.id })
    }

    /** AE4: library media all older than the month window → no card. */
    @Test
    fun `no recent media produces no highlights card`() = runBlocking {
        writeConfig(StoryCardsConfig())
        val distributor = TestDistributor()
        distributor.timeline.value = MediaState(
            listOf(
                media(1, year = 2020, month = 6, day = 1),
                media(2, year = 2020, month = 6, day = 2),
                media(3, year = 2020, month = 6, day = 3),
            )
        )
        distributor.albums.value = AlbumState(albums = listOf(album(1, "Camera", count = 3)))

        val vm = viewModel(FakeMediaRepository(), distributor)
        val cards = awaitCards(vm) { c -> c.any { it.type == StoryCardType.ALBUMS } }

        assertTrue(cardsOfType(cards, StoryCardType.HIGHLIGHTS).isEmpty())
    }

    /** A window with <3 scored items is noise and emits no card. */
    @Test
    fun `fewer than three recent items emits no highlights card`() = runBlocking {
        writeConfig(StoryCardsConfig())
        val distributor = TestDistributor()
        distributor.timeline.value = MediaState(
            listOf(
                media(1, year = 2026, month = 9, day = 17),
                media(2, year = 2026, month = 9, day = 16),
                media(3, year = 2020, month = 6, day = 1),
            )
        )
        distributor.albums.value = AlbumState(albums = listOf(album(1, "Camera", count = 3)))

        val vm = viewModel(FakeMediaRepository(), distributor)
        val cards = awaitCards(vm) { c -> c.any { it.type == StoryCardType.ALBUMS } }

        assertTrue(cardsOfType(cards, StoryCardType.HIGHLIGHTS).isEmpty())
    }

    /** KTD2: excluded-album media can't leak into a highlights card either. */
    @Test
    fun `excluded album media does not appear in highlights`() = runBlocking {
        writeConfig(StoryCardsConfig(excludedAlbumIds = setOf(2L)))
        val distributor = TestDistributor()
        distributor.timeline.value = MediaState(
            listOf(
                media(1, albumID = 1, year = 2026, month = 9, day = 16),
                media(2, albumID = 1, year = 2026, month = 9, day = 17),
                media(3, albumID = 1, year = 2026, month = 9, day = 15),
                media(4, albumID = 2, albumLabel = "Excluded", year = 2026, month = 9, day = 17),
                media(5, albumID = 2, albumLabel = "Excluded", year = 2026, month = 9, day = 16),
            )
        )
        distributor.albums.value = AlbumState(
            albums = listOf(album(1, "Camera", count = 3), album(2, "Excluded", count = 2))
        )

        val vm = viewModel(FakeMediaRepository(), distributor)
        val cards = awaitCards(vm) { c -> c.any { it.type == StoryCardType.HIGHLIGHTS } }

        val highlights = cardsOfType(cards, StoryCardType.HIGHLIGHTS)
        assertTrue(highlights.isNotEmpty())
        assertTrue(highlights.all { card -> card.mediaList.none { it.albumID == 2L } })
        assertFalse(4L in allMediaIds(cards))
        assertFalse(5L in allMediaIds(cards))
    }

    // ---------- U8: PEOPLE cards (R9, AE5, KTD4/KTD5) ----------

    private fun person(
        id: String,
        name: String = "",
        assetCount: Int = 5,
        thumbnailUrl: String? = "file:///faces/$id.jpg",
    ) = PersonInfo(
        id = id,
        name = name,
        providerType = ProviderType.LOCAL_PEOPLE,
        serverConfigId = LOCAL_PEOPLE_CONFIG_ID,
        thumbnailUrl = thumbnailUrl,
        assetCount = assetCount,
    )

    private fun personEntity(
        id: String,
        thumbnailMediaId: Long?,
        thumbnailUrl: String? = "file:///faces/$id.jpg",
        name: String = "",
    ) = PersonEntity(
        id = id,
        name = name,
        providerType = ProviderType.LOCAL_PEOPLE,
        thumbnailMediaId = thumbnailMediaId,
        thumbnailUrl = thumbnailUrl,
        faceCount = 5,
    )

    private fun peopleRegistry(
        provider: FakePeopleProvider,
        configId: Long = LOCAL_PEOPLE_CONFIG_ID,
    ) = ProviderRegistry().apply { register(configId, provider) }

    /**
     * AE5: clusters under the minimum size produce no card — and the
     * expensive `getPersonMedia` full-library load is never spent on them.
     */
    @Test
    fun `persons below minimum cluster size produce no card`() = runBlocking {
        writeConfig(StoryCardsConfig())
        val distributor = TestDistributor()
        val items = (1L..6L).map { media(it, albumID = 1) }
        distributor.timeline.value = MediaState(items)
        distributor.albums.value = AlbumState(albums = listOf(album(1, "Camera", count = 6)))
        val provider = FakePeopleProvider(
            providerType = ProviderType.LOCAL_PEOPLE,
            people = listOf(
                person("local_a", name = "Alice", assetCount = 4),
                person("local_tiny", name = "Tiny", assetCount = 2),
            ),
            personMedia = mapOf(
                "local_a" to items.take(4),
                "local_tiny" to items.take(2),
            ),
        )

        val vm = viewModel(
            FakeMediaRepository(), distributor,
            registry = peopleRegistry(provider),
        )
        val cards = awaitCards(vm) { c -> c.any { it.type == StoryCardType.PEOPLE } }

        val people = cardsOfType(cards, StoryCardType.PEOPLE)
        assertEquals(listOf("local_a"), people.map { it.personId })
        assertFalse("local_tiny" in provider.personMediaCalls)
    }

    /** AE5: unnamed clusters persist as `""` and render the neutral label. */
    @Test
    fun `unnamed cluster renders the neutral person label`() = runBlocking {
        writeConfig(StoryCardsConfig())
        val distributor = TestDistributor()
        val items = (1L..4L).map { media(it, albumID = 1) }
        distributor.timeline.value = MediaState(items)
        distributor.albums.value = AlbumState(albums = listOf(album(1, "Camera", count = 4)))
        val provider = FakePeopleProvider(
            providerType = ProviderType.LOCAL_PEOPLE,
            people = listOf(person("local_unnamed", name = "", assetCount = 4)),
            personMedia = mapOf("local_unnamed" to items),
        )

        val vm = viewModel(
            FakeMediaRepository(), distributor,
            registry = peopleRegistry(provider),
        )
        val cards = awaitCards(vm) { c -> c.any { it.type == StoryCardType.PEOPLE } }

        val card = cardsOfType(cards, StoryCardType.PEOPLE).single()
        assertEquals("Person", card.title)
    }

    /**
     * Hidden persons never produce cards: the provider's visible-only query
     * drops them upstream, and the ViewModel has no path that re-includes
     * them — the hidden id is never emitted, so its media is never fetched
     * and no card exists for it.
     */
    @Test
    fun `hidden persons never produce cards`() = runBlocking {
        writeConfig(StoryCardsConfig())
        val distributor = TestDistributor()
        val items = (1L..4L).map { media(it, albumID = 1) }
        distributor.timeline.value = MediaState(items)
        distributor.albums.value = AlbumState(albums = listOf(album(1, "Camera", count = 4)))
        val provider = FakePeopleProvider(
            providerType = ProviderType.LOCAL_PEOPLE,
            // Only the visible person is emitted — getVisibleByProvider
            // already dropped "local_hidden" upstream.
            people = listOf(person("local_visible", name = "Visible", assetCount = 4)),
            personMedia = mapOf(
                "local_visible" to items,
                "local_hidden" to items,
            ),
        )

        val vm = viewModel(
            FakeMediaRepository(), distributor,
            registry = peopleRegistry(provider),
        )
        val cards = awaitCards(vm) { c -> c.any { it.type == StoryCardType.PEOPLE } }

        val people = cardsOfType(cards, StoryCardType.PEOPLE)
        assertEquals(listOf("local_visible"), people.map { it.personId })
        assertFalse("local_hidden" in provider.personMediaCalls)
    }

    /**
     * KTD4 local-only boundary: a registered remote (IMMICH)
     * [PeopleCapableProvider] emits persons yet produces no cards — cloud
     * people are deferred scope. The local provider beside it still works.
     */
    @Test
    fun `non local people provider produces no cards`() = runBlocking {
        writeConfig(StoryCardsConfig())
        val distributor = TestDistributor()
        val items = (1L..5L).map { media(it, albumID = 1) }
        distributor.timeline.value = MediaState(items)
        distributor.albums.value = AlbumState(albums = listOf(album(1, "Camera", count = 5)))
        val registry = ProviderRegistry().apply {
            register(
                LOCAL_PEOPLE_CONFIG_ID,
                FakePeopleProvider(
                    providerType = ProviderType.LOCAL_PEOPLE,
                    people = listOf(person("local_a", name = "Alice", assetCount = 4)),
                    personMedia = mapOf("local_a" to items.take(4)),
                )
            )
            register(
                42L,
                FakePeopleProvider(
                    providerType = ProviderType.IMMICH,
                    people = listOf(person("remote_a", name = "Remote", assetCount = 9)),
                    personMedia = mapOf("remote_a" to items),
                )
            )
        }
        val remote = registry.getByConfigId(42L) as FakePeopleProvider

        val vm = viewModel(FakeMediaRepository(), distributor, registry = registry)
        val cards = awaitCards(vm) { c -> c.any { it.type == StoryCardType.PEOPLE } }

        val people = cardsOfType(cards, StoryCardType.PEOPLE)
        assertEquals(listOf("local_a"), people.map { it.personId })
        assertTrue(remote.personMediaCalls.isEmpty())
    }

    /**
     * KTD2 + KTD5: excluded-album media of a person is stripped from the
     * card's mediaList, and a face crop whose source media is excluded can
     * no longer bind — the cover falls back to a filtered media item.
     */
    @Test
    fun `person media resolves through filtered set and unbound crop falls back`() = runBlocking {
        writeConfig(StoryCardsConfig(excludedAlbumIds = setOf(2L)))
        val distributor = TestDistributor()
        val m1 = media(1, albumID = 1)
        val m2 = media(2, albumID = 1)
        val m3 = media(3, albumID = 1)
        val excluded = media(4, albumID = 2, albumLabel = "Excluded")
        distributor.timeline.value = MediaState(listOf(m1, m2, m3, excluded))
        distributor.albums.value = AlbumState(
            albums = listOf(album(1, "Camera", count = 3), album(2, "Excluded", count = 1))
        )
        val provider = FakePeopleProvider(
            providerType = ProviderType.LOCAL_PEOPLE,
            people = listOf(
                person("local_a", name = "Alice", assetCount = 4,
                    thumbnailUrl = "file:///faces/crop_a.jpg")
            ),
            personMedia = mapOf("local_a" to listOf(m1, m2, m3, excluded)),
        )
        val personDao = FakePersonDao(
            byId = mapOf(
                "local_a" to personEntity(
                    "local_a", thumbnailMediaId = 4L,
                    thumbnailUrl = "file:///faces/crop_a.jpg", name = "Alice"
                )
            )
        )

        val vm = viewModel(
            FakeMediaRepository(), distributor,
            registry = peopleRegistry(provider),
            personDao = personDao.asDao(),
        )
        val cards = awaitCards(vm) { c -> c.any { it.type == StoryCardType.PEOPLE } }

        val card = cardsOfType(cards, StoryCardType.PEOPLE).single()
        assertEquals(setOf(1L, 2L, 3L), card.mediaList.mapTo(HashSet()) { it.id })
        assertNull(card.thumbnailUri)
        assertNotNull(card.thumbnailMedia)
        assertTrue(card.thumbnailMedia!!.id in setOf(1L, 2L, 3L))
    }

    /**
     * KTD5 positive case: when the crop's `thumbnailMediaId` binds inside
     * the filtered set, the card carries the face-crop `thumbnailUri` and
     * no media cover — the row renders the crop (thumbnailUri path).
     */
    @Test
    fun `bound face crop renders as card cover`() = runBlocking {
        writeConfig(StoryCardsConfig())
        val distributor = TestDistributor()
        val items = (1L..4L).map { media(it, albumID = 1) }
        distributor.timeline.value = MediaState(items)
        distributor.albums.value = AlbumState(albums = listOf(album(1, "Camera", count = 4)))
        val provider = FakePeopleProvider(
            providerType = ProviderType.LOCAL_PEOPLE,
            people = listOf(
                person("local_a", name = "Alice", assetCount = 4,
                    thumbnailUrl = "file:///faces/crop_a.jpg")
            ),
            personMedia = mapOf("local_a" to items),
        )
        val personDao = FakePersonDao(
            byId = mapOf(
                "local_a" to personEntity(
                    "local_a", thumbnailMediaId = 2L,
                    thumbnailUrl = "file:///faces/crop_a.jpg", name = "Alice"
                )
            )
        )

        val vm = viewModel(
            FakeMediaRepository(), distributor,
            registry = peopleRegistry(provider),
            personDao = personDao.asDao(),
        )
        val cards = awaitCards(vm) { c -> c.any { it.type == StoryCardType.PEOPLE } }

        val card = cardsOfType(cards, StoryCardType.PEOPLE).single()
        assertEquals(Uri.parse("file:///faces/crop_a.jpg"), card.thumbnailUri)
        assertNull(card.thumbnailMedia)
    }

    /** Empty-intersection guard: a person with no visible media gets no card. */
    @Test
    fun `person whose media is entirely excluded produces no card`() = runBlocking {
        writeConfig(StoryCardsConfig(excludedAlbumIds = setOf(2L)))
        val distributor = TestDistributor()
        val visible = (1L..3L).map { media(it, albumID = 1) }
        val hidden = (4L..6L).map { media(it, albumID = 2, albumLabel = "Excluded") }
        distributor.timeline.value = MediaState(visible + hidden)
        distributor.albums.value = AlbumState(
            albums = listOf(album(1, "Camera", count = 3), album(2, "Excluded", count = 3))
        )
        val provider = FakePeopleProvider(
            providerType = ProviderType.LOCAL_PEOPLE,
            people = listOf(person("local_a", name = "Alice", assetCount = 3)),
            personMedia = mapOf("local_a" to hidden),
        )

        val vm = viewModel(
            FakeMediaRepository(), distributor,
            registry = peopleRegistry(provider),
        )
        val cards = awaitCards(vm) { c -> c.any { it.type == StoryCardType.ALBUMS } }

        assertTrue(cardsOfType(cards, StoryCardType.PEOPLE).isEmpty())
    }

    /**
     * KTD4: card ids are `8_000_000L +` a 40-bit masked FNV-1a hash of the
     * personId — asserted unique across a synthetic `local_*` id set. The
     * run also documents the pool bound: far more emitted persons than
     * `peopleCards` carries, so `getPersonMedia` stays bounded.
     */
    @Test
    fun `people card ids are unique across a synthetic person set`() = runBlocking {
        writeConfig(StoryCardsConfig())
        val distributor = TestDistributor()
        val items = (1L..4L).map { media(it, albumID = 1) }
        distributor.timeline.value = MediaState(items)
        distributor.albums.value = AlbumState(albums = listOf(album(1, "Camera", count = 4)))
        val persons = (1..250).map {
            person("local_9f8e7d6c-b4a2-$it", name = "Person $it", assetCount = 5)
        }
        val provider = FakePeopleProvider(
            providerType = ProviderType.LOCAL_PEOPLE,
            people = persons,
            personMedia = persons.associate { it.id to items.take(3) },
        )

        val vm = viewModel(
            FakeMediaRepository(), distributor,
            registry = peopleRegistry(provider),
        )
        val people = withTimeout(30_000) { vm.peopleCards.first { it.isNotEmpty() } }

        // The eligible pool is bounded at PEOPLE_POOL_LIMIT (20) despite
        // 250 emitted persons — each combine emission then fetches person
        // media only within that bound.
        assertEquals(20, people.size)
        assertEquals(people.size, people.map { it.id }.toSet().size)
        assertTrue(people.all { it.id >= 8_000_000L })
        assertTrue(people.all { it.type == StoryCardType.PEOPLE })
        // getPersonMedia is never consulted for persons outside the pool —
        // every recorded call belongs to one of the 20 pooled ids.
        val pooledIds = persons
            .sortedByDescending { it.assetCount }
            .take(20)
            .mapTo(HashSet()) { it.id }
        assertTrue(provider.personMediaCalls.all { it in pooledIds })
    }

    /**
     * Provider absent (nothing registered) and provider present but
     * unavailable (no face models / `ENABLE_INDEXING=false` debug) both
     * leave the strip unchanged — silent, no crash.
     */
    @Test
    fun `missing or unavailable people provider leaves the strip unchanged`() = runBlocking {
        writeConfig(StoryCardsConfig())
        val registries = listOf(
            ProviderRegistry(),
            peopleRegistry(
                FakePeopleProvider(ProviderType.LOCAL_PEOPLE, isAvailable = false)
            ),
        )
        for (registry in registries) {
            val distributor = TestDistributor()
            seedAlbumPool(distributor, albumCount = 3)
            val vm = viewModel(FakeMediaRepository(), distributor, registry = registry)
            val cards = awaitCards(vm) { c -> c.any { it.type == StoryCardType.ALBUMS } }
            assertTrue(cardsOfType(cards, StoryCardType.PEOPLE).isEmpty())
        }
    }

    /** Seeds [albumCount] albums with one timeline media item each. */
    private fun seedAlbumPool(distributor: TestDistributor, albumCount: Int) {
        val items = (1L..albumCount.toLong()).map { media(it, albumID = it) }
        distributor.timeline.value = MediaState(items)
        distributor.albums.value = AlbumState(
            albums = (1L..albumCount.toLong()).map { album(it, "Album$it", count = 1) }
        )
    }

    private companion object {
        /** Raw preferences key — mirrors Settings.Misc.STORY_CARDS_CONFIG (private there). */
        const val STORY_CARDS_CONFIG_KEY = "story_cards_config"

        /** Pinned day for the injected rotation Clock (2026-09-18T12:00Z). */
        val TEST_INSTANT: Instant = Instant.parse("2026-09-18T12:00:00Z")
    }
}
