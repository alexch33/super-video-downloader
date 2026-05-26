package com.myAllVideoBrowser.util.downloaders.youtubedl_downloader

import android.content.Context
import android.util.Base64
import androidx.work.*
import com.google.gson.Gson
import com.myAllVideoBrowser.data.local.room.entity.ProgressInfo
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo
import com.myAllVideoBrowser.util.AppLogger
import com.myAllVideoBrowser.util.ContextUtils
import com.myAllVideoBrowser.util.downloaders.generic_downloader.GenericDownloader
import java.util.concurrent.TimeUnit


object YoutubeDlDownloader : GenericDownloader() {

    override fun stopAndSaveDownload(context: Context, videoInfo: VideoInfo) {
        val downloadWork = getWorkRequest(videoInfo.id)
        val downloaderData = getDownloadDataFromVideoInfo(videoInfo)
        downloaderData.putString(Constants.ACTION_KEY, DownloaderActions.STOP_SAVE_ACTION)
        downloadWork.setInputData(downloaderData.build())

        runWorkerTask(
            context, videoInfo, downloadWork.build()
        )
    }

    override fun getDownloadDataFromVideoInfo(
        videoInfo: VideoInfo
    ): Data.Builder {
        val videoUrl: String = if (videoInfo.downloadUrls.isNotEmpty()) {
            videoInfo.originalUrl
        } else {
            videoInfo.formats.formats.firstOrNull()?.url.toString()
        }

        val data = Data.Builder()

        data.putString(Constants.URL_KEY, videoUrl)
        data.putString(Constants.TITLE_KEY, videoInfo.title)
        data.putString(Constants.FILENAME_KEY, videoInfo.name)

        data.putString(Constants.ORIGIN_KEY, videoInfo.originalUrl)
        data.putString(Constants.TASK_ID_KEY, videoInfo.id)
        data.putBoolean(Constants.IS_AUDIO_ONLY_EXTRACT, videoInfo.isAudioOnlyExtract)
        data.putBoolean(Constants.IS_LIVE, videoInfo.isLive)

        if (videoInfo.formats.formats.firstOrNull() != null && videoInfo.formats.formats.isNotEmpty()) {
            val stringifiedFormatEntity =
                Gson().toJson(videoInfo.formats.formats.firstOrNull()).toString()
            val encodedEntity =
                Base64.encodeToString(stringifiedFormatEntity.toByteArray(), Base64.DEFAULT)

            val compressedEntity = compressString(encodedEntity)
            AppLogger.d("superZip ${compressedEntity.toByteArray().size}  ---- ${encodedEntity.toByteArray().size}")

            saveStringToSharedPreferences(
                ContextUtils.getApplicationContext(), videoInfo.id, compressedEntity
            )
        }

        return data
    }

    override fun getWorkRequest(id: String): OneTimeWorkRequest.Builder {
        return OneTimeWorkRequest.Builder(YoutubeDlDownloaderWorker::class.java).addTag(id)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
    }
}
