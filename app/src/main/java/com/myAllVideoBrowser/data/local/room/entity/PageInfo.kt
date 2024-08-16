package com.myAllVideoBrowser.data.local.room.entity

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.google.gson.annotations.Expose
import com.google.gson.annotations.SerializedName
import java.util.*

@Entity(tableName = "PageInfo")
data class PageInfo(
    @ColumnInfo(name = "id")
    var id: String = UUID.randomUUID().toString(),

    @ColumnInfo(name = "isSystem")
    @SerializedName("isSystem")
    @Expose
    var isSystem: Boolean = true,

    @ColumnInfo(name = "name")
    @SerializedName("name")
    @Expose
    var name: String = "",

    @PrimaryKey
    @ColumnInfo(name = "link")
    @SerializedName("link")
    @Expose
    var link: String = "",

    @ColumnInfo(name = "icon")
    @SerializedName("icon")
    @Expose
    var icon: String = "",

    @ColumnInfo(typeAffinity = ColumnInfo.BLOB)
    var favicon: ByteArray? = null
) {
    // TODO use regex
    fun getTitleFiltered(): String {
        return name
            .replace("www.", "")
            .replace(".com", "")
            .replaceFirstChar { it.uppercase() }
    }

    fun faviconBitmap(): Bitmap? {
        if (favicon == null) {
            return null
        }
        return BitmapFactory.decodeByteArray(favicon, 0, favicon?.size ?: 0)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as PageInfo

        return link == other.link
    }

    override fun hashCode(): Int {
        return 31 * link.hashCode()
    }
}