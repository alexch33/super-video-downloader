package com.myAllVideoBrowser.data.remote

import com.myAllVideoBrowser.data.local.room.entity.VideoInfo
import com.myAllVideoBrowser.data.remote.service.VideoService
import com.myAllVideoBrowser.data.remote.service.VideoServiceSuperX
import com.myAllVideoBrowser.data.repository.VideoRepository
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VideoRemoteDataSource @Inject constructor(
    private val videoService: VideoService,
    private val videoServiceSuperX: VideoServiceSuperX
) : VideoRepository {

    override fun getVideoInfoBySuperXDetector(
        url: Request,
        isM3u8: Boolean,
        isMpd: Boolean,
        isAudioCheck: Boolean
    ): VideoInfo? {
        return videoServiceSuperX.getVideoInfo(url, isM3u8, isMpd, isAudioCheck)?.videoInfo
    }

    override fun getVideoInfo(
        url: Request,
        isM3u8OrMpd: Boolean,
        isAudioCheck: Boolean
    ): VideoInfo? {
        return videoService.getVideoInfo(url, isM3u8OrMpd, isM3u8OrMpd, isAudioCheck)?.videoInfo
    }

    override fun getVideoInfoOffline(
        baseUrl: String,
        manifestBody: String,
        isM3u8: Boolean,
        isMpd: Boolean,
        headers: Map<String, String>
    ): VideoInfo? {
        return null
    }

    override fun getVideoAudioInfoLocal(
        url: String,
        headers: Map<String, String>,
        contentType: String,
        contentLength: Long,
        title: String,
        originalUrl: String
    ): VideoInfo? {
        return null
    }

    override fun saveVideoInfo(videoInfo: VideoInfo) {
    }
}