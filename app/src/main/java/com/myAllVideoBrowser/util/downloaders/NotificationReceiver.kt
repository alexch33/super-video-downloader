package com.myAllVideoBrowser.util.downloaders

import android.content.Context
import android.content.Intent
import com.myAllVideoBrowser.data.local.VideoMetadataManager
import com.myAllVideoBrowser.data.local.room.entity.ProgressInfo
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo
import com.myAllVideoBrowser.data.repository.ProgressRepository
import com.myAllVideoBrowser.util.AppLogger
import com.myAllVideoBrowser.util.downloaders.custom_downloader.CustomRegularDownloader
import com.myAllVideoBrowser.util.downloaders.super_x_downloader.SuperXDownloader
import com.myAllVideoBrowser.util.downloaders.youtubedl_downloader.YoutubeDlDownloader
import dagger.android.DaggerBroadcastReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

class NotificationReceiver : DaggerBroadcastReceiver() {
    @Inject
    lateinit var progressRepository: ProgressRepository

    @Inject
    lateinit var systemDownloadManager: SystemDownloadManager

    private val receiverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)

        val taskId = intent.extras?.getString(TASK_ID)
        if (taskId.isNullOrEmpty()) {
            AppLogger.e("NotificationReceiver: taskId is null or empty")
            return
        }
        receiverScope.launch {
            val progressInfo = progressRepository.getProgressInfos().blockingFirst()
                .firstOrNull { it.id == taskId }

            AppLogger.d("-----------------------------------   $taskId  $progressInfo")

            if (progressInfo == null) {
                return@launch
            }

            val videoInfo = VideoMetadataManager.getVideoInfo(progressInfo.id) ?: return@launch

            when (intent.action) {
                ACTION_PAUSE -> {
                    handlePause(context, videoInfo)
                }

                ACTION_RESUME -> {
                    handleResume(context, videoInfo)
                }

                ACTION_CANCEL -> {
                    handleCancel(context, progressInfo, videoInfo)
                }

                ACTION_STOP_AND_SAVE -> {
                    handleStopAndSave(context, progressInfo, videoInfo)
                }

                else -> {
                    AppLogger.d("ACTION NOT SUPPORTED ${intent.action}")
                }
            }
        }
    }

    private fun handleStopAndSave(context: Context, task: ProgressInfo, videoInfo: VideoInfo) {
        AppLogger.d("HANDLE STOP AND SAVE $task")
        systemDownloadManager.stopAndSaveDownload(context, videoInfo)
    }

    private fun handleCancel(context: Context, task: ProgressInfo, videoInfo: VideoInfo) {
        AppLogger.d("HANDLE CANCEL $task")
        progressRepository.deleteProgressInfo(task)
        systemDownloadManager.cancelDownload(context, videoInfo, true)
    }

    private fun handleResume(context: Context, task: VideoInfo) {
        AppLogger.d("HANDLE RESUME $task")
        systemDownloadManager.resumeDownload(context, task)
    }

    private fun handlePause(context: Context, task: VideoInfo) {
        AppLogger.d("HANDLE PAUSE $task")
        systemDownloadManager.pauseDownload(context, task)
    }

    companion object {
        const val TASK_ID = "TASK_ID"
        const val ACTION_PAUSE = "ACTION_PAUSE"
        const val ACTION_RESUME = "ACTION_RESUME"
        const val ACTION_CANCEL = "ACTION_CANCEL"
        const val ACTION_STOP_AND_SAVE = "ACTION_STOP_AND_SAVE"
    }
}
