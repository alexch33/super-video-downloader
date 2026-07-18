package com.myAllVideoBrowser.data.local.room.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.*


@Entity(tableName = "HistoryItem")
data class HistoryItem(
    @PrimaryKey
    var id: String = UUID.randomUUID().toString(),
    var title: String? = null,
    var url: String,
    var datetime: Long = Date().time,
    var faviconUrl: String? = null
) {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as HistoryItem

        if (id != other.id) return false
        if (url != other.url) return false
        if (datetime != other.datetime) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + url.hashCode()
        result = 31 * result + datetime.hashCode()
        return result
    }
}
