package com.myAllVideoBrowser.data.local

import com.google.gson.Gson
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo
import com.tencent.mmkv.MMKV

object VideoMetadataManager {
    private val kv = MMKV.defaultMMKV()
    private val gson = Gson()

    fun saveVideoInfo(id: String, videoInfo: VideoInfo) {
        val json = gson.toJson(videoInfo)
        kv.encode(id, json)
    }

    fun getVideoInfo(id: String): VideoInfo? {
        val json = kv.decodeString(id) ?: return null
        return gson.fromJson(json, VideoInfo::class.java)
    }

    fun deleteVideoInfo(id: String) {
        kv.removeValueForKey(id)
    }
}
