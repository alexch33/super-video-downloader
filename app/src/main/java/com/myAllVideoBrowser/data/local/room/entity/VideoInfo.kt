package com.myAllVideoBrowser.data.local.room.entity

import androidx.room.*
import com.google.gson.Gson
import com.google.gson.annotations.Expose
import com.google.gson.annotations.JsonAdapter
import com.google.gson.annotations.SerializedName
import com.myAllVideoBrowser.util.RequestListTypeAdapter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.Headers.Companion.toHeaders
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.*

@Entity(tableName = "VideoInfo")
@TypeConverters(FormatsConverter::class, DownloadUrlsConverter::class)
data class VideoInfo(
    @PrimaryKey
    @ColumnInfo(name = "id")
    var id: String = UUID.randomUUID().toString(),

    @ColumnInfo(name = "downloadUrls")
    @SerializedName("urls")
    @Expose
    @JsonAdapter(RequestListTypeAdapter::class)
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
    var isRegularDownload: Boolean = false,

    @ColumnInfo(name = "isLive", defaultValue = "0")
    @SerializedName("isLive")
    @Expose
    var isLive: Boolean = false,

    @ColumnInfo(name = "isDetectedBySuperX", defaultValue = "0")
    @SerializedName("isDetectedBySuperX")
    @Expose
    var isDetectedBySuperX: Boolean = false,

    @ColumnInfo(name = "isAudioOnlyExtract", defaultValue = "0")
    @SerializedName("isAudioOnlyExtract")
    @Expose
    var isAudioOnlyExtract: Boolean = false
) {

    /**
     * Repairs the object if GSON/Room bypassed Kotlin's nullability during deserialization.
     * i have no idea how to reproduce case when some fields became null, so this is just crash fix
     */
    fun repairNulls(): VideoInfo {
        @Suppress("SENSELESS_COMPARISON")
        if (id == null) id = UUID.randomUUID().toString()
        @Suppress("SENSELESS_COMPARISON")
        if (downloadUrls == null) downloadUrls = emptyList()
        @Suppress("SENSELESS_COMPARISON")
        if (formats == null) {
            formats = VideFormatEntityList(emptyList())
        } else {
            formats.fixNulls()
        }
        @Suppress("SENSELESS_COMPARISON")
        if (title == null) title = ""
        @Suppress("SENSELESS_COMPARISON")
        if (ext == null) ext = ""
        @Suppress("SENSELESS_COMPARISON")
        if (thumbnail == null) thumbnail = ""
        @Suppress("SENSELESS_COMPARISON")
        if (originalUrl == null) originalUrl = ""
        return this
    }

    val firstUrlToString: String
        get() {
            if (downloadUrls.isNotEmpty()) {
                return downloadUrls.firstOrNull()?.url.toString()
            }
            return ""
        }

    val name
        get() = "$title.$ext"

    val isM3u8: Boolean
        get() {
            return (formats.allFormats()).any { format -> format.isM3u8 }
        }

    val isMpd: Boolean
        get() {
            return (formats.allFormats()).any { format -> format.isMpd }
        }
    val isMaster get() = isM3u8 && (formats.allFormats().size) > 1

    fun isTikTokVideo(): Boolean {
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
        if (isAudioOnlyExtract != other.isAudioOnlyExtract) return false

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
        result = 31 * result + isAudioOnlyExtract.hashCode()
        return result
    }
}

class FormatsConverter {
    @TypeConverter
    fun convertFormatListToJSONString(formatList: VideFormatEntityList?): String =
        Gson().toJson(formatList ?: VideFormatEntityList(emptyList()))

    @TypeConverter
    fun convertJSONStringToFormatList(jsonString: String?): VideFormatEntityList {
        if (jsonString.isNullOrEmpty()) return VideFormatEntityList(emptyList())
        return try {
            val result = Gson().fromJson(jsonString, VideFormatEntityList::class.java)
            result?.fixNulls() ?: VideFormatEntityList(emptyList())
        } catch (_: Exception) {
            VideFormatEntityList(emptyList())
        }
    }
}

class DownloadUrlsConverter {
    companion object {
        const val URL_KEY = "url"
        const val METHOD = "method"
        const val BODY = "body"
        const val HEADERS = "headers"
    }

    @TypeConverter
    fun fromSource(sourceList: List<Request>?): String {
        val resultBuffer = StringBuffer()
        val list = sourceList ?: return ""

        for (source in list) {
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
    fun toSource(inputList: String?): List<Request> {
        val resultList = mutableListOf<Request>()
        if (inputList.isNullOrEmpty()) return resultList

        for (input in inputList.split(">^^^<").filter { it.isNotEmpty() }) {
            try {
                val jsonMap = Json.parseToJsonElement(input).jsonObject.toMutableMap<String, Any>()

                val body = (jsonMap[BODY] ?: "").toString()

                val headers =
                    Json.parseToJsonElement(jsonMap[HEADERS].toString()).jsonObject.toMutableMap<String, Any>()

                val urlToAdd = jsonMap[URL_KEY].toString().toHttpUrlOrNull() ?: continue
                resultList.add(
                    Request.Builder().url(urlToAdd)
                        .headers(headers.map { it.key to it.value.toString() }.toMap().toHeaders())
                        .method(
                            jsonMap[METHOD].toString(),
                            body.toRequestBody(null)
                        ).build()
                )
            } catch (_: Exception) {
                // Skip invalid entries
            }
        }

        return resultList
    }
}
