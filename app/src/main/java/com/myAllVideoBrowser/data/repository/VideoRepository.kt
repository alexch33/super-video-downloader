package com.myAllVideoBrowser.data.repository

import androidx.annotation.VisibleForTesting
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo
import com.myAllVideoBrowser.di.qualifier.RemoteData
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

interface VideoRepository {
    fun getVideoInfoBySuperXDetector(
        url: Request,
        isM3u8: Boolean = false,
        isMpd: Boolean = false,
        isAudioCheck: Boolean
    ): VideoInfo?

    fun getVideoInfo(url: Request, isM3u8OrMpd: Boolean = false, isAudioCheck: Boolean): VideoInfo?

    fun saveVideoInfo(videoInfo: VideoInfo)
}

@Singleton
class VideoRepositoryImpl @Inject constructor(
    @param:RemoteData private val remoteDataSource: VideoRepository
) : VideoRepository {

    @VisibleForTesting
    internal var cachedVideos: MutableMap<String, VideoInfo> = mutableMapOf()
    internal var cachedVideosFfmpeg: MutableMap<String, VideoInfo> = mutableMapOf()

    override fun getVideoInfoBySuperXDetector(
        url: Request,
        isM3u8: Boolean,
        isMpd: Boolean,
        isAudioCheck: Boolean
    ): VideoInfo? {
        cachedVideosFfmpeg[url.url.toString()]?.let { return it }

        return getAndCacheRemoteVideoFfmpeg(url, isM3u8, isMpd, isAudioCheck)
    }

    override fun getVideoInfo(
        url: Request,
        isM3u8OrMpd: Boolean,
        isAudioCheck: Boolean
    ): VideoInfo? {
        cachedVideos[url.url.toString()]?.let { return it }

        return getAndCacheRemoteVideo(url, isM3u8OrMpd, isAudioCheck)
    }

    override fun saveVideoInfo(videoInfo: VideoInfo) {
        cachedVideos[videoInfo.originalUrl] = videoInfo
    }

    private fun getAndCacheRemoteVideoFfmpeg(
        url: Request,
        isM3u8: Boolean,
        isMpd: Boolean,
        isAudioCheck: Boolean
    ): VideoInfo? {
        val videoInfo = remoteDataSource.getVideoInfoBySuperXDetector(url, isM3u8, isMpd, isAudioCheck)
        if (videoInfo != null) {
            videoInfo.originalUrl = url.url.toString()
            cachedVideosFfmpeg[videoInfo.originalUrl] = videoInfo

            return videoInfo
        }
        return null
    }

    private fun getAndCacheRemoteVideo(
        url: Request,
        isM3u8OrMpd: Boolean,
        isAudioCheck: Boolean
    ): VideoInfo? {
        val videoInfo = remoteDataSource.getVideoInfo(url, isM3u8OrMpd, isAudioCheck)
        if (videoInfo != null) {
            videoInfo.originalUrl = url.url.toString()
            cachedVideos[videoInfo.originalUrl] = videoInfo

            return videoInfo
        }
        return null
    }
}