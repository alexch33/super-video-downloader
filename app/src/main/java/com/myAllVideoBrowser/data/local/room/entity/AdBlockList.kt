package com.myAllVideoBrowser.data.local.room.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "adblock_lists")
data class AdBlockList(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val url: String?, // Null for built-in lists
    val isEnabled: Boolean = true,
    val lastUpdated: Long = 0,
    val localPath: String? = null,
    val isDownloaded: Boolean = false,
    val isDownloadFailed: Boolean = false
)
