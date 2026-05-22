package com.myAllVideoBrowser.util.downloaders

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.myAllVideoBrowser.data.local.VideoMetadataManager
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo
import com.myAllVideoBrowser.util.AppLogger
import com.myAllVideoBrowser.util.FileUtil
import com.myAllVideoBrowser.util.SharedPrefHelper
import com.myAllVideoBrowser.util.downloaders.custom_downloader.CustomRegularDownloader
import com.myAllVideoBrowser.util.downloaders.generic_downloader.IDownloader
import com.myAllVideoBrowser.util.downloaders.super_x_downloader.SuperXDownloader
import com.myAllVideoBrowser.util.downloaders.youtubedl_downloader.YoutubeDlDownloader
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import com.myAllVideoBrowser.util.ContextUtils
import com.myAllVideoBrowser.util.Memory
import java.util.concurrent.TimeUnit

@Singleton
class SystemDownloadManager @Inject constructor(
    private val sharedPrefHelper: SharedPrefHelper,
    private val fileUtil: FileUtil
) : IDownloader {

    companion object {
        const val PENDING_EXT = ".PENDING"
        const val DOWNLOADING_EXT = ".DOWNLOADING"
    }

    private fun getDownloader(videoInfo: VideoInfo): IDownloader {
        return when {
            videoInfo.isRegularDownload -> CustomRegularDownloader
            videoInfo.isDetectedBySuperX -> SuperXDownloader
            else -> YoutubeDlDownloader
        }
    }

    override fun startDownload(context: Context, videoInfo: VideoInfo) {
        AppLogger.d("SystemDownloadManager: startDownload ${videoInfo.id}")
        VideoMetadataManager.saveVideoInfo(videoInfo.id, videoInfo)
        createFlagFile(videoInfo.id, PENDING_EXT)
        checkQueue(context)
    }

    override fun pauseDownload(context: Context, videoInfo: VideoInfo) {
        AppLogger.d("SystemDownloadManager: pauseDownload ${videoInfo.id}")
        getDownloader(videoInfo).pauseDownload(context, videoInfo)
        removeFlagFiles(videoInfo.id)
        checkQueue(context)
    }

    override fun resumeDownload(context: Context, videoInfo: VideoInfo) {
        AppLogger.d("SystemDownloadManager: resumeDownload ${videoInfo.id}")
        startDownload(context, videoInfo)
    }

    override fun cancelDownload(context: Context, videoInfo: VideoInfo, removeFile: Boolean) {
        AppLogger.d("SystemDownloadManager: cancelDownload ${videoInfo.id}")
        getDownloader(videoInfo).cancelDownload(context, videoInfo, removeFile)
        VideoMetadataManager.deleteVideoInfo(videoInfo.id)
        removeFlagFiles(videoInfo.id)
        checkQueue(context)
    }

    override fun stopAndSaveDownload(context: Context, videoInfo: VideoInfo) {
        AppLogger.d("SystemDownloadManager: stopAndSave ${videoInfo.id}")
        getDownloader(videoInfo).stopAndSaveDownload(context, videoInfo)
        VideoMetadataManager.deleteVideoInfo(videoInfo.id)
        removeFlagFiles(videoInfo.id)
        checkQueue(context)
    }

    override fun isWorkScheduled(context: Context, workId: String): Boolean {
        return getFlagFile(workId, DOWNLOADING_EXT)?.exists() == true ||
                getFlagFile(workId, PENDING_EXT)?.exists() == true
    }

    @Synchronized
    fun checkQueue(context: Context) {
        val tmpDir = fileUtil.tmpDir
        if (!tmpDir.exists()) tmpDir.mkdirs()

        val downloadingFiles =
            tmpDir.listFiles { _, name -> name.endsWith(DOWNLOADING_EXT) } ?: emptyArray()
        val maxSimultaneous = sharedPrefHelper.getMaxSimultaneousDownloads()

        AppLogger.d("SystemDownloadManager: checkQueue. Active: ${downloadingFiles.size}, Max: $maxSimultaneous")

        if (downloadingFiles.size < maxSimultaneous) {
            if (Memory.isMemoryCritical(context)) {
                AppLogger.w("SystemDownloadManager: Memory is critical. Postponing queue.")
                return
            }

            val pendingFiles =
                tmpDir.listFiles { _, name -> name.endsWith(PENDING_EXT) } ?: emptyArray()
            pendingFiles.sortBy { it.lastModified() }

            val slotsAvailable = maxSimultaneous - downloadingFiles.size
            for (i in 0 until minOf(slotsAvailable, pendingFiles.size)) {
                val pendingFile = pendingFiles[i]
                val taskId = pendingFile.name.removeSuffix(PENDING_EXT)
                val videoInfo = VideoMetadataManager.getVideoInfo(taskId)

                if (videoInfo != null) {
                    val downloadingFile = File(tmpDir, taskId + DOWNLOADING_EXT)
                    if (pendingFile.renameTo(downloadingFile)) {
                        AppLogger.d("SystemDownloadManager: Starting queued task $taskId $downloadingFile")
                        getDownloader(videoInfo).startDownload(context, videoInfo)
                    }
                } else {
                    AppLogger.w("SystemDownloadManager: VideoInfo not found for taskId $taskId")
                    pendingFile.delete()
                }
            }
        }
    }

    fun onTaskFinished(taskId: String, isSuccess: Boolean) {
        val context = ContextUtils.getApplicationContext()

        AppLogger.d("SystemDownloadManager: task finished $taskId")
        if (isSuccess) {
            VideoMetadataManager.deleteVideoInfo(taskId)
        }
        removeFlagFiles(taskId)
        val queueRequest = OneTimeWorkRequestBuilder<QueueWorker>()
            .setInitialDelay(500, TimeUnit.MILLISECONDS)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "SYSTEM_DOWNLOAD_QUEUE_CHECK",
            ExistingWorkPolicy.REPLACE,
            queueRequest
        )
    }

    private fun getFlagFile(taskId: String, extension: String): File? {
        return try {
            File(fileUtil.tmpDir, taskId + extension)
        } catch (e: Throwable) {
            AppLogger.e("$e.message")
            null
        }
    }

    private fun removeFlagFiles(taskId: String) {
        try {
            File(fileUtil.tmpDir, taskId + PENDING_EXT).delete()
        } catch (e: Throwable) {
            AppLogger.e("$e.message")
        }
        try {
            File(fileUtil.tmpDir, taskId + DOWNLOADING_EXT).delete()
        } catch (e: Throwable) {
            AppLogger.e("$e.message")
        }
    }


    private fun createFlagFile(taskId: String, extension: String) {
        try {
            removeFlagFiles(taskId)
            val file = File(fileUtil.tmpDir, taskId + extension)
            file.createNewFile()
        } catch (e: Exception) {
            AppLogger.e("Error creating flag file: ${e.message}")
        }
    }
}
