package com.myAllVideoBrowser.data.local.room.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.google.gson.annotations.Expose
import com.google.gson.annotations.SerializedName
import java.util.*

@Entity(tableName = "SupportedPage")
data class SupportedPage constructor(
    @PrimaryKey
    @ColumnInfo(name = "id")
    var id: String = UUID.randomUUID().toString(),

    @ColumnInfo(name = "name")
    @SerializedName("name")
    @Expose
    var name: String = "",

    @ColumnInfo(name = "pattern")
    @SerializedName("pattern")
    @Expose
    var pattern: String = ""
)