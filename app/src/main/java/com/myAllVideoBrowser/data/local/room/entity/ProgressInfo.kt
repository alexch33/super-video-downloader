package com.myAllVideoBrowser.data.local.room.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import com.myAllVideoBrowser.util.FileUtil.Companion.getFileSizeReadable
import com.myAllVideoBrowser.util.RoomConverter
import com.myAllVideoBrowser.util.downloaders.generic_downloader.models.VideoTaskState
import java.util.*

@Entity(tableName = "ProgressInfo")
@TypeConverters(RoomConverter::class)
data class ProgressInfo(
    @PrimaryKey
    var id: String = UUID.randomUUID().toString(),
    var downloadId: Long = 0,

    @ColumnInfo(defaultValue = "")
    var title: String = "",

    @ColumnInfo(defaultValue = "")
    var thumbnail: String = "",

    @ColumnInfo(defaultValue = "0")
    var progressDownloaded: Long = 0,

    @ColumnInfo(defaultValue = "0")
    var progressTotal: Long = 0,

    var downloadStatus: Int = -1,
    var isLive: Boolean = false,
    var isM3u8: Boolean = false,
    var isRegularDownload: Boolean = false,
    var isDetectedBySuperX: Boolean = false,
    var fragmentsDownloaded: Int = 0,

    @ColumnInfo(defaultValue = "1")
    var fragmentsTotal: Int = 1,

    var infoLine: String = ""
) {
    @Ignore
    var progress: Int = 0
        get() = if (progressTotal > 0) (progressDownloaded * 100f / progressTotal).toInt() else 0

    @Ignore
    var progressSize: String = ""
        get() = getFileSizeReadable(progressDownloaded.toDouble()) + "/" + getFileSizeReadable(
            progressTotal.toDouble()
        ) + " - $downloadStatusFormatted"

    @Ignore
    var downloadStatusFormatted: String = ""
        get() = when (downloadStatus) {
            VideoTaskState.DOWNLOADING -> "downloading"
            VideoTaskState.SUCCESS -> "success"
            VideoTaskState.PAUSE -> "pause"
            VideoTaskState.PENDING -> "pending"
            VideoTaskState.PREPARE -> "prepare"
            VideoTaskState.ENOSPC -> "failed"
            VideoTaskState.ERROR -> "failed"
            else -> "undefined"
        }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ProgressInfo
        return id == other.id &&
                downloadId == other.downloadId &&
                progressDownloaded == other.progressDownloaded &&
                progressTotal == other.progressTotal &&
                downloadStatus == other.downloadStatus &&
                isM3u8 == other.isM3u8 &&
                isRegularDownload == other.isRegularDownload &&
                isDetectedBySuperX == other.isDetectedBySuperX &&
                fragmentsDownloaded == other.fragmentsDownloaded &&
                fragmentsTotal == other.fragmentsTotal &&
                infoLine == other.infoLine
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + downloadId.hashCode()
        result = 31 * result + progressDownloaded.hashCode()
        result = 31 * result + progressTotal.hashCode()
        result = 31 * result + downloadStatus
        result = 31 * result + isM3u8.hashCode()
        result = 31 * result + isRegularDownload.hashCode()
        result = 31 * result + isDetectedBySuperX.hashCode()
        result = 31 * result + fragmentsDownloaded
        result = 31 * result + fragmentsTotal
        result = 31 * result + infoLine.hashCode()
        return result
    }
}