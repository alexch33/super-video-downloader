package com.myAllVideoBrowser.util.downloaders.ffmpeg_downloader

import android.util.Base64
import androidx.work.BackoffPolicy
import androidx.work.Data
import androidx.work.OneTimeWorkRequest
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo
import com.myAllVideoBrowser.util.AppLogger
import com.myAllVideoBrowser.util.ContextUtils
import com.myAllVideoBrowser.util.downloaders.generic_downloader.GenericDownloader
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object FfmpegDownloader : GenericDownloader() {
    override fun getDownloadDataFromVideoInfo(videoInfo: VideoInfo): Data.Builder {
        val videoUrl = videoInfo.originalUrl
        val headers = videoInfo.formats.formats.firstOrNull()?.httpHeaders
        val headersMap = headers?.toMap<String, String>()?.toMutableMap() ?: mutableMapOf()

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
            "FFmpegDownloader: Zipped headers size $headersMap  ${zipHeaders.toByteArray().size} from ${headersVal.toByteArray().size}"
        )

        saveStringToSharedPreferences(
            ContextUtils.getApplicationContext(), videoInfo.id, zipHeaders
        )

        data.putString(Constants.TITLE_KEY, videoInfo.title)
        data.putString(Constants.FILENAME_KEY, fileName)
        return data
    }

    override fun getWorkRequest(id: String): OneTimeWorkRequest.Builder {
        return OneTimeWorkRequest.Builder(FfmpegDownloaderWorker::class.java)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
            .addTag(id)
    }
}
