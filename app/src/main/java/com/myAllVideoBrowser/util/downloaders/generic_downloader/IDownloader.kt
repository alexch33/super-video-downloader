package com.myAllVideoBrowser.util.downloaders.generic_downloader

import android.content.Context
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo

interface IDownloader {
    fun startDownload(context: Context, videoInfo: VideoInfo)
    fun pauseDownload(context: Context, videoInfo: VideoInfo)
    fun resumeDownload(context: Context, videoInfo: VideoInfo)
    fun cancelDownload(context: Context, videoInfo: VideoInfo, removeFile: Boolean)
    fun stopAndSaveDownload(context: Context, videoInfo: VideoInfo)
    fun isWorkScheduled(context: Context, workId: String): Boolean
}
