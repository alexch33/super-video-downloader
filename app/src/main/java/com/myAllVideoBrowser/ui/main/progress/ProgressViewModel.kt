package com.myAllVideoBrowser.ui.main.progress

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.databinding.ObservableField
import androidx.lifecycle.viewModelScope
import com.myAllVideoBrowser.data.local.VideoMetadataManager
import com.myAllVideoBrowser.data.local.room.entity.ProgressInfo
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo
import com.myAllVideoBrowser.data.repository.ProgressRepository
import com.myAllVideoBrowser.ui.main.base.BaseViewModel
import com.myAllVideoBrowser.util.ContextUtils
import com.myAllVideoBrowser.util.downloaders.SystemDownloadManager
import com.myAllVideoBrowser.util.downloaders.generic_downloader.models.VideoTaskState
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.disposables.CompositeDisposable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.rx3.asFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class ProgressViewModel @Inject constructor(
    private val progressRepository: ProgressRepository,
    private val systemDownloadManager: SystemDownloadManager
) : BaseViewModel() {
    @VisibleForTesting
    internal val compositeDisposable: CompositeDisposable = CompositeDisposable()

    var progressInfos: ObservableField<List<ProgressInfo>> = ObservableField(emptyList())
    private val progressExecutor = Executors.newFixedThreadPool(3).asCoroutineDispatcher()
    private val singleThreadExecutor = Executors.newFixedThreadPool(1).asCoroutineDispatcher()

    override fun start() {
        downloadProgressStartListen()
    }

    override fun stop() {
        compositeDisposable.clear()
    }

    fun stopAndSaveDownload(id: Long) {
        viewModelScope.launch(singleThreadExecutor) {
            val inf = progressInfos.get()?.find { it.downloadId == id }
            inf?.let { info ->
                val videoInfo = VideoMetadataManager.getVideoInfo(info.id)
                videoInfo?.let { video ->
                    val updated = info.copy(downloadStatus = VideoTaskState.PREPARE)
                    saveProgressInfo(updated)
                    val ctx = ContextUtils.getApplicationContext()
                    systemDownloadManager.stopAndSaveDownload(ctx, video)
                }
            }
        }
    }

    fun cancelDownload(id: Long, removeFile: Boolean) {
        viewModelScope.launch(singleThreadExecutor) {
            val inf = progressInfos.get()?.find { it.downloadId == id }
            inf?.let { progressInfo ->
                val videoInfo = VideoMetadataManager.getVideoInfo(progressInfo.id)

                deleteProgressInfo(progressInfo)

                videoInfo?.let { vInfo ->
                    val ctx = ContextUtils.getApplicationContext()
                    systemDownloadManager.cancelDownload(ctx, vInfo, removeFile)
                }

                val newList = progressInfos.get()?.filter { it.id != progressInfo.id }
                progressInfos.set(newList?.sortedBy { it.id })
            }
        }
    }

    fun pauseDownload(id: Long) {
        viewModelScope.launch(singleThreadExecutor) {
            val inf = progressInfos.get()?.find { it.downloadId == id }
            inf?.let { info ->
                val videoInfo = VideoMetadataManager.getVideoInfo(info.id)
                videoInfo?.let { video ->
                    val updated = info.copy(downloadStatus = VideoTaskState.PAUSE)
                    saveProgressInfo(updated)
                    val ctx = ContextUtils.getApplicationContext()
                    systemDownloadManager.pauseDownload(ctx, video)
                }
            }
        }
    }

    fun resumeDownload(id: Long) {
        viewModelScope.launch(singleThreadExecutor) {
            val inf = progressInfos.get()?.find { it.downloadId == id }
            inf?.let { info ->
                val videoInfo = VideoMetadataManager.getVideoInfo(info.id)
                videoInfo?.let { video ->
                    val updated = info.copy(downloadStatus = VideoTaskState.PREPARE)
                    saveProgressInfo(updated)
                    val ctx = ContextUtils.getApplicationContext()
                    systemDownloadManager.resumeDownload(ctx, video)
                }
            }
        }
    }

    fun downloadVideo(context: Context?, videoInfo: VideoInfo?) {
        viewModelScope.launch(singleThreadExecutor) {
            videoInfo?.let {
                val progressInfo = ProgressInfo(
                    id = it.id,
                    downloadId = it.id.hashCode().toLong(),
                    title = it.title,
                    thumbnail = it.thumbnail,
                    isM3u8 = it.isM3u8,
                    isRegularDownload = it.isRegularDownload,
                    isDetectedBySuperX = it.isDetectedBySuperX,
                    downloadStatus = VideoTaskState.ENQUEUE,
                    infoLine = "ENQUEUED"
                )

                saveProgressInfo(progressInfo)

                val ctx = context ?: ContextUtils.getApplicationContext()
                systemDownloadManager.startDownload(ctx, it)
            }
        }
    }

    private suspend fun saveProgressInfo(progressInfo: ProgressInfo) {
        withContext(Dispatchers.IO) {
            progressRepository.saveProgressInfo(progressInfo)
        }
    }

    private suspend fun deleteProgressInfo(progressInfo: ProgressInfo) {
        withContext(Dispatchers.IO) {
            progressRepository.deleteProgressInfo(progressInfo)
        }
    }

    @VisibleForTesting
    internal fun downloadProgressStartListen() {
        viewModelScope.launch(progressExecutor) {
            try {
                progressObservable().asFlow().collect { progressInfoList ->
                    progressInfos.set(progressInfoList.sortedBy { it.id })
                }
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }
    }

    private fun progressObservable(): Observable<List<ProgressInfo>> {
        return progressRepository.getProgressInfos()
            .throttleLast(1000, TimeUnit.MILLISECONDS)
            .map { it.filter { info -> info.downloadStatus != VideoTaskState.SUCCESS } }
            .toObservable()
    }
}
