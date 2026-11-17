package com.myAllVideoBrowser.data.local

import com.myAllVideoBrowser.data.local.room.dao.VideoDao
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo
import com.myAllVideoBrowser.data.local.service.VideoServiceSuperXLocal
import com.myAllVideoBrowser.data.repository.VideoRepository
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VideoLocalDataSource @Inject constructor(
    private val videoDao: VideoDao,
    private val videoServiceSuperXLocal: VideoServiceSuperXLocal
) : VideoRepository {
    override fun getVideoInfoBySuperXDetector(
        url: Request,
        isM3u8: Boolean,
        isMpd: Boolean,
        isAudioCheck: Boolean
    ): VideoInfo {
        return videoDao.getVideoById(url.url.toString()).toSingle().blockingGet()
    }

    override fun getVideoInfo(
        url: Request,
        isM3u8OrMpd: Boolean,
        isAudioCheck: Boolean
    ): VideoInfo {
        return videoDao.getVideoById(url.url.toString()).toSingle().blockingGet()
    }

    override fun getVideoInfoOffline(
        baseUrl: String,
        manifestBody: String,
        isM3u8: Boolean,
        isMpd: Boolean,
        headers: Map<String, String>
    ): VideoInfo? {
        return videoServiceSuperXLocal.getVideoInfo(baseUrl, manifestBody, isM3u8, isMpd, headers)?.videoInfo
    }

    override fun saveVideoInfo(videoInfo: VideoInfo) {
        videoDao.insertVideo(videoInfo)
    }

}