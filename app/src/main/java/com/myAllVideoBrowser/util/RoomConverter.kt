package com.myAllVideoBrowser.util

import androidx.room.TypeConverter
import com.google.gson.GsonBuilder
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo

class RoomConverter {
    private val gson = GsonBuilder()
        .registerTypeAdapter(RequestListTypeAdapter.TYPE, RequestListTypeAdapter())
        .create()

    @TypeConverter
    fun convertJsonToVideo(json: String): VideoInfo? {
        return try {
            gson.fromJson(json, VideoInfo::class.java)
        } catch (e: Exception) {
            null
        }
    }

    @TypeConverter
    fun convertListVideosToJson(video: VideoInfo): String {
        return gson.toJson(video)
    }
}
