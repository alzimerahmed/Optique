/*
 * SPDX-FileCopyrightText: 2023-2026 alzimerahmed
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.feature_node.presentation.settings.subsettings

import android.content.Context
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import androidx.work.WorkManager
import com.dot.gallery.R
import com.dot.gallery.cloud.core.ProviderType
import com.dot.gallery.cloud.data.dao.DetectedFaceDao
import com.dot.gallery.cloud.data.dao.PersonDao
import com.dot.gallery.core.Settings
import com.dot.gallery.core.ml.DownloadInfo
import com.dot.gallery.core.ml.ModelFileInfo
import com.dot.gallery.core.ml.ModelGroup
import com.dot.gallery.core.ml.ModelManager
import com.dot.gallery.core.ml.ModelStatus
import com.dot.gallery.core.workers.cancelModelDownload
import com.dot.gallery.core.workers.downloadModels
import com.dot.gallery.core.smart.SmartScanPlan
import com.dot.gallery.core.smart.SmartScanScheduler
import com.dot.gallery.feature_node.data.data_source.InternalDatabase
import com.dot.gallery.feature_node.data.data_source.MediaFeature
import com.dot.gallery.feature_node.data.data_source.SmartScanDao
import com.dot.gallery.feature_node.data.data_source.SmartScanFeature
import com.dot.gallery.feature_node.data.data_source.SmartScanPhaseEntity
import com.dot.gallery.feature_node.data.data_source.SmartScanRunEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

internal enum class ModelManagementAction(val enabled: Boolean) {
    DELETE(true),
    CANCEL_DOWNLOAD(true),
    DOWNLOAD(true),
    COPYING(false),
    INSTALLED_OFFLINE(false),
    UNAVAILABLE_OFFLINE(false),
}

internal fun resolveModelManagementAction(
    status: ModelStatus,
    hasInternetPermission: Boolean,
): ModelManagementAction = when (status) {
    ModelStatus.COPYING -> ModelManagementAction.COPYING
    ModelStatus.READY -> if (hasInternetPermission) {
        ModelManagementAction.DELETE
    } else {
        ModelManagementAction.INSTALLED_OFFLINE
    }
    ModelStatus.DOWNLOADING -> if (hasInternetPermission) {
        ModelManagementAction.CANCEL_DOWNLOAD
    } else {
        ModelManagementAction.UNAVAILABLE_OFFLINE
    }
    ModelStatus.ERROR, ModelStatus.NOT_INSTALLED -> if (hasInternetPermission) {
        ModelManagementAction.DOWNLOAD
    } else {
        ModelManagementAction.UNAVAILABLE_OFFLINE
    }
}

/**
 * Whether the "Delete all face data" control is enabled. Only when no SmartScan run
 * exists at all — queued or running (KTD4). The caller must feed this from the
 * *unfiltered* `SmartScanDao.observeActiveRun()`/`getActiveRun()` feed: the
 * `shouldShowRun`-filtered [SmartFeaturesViewModel.activeSmartScan] reports null
 * for queued automatic runs, which would let a purge race indexing.
 */
internal fun isFaceDataPurgeEnabled(hasActiveRun: Boolean): Boolean = !hasActiveRun

