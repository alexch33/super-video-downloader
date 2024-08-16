package com.myAllVideoBrowser.data.local.room.entity

import androidx.room.*
import com.google.gson.Gson
import com.google.gson.annotations.Expose
import com.google.gson.annotations.SerializedName
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.Headers.Companion.toHeaders
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.*

@Entity(tableName = "VideoInfo")
data class VideoInfo(
    @PrimaryKey
    @ColumnInfo(name = "id")
    var id: String = UUID.randomUUID().toString(),

    @ColumnInfo(name = "downloadUrls")
    @SerializedName("urls")
    @Expose
    var downloadUrls: List<Request> = emptyList(),

    @ColumnInfo(name = "title")
    @SerializedName("title")
    @Expose
    var title: String = "",

    @ColumnInfo(name = "ext")
    @SerializedName("ext")
    @Expose
    var ext: String = "",

    @ColumnInfo(name = "thumbnail")
    @SerializedName("thumbnail")
    @Expose
    var thumbnail: String = "",

    @ColumnInfo(name = "duration")
    @SerializedName("duration")
    @Expose
    var duration: Long = 0,

    @ColumnInfo(name = "originalUrl")
    var originalUrl: String = "",

    @ColumnInfo(name = "formats")
    @SerializedName("formats")
    @Expose
    var formats: VideFormatEntityList = VideFormatEntityList(emptyList()),

    @ColumnInfo(name = "isRegular")
    @SerializedName("isRegular")
    @Expose
    var isRegularDownload: Boolean = false
) {

    val firstUrlToString: String
        get() {
            if (downloadUrls.isNotEmpty()) {
                return downloadUrls.firstOrNull()?.url.toString()
            }
            return ""
        }

    val name
        get() = "$title.$ext"

    val isM3u8
        get() = originalUrl.contains(".m3u8") || originalUrl.contains(".mpd") || formats.formats.any { url ->
            url.url.toString().contains(".m3u8") || url.url.toString()
                .contains(".mpd") || originalUrl.contains(".txt")
        }

    val isMaster get() = isM3u8 && formats.formats.size > 1

    fun isTikTokVideo() : Boolean {
        return originalUrl.contains("tiktok.com")
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as VideoInfo

        if (id != other.id) return false
        if (downloadUrls != other.downloadUrls) return false
        if (title != other.title) return false
        if (ext != other.ext) return false
        if (thumbnail != other.thumbnail) return false
        if (duration != other.duration) return false
        if (originalUrl != other.originalUrl) return false
        if (formats != other.formats) return false
        if (isRegularDownload != other.isRegularDownload) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + downloadUrls.hashCode()
        result = 31 * result + title.hashCode()
        result = 31 * result + ext.hashCode()
        result = 31 * result + thumbnail.hashCode()
        result = 31 * result + duration.hashCode()
        result = 31 * result + originalUrl.hashCode()
        result = 31 * result + formats.hashCode()
        result = 31 * result + isRegularDownload.hashCode()
        return result
    }


}

class FormatsConverter {
    @TypeConverter
    fun convertFormatListToJSONString(formatList: VideFormatEntityList): String =
        Gson().toJson(formatList)

    @TypeConverter
    fun convertJSONStringToFormatList(jsonString: String): VideFormatEntityList =
        Gson().fromJson(jsonString, VideFormatEntityList::class.java)
}

class DownloadUrlsConverter {
    companion object {
        const val URL_KEY = "url"
        const val METHOD = "method"
        const val BODY = "body"
        const val HEADERS = "headers"
    }

    @TypeConverter
    fun fromSource(sourceList: List<Request>): String {
        val resultBuffer = StringBuffer()

        for (source in sourceList) {
            val url = source.url.toString()
            val method = source.method
            val body = source.body.toString()

            val headers = mutableMapOf<String, String>()

            for (headerName in source.headers.names()) {
                headers[headerName] = source.headers[headerName] ?: ""
            }

            val jsonMap = mutableMapOf<String, String>()
            jsonMap[URL_KEY] = url
            jsonMap[METHOD] = method
            jsonMap[BODY] = body
            jsonMap[HEADERS] =
                (headers as Map<String, String>?)?.let { JSONObject(it).toString() } ?: "{}"

            resultBuffer.append((jsonMap as Map<*, *>?)?.let { JSONObject(it).toString() } ?: "{}")
                .append(">^^^<")
        }

        return resultBuffer.toString()
    }

    @TypeConverter
    fun toSource(inputList: String): List<Request> {
        val resultList = mutableListOf<Request>()

        for (input in inputList.split(">^^^<").filter { it.isNotEmpty() }) {
            val jsonMap = Json.parseToJsonElement(input).jsonObject.toMutableMap<String, Any>()

            val body = (jsonMap[BODY] ?: "").toString()

            // TODO FIX serialize error on some headers
            val headers =
                Json.parseToJsonElement(jsonMap[HEADERS].toString()).jsonObject.toMutableMap<String, Any>()

            resultList.add(
                Request.Builder().url(jsonMap[URL_KEY].toString())
                    .headers(headers.map { it.key to it.value.toString() }.toMap().toHeaders())
                    .method(
                        jsonMap[METHOD].toString(),
                        body.toRequestBody(null)
                    ).build()
            )
        }

        return resultList
    }
}
