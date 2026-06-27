package com.myAllVideoBrowser.data.local

import com.google.gson.GsonBuilder
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo
import com.myAllVideoBrowser.data.local.room.entity.VideFormatEntityList
import com.myAllVideoBrowser.util.RequestListTypeAdapter
import com.tencent.mmkv.MMKV

object VideoMetadataManager {
    private val kv = MMKV.defaultMMKV()
    private val gson = GsonBuilder()
        .registerTypeAdapter(RequestListTypeAdapter.TYPE, RequestListTypeAdapter())
        .create()

    fun saveVideoInfo(id: String, videoInfo: VideoInfo) {
        val json = gson.toJson(videoInfo)
        kv.encode(id, json)
    }

    fun getVideoInfo(id: String): VideoInfo? {
        val json = kv.decodeString(id) ?: return null
        return try {
            gson.fromJson(json, VideoInfo::class.java)?.apply {
                // Manually enforce non-nullability to prevent GSON reflection bypass
                if (formats == null) {
                    formats = VideFormatEntityList(emptyList())
                } else if (formats.formats == null) {
                    // Re-initialize if the internal list was set to null by GSON
                    formats = VideFormatEntityList(emptyList())
                }
                
                if (downloadUrls == null) {
                    downloadUrls = emptyList()
                }
                
                if (title == null) title = ""
                if (ext == null) ext = ""
            }
        } catch (_: Throwable) {
            null
        }
    }

    fun deleteVideoInfo(id: String) {
        kv.removeValueForKey(id)
    }
}
