package com.myAllVideoBrowser.util

import androidx.room.TypeConverter
import com.google.gson.Gson
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo

class RoomConverter {

    @TypeConverter
    fun convertJsonToVideo(json: String): VideoInfo {
        return Gson().fromJson(json, VideoInfo::class.java)
    }

    @TypeConverter
    fun convertListVideosToJson(video: VideoInfo): String {
        return Gson().toJson(video)
    }
}