/*
 * SPDX-FileCopyrightText: 2023-2026 alzimerahmed
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.feature_node.presentation.storycards

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dot.gallery.R
import com.dot.gallery.cloud.core.MemoryInfo
import com.dot.gallery.cloud.core.PersonInfo
import com.dot.gallery.cloud.core.ProviderRegistry
import com.dot.gallery.cloud.core.ProviderType
import com.dot.gallery.cloud.core.capabilities.MemoriesCapableProvider
import com.dot.gallery.cloud.core.capabilities.PeopleCapableProvider
import com.dot.gallery.cloud.core.stableIdHash
import com.dot.gallery.cloud.data.dao.DetectedFaceDao
import com.dot.gallery.cloud.data.dao.PersonDao
import com.dot.gallery.core.MediaDistributor
import com.dot.gallery.core.Resource
import com.dot.gallery.core.Settings
import com.dot.gallery.feature_node.domain.model.Media
import com.dot.gallery.feature_node.domain.model.MediaMetadata
import com.dot.gallery.feature_node.domain.model.StoryCard
import com.dot.gallery.feature_node.domain.model.StoryCardType
import com.dot.gallery.feature_node.domain.model.StoryCardsConfig
import com.dot.gallery.feature_node.domain.model.locationKey
import com.dot.gallery.feature_node.domain.repository.MediaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDate
import java.util.Calendar
import javax.inject.Inject

@HiltViewModel
class StoryCardsViewModel @Inject constructor(
    private val repository: MediaRepository,
    private val distributor: MediaDistributor,
    private val providerRegistry: ProviderRegistry,
    private val personDao: PersonDao,
    private val faceDao: DetectedFaceDao,
    @param:ApplicationContext private val context: Context,
    private val clock: Clock
) : ViewModel() {

    // One collector: stateIn holds the decoded config for all consumers and
    // distinctUntilChanged stops app-wide DataStore writes (which re-emit the
    // identical JSON) from re-running every downstream combine. The disabled
    // seed means "not loaded yet" — an enabled default would briefly build
    // cards with no exclusions before the stored config arrives.
    private val configFlow = Settings.Misc.getStoryCardsConfig(context)
        .distinctUntilChanged()
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            StoryCardsConfig(enabled = false)
        )

    private val timelineMedia = distributor.timelineMediaFlow
        .map { it.media }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val albumsState = distributor.albumsFlow

    private val favoritesMedia = distributor.favoritesMediaFlow
        .map { it.media }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val metadataFlow = repository.getMetadata()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // Fetch above the maximum configurable cap so the eligible pool has
    // material for rotation (and user caps above the old fetch limit of 5).
    private val topCategories = repository.getTopCategories(CATEGORY_POOL_LIMIT)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * KTD2/R4 source scoping shared by every card flow: excluded
     * albums/categories/locations contribute no media to any card's
     * mediaList. Hoisted so [storyCards], [peopleCards] and [categoryCards]
     * consume one filtered emission instead of each re-running the
     * suspend DAO-backed filter per upstream emission.
     */
    private val scopedMediaFlow = combine(configFlow, timelineMedia, metadataFlow) { c, m, md ->
        m.withoutExcludedSources(c, md)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val storyCards: StateFlow<List<StoryCard>> = combine(
        configFlow,
        scopedMediaFlow,
        albumsState,
        favoritesMedia,
        metadataFlow,
    ) { config, scopedMedia, albums, favorites, metadata ->
        if (!config.enabled || scopedMedia.isEmpty()) return@combine emptyList()

        // Locked albums compose on top of the exclusion filter: their media
        // is already absent from [scopedMedia] (the main timeline drops it)
        // but NOT from [favorites], so favorites are intersected with the
        // filtered set below — closing the FAVORITES locked-media leak (AE2).
        val scopedIds = scopedMedia.mapTo(HashSet()) { it.id }

        val covers = coverContext(favorites, metadata)

        val cards = mutableListOf<StoryCard>()
        for (type in config.activeTypes) {
            when (type) {
                StoryCardType.MEMORIES -> {
                    cards.addAll(buildMemoryCards(scopedMedia, covers))
                }
                StoryCardType.ALBUMS -> {
                    cards.addAll(
                        buildAlbumCards(scopedMedia, albums.albums, config.excludedAlbumIds, covers)
                    )
                }
                StoryCardType.FAVORITES -> {
                    val scopedFavorites = favorites.filter { it.id in scopedIds }
                    if (scopedFavorites.isNotEmpty()) {
                        cards.add(buildFavoritesCard(scopedFavorites, covers))
                    }
                }
                StoryCardType.LOCATIONS -> {
                    cards.addAll(buildLocationCards(scopedMedia, metadata, covers))
                }
                StoryCardType.CATEGORIES -> {
                    // Categories are handled in the separate combine below
                }
                StoryCardType.CLOUD_MEMORIES -> {
                    // Cloud memories are handled in the separate _cloudMemoryCards flow
                }
                StoryCardType.HIGHLIGHTS -> {
                    cards.addAll(buildHighlightCards(scopedMedia, covers))
                }
                StoryCardType.PEOPLE -> {
                    // People cards are handled in a separate provider flow
                }
            }
        }
        cards
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * KTD2 source scoping: removes media belonging to excluded albums,
     * excluded `"city, country"` location keys (the same key
     * [buildLocationCards] groups by), and excluded categories — categories
     * are media-filtered like the other kinds, resolved to member media ids
     * via [MediaRepository.getMediaIdsInCategoryAsync].
     *
     * Identity-based (id/albumID) exclusions can never match remote media:
     * cloud media ids are strictly negative and their albumID is a cloud
     * constant, so cloud-memory card mediaLists pass through this same filter
     * harmlessly without special-casing.
     */
    private suspend fun List<Media.UriMedia>.withoutExcludedSources(
        config: StoryCardsConfig,
        metadata: List<MediaMetadata>,
    ): List<Media.UriMedia> {
        if (config.excludedAlbumIds.isEmpty() &&
            config.excludedLocationKeys.isEmpty() &&
            config.excludedCategoryIds.isEmpty()
        ) {
            return this
        }
        val excludedMediaIds = HashSet<Long>()
        if (config.excludedLocationKeys.isNotEmpty()) {
            for (meta in metadata) {
                val key = meta.locationKey ?: continue
                if (key in config.excludedLocationKeys) {
                    excludedMediaIds += meta.mediaId
                }
            }
        }
        for (categoryId in config.excludedCategoryIds) {
            excludedMediaIds += repository.getMediaIdsInCategoryAsync(categoryId)
        }
        return filter { it.albumID !in config.excludedAlbumIds && it.id !in excludedMediaIds }
    }

    /**
     * Signal bundle shared by the card flows — the identical [CoverContext]
     * construction used to be inlined at each combine. [categorizedIds]
     * defaults to the full classified set; the category flow passes
     * `emptySet()` because its members are uniformly categorized.
     */
    private suspend fun coverContext(
        favorites: List<Media.UriMedia>,
        metadata: List<MediaMetadata>,
        categorizedIds: Set<Long>? = null,
    ): CoverContext = CoverContext(
        favoriteIds = favorites.mapTo(HashSet()) { it.id },
        categorizedIds = categorizedIds ?: repository.getAllClassifiedMediaIds().toSet(),
        metadataById = metadata.associateBy { it.mediaId },
    )

    private val _cloudMemoryCards = MutableStateFlow<List<StoryCard>>(emptyList())

    /**
     * Local face clusters emitted by the local people provider, kept with
     * the provider that produced them. [PersonEntry.thumbnailMediaId] is
     * resolved once at entry build (KTD5 crop binding) so [peopleCards]
     * pays no per-emission [PersonDao] lookup.
     */
    private val _personEntries = MutableStateFlow<List<PersonEntry>>(emptyList())

    private data class PersonEntry(
        val provider: PeopleCapableProvider,
        val info: PersonInfo,
        val thumbnailMediaId: Long?,
    )

    init {
        loadCloudMemories()
        loadPeople()
    }

    private fun loadCloudMemories() {
        val providers = providerRegistry.getByCapability<MemoriesCapableProvider>()
        if (providers.isEmpty()) return
        viewModelScope.launch {
            for (provider in providers) {
                provider.getMemories().collect { resource ->
                    when (resource) {
                        is Resource.Success -> {
                            val memories = resource.data ?: emptyList()
                            _cloudMemoryCards.value = buildCloudMemoryCards(memories)
                        }
                        is Resource.Error -> { /* Silently ignore — local memories still work */ }
                    }
                }
            }
        }
    }

    private fun buildCloudMemoryCards(memories: List<MemoryInfo>): List<StoryCard> {
        return memories.filter { it.media.isNotEmpty() }.mapIndexed { index, memory ->
            val currentYear = Calendar.getInstance().get(Calendar.YEAR)
            val yearsAgo = currentYear - memory.year
            StoryCard(
                id = CLOUD_MEMORY_ID_BASE + memory.year.toLong() * 100 + index,
                type = StoryCardType.CLOUD_MEMORIES,
                title = yearsAgoTitle(yearsAgo),
                subtitle = if (memory.year > 0) "${memory.year}" else null,
                thumbnailMedia = REMOTE_COVERS.cover(memory.media),
                mediaList = memory.media,
                year = memory.year
            )
        }
    }

    /**
     * R9/KTD4: PEOPLE cards come from local on-device face clusters only.
     * [ProviderRegistry.getPeopleProviders] also returns remote providers
     * implementing [PeopleCapableProvider] (Immich) — deferred scope — so
     * the local-only boundary is enforced on [ProviderType], not on
     * availability. A missing or unavailable provider (no face models,
     * noML/offline flavor, `ENABLE_INDEXING=false` debug builds) leaves the
     * entry flow empty — silent absence, same as the CLOUD_MEMORIES path.
     *
     * The size gate (AE5) runs here so [peopleCards] only pays the
     * per-cluster media-id lookup for clusters that can produce a card;
     * [PEOPLE_POOL_LIMIT] bounds the calls.
     */
    private fun loadPeople() {
        val providers = providerRegistry.getPeopleProviders()
            .filter { it.providerType == ProviderType.LOCAL_PEOPLE }
        if (providers.isEmpty()) return
        viewModelScope.launch {
            for (provider in providers) {
                provider.getPeople().collect { resource ->
                    when (resource) {
                        is Resource.Success -> {
                            val mapped = resource.data.orEmpty()
                                .filter { it.assetCount >= MIN_PERSON_ASSET_COUNT }
                                .sortedByDescending { it.assetCount }
                                .take(PEOPLE_POOL_LIMIT)
                                .map { info ->
                                    PersonEntry(
                                        provider = provider,
                                        info = info,
                                        thumbnailMediaId = runCatching {
                                            personDao.getById(info.id)?.thumbnailMediaId
                                        }.getOrNull(),
                                    )
                                }
                            // Provider flows re-emit on unrelated table
                            // writes — skip the no-change update so the
                            // downstream combine is not re-run for it.
                            if (mapped != _personEntries.value) {
                                _personEntries.value = mapped
                            }
                        }
                        is Resource.Error -> { /* Silently ignore — no people cards */ }
                    }
                }
            }
        }
    }

    /**
     * R9/AE5: one PEOPLE card per local face cluster. Person media resolves
     * through the same exclusion-filtered timeline set as every other card
     * (KTD2) — locked or excluded media never enters the card's list or its
     * cover, and a person whose media is entirely filtered out produces no
     * card rather than an empty viewer.
     *
     * Membership comes from [DetectedFaceDao.getMediaIdsForPerson] — the
     * same source `LocalPeopleProvider.getPersonMedia` intersects with the
     * full local+cloud library, so reading the ids directly is identical
     * after the scoped-set lookup while skipping the per-person
     * full-library load. `getCompleteMedia` is never consulted here.
     *
     * Cover (KTD5): the face-crop `thumbnailUri` renders only when the
     * crop's source media (`PersonEntity.thumbnailMediaId`, resolved into
     * [PersonEntry] by [loadPeople]) binds inside the filtered set;
     * unbindable crops fall back to a filtered media item.
     */
    val peopleCards: StateFlow<List<StoryCard>> = combine(
        configFlow,
        scopedMediaFlow,
        favoritesMedia,
        metadataFlow,
        _personEntries,
    ) { config, scoped, favorites, metadata, persons ->
        if (!config.enabled ||
            StoryCardType.PEOPLE in config.disabledTypes ||
            persons.isEmpty()
        ) {
            return@combine emptyList()
        }
        val scopedById = scoped.associateBy { it.id }
        val covers = coverContext(favorites, metadata)
        persons.mapNotNull { (_, person, cropMediaId) ->
            // A failing DAO read degrades to "no media" — the person is
            // skipped by the empty guard rather than breaking the strip.
            val personMedia = runCatching {
                faceDao.getMediaIdsForPerson(person.id)
            }.getOrNull()
                .orEmpty()
                .mapNotNull { scopedById[it] }
                .sortedByDescending { it.definedTimestamp }
            if (personMedia.isEmpty()) return@mapNotNull null
            // Crop binds only when its source media is in THIS person's
            // filtered list — the original `thumbnailMediaId in personMedia`
            // rule; a media id merely present elsewhere in the timeline
            // does not validate the crop.
            val cropBound = person.thumbnailUrl != null &&
                personMedia.any { it.id == cropMediaId }
            StoryCard(
                id = PEOPLE_ID_BASE +
                    (stableIdHash(person.id) and PERSON_ID_MASK),
                type = StoryCardType.PEOPLE,
                title = person.name.takeIf { it.isNotBlank() }
                    ?: context.getString(R.string.story_cards_person_unnamed),
                subtitle = mediaCountString(personMedia.size),
                thumbnailUri = if (cropBound) Uri.parse(person.thumbnailUrl) else null,
                thumbnailMedia = if (cropBound) null else covers.cover(personMedia),
                mediaList = personMedia.take(20),
                personId = person.id,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val categoryCards: StateFlow<List<StoryCard>> = combine(
        configFlow,
        topCategories,
        scopedMediaFlow,
        favoritesMedia,
        metadataFlow
    ) { config, categories, scoped, favorites, metadata ->
        if (!config.enabled || StoryCardType.CATEGORIES in config.disabledTypes) {
            return@combine emptyList()
        }
        // KTD2: the same exclusion filter gates both card eligibility (below)
        // and member media (the lookup map resolves through the filtered set).
        val mediaMap = scoped.associateBy { it.id }
        // Members are uniformly categorized, so the categorized signal carries
        // no weight here — favorite/flagged/photo/screenshot still discriminate.
        val covers = coverContext(favorites, metadata, categorizedIds = emptySet())
        categories.mapNotNull { cat ->
            if (cat.id in config.excludedCategoryIds) return@mapNotNull null
            val mediaIds = repository.getMediaIdsInCategoryAsync(cat.id)
            val categoryMedia = mediaIds.mapNotNull { mediaMap[it] }
                .sortedByDescending { it.definedTimestamp }
            if (categoryMedia.isEmpty()) return@mapNotNull null
            StoryCard(
                id = CATEGORY_ID_BASE + cat.id,
                type = StoryCardType.CATEGORIES,
                title = cat.name,
                subtitle = mediaCountString(cat.mediaCount),
                thumbnailMedia = covers.cover(categoryMedia),
                mediaList = categoryMedia.take(20),
                categoryId = cat.id
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * KTD4: the typed `combine` arity caps at five inputs, so the two
     * provider-side card flows merge upstream into a single feed for
     * [allCards].
     */
    private data class ProviderCards(
        val cloud: List<StoryCard>,
        val people: List<StoryCard>,
    )

    private val providerCards = combine(_cloudMemoryCards, peopleCards) { cloud, people ->
        ProviderCards(cloud, people)
    }

    val allCards: StateFlow<List<StoryCard>?> = combine(
        configFlow,
        storyCards,
        categoryCards,
        providerCards,
        timelineMedia
    ) { config, cards, catCards, provider, media ->
        // null = still loading (timeline hasn't loaded yet)
        if (media.isEmpty()) return@combine null
        if (!config.enabled) return@combine emptyList()
        val merged = mutableListOf<StoryCard>()
        val orderedTypes = config.activeTypes
        // R6/KTD3: one day-seed per emission, mixed with the type identity so
        // each type gets an independent deterministic pick.
        val dayEpoch = LocalDate.now(clock).toEpochDay()
        for (type in orderedTypes) {
            val eligible = when (type) {
                StoryCardType.CATEGORIES -> catCards
                StoryCardType.CLOUD_MEMORIES -> provider.cloud
                StoryCardType.PEOPLE -> provider.people
                else -> cards.filter { it.type == type }
            }
            // R5: per-type cap — defaults preserve the limits the builders
            // used to hardcode; types with no entry (FAVORITES) are uncapped.
            val cap = config.maxCardsPerType[type]
                ?: StoryCardsConfig.DEFAULT_MAX_CARDS_PER_TYPE[type]
                ?: Int.MAX_VALUE
            // Rotation runs after activeTypes ordering: type order and count
            // never change, only which eligible cards fill each slot.
            merged.addAll(
                StoryCardSelection.rotatePick(
                    eligible,
                    seed = dayEpoch * SEED_TYPE_STRIDE + type.ordinal,
                    count = cap
                )
            )
        }
        merged
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private var lastMetadataFetchId: Long? = null

    fun ensureMetadataAvailable(media: Media?) {
        if (media == null) return
        if (media.id == lastMetadataFetchId) return
        val existing = metadataFlow.value.firstOrNull { it.mediaId == media.id }
        if (existing != null && (existing.imageWidth > 0 || existing.manufacturerName != null)) {
            return
        }
        lastMetadataFetchId = media.id
        viewModelScope.launch(Dispatchers.IO) {
            repository.collectMetadataFor(media)
        }
    }

    private fun buildMemoryCards(
        media: List<Media.UriMedia>,
        covers: CoverContext
    ): List<StoryCard> {
        // "Today" comes from the injected clock (same source as the
        // rotation seed); Calendar.MONTH is 0-based, monthValue 1-based.
        val todayDate = LocalDate.now(clock)
        val todayMonth = todayDate.monthValue - 1
        val todayDay = todayDate.dayOfMonth
        val currentYear = todayDate.year

        // One reused Calendar for media-item field reads; one "today"
        // Calendar hoisted out of the per-item fallback loop.
        val cal = Calendar.getInstance()
        val mediaCal = Calendar.getInstance()
        val todayCal = Calendar.getInstance().apply {
            set(Calendar.YEAR, currentYear)
            set(Calendar.MONTH, todayMonth)
            set(Calendar.DAY_OF_MONTH, todayDay)
        }

        // Exact day match first
        var memories = media.filter { m ->
            cal.timeInMillis = m.definedTimestamp * 1000L
            val year = cal.get(Calendar.YEAR)
            val month = cal.get(Calendar.MONTH)
            val day = cal.get(Calendar.DAY_OF_MONTH)
            year < currentYear && month == todayMonth && day == todayDay
        }

        // Fallback: ±3 day window if fewer than 3 results
        if (memories.size < 3) {
            memories = media.filter { m ->
                cal.timeInMillis = m.definedTimestamp * 1000L
                val year = cal.get(Calendar.YEAR)
                val month = cal.get(Calendar.MONTH)
                val day = cal.get(Calendar.DAY_OF_MONTH)
                if (year >= currentYear) return@filter false

                mediaCal.set(Calendar.YEAR, currentYear)
                mediaCal.set(Calendar.MONTH, month)
                mediaCal.set(Calendar.DAY_OF_MONTH, day)
                val diffMs = kotlin.math.abs(mediaCal.timeInMillis - todayCal.timeInMillis)
                val diffDays = diffMs / (1000 * 60 * 60 * 24)
                diffDays <= 3
            }
        }

        if (memories.isEmpty()) return emptyList()

        // Group by year
        val byYear = memories.groupBy { m ->
            cal.timeInMillis = m.definedTimestamp * 1000L
            cal.get(Calendar.YEAR)
        }.toSortedMap(compareByDescending { it })

        return byYear.map { (year, yearMedia) ->
            val yearsAgo = currentYear - year
            val cover = covers.cover(yearMedia)
            StoryCard(
                id = MEMORY_ID_BASE + year.toLong(),
                type = StoryCardType.MEMORIES,
                title = yearsAgoTitle(yearsAgo),
                // Date context (R1): the representative item's formatted date,
                // falling back to the bare year.
                subtitle = cover?.fullDate?.takeIf { it.isNotBlank() } ?: "$year",
                thumbnailMedia = cover,
                mediaList = yearMedia.sortedByDescending { it.definedTimestamp },
                year = year
            )
        }
    }

    private fun buildAlbumCards(
        media: List<Media.UriMedia>,
        albums: List<com.dot.gallery.feature_node.domain.model.Album>,
        excludedAlbumIds: Set<Long>,
        covers: CoverContext
    ): List<StoryCard> {
        // Pick recent/pinned albums with content. The full eligible pool is
        // emitted here — the per-type cap and seeded rotation are applied at
        // the allCards slot fill (R5/R6).
        // Excluded albums are dropped from card eligibility alongside locked
        // ones; their media is already absent from [media] (KTD2).
        val highlighted = albums
            .filter { it.count > 0 && !it.isLocked && it.id !in excludedAlbumIds }
            .sortedWith(
                compareByDescending<com.dot.gallery.feature_node.domain.model.Album> { it.isPinned }
                    .thenByDescending { it.timestamp }
            )

        val mediaByAlbum = media.groupBy { it.albumID }

        return highlighted.mapNotNull { album ->
            val albumMedia = mediaByAlbum[album.id] ?: return@mapNotNull null
            StoryCard(
                id = ALBUM_ID_BASE + album.id,
                type = StoryCardType.ALBUMS,
                title = album.label,
                subtitle = mediaCountString(album.count.toInt()),
                thumbnailMedia = covers.cover(albumMedia),
                mediaList = albumMedia.sortedByDescending { it.definedTimestamp }.take(20),
                albumId = album.id
            )
        }
    }

    private fun buildFavoritesCard(
        favorites: List<Media.UriMedia>,
        covers: CoverContext
    ): StoryCard {
        return StoryCard(
            id = FAVORITES_ID,
            type = StoryCardType.FAVORITES,
            title = context.getString(R.string.favorites),
            subtitle = mediaCountString(favorites.size),
            thumbnailMedia = covers.cover(favorites),
            mediaList = favorites.take(20)
        )
    }

    private fun buildLocationCards(
        media: List<Media.UriMedia>,
        metadata: List<MediaMetadata>,
        covers: CoverContext
    ): List<StoryCard> {
        val mediaById = media.associateBy { it.id }
        // Group metadata entries by their (city, country) pair — grouping on
        // the pair keeps the fields intact; cities containing commas would
        // corrupt a split of the joined "city, country" display string.
        val locationGroups = LinkedHashMap<Pair<String, String>, MutableList<Media.UriMedia>>()
        for (meta in metadata) {
            val city = meta.gpsLocationNameCity
            val country = meta.gpsLocationNameCountry
            if (meta.locationKey == null || city == null || country == null) continue
            val m = mediaById[meta.mediaId] ?: continue
            locationGroups.getOrPut(city to country) { mutableListOf() }.add(m)
        }
        // Sort groups by count descending — the full pool is emitted; the
        // per-type cap and rotation are applied at the allCards slot fill.
        return locationGroups.entries
            .sortedByDescending { it.value.size }
            .mapNotNull { (location, locationMedia) ->
                val sorted = locationMedia.sortedByDescending { it.definedTimestamp }
                val city = location.first
                val country = location.second
                val title = "$city, $country"
                StoryCard(
                    id = LOCATION_ID_BASE + (title.hashCode().toLong() and 0xFFFFFFL),
                    type = StoryCardType.LOCATIONS,
                    title = title,
                    subtitle = mediaCountString(locationMedia.size),
                    thumbnailMedia = covers.cover(sorted),
                    mediaList = sorted.take(20),
                    locationCity = city,
                    locationCountry = country
                )
            }
    }

    /**
     * R8/KTD6: "This week" (7 days) and "This month" (30 days) windows of
     * standout recent media, selected from the exclusion-filtered [media]
     * (KTD2). Scoring and cover picking reuse the shared
     * [StoryCardSelection] signals; a window emits no card when fewer than
     * the minimum qualify (AE4).
     */
    private fun buildHighlightCards(
        media: List<Media.UriMedia>,
        covers: CoverContext,
    ): List<StoryCard> {
        val nowSec = clock.instant().epochSecond
        // The selector ignores anything older than the widest window anyway —
        // build candidates only for the 30-day pool instead of every item.
        val poolStartSec = nowSec - HighlightWindow.entries.maxOf { it.days } * 86_400L
        val candidates = media
            .filter { it.definedTimestamp > poolStartSec }
            .map { covers.candidate(it) }
        val mediaById = media.associateBy { it.id }
        return HighlightWindow.entries.mapIndexedNotNull { index, window ->
            val picked = StoryCardSelection.selectHighlights(
                candidates = candidates,
                nowEpochSec = nowSec,
                windowDays = window.days,
            )
            if (picked.isEmpty()) return@mapIndexedNotNull null
            val pickedMedia = picked.mapNotNull { mediaById[it.id] }
            StoryCard(
                id = HIGHLIGHT_ID_BASE + index,
                type = StoryCardType.HIGHLIGHTS,
                title = context.getString(window.titleRes),
                subtitle = mediaCountString(pickedMedia.size),
                thumbnailMedia = covers.cover(pickedMedia),
                mediaList = pickedMedia
            )
        }
    }

    /**
     * Trailing recency windows for [StoryCardType.HIGHLIGHTS] — emitted in
     * declaration order, which also determines the card-id window index
     * (KTD4 `7_000_000L` namespace).
     */
    private enum class HighlightWindow(val days: Int, val titleRes: Int) {
        WEEK(7, R.string.story_cards_highlights_this_week),
        MONTH(30, R.string.story_cards_highlights_this_month),
    }

    private fun mediaCountString(count: Int): String =
        context.resources.getQuantityString(R.plurals.item_count, count, count)

    /** "N years ago" (N ≥ 1) or "This year" — shared by memory + cloud-memory titles. */
    private fun yearsAgoTitle(yearsAgo: Int): String =
        if (yearsAgo > 0) {
            context.resources.getQuantityString(
                R.plurals.story_cards_years_ago, yearsAgo, yearsAgo
            )
        } else {
            context.getString(R.string.story_cards_this_year)
        }

    /**
     * Signal bundle for [StoryCardSelection.cover] — captures the KTD6
     * curation signals (favorites, category membership, `MediaMetadata`
     * relevance flags) once per card-build pass.
     */
    private data class CoverContext(
        val favoriteIds: Set<Long>,
        val categorizedIds: Set<Long>,
        val metadataById: Map<Long, MediaMetadata>,
    ) {
        /** Maps a media item to its KTD6 signal bundle (shared by cover and highlights). */
        fun candidate(m: Media.UriMedia) = StoryCardSelection.Candidate(
            id = m.id,
            timestampSec = m.definedTimestamp,
            isFavorite = m.favorite == 1 || m.id in favoriteIds,
            isCategorized = m.id in categorizedIds,
            isFlagged = metadataById[m.id]?.isRelevant == true,
            isScreenshot = StoryCardSelection.isScreenshotLike(
                m.label, m.path, m.relativePath
            ),
            isPhoto = !m.mimeType.startsWith("video/"),
        )

        fun cover(list: List<Media.UriMedia>): Media.UriMedia? =
            StoryCardSelection.cover(list) { m -> candidate(m) }
    }

    private companion object {
        /**
         * Cover context for remote (cloud-provider) media: local favorites,
         * categories and metadata never apply, but the media's own
         * `favorite` flag and screenshot/photo heuristics still do.
         */
        val REMOTE_COVERS = CoverContext(emptySet(), emptySet(), emptyMap())

        /**
         * Category pool fetch size — must exceed the maximum configurable
         * cap (slider range 1–10) so rotation has eligible cards to pick
         * from and caps above the old fetch limit of 5 are honored.
         */
        const val CATEGORY_POOL_LIMIT = 20

        /** Stride mixing the day-of-epoch seed with the card-type ordinal (KTD3). */
        const val SEED_TYPE_STRIDE = 31L

        /** Card-id namespace for MEMORIES cards (KTD4): base + year. */
        const val MEMORY_ID_BASE = 1_000_000L

        /** Card-id namespace for ALBUMS cards (KTD4): base + album id. */
        const val ALBUM_ID_BASE = 2_000_000L

        /** Card-id namespace for CATEGORIES cards (KTD4): base + category id. */
        const val CATEGORY_ID_BASE = 3_000_000L

        /** Card-id namespace for the single FAVORITES card (KTD4). */
        const val FAVORITES_ID = 4_000_000L

        /** Card-id namespace for LOCATIONS cards (KTD4): base + masked title hash. */
        const val LOCATION_ID_BASE = 5_000_000L

        /** Card-id namespace for CLOUD_MEMORIES cards (KTD4): base + year*100 + index. */
        const val CLOUD_MEMORY_ID_BASE = 6_000_000L

        /** Card-id namespace for HIGHLIGHTS windows (KTD4): base + window index. */
        const val HIGHLIGHT_ID_BASE = 7_000_000L

        /** Minimum cluster size for a PEOPLE card (R9/AE5). */
        const val MIN_PERSON_ASSET_COUNT = 3

        /**
         * Person pool bound — the eligible pool is capped well above the
         * maximum configurable card cap (slider range 1–10) while bounding
         * the per-emission media-id lookup cost.
         */
        const val PEOPLE_POOL_LIMIT = 20

        /** Card-id namespace for PEOPLE cards (KTD4): base + wide hash of personId. */
        const val PEOPLE_ID_BASE = 8_000_000L

        /**
         * Mask applied to [stableIdHash] for person card
         * ids — 40 bits; a 20-bit mask collides across `local_<uuid>`
         * strings (KTD4).
         */
        const val PERSON_ID_MASK = 0xFFFFFFFFFFL
    }
}
