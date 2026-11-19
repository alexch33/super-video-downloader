package com.myAllVideoBrowser.data.local.model

import android.net.Uri

data class LocalVideo(
    var id: Long,
    var uri: Uri,
    var name: String,
    var isPrivate: Boolean = false
) {

    var size: String = ""

    val thumbnailPath: Uri
        get() = uri

}