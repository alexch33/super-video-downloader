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
import com.myAllVideoBrowser.util.FileUtil
import com.myAllVideoBrowser.util.downloaders.generic_downloader.models.VideoTaskState
import com.myAllVideoBrowser.util.downloaders.custom_downloader.CustomRegularDownloader
import com.myAllVideoBrowser.util.downloaders.super_x_downloader.SuperXDownloader
import com.myAllVideoBrowser.util.downloaders.youtubedl_downloader.YoutubeDlDownloader
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
                    if (video.isRegularDownload) {
                        CustomRegularDownloader.stopAndSaveDownload(
                            ContextUtils.getApplicationContext(),
                            video
                        )
                    } else {
                        val updated = info.copy(downloadStatus = VideoTaskState.PREPARE)
                        saveProgressInfo(updated)
                        if (video.isDetectedBySuperX) {
                            SuperXDownloader.stopAndSaveDownload(
                                ContextUtils.getApplicationContext(),
                                video
                            )
                        } else {
                            YoutubeDlDownloader.stopAndSaveDownload(
                                ContextUtils.getApplicationContext(),
                                video
                            )
                        }
                    }
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
                    if (vInfo.isRegularDownload) {
                        CustomRegularDownloader.cancelDownload(
                            ContextUtils.getApplicationContext(),
                            vInfo,
                            removeFile
                        )
                    } else {
                        if (vInfo.isDetectedBySuperX) {
                            SuperXDownloader.cancelDownload(
                                ContextUtils.getApplicationContext(),
                                vInfo,
                                removeFile
                            )
                        } else {
                            YoutubeDlDownloader.cancelDownload(
                                ContextUtils.getApplicationContext(),
                                vInfo,
                                removeFile
                            )
                        }
                    }
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
                    if (video.isRegularDownload) {
                        CustomRegularDownloader.pauseDownload(
                            ContextUtils.getApplicationContext(),
                            video
                        )
                    } else {
                        val updated = info.copy(downloadStatus = VideoTaskState.PREPARE)
                        saveProgressInfo(updated)
                        if (video.isDetectedBySuperX) {
                            SuperXDownloader.pauseDownload(
                                ContextUtils.getApplicationContext(),
                                video
                            )
                        } else {
                            YoutubeDlDownloader.pauseDownload(
                                ContextUtils.getApplicationContext(),
                                video
                            )
                        }
                    }
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
                    if (video.isRegularDownload) {
                        CustomRegularDownloader.resumeDownload(
                            ContextUtils.getApplicationContext(),
                            video
                        )
                    } else {
                        val updated = info.copy(downloadStatus = VideoTaskState.PREPARE)
                        saveProgressInfo(updated)
                        if (video.isDetectedBySuperX) {
                            SuperXDownloader.resumeDownload(
                                ContextUtils.getApplicationContext(),
                                video
                            )
                        } else {
                            YoutubeDlDownloader.resumeDownload(
                                ContextUtils.getApplicationContext(),
                                video
                            )
                        }
                    }
                }
            }
        }
    }

    fun downloadVideo(context: Context?, videoInfo: VideoInfo?) {
        viewModelScope.launch(singleThreadExecutor) {
            videoInfo?.let {
                VideoMetadataManager.saveVideoInfo(it.id, it)

                val progressInfo = ProgressInfo(
                    id = it.id,
                    downloadId = it.id.hashCode().toLong(),
                    title = it.title,
                    thumbnail = it.thumbnail,
                    isM3u8 = it.isM3u8,
                    isRegularDownload = it.isRegularDownload,
                    isDetectedBySuperX = it.isDetectedBySuperX
                )

                saveProgressInfo(progressInfo)

                val ctx = context ?: ContextUtils.getApplicationContext()
                if (it.isRegularDownload) {
                    CustomRegularDownloader.startDownload(ctx, it)
                } else if (it.isDetectedBySuperX) {
                    SuperXDownloader.startDownload(ctx, it)
                } else {
                    YoutubeDlDownloader.startDownload(ctx, it)
                }
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
            VideoMetadataManager.deleteVideoInfo(progressInfo.id)
        }
    }

    @VisibleForTesting
    internal fun downloadProgressStartListen() {
        viewModelScope.launch(progressExecutor) {
            try {
                progressObservable().asFlow().collect { progressInfoList ->
                    progressInfos.set(progressInfoList.sortedBy { it.id })
                }
            } catch (e: Exception) {
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
