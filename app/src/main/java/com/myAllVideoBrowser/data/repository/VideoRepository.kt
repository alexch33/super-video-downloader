package com.myAllVideoBrowser.data.repository

import androidx.annotation.VisibleForTesting
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo
import com.myAllVideoBrowser.di.qualifier.RemoteData
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

interface VideoRepository {

    fun getVideoInfo(url: Request): VideoInfo?

    fun saveVideoInfo(videoInfo: VideoInfo)
}

@Singleton
class VideoRepositoryImpl @Inject constructor(
    @RemoteData private val remoteDataSource: VideoRepository
) : VideoRepository {

    @VisibleForTesting
    internal var cachedVideos: MutableMap<String, VideoInfo> = mutableMapOf()

    override fun getVideoInfo(url: Request): VideoInfo? {
        cachedVideos[url.url.toString()]?.let { return it }

        return getAndCacheRemoteVideo(url)
    }

    override fun saveVideoInfo(videoInfo: VideoInfo) {
        cachedVideos[videoInfo.originalUrl] = videoInfo
    }

    private fun getAndCacheRemoteVideo(url: Request): VideoInfo? {
        val videoInfo = remoteDataSource.getVideoInfo(url)
        if (videoInfo != null) {
            videoInfo.originalUrl = url.url.toString()
            cachedVideos[videoInfo.originalUrl] = videoInfo

            return videoInfo
        }
        return null
    }
}