@HiltViewModel
class SmartFeaturesViewModel @Inject constructor(
    private val modelManager: ModelManager,
    private val workManager: WorkManager,
    private val smartScanScheduler: SmartScanScheduler,
    private val smartScanDao: SmartScanDao,
    private val personDao: PersonDao,
    private val detectedFaceDao: DetectedFaceDao,
    private val database: InternalDatabase,
    @param:ApplicationContext private val context: Context
) : ViewModel() {

    // Per-group observable state. UI screens pass the relevant [ModelGroup] (SEARCH for smart
    // search + categories, CUTOUT for subject cutout) so each feature is managed independently.
    fun modelStatus(group: ModelGroup): StateFlow<ModelStatus> = modelManager.status(group)
    fun downloadProgress(group: ModelGroup): StateFlow<Float> = modelManager.downloadProgress(group)
    fun errorMessage(group: ModelGroup): StateFlow<String?> = modelManager.errorMessage(group)
    fun downloadInfo(group: ModelGroup): StateFlow<DownloadInfo> = modelManager.downloadInfo(group)

    fun installedSize(group: ModelGroup): Long = modelManager.getInstalledSize(group)

    suspend fun getFileInfos(group: ModelGroup): List<ModelFileInfo> = modelManager.getFileInfos(group)

    val hasInternetPermission: Boolean get() = modelManager.hasInternetPermission
    val areAiFeaturesAvailable: Boolean get() = modelManager.areAiFeaturesAvailable

    val includeIgnoredAlbums: StateFlow<Boolean> = Settings.SmartFeatures.includeIgnoredAlbums(context).stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = false
    )

    val semanticIndexingEnabled: StateFlow<Boolean> = Settings.SmartFeatures.semanticIndexing(context).stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = true
    )

    val activeSmartScan: StateFlow<SmartScanRunEntity?> = smartScanDao.observeActiveRun()
        .map { run -> run?.takeIf { SmartScanPlan.shouldShowRun(it.userVisible, it.totalMedia) } }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    val latestSmartScan: StateFlow<SmartScanRunEntity?> = smartScanDao.observeLatestRun().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = null
    )

    /**
     * Unfiltered active-run feed for the face-data purge gate (KTD4). Unlike
     * [activeSmartScan] this does NOT apply [SmartScanPlan.shouldShowRun], so queued
     * automatic runs still block the delete-all control.
     */
    val unfilteredActiveSmartScan: StateFlow<SmartScanRunEntity?> = smartScanDao.observeActiveRun()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    private val _isPurgingFaceData = MutableStateFlow(false)
    /** True while [deleteAllFaceData] is in flight — the settings row disables itself. */
    val isPurgingFaceData: StateFlow<Boolean> = _isPurgingFaceData.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val activeSmartScanPhases: StateFlow<List<SmartScanPhaseEntity>> = activeSmartScan
        .flatMapLatest { run ->
            if (run == null) flowOf(emptyList()) else smartScanDao.observePhases(run.runId)
        }
        .map { phases ->
            phases.sortedBy { SmartScanPlan.orderedPhases.indexOf(it.phase) }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    fun downloadModels(group: ModelGroup) {
        if (modelManagementAction(group) != ModelManagementAction.DOWNLOAD) return
        workManager.downloadModels(group)
    }

    fun cancelDownload(group: ModelGroup) {
        if (modelManagementAction(group) != ModelManagementAction.CANCEL_DOWNLOAD) return
        workManager.cancelModelDownload(group)
        viewModelScope.launch {
            modelManager.deleteModels(group)
        }
    }

    fun deleteModels(group: ModelGroup) {
        if (modelManagementAction(group) != ModelManagementAction.DELETE) return
        viewModelScope.launch {
            modelManager.deleteModels(group)
        }
    }

    private fun modelManagementAction(group: ModelGroup): ModelManagementAction =
        resolveModelManagementAction(modelManager.status(group).value, modelManager.hasInternetPermission)

    fun setIncludeIgnoredAlbums(include: Boolean) {
        viewModelScope.launch {
            Settings.SmartFeatures.setIncludeIgnoredAlbums(context, include)
            smartScanScheduler.fullRefresh()
        }
    }

    fun setSemanticIndexingEnabled(enabled: Boolean) {
        viewModelScope.launch {
            Settings.SmartFeatures.setSemanticIndexing(context, enabled)
        }
    }

    fun refreshMetadata() = request(SmartScanFeature.METADATA.bit)

    fun refreshEmbeddings() {
        viewModelScope.launch {
            // Read the preference fresh: the StateFlow uses WhileSubscribed(5000), so its
            // .value can be stale (initialValue=true) right after a just-written toggle.
            val enabled = Settings.SmartFeatures.semanticIndexing(context).first()
            if (!enabled) return@launch
            request(SmartScanFeature.EMBEDDINGS.bit)
        }
    }

    fun refreshCategories() = request(SmartScanFeature.CATEGORIES.bit)

    fun refreshPersons() = request(SmartScanFeature.PERSONS.bit)

    fun refreshAll() {
        viewModelScope.launch { smartScanScheduler.all(userVisible = true) }
    }

    fun fullRefresh() {
        viewModelScope.launch { smartScanScheduler.fullRefresh() }
    }

    fun cancelActiveScan() {
        val runId = activeSmartScan.value?.runId ?: return
        viewModelScope.launch { smartScanScheduler.cancel(runId) }
    }

    fun retryLatestScan() {
        viewModelScope.launch { smartScanScheduler.retryFailed(latestSmartScan.value?.runId) }
    }

    /**
     * Purges all local face data (KTD4): people rows, detected faces, face clusters
     * (removed via the `people` → `face_clusters` FK cascade), stored face
     * thumbnails, and the `FACE_DETECTION` media-feature state so indexing rebuilds
     * cleanly on the next scan (R8). Pure DAO/file work — no provider or model
     * calls, so it works with face models absent (R11). Device-local only.
     */
    fun deleteAllFaceData() {
        if (_isPurgingFaceData.value) return
        viewModelScope.launch {
            // Re-check at action time (KTD4): a queued/running scan may have started
            // after the preference was rendered enabled.
            if (smartScanDao.getActiveRun() != null) return@launch
            _isPurgingFaceData.value = true
            // runCatching keeps the purge steps in one failure domain: any DAO or
            // filesystem failure surfaces the same failure Toast (U4 test contract).
            val purged = runCatching {
                database.withTransaction {
                    detectedFaceDao.deleteAll()
                    personDao.deleteByProvider(ProviderType.LOCAL_PEOPLE)
                    // The state reset stays inside the transaction: isCurrentFaceDetection
                    // treats SUCCEEDED rows with empty face headers as current, so a crash
                    // between commits would permanently block re-indexing (KTD4).
                    smartScanDao.deleteFeatureStates(MediaFeature.FACE_DETECTION)
                }
                // After the commit: wipe stored face thumbnails and verify the dir is
                // gone — a restore-visible residue must not survive the purge.
                val thumbsDir = File(context.filesDir, "face_thumbs")
                thumbsDir.deleteRecursively() && !thumbsDir.exists()
            }.getOrElse { e ->
                // Keep structured concurrency: cancellation is not a purge failure.
                if (e is CancellationException) throw e
                false
            }
            _isPurgingFaceData.value = false
            Toast.makeText(
                context,
                context.getString(
                    if (purged) {
                        R.string.hidden_people_delete_all_success
                    } else {
                        R.string.hidden_people_delete_all_failure
                    }
                ),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun request(features: Int) {
        viewModelScope.launch { smartScanScheduler.manual(features) }
    }
}
