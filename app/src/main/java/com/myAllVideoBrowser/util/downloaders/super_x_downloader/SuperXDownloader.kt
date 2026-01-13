package com.myAllVideoBrowser.util.downloaders.super_x_downloader

import android.content.Context
import android.util.Base64
import androidx.work.BackoffPolicy
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import com.myAllVideoBrowser.data.local.room.entity.ProgressInfo
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo
import com.myAllVideoBrowser.util.AppLogger
import com.myAllVideoBrowser.util.ContextUtils
import com.myAllVideoBrowser.util.downloaders.generic_downloader.GenericDownloader
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object SuperXDownloader : GenericDownloader() {

    fun runWorkerTask(
        context: Context,
        info: VideoInfo,
        taskData: OneTimeWorkRequest,
        action: String
    ) {
        if (action == DownloaderActions.PAUSE) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                info.id + DownloaderActions.PAUSE, ExistingWorkPolicy.APPEND_OR_REPLACE, taskData
            )
            return
        }

        if (action == DownloaderActions.CANCEL) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                info.id + DownloaderActions.CANCEL, ExistingWorkPolicy.APPEND_OR_REPLACE, taskData
            )
            return
        }

        if (action == DownloaderActions.STOP_SAVE_ACTION) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                info.id + DownloaderActions.STOP_SAVE_ACTION,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                taskData
            )
            return
        }
    }

    fun stopAndSaveDownload(context: Context, progressInfo: ProgressInfo) {
        val downloadWork = getWorkRequest(progressInfo.videoInfo.id)
        val downloaderData =
            getDownloadDataFromVideoInfo(progressInfo.videoInfo)
        downloaderData.putString(Constants.ACTION_KEY, DownloaderActions.STOP_SAVE_ACTION)
        downloadWork.setInputData(downloaderData.build())

        runWorkerTask(
            context,
            progressInfo.videoInfo,
            downloadWork.build(),
            DownloaderActions.STOP_SAVE_ACTION
        )
    }

    override fun cancelDownload(context: Context, progressInfo: ProgressInfo, removeFile: Boolean) {
        val downloadWork = getWorkRequest(progressInfo.videoInfo.id)
        DownloaderActions
        val downloaderData =
            getDownloadDataFromVideoInfo(progressInfo.videoInfo)
        downloaderData.putString(Constants.ACTION_KEY, DownloaderActions.CANCEL)
        downloaderData.putString(Constants.IS_FILE_REMOVE_KEY, removeFile.toString())
        downloadWork.setInputData(downloaderData.build())

        runWorkerTask(
            context,
            progressInfo.videoInfo,
            downloadWork.build(), DownloaderActions.CANCEL
        )
    }

    override fun pauseDownload(context: Context, progressInfo: ProgressInfo) {
        val downloadWork = getWorkRequest(progressInfo.videoInfo.id)

        val downloaderData =
            getDownloadDataFromVideoInfo(progressInfo.videoInfo)
        downloaderData.putString(Constants.ACTION_KEY, DownloaderActions.PAUSE)
        downloadWork.setInputData(downloaderData.build())

        runWorkerTask(
            context,
            progressInfo.videoInfo,
            downloadWork.build(), DownloaderActions.PAUSE
        )
    }

    override fun resumeDownload(context: Context, progressInfo: ProgressInfo) {
        val downloadWork = getWorkRequest(progressInfo.videoInfo.id)

        val downloaderData =
            getDownloadDataFromVideoInfo(progressInfo.videoInfo)
        downloaderData.putString(Constants.ACTION_KEY, DownloaderActions.RESUME)
        downloadWork.setInputData(downloaderData.build())

        runWorkerTask(
            context,
            progressInfo.videoInfo,
            downloadWork.build()
        )
    }

    override fun getDownloadDataFromVideoInfo(videoInfo: VideoInfo): Data.Builder {
        val videoUrl = videoInfo.originalUrl
        val headers = videoInfo.formats.formats.firstOrNull()?.httpHeaders
        val headersMap = headers?.toMap()?.toMutableMap() ?: mutableMapOf()

        val fileName = videoInfo.name

        val cookie = headersMap["Cookie"]
        if (cookie != null) {
            headersMap["Cookie"] =
                Base64.encodeToString(cookie.toByteArray(), Base64.DEFAULT)
        }

        val headersForClean = (headersMap as Map<*, *>?)?.let { JSONObject(it).toString() }
        val headersVal = try {
            Base64.encodeToString(headersForClean?.toByteArray(), Base64.DEFAULT)
        } catch (_: Exception) {
            "{}"
        }
        val data = Data.Builder()
        data.putString(Constants.URL_KEY, videoUrl)
        data.putString(Constants.TASK_ID_KEY, videoInfo.id)

        val zipHeaders = compressString(headersVal)
        AppLogger.d(
            "SuperXDownloader: Zipped headers size $headersMap  ${zipHeaders.toByteArray().size} from ${headersVal.toByteArray().size}"
        )

        saveStringToSharedPreferences(
            ContextUtils.getApplicationContext(), videoInfo.id, zipHeaders
        )

        data.putLong(Constants.DURATION, videoInfo.duration)
        data.putString(Constants.TITLE_KEY, videoInfo.title)
        data.putString(Constants.FILENAME_KEY, fileName)
        data.putBoolean(Constants.IS_M3U8, videoInfo.isM3u8)
        data.putBoolean(Constants.IS_MPD, videoInfo.isMpd)
        data.putString(
            Constants.SELECTED_FORMAT_ID,
            videoInfo.formats.formats.firstOrNull()?.formatId
        )
        data.putBoolean(Constants.IS_LIVE, videoInfo.isLive)
        data.putString(Constants.VIDEO_CODEC, videoInfo.formats.formats.firstOrNull()?.vcodec)
        return data
    }

    override fun getWorkRequest(id: String): OneTimeWorkRequest.Builder {
        return OneTimeWorkRequest.Builder(SuperXDownloaderWorker::class.java)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
            .addTag(id)
    }
}
