/*
 * SPDX-FileCopyrightText: 2023-2026 alzimerahmed
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.feature_node.presentation.storycards

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dot.gallery.R
import com.dot.gallery.cloud.core.MemoryInfo
import com.dot.gallery.cloud.core.ProviderRegistry
import com.dot.gallery.cloud.core.capabilities.MemoriesCapableProvider
import com.dot.gallery.core.MediaDistributor
import com.dot.gallery.core.Resource
import com.dot.gallery.core.Settings
import com.dot.gallery.feature_node.domain.model.Media
import com.dot.gallery.feature_node.domain.model.MediaMetadata
import com.dot.gallery.feature_node.domain.model.StoryCard
import com.dot.gallery.feature_node.domain.model.StoryCardType
import com.dot.gallery.feature_node.domain.model.StoryCardsConfig
import com.dot.gallery.feature_node.domain.repository.MediaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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
    @param:ApplicationContext private val context: Context,
    private val clock: Clock
) : ViewModel() {

    private val configFlow = Settings.Misc.getStoryCardsConfig(context)

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

    val storyCards: StateFlow<List<StoryCard>> = combine(
        configFlow,
        timelineMedia,
        albumsState,
        favoritesMedia,
        metadataFlow,
    ) { config, media, albums, favorites, metadata ->
        if (!config.enabled || media.isEmpty()) return@combine emptyList()

        // KTD2/R4 source scoping: excluded albums/categories/locations produce
        // no card AND contribute no media to any card's mediaList. Computed
        // once per emission so every builder resolves through the same set.
        // Locked albums compose on top: their media is already absent from
        // [media] (the main timeline drops it) but NOT from [favorites], so
        // favorites are intersected with the filtered set below — closing the
        // FAVORITES locked-media leak (AE2).
        val scopedMedia = media.withoutExcludedSources(config, metadata)
        val scopedIds = scopedMedia.mapTo(HashSet()) { it.id }

        val covers = CoverContext(
            favoriteIds = favorites.mapTo(HashSet()) { it.id },
            categorizedIds = repository.getAllClassifiedMediaIds().toSet(),
            metadataById = metadata.associateBy { it.mediaId },
        )

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
                val city = meta.gpsLocationNameCity ?: continue
                val country = meta.gpsLocationNameCountry ?: continue
                if ("$city, $country" in config.excludedLocationKeys) {
                    excludedMediaIds += meta.mediaId
                }
            }
        }
        for (categoryId in config.excludedCategoryIds) {
            excludedMediaIds += repository.getMediaIdsInCategoryAsync(categoryId)
        }
        return filter { it.albumID !in config.excludedAlbumIds && it.id !in excludedMediaIds }
    }

    private val _cloudMemoryCards = MutableStateFlow<List<StoryCard>>(emptyList())

    init {
        loadCloudMemories()
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
                id = 6_000_000L + memory.year.toLong() * 100 + index,
                type = StoryCardType.CLOUD_MEMORIES,
                title = if (yearsAgo > 0) "$yearsAgo ${if (yearsAgo == 1) "year" else "years"} ago"
                    else "This year",
                subtitle = if (memory.year > 0) "${memory.year}" else null,
                thumbnailMedia = REMOTE_COVERS.cover(memory.media),
                mediaList = memory.media,
                year = memory.year
            )
        }
    }

    val categoryCards: StateFlow<List<StoryCard>> = combine(
        configFlow,
        topCategories,
        timelineMedia,
        favoritesMedia,
        metadataFlow
    ) { config, categories, media, favorites, metadata ->
        if (!config.enabled || StoryCardType.CATEGORIES in config.disabledTypes) {
            return@combine emptyList()
        }
        // KTD2: the same exclusion filter gates both card eligibility (below)
        // and member media (the lookup map resolves through the filtered set).
        val mediaMap = media.withoutExcludedSources(config, metadata).associateBy { it.id }
        // Members are uniformly categorized, so the categorized signal carries
        // no weight here — favorite/flagged/photo/screenshot still discriminate.
        val covers = CoverContext(
            favoriteIds = favorites.mapTo(HashSet()) { it.id },
            categorizedIds = emptySet(),
            metadataById = metadata.associateBy { it.mediaId },
        )
        categories.mapNotNull { cat ->
            if (cat.id in config.excludedCategoryIds) return@mapNotNull null
            val mediaIds = repository.getMediaIdsInCategoryAsync(cat.id)
            val categoryMedia = mediaIds.mapNotNull { mediaMap[it] }
                .sortedByDescending { it.definedTimestamp }
            if (categoryMedia.isEmpty()) return@mapNotNull null
            StoryCard(
                id = 3_000_000L + cat.id,
                type = StoryCardType.CATEGORIES,
                title = cat.name,
                subtitle = mediaCountString(cat.mediaCount),
                thumbnailMedia = covers.cover(categoryMedia),
                mediaList = categoryMedia.take(20),
                categoryId = cat.id
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val allCards: StateFlow<List<StoryCard>?> = combine(
        configFlow,
        storyCards,
        categoryCards,
        _cloudMemoryCards,
        timelineMedia
    ) { config, cards, catCards, cloudCards, media ->
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
                StoryCardType.CLOUD_MEMORIES -> cloudCards
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
        val today = Calendar.getInstance()
        val todayMonth = today.get(Calendar.MONTH)
        val todayDay = today.get(Calendar.DAY_OF_MONTH)
        val currentYear = today.get(Calendar.YEAR)

        val cal = Calendar.getInstance()

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

                val mediaCal = Calendar.getInstance().apply {
                    set(Calendar.YEAR, currentYear)
                    set(Calendar.MONTH, month)
                    set(Calendar.DAY_OF_MONTH, day)
                }
                val todayCal = Calendar.getInstance().apply {
                    set(Calendar.YEAR, currentYear)
                    set(Calendar.MONTH, todayMonth)
                    set(Calendar.DAY_OF_MONTH, todayDay)
                }
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
                id = 1_000_000L + year.toLong(),
                type = StoryCardType.MEMORIES,
                title = "$yearsAgo ${if (yearsAgo == 1) "year" else "years"} ago",
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
                id = 2_000_000L + album.id,
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
            id = 4_000_000L,
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
        // Group metadata entries by "city, country", collecting all matching media
        val locationGroups = LinkedHashMap<String, MutableList<Media.UriMedia>>()
        for (meta in metadata) {
            if (meta.gpsLocationNameCity == null || meta.gpsLocationNameCountry == null) continue
            val m = mediaById[meta.mediaId] ?: continue
            val key = "${meta.gpsLocationNameCity}, ${meta.gpsLocationNameCountry}"
            locationGroups.getOrPut(key) { mutableListOf() }.add(m)
        }
        // Sort groups by count descending — the full pool is emitted; the
        // per-type cap and rotation are applied at the allCards slot fill.
        return locationGroups.entries
            .sortedByDescending { it.value.size }
            .mapNotNull { (location, locationMedia) ->
                val sorted = locationMedia.sortedByDescending { it.definedTimestamp }
                val city = location.substringBefore(",").trim()
                val country = location.substringAfterLast(", ").trim()
                StoryCard(
                    id = 5_000_000L + (location.hashCode().toLong() and 0xFFFFFFL),
                    type = StoryCardType.LOCATIONS,
                    title = location,
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
        val candidates = media.map { covers.candidate(it) }
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
        context.resources.getQuantityString(R.plurals.story_cards_media_count, count, count)

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

        /** Card-id namespace for HIGHLIGHTS windows (KTD4): base + window index. */
        const val HIGHLIGHT_ID_BASE = 7_000_000L
    }
}
