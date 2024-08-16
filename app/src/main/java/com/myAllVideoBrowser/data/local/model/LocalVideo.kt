package com.myAllVideoBrowser.data.local.model

import android.net.Uri

data class LocalVideo(
    var id: Long,
    var uri: Uri,
    var name: String
) {

    var size: String = ""

    val thumbnailPath: Uri
        get() = uri

}