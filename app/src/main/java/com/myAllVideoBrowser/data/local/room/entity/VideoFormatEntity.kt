package com.myAllVideoBrowser.data.local.room.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.google.gson.annotations.Expose
import com.google.gson.annotations.SerializedName
import java.util.*

@Entity(tableName = "VideoFormat")
data class VideoFormatEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    var id: String = UUID.randomUUID().toString(),

    @ColumnInfo(name = "asr")
    @SerializedName("asr")
    @Expose
    val asr: Int = 0,

    @ColumnInfo(name = "tbr")
    @SerializedName("tbr")
    @Expose
    val tbr: Int = 0,

    @ColumnInfo(name = "abr")
    @SerializedName("abr")
    @Expose
    val abr: Int = 0,

    @ColumnInfo(name = "format")
    @SerializedName("format")
    @Expose
    val format: String? = null,

    @ColumnInfo(name = "formatId")
    @SerializedName("formatId")
    @Expose
    val formatId: String? = null,

    @ColumnInfo(name = "formatNote")
    @SerializedName("formatNote")
    @Expose
    val formatNote: String? = null,

    @ColumnInfo(name = "ext")
    @SerializedName("ext")
    @Expose
    val ext: String? = null,

    @ColumnInfo(name = "preference")
    @SerializedName("preference")
    @Expose
    val preference: Int = 0,

    @ColumnInfo(name = "vcodec")
    @SerializedName("vcodec")
    @Expose
    val vcodec: String? = null,

    @ColumnInfo(name = "acodec")
    @SerializedName("acodec")
    @Expose
    val acodec: String? = null,

    @ColumnInfo(name = "width")
    @SerializedName("width")
    @Expose
    val width: Int = 0,

    @ColumnInfo(name = "height")
    @SerializedName("height")
    @Expose
    val height: Int = 0,

    @ColumnInfo(name = "fileSize")
    @SerializedName("fileSize")
    @Expose
    val fileSize: Long = 0,

    @ColumnInfo(name = "fileSizeApproximate")
    @SerializedName("fileSizeApproximate")
    @Expose
    val fileSizeApproximate: Long = 0,

    @ColumnInfo(name = "fps")
    @SerializedName("fps")
    @Expose
    val fps: Int = 0,

    @ColumnInfo(name = "url")
    @SerializedName("url")
    @Expose
    val url: String? = null,

    @ColumnInfo(name = "manifestUrl")
    @SerializedName("manifestUrl")
    @Expose
    val manifestUrl: String? = null,

    @ColumnInfo(name = "httpHeaders")
    @SerializedName("httpHeaders")
    @Expose
    val httpHeaders: Map<String, String>? = null
)

data class VideFormatEntityList(
    val formats: List<VideoFormatEntity>
)